package it.winsipphone.tester.util

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import it.winsipphone.tester.contacts.DoorButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Comandi HTTP dei pulsanti apertura porta (2N, Hikvision, relè...). Accetta certificati self-signed. */
object Http {
    private const val TAG = "Http"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val baseClient: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Autenticazione Basic/Digest automatica (2N e Hikvision usano Digest). */
    private fun clientFor(user: String, password: String): OkHttpClient {
        if (user.isEmpty()) return baseClient
        val creds = Credentials(user, password)
        val authenticator = DispatchingAuthenticator.Builder()
            .with("digest", DigestAuthenticator(creds))
            .with("basic", BasicAuthenticator(creds))
            .build()
        val cache: MutableMap<String, CachingAuthenticator> = ConcurrentHashMap()
        return baseClient.newBuilder()
            .authenticator(CachingAuthenticatorDecorator(authenticator, cache))
            .addInterceptor(AuthenticationCacheInterceptor(cache))
            .build()
    }

    data class Result(val ok: Boolean, val code: Int, val body: String)

    suspend fun execute(b: DoorButton): Result = withContext(Dispatchers.IO) {
        if (b.url.isBlank()) return@withContext Result(false, 0, "URL vuoto")
        try {
            val (url, user, pass) = splitUserInfo(b.url, b.user, b.password)
            val method = b.method.uppercase(Locale.ROOT)
            val body = if (method == "GET") null else b.body.toRequestBody(
                (if (b.body.trimStart().startsWith("{")) "application/json" else if (b.body.trimStart().startsWith("<")) "application/xml" else "text/plain").toMediaTypeOrNull()
            )
            clientFor(user, pass).newCall(Request.Builder().url(url).method(method, body).build()).execute().use { r ->
                val text = r.body?.string()?.take(500) ?: ""
                L.i(TAG, "$method $url -> ${r.code}")
                Result(r.isSuccessful, r.code, text)
            }
        } catch (e: Exception) {
            L.w(TAG, "Richiesta fallita ${b.url}", e)
            Result(false, 0, e.message ?: "errore")
        }
    }

    fun fire(b: DoorButton, onDone: (Result) -> Unit) {
        scope.launch {
            val r = execute(b)
            withContext(Dispatchers.Main) { onDone(r) }
        }
    }

    /** Estrae eventuali credenziali scritte nell'URL (http://user:pass@host/...). */
    private fun splitUserInfo(url: String, user: String, pass: String): Triple<String, String, String> {
        val m = Regex("^(\\w+://)([^/@:]+)(?::([^/@]*))?@(.*)$").find(url) ?: return Triple(url, user, pass)
        val (scheme, u, p, rest) = m.destructured
        return Triple(scheme + rest, user.ifEmpty { java.net.URLDecoder.decode(u, "UTF-8") },
            pass.ifEmpty { java.net.URLDecoder.decode(p, "UTF-8") })
    }
}
