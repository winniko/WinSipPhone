package it.winsipphone.tester.sip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import it.winsipphone.tester.BlfState
import it.winsipphone.tester.BuildConfig
import it.winsipphone.tester.Bus
import it.winsipphone.tester.CallUi
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.contacts.Contacts
import it.winsipphone.tester.contacts.DoorButton
import it.winsipphone.tester.history.CallEntry
import it.winsipphone.tester.history.CallLog
import it.winsipphone.tester.media.Ringer
import it.winsipphone.tester.service.CallNotifications
import it.winsipphone.tester.service.PhoneService
import it.winsipphone.tester.util.Http
import it.winsipphone.tester.util.L
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallListenerStub
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Event
import org.linphone.core.Factory
import org.linphone.core.MediaEncryption
import org.linphone.core.PayloadType
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.SubscriptionState

/**
 * Stack SIP (liblinphone). Tutti i metodi vanno chiamati sul main thread:
 * gli oggetti linphone NON vanno mai toccati da altri thread (deadlock).
 */
object SipManager {
    private const val TAG = "SIP"
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    var core: Core? = null
        private set
    private var current: Call? = null
    private var answered = false
    private var answeredAt = 0L
    private var startedAt = 0L
    private var outgoing = false
    private var userDeclined = false
    private var hadVideo = false
    private var remote = ""
    private var name = ""
    private var routedOnce = false
    private var pendingReconfigure = false
    private val blfEvents = HashMap<String, Event>()

    val hasCall: Boolean get() = current != null
    val isAnswered: Boolean get() = answered

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ------------------------------------------------------------------ ciclo di vita

    /** Avvia o riconfigura il telefono con la configurazione attuale. */
    fun start() {
        if (current != null) {
            // Riconfigurare ora interromperebbe la chiamata: lo faccio quando finisce.
            pendingReconfigure = true
            return
        }
        try {
            val c = core ?: createCore().also { core = it }
            configure(c)
        } catch (e: Throwable) {
            L.e(TAG, "Avvio SIP fallito", e)
            Bus.registration.value = "errore: ${e.message}"
        }
    }

    fun stop() {
        clearBlf()
        core?.let {
            runCatching { it.removeListener(coreListener); it.stop() }
        }
        core = null
        current = null
        Bus.registered.value = false
        Bus.call.value = CallUi.Idle
    }

    private fun createCore(): Core {
        val adv = ConfigRepository.current.advanced
        val factory = Factory.instance()
        factory.setDebugMode(adv.debugLogs, "WinSipPhone/Linphone")
        factory.enableLogcatLogs(adv.debugLogs)
        SipTrace.install(adv.sipTrace)
        val c = factory.createCore(null, null, app)
        c.setUserAgent("WinSipPhone-Tester", BuildConfig.VERSION_NAME)
        c.isIpv6Enabled = false
        c.isNativeRingingEnabled = false
        c.ring = null
        c.addListener(coreListener)
        val res = c.start()
        L.i(TAG, "Motore SIP avviato ($res)")
        return c
    }

