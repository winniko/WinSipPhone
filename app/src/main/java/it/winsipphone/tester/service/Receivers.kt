package it.winsipphone.tester.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.sip.SipManager
import it.winsipphone.tester.util.L

/** Avvio automatico all'accensione del telefono e dopo un aggiornamento dell'app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ConfigRepository.current.account.enabled && !ConfigRepository.current.direct.acceptIncoming) return
        L.i("Avvio", "Avvio automatico (${intent.action})")
        PhoneService.start(context)
    }
}

/**
 * Sveglia periodica: in standby (Doze) Android ferma i timer dell'app; ogni pochi minuti
 * risveglio il telefono per un attimo e rinnovo la registrazione al centralino.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    companion object {
        private const val INTERVAL_MS = 9 * 60 * 1000L

        private fun pending(ctx: Context) = PendingIntent.getBroadcast(ctx, 0,
            Intent(ctx, KeepAliveReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(ctx))
            }
        }

        fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pr = goAsync()
        val wl = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WinSipPhone:keepalive")
        wl.acquire(10_000)
        Handler(Looper.getMainLooper()).post {
            if (PhoneService.running) SipManager.keepAliveTick() else PhoneService.start(context)
            schedule(context)
            // Lascio qualche secondo al motore SIP per completare la registrazione
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { if (wl.isHeld) wl.release() }
                pr.finish()
            }, 6_000)
        }
    }
}

/** Pulsante "Rifiuta" della notifica di chiamata in arrivo. */
class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DECLINE = "it.winsipphone.tester.DECLINE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DECLINE) SipManager.hangup()
    }
}
