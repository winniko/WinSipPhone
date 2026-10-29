package it.winsipphone.tester.config

import android.content.Context
import android.content.SharedPreferences
import it.winsipphone.tester.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ConfigRepository {
    private const val PREFS = "winsipphone"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_INTRO = "intro_done"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        isLenient = true
        coerceInputValues = true
    }

    private lateinit var prefs: SharedPreferences
    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config
    val current: AppConfig get() = _config.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _config.value = migrate(prefs.getString(KEY_CONFIG, null)?.let { parseOrNull(it) } ?: AppConfig())
    }

    fun save(cfg: AppConfig) {
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(cfg)).apply()
        _config.value = cfg
        L.i("Config", "Configurazione salvata")
    }

    fun update(block: (AppConfig) -> AppConfig) = save(block(current))

    /** JSON della configurazione senza password (per condividerla in sicurezza). */
    fun exportJson(withPassword: Boolean): String {
        val c = if (withPassword) current else current.copy(account = current.account.copy(password = ""))
        return json.encodeToString(c)
    }

    /** Restituisce null se ok, altrimenti il messaggio d'errore. */
    fun importJson(text: String): String? = try {
        val c = migrate(json.decodeFromString(AppConfig.serializer(), text))
        // Un file senza password non cancella quella già salvata
        save(if (c.account.password.isEmpty()) c.copy(account = c.account.copy(password = current.account.password)) else c)
        null
    } catch (e: Exception) {
        e.message ?: "File non valido"
    }

    /** v2: porta locale 5060 -> 5062 (la 5060 è bloccata su molti smartphone). */
    private fun migrate(c: AppConfig): AppConfig {
        if (c.configVersion >= 2) return c
        val m = c.copy(
            advanced = if (c.advanced.localPort == 5060) c.advanced.copy(localPort = 5062) else c.advanced,
            configVersion = 2,
        )
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(m)).apply()
        if (m.advanced.localPort != c.advanced.localPort) L.i("Config", "Porta del telefono cambiata da 5060 a 5062")
        return m
    }

    private fun parseOrNull(text: String): AppConfig? = try {
        json.decodeFromString(AppConfig.serializer(), text)
    } catch (e: Exception) {
        L.e("Config", "Configurazione illeggibile, uso i valori predefiniti", e)
        null
    }

    var introDone: Boolean
        get() = prefs.getBoolean(KEY_INTRO, false)
        set(v) { prefs.edit().putBoolean(KEY_INTRO, v).apply() }
}
