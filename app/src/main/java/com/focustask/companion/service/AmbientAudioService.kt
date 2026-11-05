package com.focustask.companion.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.util.startForegroundCompat

/**
 * Plays a generated soundscape or the user's own audio file in a loop.
 * Its state (playing, type, volume) is kept in SharedPreferences so screens can show it.
 */
class AmbientAudioService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var noisePlayer: NoisePlayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_SET_VOLUME) {
            if (noisePlayer == null && mediaPlayer == null) {
                // Nothing is playing (stale state after the process was killed).
                prefs(this).edit().putBoolean(KEY_PLAYING, false).apply()
                stopSelf()
            } else {
                applyVolume(prefs(this).getFloat(KEY_VOLUME, DEFAULT_VOLUME))
            }
            return START_NOT_STICKY
        }

        val soundType = intent.getStringExtra(EXTRA_SOUND_TYPE) ?: TYPE_WAVES
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
        val volume = prefs(this).getFloat(KEY_VOLUME, DEFAULT_VOLUME)
        if (soundType == TYPE_CUSTOM && customUri != null) {
            playFile(Uri.parse(customUri), volume)
        } else {
            noisePlayer = NoisePlayer(soundType, volume).also { it.start() }
        }
        prefs(this).edit()
            .putBoolean(KEY_PLAYING, true)
            .putString(KEY_TYPE, soundType)
            .apply {
                if (customUri != null) putString(KEY_CUSTOM_URI, customUri)
            }
            .apply()
        return START_NOT_STICKY
    }

    private fun playFile(uri: Uri, volume: Float) {
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
            mp.setVolume(volume, volume)
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

    private fun applyVolume(volume: Float) {
        noisePlayer?.volume = volume
        mediaPlayer?.setVolume(volume, volume)
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
            .setContentTitle("Now playing")
            .setContentText(sound)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        stopPlayback()
        prefs(this).edit().putBoolean(KEY_PLAYING, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class Sound(val type: String, val title: String, val subtitle: String, @DrawableRes val art: Int)

    companion object {
        private const val TAG = "AmbientAudioService"
        const val NOTIFICATION_ID = 4003
        const val EXTRA_SOUND_TYPE = "extra_sound_type"
        const val EXTRA_CUSTOM_URI = "extra_custom_uri"
        private const val ACTION_STOP = "com.focustask.companion.ACTION_STOP_AMBIENT"
        private const val ACTION_SET_VOLUME = "com.focustask.companion.ACTION_SET_VOLUME"

        const val PREFS = "ambient_audio"
        const val KEY_PLAYING = "playing"
        const val KEY_TYPE = "type"
        const val KEY_VOLUME = "volume"
        const val KEY_CUSTOM_URI = "custom_uri"
        private const val DEFAULT_VOLUME = 0.7f

        const val TYPE_WAVES = "waves"
        const val TYPE_FOREST = "forest"
        const val TYPE_PINK = "pink"
        const val TYPE_WHITE = "white"
        const val TYPE_BROWN = "brown"
        const val TYPE_CUSTOM = "custom"

        val SOUNDS = listOf(
            Sound(TYPE_WAVES, "Beach Waves", "Rolling surf", R.drawable.art_beach),
            Sound(TYPE_FOREST, "Forest Birds", "Wind and birdsong", R.drawable.art_forest),
            Sound(TYPE_PINK, "Gentle Rain", "Soft rainfall", R.drawable.art_rain),
            Sound(TYPE_WHITE, "White Noise", "Steady hiss", R.drawable.art_noise),
            Sound(TYPE_CUSTOM, "My Audio", "Long-press to pick a file", R.drawable.art_custom)
        )

        fun soundFor(type: String?): Sound =
            SOUNDS.firstOrNull { it.type == type }
                ?: Sound(TYPE_BROWN, "Deep Rumble", "Brown noise", R.drawable.art_noise)

        fun labelFor(type: String) = soundFor(type).title

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun isPlaying(context: Context) = prefs(context).getBoolean(KEY_PLAYING, false)
        fun currentType(context: Context) = prefs(context).getString(KEY_TYPE, TYPE_WAVES) ?: TYPE_WAVES
        fun volume(context: Context) = prefs(context).getFloat(KEY_VOLUME, DEFAULT_VOLUME)
        fun lastCustomUri(context: Context) = prefs(context).getString(KEY_CUSTOM_URI, null)

        fun start(context: Context, type: String, customUri: String? = null) {
            val intent = Intent(context, AmbientAudioService::class.java)
                .putExtra(EXTRA_SOUND_TYPE, type)
                .putExtra(EXTRA_CUSTOM_URI, customUri)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setVolume(context: Context, volume: Float) {
            prefs(context).edit().putFloat(KEY_VOLUME, volume.coerceIn(0f, 1f)).apply()
            if (isPlaying(context)) {
                try {
                    context.startService(Intent(context, AmbientAudioService::class.java).setAction(ACTION_SET_VOLUME))
                } catch (_: IllegalStateException) {
                    // app in background; the new volume applies next time
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AmbientAudioService::class.java))
        }
    }
}
