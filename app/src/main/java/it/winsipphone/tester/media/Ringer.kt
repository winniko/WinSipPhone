package it.winsipphone.tester.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import it.winsipphone.tester.config.ConfigRepository
import it.winsipphone.tester.util.L

/** Suoneria delle chiamate in arrivo: suoneria di sistema del telefono, rispetta silenzioso/vibrazione. */
object Ringer {
    private const val TAG = "Suoneria"
    private lateinit var app: Context
    private var player: MediaPlayer? = null
    private var vibrating = false

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun start() {
        stop()
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = am.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            try {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(app, uri)
                    isLooping = true
                    setOnPreparedListener { it.start() }
                    prepareAsync()
                }
            } catch (e: Exception) {
                L.w(TAG, "Suoneria non disponibile", e)
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT && ConfigRepository.current.audio.vibrate) {
            vibrator()?.let {
                it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 1))
                vibrating = true
            }
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        if (vibrating) {
            vibrator()?.cancel()
            vibrating = false
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}
