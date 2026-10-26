package it.winsipphone.tester.ui

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import it.winsipphone.tester.R
import it.winsipphone.tester.contacts.Contact
import it.winsipphone.tester.contacts.Contacts
import kotlinx.coroutines.launch

/** Rubrica interna: tocca = chiama, tieni premuto = altre azioni. */
class ContactsTab(private val act: MainActivity) {
    val view: View
    private val adapter = SimpleAdapter<Contact> { c ->
        val subtitle = c.number + (if (c.doors.isNotEmpty()) "  ·  ${c.doors.size} pulsanti porta" else "")
        act.listRow(if (c.favorite) R.drawable.ic_star else R.drawable.ic_person,
            if (c.favorite) Color.rgb(255, 202, 40) else C.accent, c.name, subtitle, if (c.video) "video" else "")
    }
    private var filter = ""

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(4))
        }
        val search = EditText(act).apply {
            hint = "Cerca"
            setTextColor(Color.WHITE)
            setHintTextColor(C.gray)
            isSingleLine = true
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { filter = s.toString().trim().lowercase(); refresh() }
        })
        bar.addView(search, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(act.pillButton("+ Nuovo", C.green) { edit(null) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(8) })
        col.addView(bar)

        val list = ListView(act).apply {
            this.adapter = this@ContactsTab.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ -> val c = this@ContactsTab.adapter.getItem(pos); act.call(c.number, c.video) }
            setOnItemLongClickListener { _, _, pos, _ -> menu(this@ContactsTab.adapter.getItem(pos)); true }
            emptyView = null
        }
        col.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val empty = act.label("Rubrica vuota.\nPremi \"+ Nuovo\" per aggiungere un interno, un citofono o un indirizzo IP.\n" +
            "Per ogni contatto puoi creare pulsanti \"Apri porta\" (toni DTMF o comando HTTP).", 15f, C.dim).apply {
            gravity = Gravity.CENTER
            setPadding(act.dp(24), act.dp(40), act.dp(24), 0)
        }
        col.addView(empty, LinearLayout.LayoutParams(MATCH, WRAP))

        val bottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(8)) }
        bottom.addView(act.pillButton("Esporta", C.button) { exportContacts() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = act.dp(8) })
        bottom.addView(act.pillButton("Importa", C.button) { ImportHelper.pick(act, ImportHelper.KIND_CONTACTS) })
        col.addView(bottom)
        view = col

        act.lifecycleScope.launch {
            Contacts.list.collect {
                refresh()
                empty.show(it.isEmpty())
                list.show(it.isNotEmpty())
            }
        }
    }

    private fun refresh() {
        adapter.items = Contacts.list.value.filter {
            filter.isEmpty() || it.name.lowercase().contains(filter) || it.number.lowercase().contains(filter)
        }
    }

    private fun edit(c: Contact?) {
        act.startActivity(Intent(act, ContactEditActivity::class.java).apply { c?.let { putExtra(ContactEditActivity.EXTRA_ID, it.id) } })
    }

    private fun menu(c: Contact) {
        val items = arrayOf("Chiama (audio)", "Videochiamata", "Modifica", "Elimina")
        AlertDialog.Builder(act).setTitle(c.name).setItems(items) { _, which ->
            when (which) {
                0 -> act.call(c.number, false)
                1 -> act.call(c.number, true)
                2 -> edit(c)
                3 -> AlertDialog.Builder(act).setMessage("Eliminare ${c.name}?")
                    .setPositiveButton("Elimina") { _, _ -> Contacts.delete(c.id) }
                    .setNegativeButton("Annulla", null).show()
            }
        }.show()
    }

    private fun exportContacts() {
        if (Contacts.list.value.isEmpty()) return act.toast("Rubrica vuota")
        act.shareFile("rubrica_winsipphone.json", Contacts.exportJson(), "Rubrica WinSipPhone")
    }
}
