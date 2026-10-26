package it.winsipphone.tester.history

import android.content.Context
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class CallEntry(
    val time: Long = 0,
    val number: String = "",
    val name: String = "",
    val incoming: Boolean = true,
    val answered: Boolean = false,
    val durationSec: Int = 0,
    val video: Boolean = false,
    /** Motivo di fine/errore riportato dal SIP (es. "Busy Here", "Not Found") */
    val reason: String = "",
)

/** Storico chiamate (ultime 300). */
object CallLog {
    private const val MAX = 300
    private lateinit var file: File
    private val _list = MutableStateFlow<List<CallEntry>>(emptyList())
    val list: StateFlow<List<CallEntry>> = _list
    private val serializer = ListSerializer(CallEntry.serializer())

    fun init(context: Context) {
        file = File(context.filesDir, "calllog.json")
        _list.value = try {
            if (file.exists()) ConfigRepository.json.decodeFromString(serializer, file.readText()) else emptyList()
        } catch (e: Exception) {
            L.e("Storico", "Storico illeggibile", e)
            emptyList()
        }
    }

    fun add(e: CallEntry) = persist((listOf(e) + _list.value).take(MAX))

    fun delete(e: CallEntry) = persist(_list.value.filter { it != e })

    fun clear() = persist(emptyList())

    private fun persist(l: List<CallEntry>) {
        _list.value = l
        try {
            file.writeText(ConfigRepository.json.encodeToString(serializer, l))
        } catch (ex: Exception) {
            L.e("Storico", "Salvataggio storico fallito", ex)
        }
    }
}
