package it.winsipphone.tester.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import it.winsipphone.tester.R
import it.winsipphone.tester.contacts.Contacts
import it.winsipphone.tester.history.CallEntry
import it.winsipphone.tester.history.CallLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Storico chiamate: entranti, uscenti, perse (con il motivo SIP). */
class HistoryTab(private val act: MainActivity) {
    val view: View
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY)
    private val adapter = SimpleAdapter<CallEntry> { e ->
        val missed = e.incoming && !e.answered
        val icon = if (e.incoming) R.drawable.ic_call else R.drawable.ic_transfer
        val color = when {
            missed -> C.red
            !e.answered -> C.orange
            else -> C.green
        }
        val what = when {
            e.answered -> (if (e.video) "video " else "") + formatDuration(e.durationSec)
            e.reason.isNotEmpty() -> e.reason
            missed -> "persa"
            else -> "non risposta"
        }
        val name = if (e.name != e.number) "${e.name}  (${e.number})" else e.number
        act.listRow(icon, color, name, (if (e.incoming) "Entrante · " else "Uscente · ") + what, fmt.format(Date(e.time)))
    }

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val list = ListView(act).apply {
            this.adapter = this@HistoryTab.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ -> val e = this@HistoryTab.adapter.getItem(pos); act.call(e.number, e.video) }
            setOnItemLongClickListener { _, _, pos, _ -> menu(this@HistoryTab.adapter.getItem(pos)); true }
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val empty = act.label("Nessuna chiamata.", 15f, C.dim).apply { gravity = Gravity.CENTER; setPadding(0, act.dp(40), 0, 0) }
        col.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))
        val bottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(8)) }
        bottom.addView(act.pillButton("Cancella storico", C.button) {
            AlertDialog.Builder(act).setMessage("Cancellare tutto lo storico chiamate?")
                .setPositiveButton("Cancella") { _, _ -> CallLog.clear() }
                .setNegativeButton("Annulla", null).show()
        })
        col.addView(bottom)
        view = col
        act.lifecycleScope.launch {
            CallLog.list.collect {
                adapter.items = it
                empty.show(it.isEmpty())
            }
        }
    }

    private fun menu(e: CallEntry) {
        val inContacts = Contacts.find(e.number) != null
        val items = mutableListOf("Chiama (audio)", "Videochiamata", "Copia nel tastierino")
        if (!inContacts) items += "Aggiungi in rubrica"
        items += "Elimina dallo storico"
        AlertDialog.Builder(act).setTitle(e.name).setItems(items.toTypedArray()) { _, which ->
            when (items[which]) {
                "Chiama (audio)" -> act.call(e.number, false)
                "Videochiamata" -> act.call(e.number, true)
                "Copia nel tastierino" -> { act.dialpad.setNumber(e.number); act.selectTab(MainActivity.TAB_DIAL) }
                "Aggiungi in rubrica" -> act.startActivity(Intent(act, ContactEditActivity::class.java)
                    .putExtra(ContactEditActivity.EXTRA_NUMBER, e.number)
                    .putExtra(ContactEditActivity.EXTRA_NAME, if (e.name != e.number) e.name else ""))
                else -> CallLog.delete(e)
            }
        }.show()
    }
}
