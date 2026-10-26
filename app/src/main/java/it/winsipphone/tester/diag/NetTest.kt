package it.winsipphone.tester.diag

import it.winsipphone.tester.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Test di raggiungibilità indipendenti dal motore SIP (si possono usare da qualsiasi thread).
 * Ogni funzione restituisce righe di testo in italiano da mostrare all'utente.
 */
object NetTest {

    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${ni.name})" } }
    } catch (e: Exception) {
        emptyList()
    }

    fun resolve(host: String): String = try {
        val all = InetAddress.getAllByName(host).map { it.hostAddress }
        if (all.size == 1 && all[0] == host) "✓ indirizzo IP $host" else "✓ DNS: $host → ${all.joinToString()}"
    } catch (e: Exception) {
        "✗ DNS: impossibile risolvere \"$host\" (${e.message})"
    }

    /** Ping con il comando di sistema (3 pacchetti). */
    fun ping(host: String): String = try {
        val p = ProcessBuilder("/system/bin/ping", "-c", "3", "-W", "2", host).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        p.waitFor()
        val loss = Regex("(\\d+)% packet loss").find(out)?.groupValues?.get(1)
        val avg = Regex("= [\\d.]+/([\\d.]+)/").find(out)?.groupValues?.get(1)
        when {
            loss == null -> "? Ping non disponibile: ${out.lines().firstOrNull { it.isNotBlank() }.orEmpty()}"
            loss == "100" -> "✗ Ping $host: nessuna risposta (spento, rete diversa o ping bloccato dal firewall)"
            else -> "✓ Ping $host: persi $loss%, tempo medio ${avg ?: "?"} ms"
        }
    } catch (e: Exception) {
        "? Ping non eseguibile su questo telefono (${e.message})"
    }

    fun tcpPort(host: String, port: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
            "✓ Porta TCP $port aperta (${System.currentTimeMillis() - t0} ms)"
        } catch (e: SocketTimeoutException) {
            "✗ Porta TCP $port: nessuna risposta in 3 s (firewall o dispositivo spento)"
        } catch (e: java.net.ConnectException) {
            "✗ Porta TCP $port chiusa (il dispositivo risponde ma nessun servizio su questa porta)"
        } catch (e: Exception) {
            "✗ Porta TCP $port: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
        }
    }

    /**
     * Invia un SIP OPTIONS ("ci sei?") e mostra la risposta: codice, dispositivo (User-Agent/Server)
     * e metodi supportati. Funziona con centralini, citofoni e telefoni SIP.
     */
    fun sipOptions(host: String, port: Int, tcp: Boolean): List<String> {
        val proto = if (tcp) "TCP" else "UDP"
        val out = mutableListOf<String>()
        try {
            val target = InetAddress.getByName(host)
            val branch = "z9hG4bK" + UUID.randomUUID().toString().replace("-", "").take(16)
            val tag = UUID.randomUUID().toString().take(8)
            val callId = UUID.randomUUID().toString() + "@winsipphone"
            val t0 = System.currentTimeMillis()
            val response: String
            if (tcp) {
                Socket().use { s ->
                    s.connect(InetSocketAddress(target, port), 3000)
                    s.soTimeout = 3000
                    val local = s.localAddress.hostAddress
                    val req = request(host, port, local, s.localPort, "TCP", branch, tag, callId)
                    s.getOutputStream().write(req.toByteArray())
                    val buf = ByteArray(8192)
                    val n = s.getInputStream().read(buf)
                    response = if (n > 0) String(buf, 0, n) else ""
                }
            } else {
                DatagramSocket().use { s ->
                    s.soTimeout = 3000
                    s.connect(target, port)
                    val local = s.localAddress?.hostAddress ?: localIps().firstOrNull()?.substringBefore(' ') ?: "0.0.0.0"
                    val req = request(host, port, local, s.localPort, "UDP", branch, tag, callId).toByteArray()
                    var got: String? = null
                    // Su UDP ritento 2 volte (i pacchetti possono perdersi)
                    for (attempt in 0..2) {
                        s.send(DatagramPacket(req, req.size))
                        try {
                            val buf = ByteArray(8192)
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            got = String(p.data, 0, p.length)
                            break
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                    response = got ?: ""
                }
            }
            val ms = System.currentTimeMillis() - t0
            if (response.isBlank()) {
                out += "✗ SIP OPTIONS $proto $host:$port: nessuna risposta (porta sbagliata, firewall o SIP disattivato)"
                return out
            }
            val status = response.lineSequence().first().trim()
            fun header(vararg names: String) = names.firstNotNullOfOrNull { n ->
                Regex("(?im)^$n:\\s*(.+)$").find(response)?.groupValues?.get(1)?.trim()
            }
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val meaning = when (code) {
                200 -> "risponde"
                401, 407 -> "risponde (chiede autenticazione: normale per i centralini)"
                403 -> "risponde ma rifiuta (indirizzo non autorizzato)"
                404 -> "risponde (utente sconosciuto: normale)"
                405, 501 -> "risponde (OPTIONS non supportato: normale)"
                else -> "risponde"
            }
            out += "✓ SIP OPTIONS $proto $host:$port $meaning in $ms ms"
            out += "   Risposta: $status"
            header("Server", "User-Agent")?.let { out += "   Dispositivo: $it" }
            header("Allow")?.let { out += "   Metodi: $it" }
            header("Accept")?.let { out += "   Accetta: $it" }
            Regex("(?im)^(Via|v):.*received=([0-9.]+)").find(response)?.groupValues?.get(2)?.let {
                out += "   Il dispositivo ci vede con IP: $it" + if (it !in localIps().map { l -> l.substringBefore(' ') }) " (passa da un NAT/router)" else ""
            }
        } catch (e: Exception) {
            out += "✗ SIP OPTIONS $proto $host:$port: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
        }
        return out
    }

    private fun request(host: String, port: Int, localIp: String, localPort: Int, proto: String, branch: String, tag: String, callId: String): String {
        val uri = "sip:$host:$port"
        return "OPTIONS $uri SIP/2.0\r\n" +
            "Via: SIP/2.0/$proto $localIp:$localPort;branch=$branch;rport\r\n" +
            "Max-Forwards: 70\r\n" +
            "From: <sip:tester@$localIp>;tag=$tag\r\n" +
            "To: <$uri>\r\n" +
            "Call-ID: $callId\r\n" +
            "CSeq: 1 OPTIONS\r\n" +
            "Contact: <sip:tester@$localIp:$localPort;transport=${proto.lowercase()}>\r\n" +
            "Accept: application/sdp\r\n" +
            "User-Agent: WinSipPhone-Tester/${BuildConfig.VERSION_NAME}\r\n" +
            "Content-Length: 0\r\n\r\n"
    }
}
