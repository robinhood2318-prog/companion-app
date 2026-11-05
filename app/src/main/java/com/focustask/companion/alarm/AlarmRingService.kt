package com.focustask.companion.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.data.model.AlarmEntity
import com.focustask.companion.tts.TtsManager
import com.focustask.companion.ui.AlarmAlertActivity
import com.focustask.companion.util.VoiceSettings
import com.focustask.companion.util.notifyIfAllowed
import com.focustask.companion.util.startForegroundCompat

/**
 * Plays the alarm sound, vibration and voice callout in a foreground service, so the alarm keeps
 * ringing even if the alert screen is closed. The alert screen stops this service when the
 * challenge is solved or the alarm is snoozed.
 */
class AlarmRingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var tts: TtsManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Alarm"
        val customUri = intent.getStringExtra(EXTRA_CUSTOM_URI)
        val callout = intent.getBooleanExtra(EXTRA_CALLOUT, true) &&
            VoiceSettings.isEnabled(this, VoiceSettings.Section.ALARMS)

        val launchedByScreen = intent.getBooleanExtra(EXTRA_LAUNCHED_BY_SCREEN, false)
        val screenIntent = alertIntent(this, intent).putExtra(EXTRA_FROM_SERVICE, true)
        try {
            startForegroundCompat(NOTIFICATION_ID, buildNotification(this, label, screenIntent),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot ring in the foreground; showing notification only", e)
            notifyIfAllowed(NOTIFICATION_ID, buildNotification(this, label, alertIntent(this, intent)))
            stopSelf()
            return START_NOT_STICKY
        }

        startSound(customUri)
        startVibration()
        if (callout) {
            if (tts == null) tts = TtsManager(this)
            tts?.speak("Alarm: $label. Time to take action!")
        }

        // Works when the full-screen intent is blocked but "display over other apps" is granted.
        if (!launchedByScreen) {
            try {
                startActivity(screenIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not open alarm screen directly; notification is shown instead", e)
            }
        }

        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, MAX_RING_MS)
        Log.d(TAG, "Ringing alarm $alarmId")
        return START_NOT_STICKY
    }

    /** Tries the custom sound first, then falls back so the alarm is never silent. */
    private fun startSound(customUri: String?) {
        stopSound()
        val candidates = listOfNotNull(
            customUri?.let { Uri.parse(it) },
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        )
        for (uri in candidates) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.setDataSource(this, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                Log.w(TAG, "Alarm sound $uri failed, trying fallback", e)
                mp.release()
            }
        }
    }

    private fun stopSound() {
        player?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        player = null
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (v?.hasVibrator() == true) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
            vibrator = v
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        stopSound()
        vibrator?.cancel()
        tts?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AlarmRingService"
        const val NOTIFICATION_ID = 4004
        const val ACTION_STOP = "com.focustask.companion.ACTION_STOP_RINGING"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_LABEL = "extra_alarm_label"
        const val EXTRA_CALLOUT = "extra_callout_enabled"
        const val EXTRA_CUSTOM_URI = "extra_custom_uri"
        const val EXTRA_CHALLENGE = "extra_challenge"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
        const val EXTRA_FROM_SERVICE = "extra_from_service"
        private const val EXTRA_LAUNCHED_BY_SCREEN = "extra_launched_by_screen"
        private const val MAX_RING_MS = 10 * 60_000L

        fun start(context: Context, alarm: AlarmEntity) {
            ContextCompat.startForegroundService(context, extrasFor(context, alarm))
        }

        /** Copies the alarm extras into an intent for the alert screen. */
        fun alertIntent(context: Context, source: Intent): Intent =
            Intent(context, AlarmAlertActivity::class.java).apply {
                putExtras(source)
                removeExtra(EXTRA_LAUNCHED_BY_SCREEN)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }

        private fun buildNotification(context: Context, label: String, alertIntent: Intent): Notification {
            val pending = PendingIntent.getActivity(
                context, 0, alertIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, FocusTaskApp.CHANNEL_ALARM_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Alarm")
                .setContentText("$label — tap to dismiss or snooze")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pending, true)
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        }

        /**
         * Used when Android refuses to start the ringing service from the background.
         * Opening the alert screen from this notification starts the ringing service.
         */
        fun showFallbackNotification(context: Context, alarm: AlarmEntity) {
            val intent = alertIntent(context, extrasFor(context, alarm))
            context.notifyIfAllowed(NOTIFICATION_ID, buildNotification(context, alarm.label, intent))
        }

        private fun extrasFor(context: Context, alarm: AlarmEntity) =
            Intent(context, AlarmRingService::class.java).apply {
                putExtra(EXTRA_ALARM_ID, alarm.id)
                putExtra(EXTRA_LABEL, alarm.label)
                putExtra(EXTRA_CALLOUT, alarm.isVoiceCalloutEnabled)
                putExtra(EXTRA_CUSTOM_URI, alarm.customAudioUri)
                putExtra(EXTRA_CHALLENGE, alarm.dismissChallenge)
                putExtra(EXTRA_SNOOZE_MINUTES, alarm.snoozeMinutes)
            }

        /** Called by the alert screen when it was opened from the fallback notification. */
        fun startFromScreen(context: Context, screenIntent: Intent) {
            val intent = Intent(context, AlarmRingService::class.java).apply {
                putExtras(screenIntent)
                putExtra(EXTRA_LAUNCHED_BY_SCREEN, true)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmRingService::class.java))
        }
    }
}