    private fun configure(c: Core) {
        val cfg = ConfigRepository.current
        val adv = cfg.advanced
        SipTrace.install(adv.sipTrace)

        // Video
        c.isVideoCaptureEnabled = cfg.video.enabled
        c.isVideoDisplayEnabled = cfg.video.enabled
        c.isVideoPreviewEnabled = false
        val policy = Factory.instance().createVideoActivationPolicy()
        policy.automaticallyAccept = cfg.video.enabled && cfg.video.autoAccept
        policy.automaticallyInitiate = false
        c.videoActivationPolicy = policy

        c.isKeepAliveEnabled = adv.keepAlive
        c.incTimeout = adv.ringTimeoutSec

        // Porte locali (-1 = casuale)
        val port = if (adv.localPort <= 0) -1 else adv.localPort
        val t = c.transports
        t.udpPort = port
        t.tcpPort = port
        t.tlsPort = if (cfg.account.transport.equals("TLS", true)) -1 else 0
        c.setTransports(t)

        // Audio
        c.isEchoCancellationEnabled = cfg.audio.echoCancellation
        c.micGainDb = cfg.audio.micGainDb
        c.playbackGainDb = cfg.audio.playbackGainDb
        when (cfg.audio.dtmfMode) {
            "SIP_INFO" -> { c.useRfc2833ForDtmf = false; c.useInfoForDtmf = true }
            "INBAND" -> { c.useRfc2833ForDtmf = false; c.useInfoForDtmf = false }
            else -> { c.useRfc2833ForDtmf = true; c.useInfoForDtmf = false }
        }
        c.setAudioPayloadTypes(ordered(c.audioPayloadTypes, cfg.audio.codecs))
        c.setVideoPayloadTypes(ordered(c.videoPayloadTypes, cfg.video.codecs))

        // Cifratura e NAT
        c.mediaEncryption = when (adv.mediaEncryption) {
            "SRTP" -> MediaEncryption.SRTP
            "ZRTP" -> MediaEncryption.ZRTP
            else -> MediaEncryption.None
        }
        c.isMediaEncryptionMandatory = false
        val np = c.createNatPolicy()
        np.stunServer = adv.stunServer
        np.isStunEnabled = adv.stunServer.isNotBlank()
        np.isIceEnabled = adv.ice && adv.stunServer.isNotBlank()
        c.natPolicy = np

        // Account del centralino
        clearBlf()
        c.clearAccounts()
        c.clearAllAuthInfo()
        val acc = cfg.account
        if (acc.enabled && acc.server.isNotBlank() && acc.username.isNotBlank()) {
            val factory = Factory.instance()
            val domain = acc.domain.ifBlank { acc.server }
            val params = c.createAccountParams()
            factory.createAddress("sip:${acc.username}@$domain")?.let {
                if (acc.displayName.isNotBlank()) it.displayName = acc.displayName
                params.identityAddress = it
            }
            val tr = when (acc.transport.uppercase()) {
                "TLS" -> ";transport=tls"
                "TCP" -> ";transport=tcp"
                else -> ";transport=udp"
            }
            factory.createAddress("sip:${acc.server}:${acc.port}$tr")?.let { params.serverAddress = it }
            if (acc.outboundProxy.isNotBlank()) {
                factory.createAddress("sip:${acc.outboundProxy.removePrefix("sip:")}$tr")?.let {
                    params.setRoutesAddresses(arrayOf(it))
                    params.isOutboundProxyEnabled = true
                }
            }
            params.isRegisterEnabled = true
            params.expires = acc.expiresSec
            c.addAuthInfo(factory.createAuthInfo(acc.username, acc.authUsername.ifBlank { null }, acc.password, null, null, domain))
            val account = c.createAccount(params)
            c.addAccount(account)
            c.defaultAccount = account
            Bus.registration.value = "registrazione in corso..."
            L.i(TAG, "Account ${acc.username}@$domain via ${acc.server}:${acc.port} ${acc.transport}")
        } else {
            Bus.registration.value = if (acc.enabled) "account incompleto" else "solo chiamate dirette IP"
            Bus.blf.value = emptyMap()
        }
        Bus.registered.value = false
        L.i(TAG, "Configurazione applicata (porta locale ${if (port < 0) "casuale" else port})")
    }

    /** Codec nell'ordine richiesto, attivi solo quelli elencati (toni DTMF e rumore di fondo sempre attivi). */
    private fun ordered(all: Array<PayloadType>, wanted: String): Array<PayloadType> {
        val names = wanted.split(',', ';', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (names.isEmpty() || all.none { it.mimeType.uppercase() in names }) return all
        val first = names.flatMap { n -> all.filter { it.mimeType.uppercase() == n } }
        val result = first + all.filter { it !in first }
        result.forEach { pt ->
            val m = pt.mimeType.uppercase()
            pt.enable(m in names || m == "TELEPHONE-EVENT" || m == "CN")
        }
        return result.toTypedArray()
    }

    fun onNetworkChanged(up: Boolean) {
        // Le sottoscrizioni BLF cadono col cambio rete: le ricreo alla prossima registrazione.
        clearBlf()
        core?.let {
            it.isNetworkReachable = false
            if (up) {
                it.isNetworkReachable = true
                it.refreshRegisters()
            }
        }
    }

    /** Chiamato dalla sveglia periodica: rinnova la registrazione anche col telefono in standby. */
    fun keepAliveTick() {
        val c = core ?: return start()
        if (ConfigRepository.current.account.enabled) c.refreshRegisters()
    }

    // ------------------------------------------------------------------ listener

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState?, message: String) {
            val err = account.errorInfo
            Bus.registered.value = state == RegistrationState.Ok
            Bus.registration.value = when (state) {
                RegistrationState.Ok -> "registrato"
                RegistrationState.Progress, RegistrationState.Refreshing -> "registrazione in corso..."
                RegistrationState.Failed -> "errore: " + (if (err != null && err.protocolCode > 0) "${err.protocolCode} ${err.phrase}" else message).let(::explain)
                RegistrationState.Cleared -> "non registrato"
                else -> "nessuna registrazione"
            }
            L.i(TAG, "Registrazione: $state $message")
            if (state == RegistrationState.Ok) subscribeBlf()
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            L.i(TAG, "Chiamata ${call.remoteAddress.asStringUriOnly()} -> $state ($message)")
            when (state) {
                Call.State.IncomingReceived -> onIncoming(call)
                Call.State.OutgoingProgress -> setOutgoingStatus(call, "chiamata in corso...")
                Call.State.OutgoingRinging -> setOutgoingStatus(call, "squilla...")
                Call.State.OutgoingEarlyMedia -> setOutgoingStatus(call, "squilla (audio anticipato)")
                Call.State.Connected -> if (call == current && outgoing && !answered) onOutgoingConnected(call)
                Call.State.StreamsRunning, Call.State.Paused, Call.State.PausedByRemote, Call.State.Updating,
                Call.State.UpdatedByRemote, Call.State.Resuming -> updateActive(call)
                Call.State.End, Call.State.Error, Call.State.Released -> onEnded(call)
                else -> {}
            }
        }

