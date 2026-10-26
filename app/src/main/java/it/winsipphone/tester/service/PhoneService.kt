package it.winsipphone.tester.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import it.winsipphone.tester.Bus
import it.winsipphone.tester.R
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.sip.SipManager
import it.winsipphone.tester.ui.MainActivity
import it.winsipphone.tester.util.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Servizio in primo piano sempre attivo: tiene il telefono registrato al centralino. */
class PhoneService : Service() {
    companion object {
        private const val TAG = "Servizio"
        @Volatile var running = false
            private set
        private var inCall = false
        private var videoCall = false
        private var instance: PhoneService? = null

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PhoneService::class.java))
            } catch (e: Exception) {
                L.w(TAG, "Avvio servizio non consentito ora", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneService::class.java))
        }

        /** In chiamata il servizio dichiara anche microfono/fotocamera (audio con app in background). */
        fun updateForCall(context: Context, inCall: Boolean, video: Boolean) {
            this.inCall = inCall
            this.videoCall = video
            instance?.goForeground() ?: if (inCall) start(context) else Unit
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        CallNotifications.createChannels(this)
        goForeground()
        SipManager.start()
        watchNetwork()
        KeepAliveReceiver.schedule(this)
        scope.launch {
            var last = ConfigRepository.current
            ConfigRepository.config.collect { cfg ->
                if (cfg.account != last.account || cfg.advanced != last.advanced || cfg.audio != last.audio ||
                    cfg.video != last.video || cfg.direct != last.direct) SipManager.start()
                else if (cfg.blf != last.blf) SipManager.refreshBlf()
                last = cfg
            }
        }
        scope.launch {
            combine(Bus.registration, Bus.call.map { it }) { r, _ -> r }.distinctUntilChanged().collect { goForeground() }
        }
        L.i(TAG, "Servizio avviato")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        val acc = ConfigRepository.current.account
        val title = if (acc.enabled && acc.username.isNotBlank()) "Interno ${acc.username}: ${Bus.registration.value}"
        else "Telefono SIP attivo (${Bus.registration.value})"
        return NotificationCompat.Builder(this, CallNotifications.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(if (inCall) "Chiamata in corso" else "Pronto a ricevere chiamate")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun goForeground() {
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            if (inCall) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                if (Build.VERSION.SDK_INT >= 30) {
                    if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    if (videoCall && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
        }
        try {
            ServiceCompat.startForeground(this, CallNotifications.ID_SERVICE, notification(), type)
        } catch (e: Exception) {
            // Es. microfono non consentito con l'app in background: ripiego sul tipo base.
            L.w(TAG, "startForeground tipo $type non consentito", e)
            runCatching {
                ServiceCompat.startForeground(this, CallNotifications.ID_SERVICE, notification(),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            }
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Cambio rete (Wi-Fi <-> dati mobili): il telefono si ri-registra subito. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            override fun onAvailable(network: Network) {
                if (network == last) return
                last = network
                L.i(TAG, "Rete disponibile")
                scope.launch { SipManager.onNetworkChanged(true) }
            }

            override fun onLost(network: Network) {
                if (network != last) return
                last = null
                L.i(TAG, "Rete persa")
                scope.launch { SipManager.onNetworkChanged(false) }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    override fun onDestroy() {
        running = false
        instance = null
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel()
        KeepAliveReceiver.cancel(this)
        SipManager.stop()
        L.i(TAG, "Servizio fermato")
        super.onDestroy()
    }
}
