package it.winsipphone.tester.contacts

import android.content.Context
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/** Pulsante apertura porta mostrato durante la chiamata con il contatto. */
@Serializable
data class DoorButton(
    val label: String = "Apri porta",
    /** DTMF | HTTP */
    val type: String = "DTMF",
    /** Sequenza DTMF, es. 00 oppure *1 */
    val dtmf: String = "",
    val url: String = "",
    /** GET | POST | PUT */
    val method: String = "GET",
    val user: String = "",
    val password: String = "",
    val body: String = "",
    /** Riaggancia dopo l'apertura */
    val hangupAfter: Boolean = false,
)

@Serializable
data class Contact(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Interno (201), indirizzo IP (192.168.1.50) o indirizzo SIP (201@192.168.1.50) */
    val number: String = "",
    val video: Boolean = false,
    val favorite: Boolean = false,
    val doors: List<DoorButton> = emptyList(),
)

/** Rubrica interna dell'app, salvata in un file JSON (esportabile/importabile). */
object Contacts {
    private const val TAG = "Rubrica"
    private lateinit var file: File
    private val _list = MutableStateFlow<List<Contact>>(emptyList())
    val list: StateFlow<List<Contact>> = _list
    private val serializer = ListSerializer(Contact.serializer())

    fun init(context: Context) {
        file = File(context.filesDir, "contacts.json")
        _list.value = try {
            if (file.exists()) ConfigRepository.json.decodeFromString(serializer, file.readText()) else emptyList()
        } catch (e: Exception) {
            L.e(TAG, "Rubrica illeggibile", e)
            emptyList()
        }
    }

    private fun persist(list: List<Contact>) {
        val sorted = list.sortedWith(compareBy({ !it.favorite }, { it.name.lowercase() }))
        _list.value = sorted
        try {
            file.writeText(ConfigRepository.json.encodeToString(serializer, sorted))
        } catch (e: Exception) {
            L.e(TAG, "Salvataggio rubrica fallito", e)
        }
    }

    fun get(id: String): Contact? = _list.value.firstOrNull { it.id == id }

    fun upsert(c: Contact) = persist(_list.value.filter { it.id != c.id } + c)

    fun delete(id: String) = persist(_list.value.filter { it.id != id })

    /** Contatto corrispondente a un numero/indirizzo (confronto sull'interno o sull'IP). */
    fun find(number: String): Contact? {
        val key = normalize(number)
        if (key.isEmpty()) return null
        return _list.value.firstOrNull { normalize(it.number) == key }
    }

    /** "sip:201@192.168.1.10;transport=udp" -> "201@192.168.1.10"; "201" -> "201" */
    fun normalize(n: String): String {
        var s = n.trim().removePrefix("sip:").removePrefix("sips:")
        s = s.substringBefore(';').substringBefore('>')
        return s.lowercase()
    }

    fun exportJson(): String = ConfigRepository.json.encodeToString(serializer, _list.value)

    /** Aggiunge/aggiorna i contatti del file. Restituisce il numero importato o un errore. */
    fun importJson(text: String): Result<Int> = try {
        val incoming = ConfigRepository.json.decodeFromString(serializer, text)
        val byId = _list.value.associateBy { it.id }.toMutableMap()
        incoming.forEach { byId[it.id] = it }
        persist(byId.values.toList())
        Result.success(incoming.size)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
