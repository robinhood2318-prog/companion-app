package com.focustask.companion.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.util.startForegroundCompat

/** Plays generated noise or the user's own audio file in a loop. */
class AmbientAudioService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var noisePlayer: NoisePlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val soundType = intent.getStringExtra(EXTRA_SOUND_TYPE) ?: TYPE_BROWN
        val customUri = intent.getStringExtra(EXTRA_CUSTOM_URI)

        try {
            startForegroundCompat(NOTIFICATION_ID, buildNotification(labelFor(soundType)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start ambient audio", e)
            stopSelf()
            return START_NOT_STICKY
        }

        stopPlayback()
        if (soundType == TYPE_CUSTOM && customUri != null) {
            playFile(Uri.parse(customUri))
        } else {
            noisePlayer = NoisePlayer(soundType).also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun playFile(uri: Uri) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.isLooping = true
            mp.setDataSource(this, uri)
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Playback error $what/$extra")
                stopSelf()
                true
            }
            mp.prepareAsync()
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.w(TAG, "Cannot play $uri", e)
            mp.release()
            stopSelf()
        }
    }

    private fun stopPlayback() {
        noisePlayer?.stop()
        noisePlayer = null
        mediaPlayer?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        mediaPlayer = null
    }

    private fun buildNotification(sound: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AmbientAudioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FocusTaskApp.CHANNEL_AUDIO_ID)
            .setContentTitle("Ambient sound playing")
            .setContentText(sound)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AmbientAudioService"
        const val NOTIFICATION_ID = 4003
        const val EXTRA_SOUND_TYPE = "extra_sound_type"
        const val EXTRA_CUSTOM_URI = "extra_custom_uri"
        private const val ACTION_STOP = "com.focustask.companion.ACTION_STOP_AMBIENT"

        const val TYPE_BROWN = "brown"
        const val TYPE_PINK = "pink"
        const val TYPE_WHITE = "white"
        const val TYPE_CUSTOM = "custom"

        fun labelFor(type: String) = when (type) {
            TYPE_BROWN -> "Ocean rumble (brown noise)"
            TYPE_PINK -> "Gentle rain (pink noise)"
            TYPE_WHITE -> "White noise"
            else -> "Your audio file"
        }

        fun start(context: Context, type: String, customUri: String? = null) {
            val intent = Intent(context, AmbientAudioService::class.java)
                .putExtra(EXTRA_SOUND_TYPE, type)
                .putExtra(EXTRA_CUSTOM_URI, customUri)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AmbientAudioService::class.java))
        }
    }
}
