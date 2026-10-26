package it.winsipphone.tester

import kotlinx.coroutines.flow.MutableStateFlow

sealed class CallUi {
    data object Idle : CallUi()
    data class Incoming(val remote: String, val name: String, val video: Boolean) : CallUi()
    data class Outgoing(val remote: String, val name: String, val video: Boolean, val status: String) : CallUi()
    data class Active(
        val remote: String,
        val name: String,
        val since: Long,
        val video: Boolean,
        val paused: Boolean,
        val pausedByRemote: Boolean,
    ) : CallUi()
    /** Chiamata appena terminata: il motivo resta visibile qualche secondo. */
    data class Ended(val remote: String, val name: String, val reason: String) : CallUi()
}

/** Stato di un interno sorvegliato (BLF). */
enum class BlfState(val label: String) {
    UNKNOWN("sconosciuto"), IDLE("libero"), RINGING("squilla"), BUSY("occupato"), OFFLINE("non raggiungibile")
}

/** Stato condiviso tra servizio e interfaccia (stesso processo). */
object Bus {
    /** Testo dello stato registrazione */
    val registration = MutableStateFlow("non configurato")
    /** true = registrato al centralino */
    val registered = MutableStateFlow(false)
    val call = MutableStateFlow<CallUi>(CallUi.Idle)
    /** Il video remoto sta arrivando */
    val remoteVideo = MutableStateFlow(false)
    /** Interno -> stato */
    val blf = MutableStateFlow<Map<String, BlfState>>(emptyMap())
    /** Altoparlante / microfono / audio dispositivo attuale */
    val speaker = MutableStateFlow(false)
    val muted = MutableStateFlow(false)
    val audioRoute = MutableStateFlow("")
    /** Contatto (rubrica) della chiamata in corso, per i pulsanti porta */
    val callContactId = MutableStateFlow<String?>(null)
}
