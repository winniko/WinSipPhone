package it.winsipphone.tester.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.winsipphone.tester.BuildConfig
import it.winsipphone.tester.Bus
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.diag.NetTest
import it.winsipphone.tester.service.CallNotifications
import it.winsipphone.tester.sip.SipManager
import it.winsipphone.tester.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Strumenti da collaudatore: stato, registro SIP, test rete, log, permessi. */
class ToolsTab(private val act: MainActivity) {
    val view: View
    private val status: TextView

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(16)) }
        col.addView(act.label("Stato", 20f, C.accent, bold = true))
        status = act.label("", 14f, Color.WHITE).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, act.dp(6), 0, act.dp(12))
        }
        col.addView(status)

        fun button(text: String, color: Int = C.button, onClick: () -> Unit) {
            col.addView(act.pillButton(text, color, onClick), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(8) })
        }
        button("Registro SIP", Color.rgb(21, 101, 192)) {
            act.startActivity(Intent(act, SipTraceActivity::class.java))
        }
        button("Test raggiungibilità", Color.rgb(21, 101, 192)) {
            act.startActivity(Intent(act, NetTestActivity::class.java))
        }
        button("Ri-registra ora al centralino") {
            SipManager.keepAliveTick()
            act.toast("Registrazione inviata")
        }
        button("Registro completo (da inviare per assistenza)") { showLog() }
        button("Permessi e batteria") { act.recheckPermissions() }
        button("Informazioni e licenza") { about() }
        view = ScrollView(act).apply { addView(col) }

        act.lifecycleScope.launch {
            act.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val ips = withContext(Dispatchers.IO) { NetTest.localIps() }
                    status.text = statusText(ips)
                    delay(3000)
                }
            }
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED

    private fun statusText(ips: List<String>): String {
        val cfg = ConfigRepository.current
        val acc = cfg.account
        val pm = act.getSystemService(PowerManager::class.java)
        fun yn(b: Boolean) = if (b) "sì" else "NO"
        return buildString {
            append("Registrazione: ${Bus.registration.value}\n")
            if (acc.enabled) append("Account: ${acc.username}@${acc.server}:${acc.port} ${acc.transport}\n")
            append("Porta SIP di questo telefono: ${if (cfg.advanced.localPort <= 0) "casuale" else cfg.advanced.localPort}" +
                (if (cfg.advanced.localPort == 5060) "  ⚠ sconsigliata: usa 5062" else "") + "\n")
            append("IP del telefono: ${ips.joinToString().ifEmpty { "nessuna rete" }}\n")
            append("Chiamate dirette IP in arrivo: ${if (cfg.direct.acceptIncoming) "accettate" else "rifiutate"}\n")
            append("Permessi: microfono ${yn(granted(Manifest.permission.RECORD_AUDIO))}, fotocamera ${yn(granted(Manifest.permission.CAMERA))}")
            if (Build.VERSION.SDK_INT >= 33) append(", notifiche ${yn(granted(Manifest.permission.POST_NOTIFICATIONS))}")
            append("\nSenza limiti batteria: ${yn(pm.isIgnoringBatteryOptimizations(act.packageName))}\n")
            append("Chiamata a schermo intero: ${yn(CallNotifications.canFullScreen(act))}\n")
            append("Versione app: ${BuildConfig.VERSION_NAME}")
        }
    }

    private fun showLog() {
        val tv = act.label(L.dump().ifEmpty { "(vuoto)" }, 11f, Color.WHITE).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
        }
        val sv = ScrollView(act).apply { addView(tv); post { fullScroll(View.FOCUS_DOWN) } }
        AlertDialog.Builder(act).setTitle("Registro dell'app").setView(sv)
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Condividi") { _, _ -> shareDiagnostics() }
            .show()
    }

    /** Un solo file per l'assistenza: stato, impostazioni (senza password), log dell'app e registro SIP. */
    private fun shareDiagnostics() {
        val cfg = ConfigRepository.exportJson(withPassword = false)
        val text = "===== WinSipPhone Tester ${BuildConfig.VERSION_NAME} — ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n\n" +
            "===== STATO\n" + statusText(NetTest.localIps()) + "\n\n" +
            "===== IMPOSTAZIONI (senza password)\n" + cfg + "\n\n" +
            "===== REGISTRO APP\n" + L.dump() + "\n\n" +
            "===== REGISTRO SIP\n" + it.winsipphone.tester.sip.SipTrace.export().ifEmpty { "(vuoto: registro SIP disattivato o nessun messaggio)" }
        act.shareFile("log_winsipphone.txt", text, "Log WinSipPhone")
    }

    private fun about() {
        AlertDialog.Builder(act).setTitle("WinSipPhone Tester ${BuildConfig.VERSION_NAME}")
            .setMessage("Telefono SIP e strumento di collaudo per centralini e videocitofoni.\n\n" +
                "Software libero e gratuito, licenza GNU AGPL v3. Usa liblinphone (Belledonne Communications, AGPL v3).\n\n" +
                "Codice sorgente: ${BuildConfig.SOURCE_URL}\n\n" +
                "Fornito senza garanzia: non usarlo come unico mezzo per le chiamate di emergenza.")
            .setPositiveButton("Ok", null)
            .setNeutralButton("Apri sorgente") { _, _ -> runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_URL))) } }
            .show()
    }
}
