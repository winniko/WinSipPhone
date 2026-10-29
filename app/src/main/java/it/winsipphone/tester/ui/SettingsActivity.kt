package it.winsipphone.tester.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.winsipphone.tester.config.AccountConfig
import it.winsipphone.tester.config.AdvancedConfig
import it.winsipphone.tester.config.AppConfig
import it.winsipphone.tester.config.AudioConfig
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.config.DirectConfig
import it.winsipphone.tester.config.VideoConfig
import it.winsipphone.tester.diag.NetTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Impostazioni: "Salva" salva e resta, "Esci" chiede se salvare le modifiche. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var build: () -> AppConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            setBackgroundColor(C.card)
        }
        top.addView(label("Impostazioni", 20f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(pillButton("Salva", C.green) { save() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        top.addView(pillButton("Esci", C.button) { exit() })
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(40)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        val c = ConfigRepository.current
        val f = Form(this, body)

        f.section("Account centralino (FreePBX)", expanded = !c.account.enabled)
        f.note("Dati dell'interno creato sul centralino (FreePBX: Applications → Extensions → interno → \"Secret\").")
        val aOn = f.switch("Usa il centralino", c.account.enabled)
        val aUser = f.text("Interno", c.account.username, "es. 201")
        val aPass = f.text("Password (secret)", c.account.password, password = true)
        val aServer = f.text("Indirizzo del centralino", c.account.server, "es. 192.168.1.10 o pbx.miodominio.it")
        val aPort = f.int("Porta del centralino", c.account.port, "di solito 5060 (TLS: 5061)")
        val aTr = f.choice("Protocollo", listOf("UDP" to "UDP (standard)", "TCP" to "TCP (meglio fuori casa / con VPN)", "TLS" to "TLS (cifrato)"), c.account.transport)
        val aName = f.text("Nome visualizzato (facoltativo)", c.account.displayName, "es. Mario cellulare")
        val aAuth = f.text("Utente di autenticazione (di solito vuoto)", c.account.authUsername)
        val aDomain = f.text("Dominio SIP (di solito vuoto = indirizzo centralino)", c.account.domain)
        val aProxy = f.text("Proxy in uscita (di solito vuoto)", c.account.outboundProxy, "host:porta")
        val aExp = f.int("Durata registrazione (secondi)", c.account.expiresSec)
        f.buttons("Prova connessione al centralino" to { testServer(aServer(), aPort(), aTr()) })

        f.section("Chiamate dirette IP")
        f.note("Per chiamare senza centralino scrivi nel tastierino l'indirizzo IP (192.168.1.50) o interno@IP (201@192.168.1.50).")
        val dIn = f.switch("Accetta chiamate dirette in arrivo (senza centralino)", c.direct.acceptIncoming)
        val dIps = f.text("IP autorizzati a chiamare (vuoto = tutti)", c.direct.allowedIps, "es. 192.168.1.50, 192.168.1.51")
        val dFromDom = f.text("Dominio del mittente (citofoni Hikvision: IP del loro server SIP)", c.direct.fromDomain, "es. 192.168.1.10 — vuoto = automatico")
        val dFromUser = f.text("Numero del mittente (facoltativo)", c.direct.fromUser, "es. 200")
        f.note("I citofoni Hikvision rispondono \"400 Bad Request\" alle chiamate dirette che non sembrano arrivare dal " +
            "loro server SIP: scrivi qui l'indirizzo del server impostato nel citofono (Configurazione → VoIP → Indirizzo IP del server).")
        val xPort = f.int("Porta SIP di QUESTO telefono (consigliata 5062, 0 = casuale)", c.advanced.localPort)
        f.note("Non è la porta del centralino. Evita la 5060: molti smartphone la riservano alle chiamate " +
            "dell'operatore e le risposte SIP non arrivano (il telefono chiamato continua a squillare). " +
            "Gli altri dispositivi chiamano questo telefono su ip-del-telefono:porta (vedi Strumenti).")

        f.section("Audio")
        val uEc = f.switch("Cancellazione eco", c.audio.echoCancellation)
        val uMic = f.float("Guadagno microfono (dB, es. 0, 3, 6)", c.audio.micGainDb)
        val uSpk = f.float("Guadagno altoparlante (dB, es. 0, 3, 6)", c.audio.playbackGainDb)
        val uDtmf = f.choice("Invio toni DTMF", listOf("RFC2833" to "RFC 2833 (consigliato)", "SIP_INFO" to "SIP INFO", "INBAND" to "Nell'audio (in-band)"), c.audio.dtmfMode)
        val uCodec = f.text("Codec audio, in ordine di preferenza", c.audio.codecs, "OPUS,G722,PCMA,PCMU")
        val uVib = f.switch("Vibrazione per le chiamate in arrivo", c.audio.vibrate)

        f.section("Video")
        val vOn = f.switch("Videochiamate attive", c.video.enabled)
        val vAuto = f.switch("Accetta il video proposto dall'altra parte", c.video.autoAccept)
        val vFront = f.switch("Usa la fotocamera frontale", c.video.frontCamera)
        val vCodec = f.text("Codec video, in ordine di preferenza", c.video.codecs, "H264,VP8")
        f.note("Sul FreePBX il video va abilitato per l'interno (Advanced → \"Allow\" con h264 e/o vp8).")

        f.section("Avanzate")
        val xKa = f.switch("Keep-alive (mantiene aperto il collegamento col centralino)", c.advanced.keepAlive)
        val xStun = f.text("Server STUN (fuori casa senza VPN, es. stun.l.google.com:19302)", c.advanced.stunServer)
        val xIce = f.switch("ICE (con STUN)", c.advanced.ice)
        val xEnc = f.choice("Cifratura audio/video", listOf("NONE" to "Nessuna", "SRTP" to "SRTP", "ZRTP" to "ZRTP"), c.advanced.mediaEncryption)
        val xRing = f.int("Durata squillo in arrivo (secondi)", c.advanced.ringTimeoutSec)
        val xAuto = f.switch("Risposta automatica (per collaudi)", c.advanced.autoAnswer)
        val xTrace = f.switch("Registro SIP attivo", c.advanced.sipTrace)
        val xDebug = f.switch("Log dettagliati nel logcat (sviluppatori)", c.advanced.debugLogs)

        f.section("Backup configurazione")
        f.note("Il file serve per copiare la configurazione su un altro telefono. La password è inclusa solo se lo scegli.")
        f.buttons(
            "Esporta" to { shareFile("config_winsipphone.json", ConfigRepository.exportJson(withPassword = false), "Configurazione WinSipPhone") },
            "Esporta con password" to { shareFile("config_winsipphone.json", ConfigRepository.exportJson(withPassword = true), "Configurazione WinSipPhone") },
            "Importa" to { ImportHelper.pick(this, ImportHelper.KIND_CONFIG) },
        )

        build = {
            c.copy(
                account = AccountConfig(aOn(), aName(), aUser(), aAuth(), aPass(), aServer(), aPort().coerceIn(1, 65535), aTr(),
                    aDomain(), aProxy(), aExp().coerceIn(60, 86400)),
                direct = DirectConfig(dIn(), dIps(), dFromDom(), dFromUser()),
                audio = AudioConfig(uEc(), uMic().coerceIn(-20f, 20f), uSpk().coerceIn(-20f, 20f), uDtmf(), uVib(), uCodec().uppercase()),
                video = VideoConfig(vOn(), vAuto(), vFront(), vCodec().uppercase()),
                advanced = AdvancedConfig(xPort().coerceIn(0, 65535), xKa(), xStun(), xIce(), xEnc(), xRing().coerceIn(10, 600), xAuto(), xTrace(), xDebug()),
            )
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exit()
        })
    }

    private fun save() {
        val cfg = build()
        if (cfg.account.enabled && (cfg.account.username.isBlank() || cfg.account.server.isBlank())) {
            toast("Per usare il centralino servono interno e indirizzo")
            return
        }
        ConfigRepository.save(cfg)
        toast("Impostazioni salvate")
    }

    private fun exit() {
        if (build() == ConfigRepository.current) return finish()
        AlertDialog.Builder(this).setMessage("Hai modifiche non salvate.")
            .setPositiveButton("Salva ed esci") { _, _ -> save(); if (build() == ConfigRepository.current) finish() }
            .setNegativeButton("Esci senza salvare") { _, _ -> finish() }
            .setNeutralButton("Annulla", null)
            .show()
    }

    private fun testServer(host: String, port: Int, transport: String) {
        if (host.isBlank()) return toast("Scrivi l'indirizzo del centralino")
        val dlg = AlertDialog.Builder(this).setTitle("Prova connessione").setMessage("Verifica in corso...").setPositiveButton("Ok", null).show()
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                listOf(NetTest.resolve(host)) + NetTest.sipOptions(host, port, tcp = transport != "UDP")
            }
            dlg.setMessage(lines.joinToString("\n") +
                "\n\nSe il centralino risponde ma la registrazione fallisce, controlla interno e password.")
        }
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        ImportHelper.handle(this, requestCode, resultCode, data)
    }
}
