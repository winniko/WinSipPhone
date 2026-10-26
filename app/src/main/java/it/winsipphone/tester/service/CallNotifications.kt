package it.winsipphone.tester.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import it.winsipphone.tester.R
import it.winsipphone.tester.ui.CallActivity
import it.winsipphone.tester.ui.MainActivity

/** Notifiche: chiamata in arrivo (schermo intero), chiamata persa. */
object CallNotifications {
    const val CH_SERVICE = "service"
    const val CH_INCOMING = "incoming_v1"
    const val CH_MISSED = "missed"
    const val ID_SERVICE = 1
    private const val ID_INCOMING = 2
    private const val ID_MISSED = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Telefono attivo", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Notifica fissa: il telefono resta collegato al centralino"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Chiamate in arrivo", NotificationManager.IMPORTANCE_HIGH).apply {
            // La suoneria la gestisce l'app (Ringer): il canale è silenzioso per non suonare due volte.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Chiamate perse", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun callIntent(ctx: Context, action: String?, req: Int): PendingIntent {
        val i = Intent(ctx, CallActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showIncoming(ctx: Context, name: String, number: String, video: Boolean) {
        val full = callIntent(ctx, null, 10)
        val answer = callIntent(ctx, CallActivity.ACTION_ANSWER, 11)
        val decline = PendingIntent.getBroadcast(ctx, 12,
            Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val caller = Person.Builder().setName(name).setImportant(true).build()
        val n = NotificationCompat.Builder(ctx, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText((if (video) "Videochiamata in arrivo" else "Chiamata in arrivo") + if (name != number) " — $number" else "")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer).setIsVideo(video))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_INCOMING, n)
    }

    fun cancelIncoming(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_INCOMING)
    }

    fun showMissed(ctx: Context, name: String, number: String) {
        val open = PendingIntent.getActivity(ctx, 20,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_HISTORY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Chiamata persa")
            .setContentText(if (name != number) "$name ($number)" else name)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_MISSED, n)
    }

    /** Su Android 14+ la schermata a pieno schermo richiede un permesso che l'utente può revocare. */
    fun canFullScreen(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
