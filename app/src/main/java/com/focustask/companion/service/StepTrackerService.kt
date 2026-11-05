package com.focustask.companion.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.data.model.DailyStepEntity
import com.focustask.companion.util.hasActivityRecognition
import com.focustask.companion.util.notifyIfAllowed
import com.focustask.companion.util.startForegroundCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Counts steps with the hardware step counter.
 *
 * The sensor reports steps since boot. We keep the last raw reading and today's total in
 * SharedPreferences, so totals survive service restarts and reboots, and reset at midnight.
 */
class StepTrackerService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var listening = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences

    private var dateKey = ""
    private var stepsToday = 0L
    private var lastRaw = -1L
    private var lastFlushMs = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dateKey = prefs.getString(KEY_DATE, "") ?: ""
        stepsToday = prefs.getLong(KEY_STEPS, 0L)
        lastRaw = prefs.getLong(KEY_LAST_RAW, -1L)
        rollOverIfNewDay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Android 14+ requires ACTIVITY_RECOGNITION for the "health" service type.
            startForegroundCompat(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot run step tracker in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listening) {
            val sm = getSystemService(SensorManager::class.java)
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (sm == null || sensor == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            sensorManager = sm
            listening = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val raw = event.values[0].toLong()
        rollOverIfNewDay()

        val delta = when {
            lastRaw < 0 -> 0L          // first reading ever: start counting from here
            raw < lastRaw -> raw       // counter restarted after a reboot
            else -> raw - lastRaw
        }
        lastRaw = raw
        stepsToday += delta
        prefs.edit().putLong(KEY_LAST_RAW, lastRaw).putLong(KEY_STEPS, stepsToday).apply()

        val now = System.currentTimeMillis()
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            lastFlushMs = now
            flush()
        }
    }

    private fun rollOverIfNewDay() {
        val today = todayKey()
        if (dateKey != today) {
            if (dateKey.isNotEmpty()) flush()  // save yesterday's final total
            dateKey = today
            stepsToday = 0L
            prefs.edit().putString(KEY_DATE, dateKey).putLong(KEY_STEPS, 0L).apply()
        }
    }

    private fun flush() {
        val record = DailyStepEntity(
            dateString = dateKey,
            steps = stepsToday.toInt(),
            distanceMeters = stepsToday * STRIDE_METERS,
            caloriesKcal = stepsToday * KCAL_PER_STEP
        )
        val dao = (applicationContext as FocusTaskApp).database.stepDao()
        scope.launch { dao.insertOrUpdate(record) }
        notifyIfAllowed(NOTIFICATION_ID, buildNotification())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, FocusTaskApp.CHANNEL_FIT_ID)
            .setContentTitle("Step tracker")
            .setContentText("$stepsToday steps today")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        listening = false
        if (dateKey.isNotEmpty()) {
            // Final write must not be cancelled with the scope.
            val record = DailyStepEntity(dateKey, stepsToday.toInt(),
                stepsToday * STRIDE_METERS, stepsToday * KCAL_PER_STEP)
            val dao = (applicationContext as FocusTaskApp).database.stepDao()
            CoroutineScope(Dispatchers.IO).launch { dao.insertOrUpdate(record) }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "StepTrackerService"
        const val NOTIFICATION_ID = 4002
        private const val PREFS = "step_tracker"
        private const val KEY_DATE = "date"
        private const val KEY_STEPS = "steps_today"
        private const val KEY_LAST_RAW = "last_raw"
        private const val FLUSH_INTERVAL_MS = 10_000L
        private const val STRIDE_METERS = 0.75f
        private const val KCAL_PER_STEP = 0.045f

        fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        /** Starts tracking only if the permission and the sensor are available. */
        fun startIfPermitted(context: Context) {
            if (!context.hasActivityRecognition()) return
            val sm = context.getSystemService(SensorManager::class.java) ?: return
            if (sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, StepTrackerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start step tracker", e)
            }
        }
    }
}
