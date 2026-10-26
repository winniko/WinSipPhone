package it.winsipphone.tester.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.diag.NetTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test di raggiungibilità di centralini, citofoni e telefoni: DNS, ping, porta TCP, SIP OPTIONS. */
class NetTestActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(C.card)
        }
        top.addView(label("Test raggiungibilità", 20f, Color.WHITE, bold = true))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val f = Form(this, body)
        val acc = ConfigRepository.current.account
        val host = f.text("Indirizzo da provare", acc.server, "es. 192.168.1.10 (centralino, citofono, telefono)")
        val port = f.int("Porta SIP", if (acc.server.isNotBlank()) acc.port else 5060)
        f.buttons(
            "Test completo" to { run(host(), port()) { h, p -> full(h, p) } },
            "Ping" to { run(host(), port()) { h, _ -> listOf(NetTest.ping(h)) } },
            "SIP UDP" to { run(host(), port()) { h, p -> NetTest.sipOptions(h, p, tcp = false) } },
            "SIP TCP" to { run(host(), port()) { h, p -> NetTest.sipOptions(h, p, tcp = true) } },
            "Porta TCP" to { run(host(), port()) { h, p -> listOf(NetTest.tcpPort(h, p)) } },
            color = android.graphics.Color.rgb(21, 101, 192),
        )
        f.note("Suggerimento: il citofono 2N risponde di solito sulla 5060, i centralini FreePBX sulla 5060 (chan_pjsip) o 5160.")
        root.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))

        output = label("", 13f, Color.WHITE).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { addView(output) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        bottom.addView(pillButton("Condividi risultato", C.button) {
            if (output.text.isNotBlank()) shareFile("test_rete.txt", output.text.toString(), "Test rete WinSipPhone")
        })
        root.addView(bottom)
        setContentView(root)
        lifecycleScope.launch {
            val ips = withContext(Dispatchers.IO) { NetTest.localIps() }
            append("IP di questo telefono: ${ips.joinToString().ifEmpty { "nessuna rete" }}")
        }
    }

    private fun full(h: String, p: Int): List<String> = buildList {
        add(NetTest.resolve(h))
        add(NetTest.ping(h))
        addAll(NetTest.sipOptions(h, p, tcp = false))
        add(NetTest.tcpPort(h, p))
        if (last().startsWith("✓")) addAll(NetTest.sipOptions(h, p, tcp = true))
        add(NetTest.tcpPort(h, 80) + "  (interfaccia web / API HTTP)")
    }

    private fun run(host: String, port: Int, test: (String, Int) -> List<String>) {
        if (host.isBlank()) return toast("Scrivi un indirizzo")
        if (job?.isActive == true) return toast("Test già in corso")
        job = lifecycleScope.launch {
            append("\n▶ $host:$port ...")
            val lines = withContext(Dispatchers.IO) { test(host.trim(), port) }
            lines.forEach { append(it) }
        }
    }

    private fun append(line: String) {
        output.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
