package it.winsipphone.tester.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.winsipphone.tester.Bus
import it.winsipphone.tester.CallUi
import it.winsipphone.tester.R
import it.winsipphone.tester.contacts.Contacts
import it.winsipphone.tester.sip.SipManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.linphone.mediastream.video.capture.CaptureTextureView

/** Schermata della chiamata (in arrivo, in uscita, in corso). Si apre anche a telefono bloccato. */
class CallActivity : AppCompatActivity() {
    companion object {
        const val ACTION_ANSWER = "it.winsipphone.tester.ANSWER"
    }

    private lateinit var remoteVideo: TextureView
    private lateinit var preview: CaptureTextureView
    private lateinit var nameText: TextView
    private lateinit var numberText: TextView
    private lateinit var stateText: TextView
    private lateinit var statsText: TextView
    private lateinit var incomingBar: LinearLayout
    private lateinit var answerVideo: View
    private lateinit var controls: LinearLayout
    private lateinit var doorsBar: LinearLayout
    private lateinit var doorsScroll: HorizontalScrollView
    private lateinit var hangupBar: View
    private lateinit var keypad: LinearLayout
    private lateinit var keypadDigits: TextView
    private val toggles = HashMap<String, RoundButton>()
    private var showStats = false
    private var proximity: PowerManager.WakeLock? = null
    private var videoAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        handleAction(intent)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 3)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(Bus.call, Bus.remoteVideo) { s, v -> s to v }.collect { (s, v) -> render(s, v) } }
                launch { Bus.muted.collect { toggles["mute"]?.setColor(if (it) C.red else C.button) } }
                launch {
                    combine(Bus.speaker, Bus.audioRoute) { sp, r -> sp to r }.collect { (sp, r) ->
                        toggles["speaker"]?.setColor(if (sp) C.accent else C.button)
                        toggles["speaker"]?.label?.text = if (r.isNotEmpty() && !sp) r.replaceFirstChar { it.uppercase() } else "Vivavoce"
                        updateProximity()
                    }
                }
                launch { Bus.callContactId.collect { buildDoors(it) } }
                launch {
                    while (true) {
                        tick()
                        delay(1000)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(i: Intent?) {
        if (i?.action == ACTION_ANSWER) {
            val s = Bus.call.value
            if (s is CallUi.Incoming) SipManager.accept(s.video && hasCamera())
        }
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ interfaccia

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(12, 16, 20)) }
        remoteVideo = TextureView(this).apply { visibility = View.GONE }
        root.addView(remoteVideo, FrameLayout.LayoutParams(MATCH, MATCH))
        preview = CaptureTextureView(this).apply { visibility = View.GONE }
        root.addView(preview, FrameLayout.LayoutParams(dp(110), dp(150), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(170); marginEnd = dp(12)
        })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(48), dp(16), dp(24))
        }
        nameText = label("", 30f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
        numberText = label("", 16f, C.dim).apply { gravity = Gravity.CENTER; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
        stateText = label("", 18f, C.accent).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); setShadowLayer(6f, 0f, 0f, Color.BLACK) }
        col.addView(nameText, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(numberText, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(stateText, LinearLayout.LayoutParams(MATCH, WRAP))

        statsText = label("", 11f, Color.WHITE).apply {
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        col.addView(statsText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        col.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Pulsanti porta del contatto
        doorsBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        doorsScroll = HorizontalScrollView(this).apply { addView(doorsBar); visibility = View.GONE }
        col.addView(doorsScroll, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(12) })

        // Comandi in chiamata
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun toggle(key: String, row: LinearLayout, icon: Int, text: String, action: () -> Unit) {
            val b = RoundButton(this, icon, C.button, text, 58).apply { this.label.textSize = 12f; onClick(action) }
            toggles[key] = b
            row.addView(b, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        toggle("mute", r1, R.drawable.ic_mic_off, "Muto") { SipManager.toggleMute() }
        toggle("speaker", r1, R.drawable.ic_speaker, "Vivavoce") { SipManager.toggleSpeaker() }
        toggle("hold", r1, R.drawable.ic_pause, "Attesa") { SipManager.toggleHold() }
        toggle("keypad", r1, R.drawable.ic_dialpad, "Tastiera") { keypad.show(keypad.visibility != View.VISIBLE) }
        toggle("transfer", r2, R.drawable.ic_transfer, "Trasferisci") { transferDialog() }
        toggle("video", r2, R.drawable.ic_videocam, "Video") {
            if (hasCamera()) SipManager.toggleVideo() else requestPermissions(arrayOf(Manifest.permission.CAMERA), 4)
        }
        toggle("camera", r2, R.drawable.ic_switch_camera, "Cambia cam") { SipManager.switchCamera() }
        toggle("stats", r2, R.drawable.ic_stats, "Statistiche") {
            showStats = !showStats
            statsText.show(showStats)
            toggles["stats"]?.setColor(if (showStats) C.accent else C.button)
            tick()
        }
        controls.addView(r1, LinearLayout.LayoutParams(MATCH, WRAP))
        controls.addView(r2, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        col.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

        // Chiamata in arrivo
        incomingBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE }
        incomingBar.addView(RoundButton(this, R.drawable.ic_call_end, C.red, "Rifiuta").apply { onClick { SipManager.hangup() } }, LinearLayout.LayoutParams(0, WRAP, 1f))
        answerVideo = RoundButton(this, R.drawable.ic_videocam, C.green, "Video").apply { onClick { SipManager.accept(hasCamera()) } }
        incomingBar.addView(answerVideo, LinearLayout.LayoutParams(0, WRAP, 1f))
        incomingBar.addView(RoundButton(this, R.drawable.ic_call, C.green, "Rispondi").apply { onClick { SipManager.accept(false) } }, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(incomingBar, LinearLayout.LayoutParams(MATCH, WRAP))

        hangupBar = RoundButton(this, R.drawable.ic_call_end, C.red, "Chiudi").apply { onClick { SipManager.hangup() } }
        col.addView(hangupBar, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // Tastiera DTMF
        keypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 17, 23, 29))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            isClickable = true
        }
        keypadDigits = label("", 28f, Color.WHITE).apply { gravity = Gravity.CENTER; minHeight = dp(44) }
        keypad.addView(keypadDigits, LinearLayout.LayoutParams(MATCH, WRAP))
        listOf("123", "456", "789", "*0#").forEach { row ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { k ->
                r.addView(DialpadTab.key(this, k.toString(), "") {
                    if (SipManager.sendDtmfKey(k)) keypadDigits.text = (keypadDigits.text.toString() + k).takeLast(20)
                }, LinearLayout.LayoutParams(0, dp(64), 1f))
            }
            keypad.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        keypad.addView(pillButton("Chiudi tastiera", C.button) { keypad.show(false) }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        root.addView(keypad, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun render(s: CallUi, remoteVideoOn: Boolean) {
        when (s) {
            is CallUi.Idle -> { finish(); return }
            is CallUi.Incoming -> {
                nameText.text = s.name
                numberText.text = if (s.name != s.remote) s.remote else ""
                stateText.text = if (s.video) "Videochiamata in arrivo" else "Chiamata in arrivo"
                incomingBar.show(true)
                answerVideo.show(s.video)
                controls.show(false)
                hangupBar.show(false)
            }
            is CallUi.Outgoing -> {
                nameText.text = s.name
                numberText.text = if (s.name != s.remote) s.remote else ""
                stateText.text = s.status
                incomingBar.show(false)
                controls.show(false)
                hangupBar.show(true)
            }
            is CallUi.Active -> {
                nameText.text = s.name
                numberText.text = if (s.name != s.remote) s.remote else ""
                incomingBar.show(false)
                controls.show(true)
                hangupBar.show(true)
                toggles["hold"]?.setColor(if (s.paused) C.orange else C.button)
                toggles["video"]?.setColor(if (s.video) C.accent else C.button)
                toggles["camera"]?.show(s.video)
                tick()
            }
            is CallUi.Ended -> {
                nameText.text = s.name
                numberText.text = if (s.name != s.remote) s.remote else ""
                stateText.text = s.reason.replaceFirstChar { it.uppercase() }
                incomingBar.show(false)
                controls.show(false)
                hangupBar.show(false)
                keypad.show(false)
            }
        }
        val video = s is CallUi.Active && s.video
        attachVideo(video, remoteVideoOn)
        updateProximity()
    }

    private fun attachVideo(video: Boolean, remoteOn: Boolean) {
        if (video && !videoAttached) {
            remoteVideo.show(true)
            preview.show(true)
            SipManager.setVideoView(remoteVideo)
            SipManager.setPreviewView(preview)
            videoAttached = true
        } else if (!video && videoAttached) {
            SipManager.setVideoView(null)
            SipManager.setPreviewView(null)
            remoteVideo.show(false)
            preview.show(false)
            videoAttached = false
        }
        // Finché non arriva il primo fotogramma lascio visibili nome e stato
        nameText.alpha = if (video && remoteOn) 0.6f else 1f
    }

    private fun tick() {
        val s = Bus.call.value
        if (s is CallUi.Active) {
            val sec = ((System.currentTimeMillis() - s.since) / 1000).toInt()
            stateText.text = when {
                s.pausedByRemote -> "In attesa (dall'altra parte) · ${formatDuration(sec)}"
                s.paused -> "In attesa · ${formatDuration(sec)}"
                else -> formatDuration(sec)
            }
        }
        if (showStats) statsText.text = SipManager.statsText().ifEmpty { "Statistiche disponibili a chiamata avviata" }
    }

    private fun buildDoors(contactId: String?) {
        doorsBar.removeAllViews()
        val doors = contactId?.let { Contacts.get(it) }?.doors.orEmpty()
        doorsScroll.show(doors.isNotEmpty())
        doors.forEach { d ->
            doorsBar.addView(pillButton(d.label.ifBlank { "Apri porta" }, Color.rgb(0, 121, 107)) {
                SipManager.openDoor(d) { _, msg -> toast(msg) }
            }.apply { setIconResource(R.drawable.ic_lock_open) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
    }

    private fun transferDialog() {
        val e = EditText(this).apply { hint = "Interno o indirizzo, es. 203"; isSingleLine = true }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        val favs = Contacts.list.value.take(6)
        if (favs.isNotEmpty()) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            favs.forEach { c -> chips.addView(pillButton(c.name, C.button) { e.setText(c.number) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }) }
            box.addView(HorizontalScrollView(this).apply { addView(chips) })
        }
        AlertDialog.Builder(this).setTitle("Trasferisci la chiamata").setView(box)
            .setPositiveButton("Trasferisci") { _, _ ->
                SipManager.transfer(e.text.toString())?.let { toast(it) } ?: toast("Trasferimento in corso...")
            }
            .setNegativeButton("Annulla", null).show()
    }

    /** Schermo spento avvicinando il telefono all'orecchio (solo chiamate audio con l'auricolare). */
    private fun updateProximity() {
        val s = Bus.call.value
        val want = (s is CallUi.Active && !s.video || s is CallUi.Outgoing && !s.video) && !Bus.speaker.value
        val pm = getSystemService(PowerManager::class.java)
        if (want && proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "WinSipPhone:proximity").also { it.acquire(4 * 3600 * 1000L) }
        } else if (!want && proximity != null) {
            proximity?.let { if (it.isHeld) it.release() }
            proximity = null
        }
    }

    override fun onDestroy() {
        proximity?.let { if (it.isHeld) it.release() }
        proximity = null
        if (videoAttached) {
            SipManager.setVideoView(null)
            SipManager.setPreviewView(null)
        }
        super.onDestroy()
    }
}
