package com.focustask.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.focustask.companion.data.database.AppDatabase

class FocusTaskApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // minSdk is 26, so notification channels are always available.
        val notificationManager = getSystemService(NotificationManager::class.java)

        // The old alarm channel played its own sound; channel settings can't be changed after
        // creation, so it is replaced. AlarmRingService now plays the sound and vibration.
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ALARM_ID)

        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM_ID,
            "Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen alarm screen"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val focusChannel = NotificationChannel(
            CHANNEL_FOCUS_ID,
            "Study Focus Guardian",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while a focus session is guarding against distracting apps"
        }

        val audioChannel = NotificationChannel(
            CHANNEL_AUDIO_ID,
            "Ambient sounds",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while an ambient sound is playing"
        }

        val fitChannel = NotificationChannel(
            CHANNEL_FIT_ID,
            "Daily Walk & Step Tracker",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background pedometer step logging"
        }

        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDER_ID,
            "Cooking reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Weekly menu reminders to start cooking"
        }

        notificationManager.createNotificationChannels(
            listOf(alarmChannel, focusChannel, audioChannel, fitChannel, reminderChannel)
        )
    }

    companion object {
        private const val LEGACY_CHANNEL_ALARM_ID = "channel_smart_alarms"
        const val CHANNEL_ALARM_ID = "channel_alarm_screen"
        const val CHANNEL_FOCUS_ID = "channel_focus_guardian"
        const val CHANNEL_AUDIO_ID = "channel_ambient_audio"
        const val CHANNEL_FIT_ID = "channel_fit_pedometer"
        const val CHANNEL_REMINDER_ID = "channel_meal_reminders"
    }
}
