package it.winsipphone.tester.sip

import it.winsipphone.tester.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import org.linphone.core.Factory
import org.linphone.core.LogLevel
import org.linphone.core.LoggingService
import org.linphone.core.LoggingServiceListener
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Registro dei messaggi SIP (INVITE, 401, 200 OK...) intercettati dai log di belle-sip.
 * I log arrivano da thread qualsiasi: tutto è sincronizzato.
 */
object SipTrace {
    data class Msg(
        val time: Long,
        val outgoing: Boolean,
        /** es. UDP://192.168.1.10:5060 */
        val remote: String,
        val firstLine: String,
        val text: String,
    ) {
        /** Riassunto per l'elenco: "INVITE 201" oppure "401 Unauthorized" */
        val summary: String get() {
            val p = firstLine.split(' ')
            return if (firstLine.startsWith("SIP/2.0")) p.drop(1).joinToString(" ")
            else p.getOrNull(0).orEmpty() + " " + p.getOrNull(1).orEmpty().removePrefix("sip:").substringBefore(';')
        }
        val cseqMethod: String get() = Regex("(?im)^CSeq:\\s*\\d+\\s+(\\S+)").find(text)?.groupValues?.get(1).orEmpty()
    }

    private const val MAX = 400
    private val buffer = ArrayDeque<Msg>(MAX)
    /** Aumenta a ogni messaggio: l'interfaccia lo osserva per aggiornarsi. */
    val version = MutableStateFlow(0)
    @Volatile var paused = false

    private val startLine = Regex("(?m)^(SIP/2\\.0 \\d{3}[^\\r\\n]*|[A-Z]+ \\S+ SIP/2\\.0)\\r?$")
    private val transport = Regex("\\[(UDP|TCP|TLS|udp|tcp|tls)://([^\\]]+)\\]")
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY)

    private val listener = object : LoggingServiceListener {
        override fun onLogMessageWritten(logService: LoggingService, domain: String, level: LogLevel, message: String) {
            val m = message
            if (level == LogLevel.Error || level == LogLevel.Fatal) L.w("Linphone", m.take(300))
            if (!m.contains("SIP/2.0")) return
            parse(m)
        }
    }

    fun install(enabled: Boolean) {
        val ls = Factory.instance().loggingService
        ls.removeListener(listener)
        ls.addListener(listener)
        // I messaggi SIP completi sono a livello "Message" (molto dettagliato): solo se il registro è attivo.
        ls.setLogLevel(if (enabled) LogLevel.Message else LogLevel.Warning)
    }

    private fun parse(m: String) {
        val match = startLine.find(m) ?: return
        val head = m.substring(0, match.range.first)
        val outgoing = when {
            head.contains("sent to", true) || head.contains("sending", true) -> true
            head.contains("received", true) || head.contains("from [", true) || head.contains("read", true) -> false
            else -> return
        }
        val remote = transport.find(head)?.let { "${it.groupValues[1].uppercase()}://${it.groupValues[2]}" } ?: ""
        val text = m.substring(match.range.first).trimEnd()
        add(Msg(System.currentTimeMillis(), outgoing, remote, match.value.trim(), text))
    }

    private fun add(msg: Msg) {
        if (paused) return
        synchronized(buffer) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(msg)
        }
        version.value = version.value + 1
    }

    /** Dal più recente al più vecchio. */
    fun snapshot(): List<Msg> = synchronized(buffer) { buffer.toList().asReversed() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        version.value = version.value + 1
    }

    /** Indirizzi RTP dall'ultimo SDP della chiamata [callId]: "audio 192.168.1.10:12000, video ..." */
    fun mediaInfo(callId: String, outgoing: Boolean): String? {
        val idRe = Regex("(?im)^(Call-ID|i):\\s*(\\S+)")
        val msg = synchronized(buffer) {
            buffer.lastOrNull { it.outgoing == outgoing && it.text.contains("m=audio") && idRe.find(it.text)?.groupValues?.get(2) == callId }
        } ?: return null
        val conn = Regex("(?m)^c=IN IP[46] (\\S+)").find(msg.text)?.groupValues?.get(1) ?: "?"
        return Regex("(?m)^m=(audio|video) (\\d+)").findAll(msg.text)
            .joinToString(", ") { "${it.groupValues[1]} $conn:${it.groupValues[2]}" }
    }

    fun time(t: Long): String = synchronized(fmt) { fmt.format(Date(t)) }

    /** Testo completo da condividere (dal più vecchio al più recente, come un trace). */
    fun export(): String = synchronized(buffer) {
        buffer.joinToString("\n\n") { m ->
            "===== ${time(m.time)} ${if (m.outgoing) "INVIATO a" else "RICEVUTO da"} ${m.remote}\n${m.text}"
        }
    }
}
