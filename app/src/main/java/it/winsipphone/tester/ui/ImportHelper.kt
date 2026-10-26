package it.winsipphone.tester.ui

import android.app.Activity
import android.content.Intent
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.contacts.Contacts

/** Importazione di rubrica e configurazione da un file scelto dall'utente. */
object ImportHelper {
    const val KIND_CONTACTS = 501
    const val KIND_CONFIG = 502

    @Suppress("DEPRECATION")
    fun pick(act: Activity, kind: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        runCatching { act.startActivityForResult(i, kind) }.onFailure { act.toast("Nessuna app per scegliere i file") }
    }

    /** Restituisce true se il risultato riguardava un'importazione (e l'ha gestito). */
    fun handle(act: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != KIND_CONTACTS && requestCode != KIND_CONFIG) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        val text = try {
            act.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            act.toast("File vuoto o illeggibile")
            return true
        }
        if (requestCode == KIND_CONTACTS) {
            Contacts.importJson(text).fold(
                { act.toast("Importati $it contatti") },
                { act.toast("Non è un file rubrica valido (${it.message?.take(80)})") })
        } else {
            val err = ConfigRepository.importJson(text)
            act.toast(if (err == null) "Configurazione importata" else "Non è un file di configurazione valido (${err.take(80)})")
            if (err == null) act.recreate()
        }
        return true
    }
}
