package it.winsipphone.tester.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.sip.SipTrace
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Elenco dei messaggi SIP scambiati, dal più recente. Tocca un messaggio per vederlo per intero. */
class SipTraceActivity : AppCompatActivity() {
    private var filter = ""
    private lateinit var pauseBtn: com.google.android.material.button.MaterialButton
    private lateinit var count: TextView

    private val adapter = SimpleAdapter<SipTrace.Msg> { m ->
        val code = if (m.firstLine.startsWith("SIP/2.0")) m.firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0 else 0
        val color = when {
            code >= 400 -> C.red
            code in 100..199 -> C.dim
            code in 200..299 -> C.green
            m.outgoing -> C.accent
            else -> Color.rgb(255, 202, 40)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val arrow = if (m.outgoing) "→ inviato" else "← ricevuto"
        row.addView(label(m.summary + if (code > 0 && m.cseqMethod.isNotEmpty()) "  (${m.cseqMethod})" else "", 15f, color, bold = true).apply { maxLines = 1 })
        row.addView(label("${SipTrace.time(m.time)}  $arrow  ${m.remote}", 12f, C.dim).apply { typeface = Typeface.MONOSPACE })
        row
    }

    @OptIn(FlowPreview::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(C.card)
        }
        pauseBtn = pillButton("Pausa", C.button) { togglePause() }
        top.addView(pauseBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        top.addView(pillButton("Cancella", C.button) { SipTrace.clear() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        top.addView(pillButton("Condividi", C.green) {
            val text = SipTrace.export()
            if (text.isBlank()) toast("Nessun messaggio") else shareFile("sip_trace.txt", text, "Registro SIP WinSipPhone")
        })
        root.addView(HorizontalScrollView(this).apply { addView(top); setBackgroundColor(C.card) }, LinearLayout.LayoutParams(MATCH, WRAP))

        val search = EditText(this).apply {
            hint = "Filtra (es. INVITE, 401, 192.168.1.10)"
            setTextColor(Color.WHITE)
            setHintTextColor(C.gray)
            isSingleLine = true
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { filter = s.toString().trim(); refresh() }
        })
        root.addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply { marginStart = dp(12); marginEnd = dp(12) })
        count = label("", 12f, C.dim).apply { setPadding(dp(16), 0, dp(16), dp(4)) }
        root.addView(count)
        if (!ConfigRepository.current.advanced.sipTrace) {
            root.addView(label("Il registro SIP è disattivato (Impostazioni → Avanzate).", 14f, C.orange).apply { setPadding(dp(16), dp(4), dp(16), dp(4)) })
        }
        val list = ListView(this).apply {
            adapter = this@SipTraceActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> show(this@SipTraceActivity.adapter.getItem(pos)) }
        }
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        updatePauseLabel()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SipTrace.version.sample(700).collect { refresh() }
            }
        }
        refresh()
    }

    private fun refresh() {
        val all = SipTrace.snapshot()
        val shown = if (filter.isEmpty()) all else all.filter { it.text.contains(filter, true) || it.remote.contains(filter, true) }
        adapter.items = shown
        count.text = if (filter.isEmpty()) "${all.size} messaggi (ultimi 400)" else "${shown.size} di ${all.size} messaggi"
    }

    private fun togglePause() {
        SipTrace.paused = !SipTrace.paused
        updatePauseLabel()
    }

    private fun updatePauseLabel() {
        pauseBtn.text = if (SipTrace.paused) "Riprendi" else "Pausa"
    }

    private fun show(m: SipTrace.Msg) {
        val tv = label(m.text, 12f, Color.WHITE).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("${if (m.outgoing) "Inviato a" else "Ricevuto da"} ${m.remote}  ${SipTrace.time(m.time)}")
            .setView(ScrollView(this).apply { addView(HorizontalScrollView(this@SipTraceActivity).apply { addView(tv) }) })
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Copia") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SIP", m.text))
                toast("Copiato")
            }
            .show()
    }
}