        override fun onNotifyReceived(core: Core, event: Event, notifiedEvent: String, body: Content?) {
            if (!notifiedEvent.equals("dialog", true)) return
            val ext = extensionOf(event) ?: return
            val state = parseDialog(body?.utf8Text.orEmpty())
            Bus.blf.value = Bus.blf.value + (ext to state)
        }

        override fun onSubscriptionStateChanged(core: Core, event: Event, state: SubscriptionState?) {
            val ext = extensionOf(event) ?: return
            L.i(TAG, "BLF $ext: sottoscrizione $state")
            if ((state == SubscriptionState.Error || state == SubscriptionState.Terminated) && blfEvents[ext]?.nativePointer == event.nativePointer) {
                Bus.blf.value = Bus.blf.value + (ext to BlfState.OFFLINE)
                blfEvents.remove(ext)
                // Riprovo fra un minuto (es. interno non ancora creato sul centralino)
                main.postDelayed({ if (Bus.registered.value) subscribeBlf() }, 60_000)
            }
        }
    }

    private val callListener = object : CallListenerStub() {
        override fun onNextVideoFrameDecoded(call: Call) {
            Bus.remoteVideo.value = true
            L.i(TAG, "Primo frame video ricevuto")
        }

        override fun onTransferStateChanged(call: Call, state: Call.State?) {
            L.i(TAG, "Stato trasferimento: $state")
        }
    }

    // ------------------------------------------------------------------ BLF (stato interni)

    private fun subscribeBlf() {
        val c = core ?: return
        val acc = ConfigRepository.current.account
        val wanted = ConfigRepository.current.blf.map { it.extension.trim() }.filter { it.isNotEmpty() }.distinct()
        (blfEvents.keys - wanted.toSet()).toList().forEach { blfEvents.remove(it)?.let { e -> runCatching { e.terminate() } } }
        val domain = acc.domain.ifBlank { acc.server }
        val states = Bus.blf.value.filterKeys { it in wanted }.toMutableMap()
        wanted.forEach { ext ->
            if (blfEvents.containsKey(ext)) return@forEach
            val addr = c.interpretUrl("sip:$ext@$domain", false) ?: return@forEach
            val ev = c.createSubscribe(addr, "dialog", 600) ?: run {
                L.w(TAG, "BLF $ext: sottoscrizione non creata")
                return@forEach
            }
            ev.addCustomHeader("Accept", "application/dialog-info+xml")
            ev.sendSubscribe(null)
            blfEvents[ext] = ev
            states.putIfAbsent(ext, BlfState.UNKNOWN)
        }
        Bus.blf.value = states
    }

    /** Chiude tutte le sottoscrizioni (terminate() richiama subito il listener: svuoto la mappa prima). */
    private fun clearBlf() {
        val evs = blfEvents.values.toList()
        blfEvents.clear()
        evs.forEach { runCatching { it.terminate() } }
    }

    /** Ricarica le sottoscrizioni BLF (dopo aver cambiato l'elenco degli interni). */
    fun refreshBlf() {
        clearBlf()
        if (Bus.registered.value) subscribeBlf() else Bus.blf.value = emptyMap()
    }

    private fun extensionOf(event: Event): String? {
        blfEvents.entries.firstOrNull { it.value.nativePointer == event.nativePointer }?.let { return it.key }
        val user = event.resource?.username ?: return null
        return user.takeIf { blfEvents.containsKey(it) }
    }

    /** dialog-info+xml (RFC 4235): confirmed = occupato, early = squilla, nessun dialogo = libero. */
    private fun parseDialog(xml: String): BlfState {
        if (xml.isBlank()) return BlfState.UNKNOWN
        val states = Regex("<(?:\\w+:)?dialog[\\s>].*?</(?:\\w+:)?dialog>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .mapNotNull { Regex("<(?:\\w+:)?state[^>]*>\\s*(\\w+)").find(it.value)?.groupValues?.get(1)?.lowercase() }
            .toList()
        return when {
            "confirmed" in states -> BlfState.BUSY
            states.any { it == "early" || it == "proceeding" || it == "trying" } -> BlfState.RINGING
            else -> BlfState.IDLE
        }
    }

    // ------------------------------------------------------------------ chiamate in arrivo

    private fun isFromPbx(call: Call): Boolean {
        val acc = ConfigRepository.current.account
        if (!acc.enabled || acc.server.isBlank()) return false
        val domain = call.remoteAddress.domain.orEmpty()
        return domain.equals(acc.server, true) || domain.equals(acc.domain, true) || remoteIp(call) == acc.server
    }

    private fun remoteIp(call: Call): String {
        val contact = call.remoteContact ?: ""
        val m = Regex("@\\[?([0-9a-fA-F.:]+?)\\]?(?::\\d+)?(?:[;>]|$)").find(contact)
        return m?.groupValues?.get(1) ?: call.remoteAddress.domain ?: ""
    }

    private fun isAllowed(call: Call): Boolean {
        if (isFromPbx(call)) return true
        val d = ConfigRepository.current.direct
        if (!d.acceptIncoming) return false
        val allowed = d.allowedIps.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val ip = remoteIp(call)
        val domain = call.remoteAddress.domain.orEmpty()
        return allowed.isEmpty() || allowed.any { it == ip || it.equals(domain, true) }
    }

    /** (numero da mostrare/richiamare, nome) */
    private fun identify(call: Call): Pair<String, String> {
        val a = call.remoteAddress
        val user = a.username.orEmpty()
        val domain = a.domain.orEmpty()
        val viaPbx = isFromPbx(call)
        val number = when {
            viaPbx && user.isNotEmpty() -> user
            user.isNotEmpty() -> "$user@$domain"
            else -> domain
        }
        val contact = Contacts.find(number) ?: Contacts.find(domain)
        Bus.callContactId.value = contact?.id
        return number to (contact?.name ?: a.displayName?.takeIf { it.isNotBlank() } ?: number)
    }

    private fun onIncoming(call: Call) {
        if (current != null && current != call) {
            L.i(TAG, "Già in chiamata: rispondo occupato")
            val (n, nm) = identify(call)
            call.decline(Reason.Busy)
            CallLog.add(CallEntry(System.currentTimeMillis(), n, nm, incoming = true, reason = "occupato (altra chiamata in corso)"))
            return
        }
        if (!isAllowed(call)) {
            L.w(TAG, "Chiamata diretta da IP non autorizzato (${remoteIp(call)}): rifiutata")
            call.decline(Reason.Forbidden)
            return
        }
        current = call
        answered = false
        outgoing = false
        userDeclined = false
        routedOnce = false
        startedAt = System.currentTimeMillis()
        call.addListener(callListener)
        val (n, nm) = identify(call)
        remote = n
        name = nm
        val video = call.remoteParams?.isVideoEnabled == true && ConfigRepository.current.video.enabled
        hadVideo = video
        Bus.remoteVideo.value = false
        Bus.muted.value = false
        Bus.call.value = CallUi.Incoming(n, nm, video)
        Ringer.start()
        CallNotifications.showIncoming(app, nm, n, video)
        if (ConfigRepository.current.advanced.autoAnswer) main.postDelayed({ if (current === call && !answered) accept(video) }, 1500)
    }

    fun accept(withVideo: Boolean) {
        val call = current ?: return
        if (answered || call.dir != Call.Dir.Incoming) return
        Ringer.stop()
        CallNotifications.cancelIncoming(app)
        val c = core ?: return
        val video = withVideo && ConfigRepository.current.video.enabled
        val p = c.createCallParams(call)
        if (p != null) {
            p.isVideoEnabled = video
            if (video) selectCamera(c)
            call.acceptWithParams(p)
        } else {
            call.accept()
        }
        answered = true
        answeredAt = System.currentTimeMillis()
        hadVideo = hadVideo || video
        PhoneService.updateForCall(app, inCall = true, video = video)
        updateActive(call)
    }

    // ------------------------------------------------------------------ chiamate in uscita

    /**
     * [target]: interno del centralino (201), IP (192.168.1.50), interno@IP o sip:...
     * Restituisce null se la chiamata è partita, altrimenti il motivo.
     */
    fun dial(target: String, video: Boolean): String? {
        val c = core ?: return "Il telefono SIP non è attivo"
        if (current != null) return "C'è già una chiamata in corso"
        val t = target.trim()
        if (t.isEmpty()) return "Scrivi un numero o un indirizzo"
        val addr = resolveAddress(c, t).getOrElse { return it.message }
        val withVideo = video && ConfigRepository.current.video.enabled
        val p = c.createCallParams(null) ?: return "Impossibile preparare la chiamata"
        p.isVideoEnabled = withVideo
        if (withVideo) selectCamera(c)
        PhoneService.updateForCall(app, inCall = true, video = withVideo)
        // Destinazione diversa dal centralino (es. citofono 192.168.1.50): chiamata diretta,
        // senza passare dall'account registrato.
        val acc = ConfigRepository.current.account
        val samePort = addr.port <= 0 || addr.port == acc.port
        val toPbx = (addr.domain.equals(acc.server, true) || addr.domain.equals(acc.domain.ifBlank { acc.server }, true)) && samePort
        val direct = !acc.enabled || !toPbx
        val savedDefault = c.defaultAccount
        if (direct && savedDefault != null) {
            p.account = null
            c.defaultAccount = null
        }
        if (direct) directFromHeader()?.let {
            p.fromHeader = it
            L.i(TAG, "Mittente della chiamata diretta: $it")
        }
        val call = try {
            c.inviteAddressWithParams(addr, p)
        } finally {
            if (direct && savedDefault != null) c.defaultAccount = savedDefault
        } ?: run {
            PhoneService.updateForCall(app, inCall = false, video = false)
            return "Chiamata non riuscita"
        }
        current = call
        answered = false
        outgoing = true
        userDeclined = false
        routedOnce = false
        hadVideo = withVideo
        startedAt = System.currentTimeMillis()
        val contact = Contacts.find(t)
        Bus.callContactId.value = contact?.id
        remote = t
        name = contact?.name ?: t
        call.addListener(callListener)
        Bus.remoteVideo.value = false
        Bus.muted.value = false
        Bus.call.value = CallUi.Outgoing(remote, name, withVideo, "chiamata in corso...")
        L.i(TAG, "Chiamata in uscita verso ${addr.asStringUriOnly()} (${if (withVideo) "video" else "audio"})" +
            (if (direct) " diretta" else " tramite centralino") + " — IP del telefono: ${it.winsipphone.tester.diag.NetTest.localIps().joinToString()}")
        // Nessuna risposta in 6 s: lo dico subito invece di restare su "chiamata in corso..."
        main.postDelayed({
            if (current === call && !answered && !SipTrace.hasReply(call.callLog.callId.orEmpty()) && ConfigRepository.current.advanced.sipTrace) {
                setOutgoingStatus(call, "il dispositivo non risponde (SIP disattivato, IP o porta errati?)")
                L.w(TAG, "Nessuna risposta SIP dopo 6 secondi da ${addr.asStringUriOnly()}")
            }
        }, 6000)
        return null
    }

    /** Mittente delle chiamate dirette (es. sip:200@192.168.1.10 per i citofoni Hikvision), null = predefinito. */
    private fun directFromHeader(): String? {
        val cfg = ConfigRepository.current
        val acc = cfg.account
        val accOn = acc.enabled && acc.server.isNotBlank() && acc.username.isNotBlank()
        val domain = cfg.direct.fromDomain.trim().ifBlank { if (accOn) acc.domain.ifBlank { acc.server } else "" }
        if (domain.isBlank()) return null
        val user = cfg.direct.fromUser.trim().ifBlank { if (accOn) acc.username else "winsipphone" }
        val name = acc.displayName.takeIf { accOn && it.isNotBlank() }?.let { "\"$it\" " }.orEmpty()
        return "$name<sip:$user@$domain>"
    }

    /** Interno (201), IP (192.168.1.50[:5060]), interno@host o sip:... -> indirizzo SIP. */
    fun resolveAddress(c: Core, target: String): Result<Address> {
        val acc = ConfigRepository.current.account
        val pbx = acc.enabled && acc.server.isNotBlank()
        val uri = when {
            target.startsWith("sip:", true) || target.startsWith("sips:", true) -> target
            target.contains('@') -> "sip:$target"
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$").matches(target) -> "sip:$target"
            Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:\\d+)?$").matches(target) && target.any { it.isLetter() } -> "sip:$target"
            pbx -> target // il dominio del centralino lo aggiunge interpretUrl
            else -> return Result.failure(IllegalArgumentException(
                "Per chiamare un interno serve l'account del centralino (Impostazioni). " +
                    "Senza centralino scrivi l'indirizzo IP, es. 192.168.1.50 o 201@192.168.1.50"))
        }
        val addr = c.interpretUrl(uri, true) ?: return Result.failure(IllegalArgumentException("Indirizzo non valido: $target"))
        return Result.success(addr)
    }

    private fun setOutgoingStatus(call: Call, status: String) {
        if (call != current || answered) return
        val s = Bus.call.value
        if (s is CallUi.Outgoing) Bus.call.value = s.copy(status = status)
    }

    private fun onOutgoingConnected(call: Call) {
        answered = true
        answeredAt = System.currentTimeMillis()
        updateActive(call)
    }

    private fun updateActive(call: Call) {
        if (call != current || !answered) return
        val video = call.currentParams.isVideoEnabled
        hadVideo = hadVideo || video
        Bus.call.value = CallUi.Active(
            remote, name, answeredAt, video,
            paused = call.state == Call.State.Paused || call.state == Call.State.Pausing,
            pausedByRemote = call.state == Call.State.PausedByRemote,
        )
        if (call.state == Call.State.StreamsRunning && !routedOnce) {
            routedOnce = true
            defaultRoute(call, video)
        }
        refreshAudioRoute(call)
    }

    // ------------------------------------------------------------------ comandi in chiamata

    fun hangup() {
        val call = current ?: return
        Ringer.stop()
        when (call.state) {
            Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> {
                userDeclined = true
                call.decline(Reason.Declined)
            }
            else -> call.terminate()
        }
    }

    fun toggleMute() {
        val call = current ?: return
        call.microphoneMuted = !call.microphoneMuted
        Bus.muted.value = call.microphoneMuted
    }

    fun toggleHold() {
        val call = current ?: return
        if (!answered) return
        val r = if (call.state == Call.State.Paused) call.resume() else call.pause()
        L.i(TAG, "Attesa ($r)")
    }

    fun toggleVideo() {
        val c = core ?: return
        val call = current ?: return
        if (!answered || !ConfigRepository.current.video.enabled) return
        val p = c.createCallParams(call) ?: return
        val on = !call.currentParams.isVideoEnabled
        p.isVideoEnabled = on
        if (on) selectCamera(c)
        call.update(p)
        if (on) {
            PhoneService.updateForCall(app, inCall = true, video = true)
            setSpeaker(true)
        }
    }

    fun switchCamera() {
        val c = core ?: return
        val cams = c.videoDevicesList.filter { !it.contains("StaticImage", true) }
        if (cams.size < 2) return
        val next = cams[(cams.indexOf(c.videoDevice) + 1) % cams.size]
        c.setVideoDevice(next)
        current?.update(null)
        L.i(TAG, "Fotocamera: $next")
    }

    private fun selectCamera(c: Core) {
        c.isVideoCaptureEnabled = true
        c.reloadVideoDevices()
        val devices = c.videoDevicesList.toList()
        val wantFront = ConfigRepository.current.video.frontCamera
        val cam = devices.firstOrNull { it.contains(if (wantFront) "front" else "back", true) }
            ?: devices.firstOrNull { !it.contains("StaticImage", true) }
        if (cam != null) c.setVideoDevice(cam)
        L.i(TAG, "Fotocamera: ${cam ?: "nessuna"} (disponibili: ${devices.joinToString()})")
    }

    /** Vivavoce per le videochiamate, auricolare per le chiamate audio (se non ci sono cuffie/bluetooth). */
    private fun defaultRoute(call: Call, video: Boolean) {
        val devs = core?.audioDevices ?: return
        val external = devs.any { it.type in setOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.Headset, AudioDevice.Type.Headphones) }
        if (external) return
        val target = devs.firstOrNull { it.type == if (video) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece }
        if (target != null) call.outputAudioDevice = target
    }

    fun toggleSpeaker() {
        val call = current ?: return
        setSpeaker(call.outputAudioDevice?.type != AudioDevice.Type.Speaker)
    }

    private fun setSpeaker(on: Boolean) {
        val call = current ?: return
        val devs = core?.audioDevices ?: return
        val target = if (on) devs.firstOrNull { it.type == AudioDevice.Type.Speaker }
        else devs.firstOrNull { it.type == AudioDevice.Type.Bluetooth }
            ?: devs.firstOrNull { it.type == AudioDevice.Type.Headset || it.type == AudioDevice.Type.Headphones }
            ?: devs.firstOrNull { it.type == AudioDevice.Type.Earpiece }
        if (target != null) call.outputAudioDevice = target
        refreshAudioRoute(call)
    }

    private fun refreshAudioRoute(call: Call) {
        val d = call.outputAudioDevice
        Bus.speaker.value = d?.type == AudioDevice.Type.Speaker
        Bus.audioRoute.value = when (d?.type) {
            AudioDevice.Type.Speaker -> "vivavoce"
            AudioDevice.Type.Earpiece -> "auricolare"
            AudioDevice.Type.Bluetooth -> "bluetooth"
            AudioDevice.Type.Headset, AudioDevice.Type.Headphones -> "cuffie"
            null -> ""
            else -> d.type.name.lowercase()
        }
    }

    fun sendDtmfKey(key: Char): Boolean {
        val call = current ?: return false
        if (!answered) return false
        core?.playDtmf(key, 120)
        val r = call.sendDtmf(key)
        L.i(TAG, "DTMF '$key' ($r)")
        return r == 0
    }

    /** Sequenza DTMF; se ne è già in corso un'altra riprova fino a 5 volte. */
    private fun sendDtmfs(seq: String, attempt: Int = 0): Boolean {
        val call = current ?: return false
        val r = call.sendDtmfs(seq)
        L.i(TAG, "DTMF '$seq' ($r)")
        if (r != 0 && attempt < 5) {
            main.postDelayed({ sendDtmfs(seq, attempt + 1) }, 400)
            return true
        }
        return r == 0
    }

    /** Suono locale del tasto (tastierino quando non si è in chiamata). */
    fun playKeyTone(key: Char) {
        if (current == null) core?.playDtmf(key, 100)
    }

    /** Trasferimento diretto (REFER) della chiamata in corso. */
    fun transfer(target: String): String? {
        val c = core ?: return "Il telefono SIP non è attivo"
        val call = current ?: return "Nessuna chiamata in corso"
        if (!answered) return "Rispondi prima di trasferire"
        val addr = resolveAddress(c, target.trim()).getOrElse { return it.message }
        val r = call.transferTo(addr)
        L.i(TAG, "Trasferimento verso ${addr.asStringUriOnly()} ($r)")
        return if (r == 0) null else "Trasferimento non riuscito (codice $r)"
    }

    fun openDoor(b: DoorButton, onResult: (Boolean, String) -> Unit) {
        fun after(ok: Boolean) {
            if (ok && b.hangupAfter) main.postDelayed({ hangup() }, 1500)
        }
        if (b.type == "HTTP") {
            Http.fire(b) { r ->
                onResult(r.ok, if (r.ok) "${b.label}: comando inviato (HTTP ${r.code})" else "${b.label}: errore HTTP ${r.code} ${r.body.take(80)}")
                after(r.ok)
            }
            return
        }
        if (current == null || !answered) return onResult(false, "Rispondi prima di aprire")
        if (b.dtmf.isBlank()) return onResult(false, "Codice DTMF non impostato")
        val ok = sendDtmfs(b.dtmf)
        onResult(ok, if (ok) "${b.label}: toni ${b.dtmf} inviati" else "${b.label}: invio toni non riuscito")
        after(ok)
    }

    // ------------------------------------------------------------------ fine chiamata

    private fun onEnded(call: Call) {
        if (call != current) return
        Ringer.stop()
        CallNotifications.cancelIncoming(app)
        call.removeListener(callListener)
        val err = call.errorInfo
        val code = if (err != null && err.protocolCode > 0) "${err.protocolCode} ${err.phrase.orEmpty()}".trim() else ""
        val noReply = outgoing && ConfigRepository.current.advanced.sipTrace && !SipTrace.hasReply(call.callLog.callId.orEmpty())
        val reasonText = describeEnd(call.reason, code, answered, noReply)
        val duration = if (answered) ((System.currentTimeMillis() - answeredAt) / 1000).toInt() else 0
        CallLog.add(CallEntry(startedAt, remote, name, incoming = !outgoing, answered = answered,
            durationSec = duration, video = hadVideo, reason = if (answered) "" else reasonText))
        if (!outgoing && !answered && !userDeclined) CallNotifications.showMissed(app, name, remote)
        L.i(TAG, "Fine chiamata: $reasonText")

        current = null
        answered = false
        outgoing = false
        runCatching { call.nativeVideoWindowId = null }
        core?.nativeVideoWindowId = null
        core?.nativePreviewWindowId = null
        core?.isVideoPreviewEnabled = false
        Bus.remoteVideo.value = false
        val ended = CallUi.Ended(remote, name, reasonText)
        Bus.call.value = ended
        main.postDelayed({ if (Bus.call.value === ended) Bus.call.value = CallUi.Idle }, 2500)
        PhoneService.updateForCall(app, inCall = false, video = false)
        if (pendingReconfigure) {
            pendingReconfigure = false
            main.post { start() }
        }
    }

    private fun describeEnd(reason: Reason?, code: String, wasAnswered: Boolean, noReply: Boolean): String {
        if (wasAnswered) return "chiamata terminata"
        val text = when (reason) {
            Reason.Declined -> "rifiutata"
            Reason.Busy -> "occupato"
            Reason.NotFound -> "numero inesistente"
            Reason.NotAnswered -> "nessuna risposta"
            Reason.Forbidden, Reason.Unauthorized -> "non autorizzato"
            Reason.IOError, Reason.ServerTimeout, Reason.NoResponse -> "non raggiungibile"
            Reason.TemporarilyUnavailable -> "non disponibile"
            Reason.NotAcceptable, Reason.UnsupportedContent -> "codec/media non compatibili"
            Reason.None, null -> when {
                !outgoing -> "persa"
                noReply -> "nessuna risposta dal dispositivo"
                else -> "annullata"
            }
            Reason.Unknown -> if (code.startsWith("400")) "rifiutata dal dispositivo — se è un citofono Hikvision imposta \"Dominio del mittente\" (Impostazioni → Chiamate dirette IP)" else "rifiutata dal dispositivo"
            else -> reason.name.lowercase()
        }
        return if (code.isNotEmpty()) "$text ($code)" else text
    }

    /** Spiegazione in italiano degli errori di registrazione più comuni. */
    private fun explain(msg: String): String = when {
        msg.startsWith("401") || msg.startsWith("403") -> "$msg — interno o password errati"
        msg.startsWith("404") -> "$msg — interno inesistente sul centralino"
        msg.startsWith("408") || msg.contains("timeout", true) || msg.contains("io error", true) -> "$msg — centralino non raggiungibile"
        else -> msg
    }

    // ------------------------------------------------------------------ video e statistiche

    fun setVideoView(view: TextureView?) {
        core?.nativeVideoWindowId = view
        current?.nativeVideoWindowId = view
    }

    fun setPreviewView(view: TextureView?) {
        core?.nativePreviewWindowId = view
        core?.isVideoPreviewEnabled = view != null
    }

    /** Statistiche della chiamata in corso, da aggiornare ogni secondo (main thread). */
    fun statsText(): String {
        val call = current ?: return ""
        val sb = StringBuilder()
        val p = call.currentParams
        p.usedAudioPayloadType?.let { sb.append("Audio: ${it.mimeType} ${it.clockRate / 1000.0} kHz\n") }
        if (p.isVideoEnabled) {
            p.usedVideoPayloadType?.let { pt ->
                val rx = p.receivedVideoDefinition
                val tx = p.sentVideoDefinition
                sb.append("Video: ${pt.mimeType}  ricevo ${rx?.width ?: 0}x${rx?.height ?: 0} @ ${"%.0f".format(p.receivedFramerate)} fps" +
                    "  invio ${tx?.width ?: 0}x${tx?.height ?: 0} @ ${"%.0f".format(p.sentFramerate)} fps\n")
            }
        }
        call.audioStats?.let { sb.append(streamStats("Audio", it)) }
        if (p.isVideoEnabled) call.videoStats?.let { sb.append(streamStats("Video", it)) }
        sb.append("Cifratura: ${p.mediaEncryption.name}\n")
        call.remoteUserAgent?.takeIf { it.isNotBlank() }?.let { sb.append("Dispositivo remoto: $it\n") }
        sb.append("Contatto remoto: ${call.remoteContact.orEmpty()}\n")
        call.callLog.callId?.let { id ->
            SipTrace.mediaInfo(id, outgoing = false)?.let { sb.append("RTP remoto: $it\n") }
            SipTrace.mediaInfo(id, outgoing = true)?.let { sb.append("RTP locale: $it\n") }
        }
        return sb.toString().trimEnd()
    }

    private fun streamStats(label: String, s: org.linphone.core.CallStats): String =
        "$label: ↓ ${"%.0f".format(s.downloadBandwidth)} kbit/s  ↑ ${"%.0f".format(s.uploadBandwidth)} kbit/s\n" +
            "   persi: ricevuti ${"%.1f".format(s.receiverLossRate)}%  dal remoto ${"%.1f".format(s.senderLossRate)}%  (${s.rtpCumPacketLoss} pacchetti)\n" +
            "   jitter ${"%.0f".format(s.receiverInterarrivalJitter * 1000)} ms  buffer ${"%.0f".format(s.jitterBufferSizeMs)} ms  " +
            "ritardo A/R ${"%.0f".format(s.roundTripDelay * 1000)} ms\n" +
            "   pacchetti ricevuti ${s.rtpPacketRecv}  inviati ${s.rtpPacketSent}" +
            (if (s.iceState != null && s.iceState != org.linphone.core.IceState.NotActivated) "  ICE ${s.iceState.name}" else "") + "\n"
}
