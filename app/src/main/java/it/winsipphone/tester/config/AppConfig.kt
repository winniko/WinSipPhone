package it.winsipphone.tester.config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val account: AccountConfig = AccountConfig(),
    val direct: DirectConfig = DirectConfig(),
    val audio: AudioConfig = AudioConfig(),
    val video: VideoConfig = VideoConfig(),
    val advanced: AdvancedConfig = AdvancedConfig(),
    /** Interni da sorvegliare nella scheda "Interni" (stato libero/occupato, BLF). */
    val blf: List<BlfEntry> = emptyList(),
    /** Versione del formato configurazione (per le conversioni automatiche). */
    val configVersion: Int = 0,
)

/** Account del centralino (FreePBX / Asterisk o qualsiasi PBX SIP). */
@Serializable
data class AccountConfig(
    val enabled: Boolean = false,
    val displayName: String = "",
    /** Interno, es. 201 */
    val username: String = "",
    /** Utente di autenticazione se diverso dall'interno (di solito vuoto). */
    val authUsername: String = "",
    val password: String = "",
    /** Indirizzo IP o nome del centralino */
    val server: String = "",
    val port: Int = 5060,
    /** UDP | TCP | TLS */
    val transport: String = "UDP",
    /** Dominio SIP se diverso dal server (di solito vuoto). */
    val domain: String = "",
    /** Proxy in uscita opzionale, es. sbc.example.com:5060 */
    val outboundProxy: String = "",
    val expiresSec: Int = 600,
)

/** Chiamate dirette verso/da indirizzi IP, senza centralino. */
@Serializable
data class DirectConfig(
    val acceptIncoming: Boolean = true,
    /** IP autorizzati a chiamare direttamente (vuoto = tutti), separati da virgola. */
    val allowedIps: String = "",
    /**
     * Dominio del mittente nelle chiamate dirette (es. IP del FreePBX). I citofoni Hikvision accettano
     * solo chiamate che sembrano arrivare dal loro server SIP: con un altro mittente rispondono 400 Bad Request.
     * Vuoto = dominio dell'account (se attivo), altrimenti l'IP del telefono.
     */
    val fromDomain: String = "",
    /** Numero del mittente nelle chiamate dirette (vuoto = interno dell'account, altrimenti "winsipphone"). */
    val fromUser: String = "",
)

@Serializable
data class AudioConfig(
    val echoCancellation: Boolean = true,
    val micGainDb: Float = 0f,
    val playbackGainDb: Float = 0f,
    /** RFC2833 | SIP_INFO | INBAND */
    val dtmfMode: String = "RFC2833",
    val vibrate: Boolean = true,
    /** Codec audio attivi, in ordine di preferenza. */
    val codecs: String = "OPUS,G722,PCMA,PCMU",
)

@Serializable
data class VideoConfig(
    val enabled: Boolean = true,
    /** Accetta automaticamente il video quando l'altra parte lo propone. */
    val autoAccept: Boolean = true,
    val frontCamera: Boolean = true,
    /** Codec video attivi, in ordine di preferenza. */
    val codecs: String = "H264,VP8",
)

@Serializable
data class AdvancedConfig(
    /**
     * Porta SIP di questo telefono (0 = casuale). NON 5060: molti smartphone (es. OnePlus) la riservano
     * alle chiamate dell'operatore (VoLTE/VoWiFi) e scartano le risposte SIP in arrivo.
     */
    val localPort: Int = 5062,
    val keepAlive: Boolean = true,
    val stunServer: String = "",
    val ice: Boolean = false,
    /** NONE | SRTP | ZRTP */
    val mediaEncryption: String = "NONE",
    val ringTimeoutSec: Int = 45,
    val autoAnswer: Boolean = false,
    /** Registra anche i messaggi SIP completi (necessario per la scheda "Registro SIP"). */
    val sipTrace: Boolean = true,
    /** Log dettagliati della libreria nel logcat (solo per sviluppatori). */
    val debugLogs: Boolean = false,
)

@Serializable
data class BlfEntry(
    val extension: String = "",
    val label: String = "",
)
