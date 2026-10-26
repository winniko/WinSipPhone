package it.winsipphone.tester.ui

import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import it.winsipphone.tester.BlfState
import it.winsipphone.tester.Bus
import it.winsipphone.tester.config.BlfEntry
import it.winsipphone.tester.config.ConfigRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Stato degli interni del centralino (BLF): libero / squilla / occupato. Tocca = chiama. */
class BlfTab(private val act: MainActivity) {
    val view: View
    private val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val info: TextView

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, act.dp(8), 0, 0) }
        info = act.label("", 14f, C.dim).apply { setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(8)) }
        col.addView(info)
        col.addView(ScrollView(act).apply { addView(listBox) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val bottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(8)) }
        bottom.addView(act.pillButton("+ Aggiungi interno", C.green) { addDialog() })
        col.addView(bottom)
        view = col

        act.lifecycleScope.launch {
            combine(ConfigRepository.config, Bus.blf, Bus.registered) { cfg, states, reg -> Triple(cfg, states, reg) }
                .collect { (cfg, states, reg) -> render(cfg.blf, states, reg, cfg.account.enabled) }
        }
    }

    private fun render(entries: List<BlfEntry>, states: Map<String, BlfState>, registered: Boolean, accountOn: Boolean) {
        info.text = when {
            !accountOn -> "Lo stato degli interni richiede l'account del centralino (Impostazioni → Account centralino)."
            !registered -> "Non registrato al centralino: stato non disponibile."
            entries.isEmpty() -> "Aggiungi gli interni da sorvegliare. Sul FreePBX devono avere gli \"hint\" attivi (di norma lo sono)."
            else -> "Tocca un interno per chiamarlo, tieni premuto per rimuoverlo."
        }
        listBox.removeAllViews()
        entries.forEach { e ->
            val st = if (registered) states[e.extension] ?: BlfState.UNKNOWN else BlfState.UNKNOWN
            val color = when (st) {
                BlfState.IDLE -> C.green
                BlfState.RINGING -> C.orange
                BlfState.BUSY -> C.red
                BlfState.OFFLINE -> C.gray
                BlfState.UNKNOWN -> Color.rgb(60, 70, 80)
            }
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
                isClickable = true
                background = act.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { act.call(e.extension, false) }
                setOnLongClickListener { remove(e); true }
            }
            row.addView(act.dot(color, 18))
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(16), 0, 0, 0) }
            texts.addView(act.label(e.label.ifBlank { "Interno ${e.extension}" }, 17f, Color.WHITE))
            texts.addView(act.label("${e.extension}  ·  ${st.label}", 14f, C.dim))
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            listBox.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun addDialog() {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        val ext = EditText(act).apply { hint = "Interno, es. 202 (più interni: 202,203,204)"; inputType = InputType.TYPE_CLASS_TEXT }
        val name = EditText(act).apply { hint = "Nome (facoltativo)" }
        box.addView(ext)
        box.addView(name)
        AlertDialog.Builder(act).setTitle("Aggiungi interno").setView(box)
            .setPositiveButton("Aggiungi") { _, _ ->
                val exts = ext.text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                if (exts.isEmpty()) return@setPositiveButton
                ConfigRepository.update { c ->
                    val existing = c.blf.map { it.extension }.toSet()
                    val added = exts.filter { it !in existing }.map { BlfEntry(it, if (exts.size == 1) name.text.toString().trim() else "") }
                    c.copy(blf = c.blf + added)
                }
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun remove(e: BlfEntry) {
        AlertDialog.Builder(act).setMessage("Rimuovere l'interno ${e.extension} dall'elenco?")
            .setPositiveButton("Rimuovi") { _, _ -> ConfigRepository.update { c -> c.copy(blf = c.blf.filter { it.extension != e.extension }) } }
            .setNegativeButton("Annulla", null).show()
    }
}
