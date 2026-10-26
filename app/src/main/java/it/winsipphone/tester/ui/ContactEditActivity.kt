package it.winsipphone.tester.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import it.winsipphone.tester.contacts.Contact
import it.winsipphone.tester.contacts.Contacts
import it.winsipphone.tester.contacts.DoorButton

/** Nuovo contatto / modifica, con fino a 3 pulsanti "Apri porta". */
class ContactEditActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
        private const val MAX_DOORS = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getStringExtra(EXTRA_ID)?.let { Contacts.get(it) }
        val c = existing ?: Contact(name = intent.getStringExtra(EXTRA_NAME).orEmpty(), number = intent.getStringExtra(EXTRA_NUMBER).orEmpty())

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.bg) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            setBackgroundColor(C.card)
        }
        top.addView(label(if (existing == null) "Nuovo contatto" else "Modifica contatto", 20f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(40)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        val f = Form(this, body)
        val name = f.text("Nome", c.name, "es. Citofono ingresso")
        val number = f.text("Numero o indirizzo", c.number, "201, 192.168.1.50 oppure 201@192.168.1.50")
        val video = f.switch("Chiama in video", c.video)
        val fav = f.switch("Preferito (in cima all'elenco)", c.favorite)

        var doors: List<() -> DoorButton> = emptyList()
        f.buttons(
            "Salva" to {
                val n = number()
                if (n.isBlank()) {
                    toast("Scrivi il numero o l'indirizzo")
                } else {
                    val list = doors.map { it() }.filter { it.label.isNotBlank() && (it.dtmf.isNotBlank() || it.url.isNotBlank()) }
                    Contacts.upsert(c.copy(name = name().ifBlank { n }, number = n, video = video(), favorite = fav(), doors = list))
                    finish()
                }
            },
            "Annulla" to { finish() },
            color = C.green,
        )
        f.section("Pulsanti apertura porta", expanded = c.doors.isNotEmpty())
        f.note("Compaiono durante la chiamata con questo contatto. DTMF = toni inviati in chiamata (es. 2N: codice impostato " +
            "nel citofono). HTTP = comando diretto al dispositivo, es. 2N: http://IP/api/switch/ctrl?switch=1&action=trigger")
        doors = (0 until MAX_DOORS).map { i ->
            val d = c.doors.getOrNull(i) ?: DoorButton(label = "")
            val s = f.sub()
            s.note("Pulsante ${i + 1}")
            val label = s.text("Nome del pulsante (vuoto = non usato)", d.label, "es. Apri cancello")
            val type = s.choice("Tipo", listOf("DTMF" to "Toni DTMF in chiamata", "HTTP" to "Comando HTTP"), d.type)
            val dtmf = s.text("Codice DTMF", d.dtmf, "es. 00 oppure *1#")
            val url = s.text("URL HTTP", d.url, "http://192.168.1.50/api/switch/ctrl?switch=1&action=trigger")
            val method = s.choice("Metodo HTTP", listOf("GET" to "GET", "POST" to "POST", "PUT" to "PUT"), d.method)
            val user = s.text("Utente HTTP (facoltativo)", d.user)
            val pass = s.text("Password HTTP", d.password, password = true)
            val bodyF = s.text("Corpo della richiesta (POST/PUT)", d.body, multiline = true)
            val hang = s.switch("Riaggancia dopo l'apertura", d.hangupAfter)
            val getter: () -> DoorButton = { DoorButton(label(), type(), dtmf(), url(), method(), user(), pass(), bodyF(), hang()) }
            getter
        }

        if (existing != null) {
            f.buttons("Elimina contatto" to {
                AlertDialog.Builder(this).setMessage("Eliminare ${existing.name}?")
                    .setPositiveButton("Elimina") { _, _ -> Contacts.delete(existing.id); finish() }
                    .setNegativeButton("Annulla", null).show()
            }, color = C.red)
        }
    }
}
