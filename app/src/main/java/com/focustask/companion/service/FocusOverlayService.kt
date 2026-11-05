package com.focustask.companion.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.tts.TtsManager
import com.focustask.companion.ui.MainActivity
import com.focustask.companion.util.canDrawOverlay
import com.focustask.companion.util.hasUsageAccess
import com.focustask.companion.util.notifyIfAllowed
import com.focustask.companion.util.startForegroundCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a timed focus session. While it runs, apps that are not on the allowed list are covered
 * with a reminder overlay (when "Display over other apps" and "Usage access" are granted).
 * Phone, emergency, launcher and system apps are never blocked.
 */
class FocusOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var monitorJob: Job? = null
    private var ttsManager: TtsManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var activeTaskName = DEFAULT_TASK
    private var repopupFrequencyMs = DEFAULT_REPOPUP_MS
    private var endAtMs = 0L
    private var lastDismissedTimeMs = 0L
    private var lastEventQueryMs = 0L
    private var currentForeground: String? = null
    private var alwaysAllowed: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        ttsManager = TtsManager(this)
        alwaysAllowed = buildAlwaysAllowedPackages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = FocusSession.prefs(this)
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean(FocusSession.KEY_ACTIVE, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent != null) {
            activeTaskName = intent.getStringExtra(EXTRA_TASK_NAME)?.take(80)?.ifBlank { null } ?: DEFAULT_TASK
            val remaining = intent.getLongExtra(EXTRA_REMAINING_MS, FocusSession.DEFAULT_DURATION_MS)
                .coerceIn(1_000L, 4 * 60 * 60_000L)
            endAtMs = System.currentTimeMillis() + remaining
            prefs.edit()
                .putBoolean(FocusSession.KEY_ACTIVE, true)
                .putString(FocusSession.KEY_TASK, activeTaskName)
                .putLong(FocusSession.KEY_END_AT, endAtMs)
                .remove(FocusSession.KEY_PAUSED_REMAINING)
                .apply()
        } else {
            // Restarted by the system after being killed: resume only if a session is still running.
            endAtMs = prefs.getLong(FocusSession.KEY_END_AT, 0L)
            if (!prefs.getBoolean(FocusSession.KEY_ACTIVE, false) || endAtMs <= System.currentTimeMillis()) {
                prefs.edit().putBoolean(FocusSession.KEY_ACTIVE, false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            activeTaskName = prefs.getString(FocusSession.KEY_TASK, DEFAULT_TASK) ?: DEFAULT_TASK
        }
        repopupFrequencyMs = prefs.getLong(FocusSession.KEY_REPOPUP, DEFAULT_REPOPUP_MS)

        try {
            startForegroundCompat(NOTIFICATION_ID, buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start focus session", e)
            prefs.edit().putBoolean(FocusSession.KEY_ACTIVE, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        startSessionLoop()
        return START_STICKY
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FocusOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FocusTaskApp.CHANNEL_FOCUS_ID)
            .setContentTitle("Focus Study Mode")
            .setContentText("Focusing on: $activeTaskName")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(endAtMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(openApp)
            .addAction(0, "End session", stopIntent)
            .build()
    }

    private fun startSessionLoop() {
        monitorJob?.cancel()
        val usageStatsManager = getSystemService(UsageStatsManager::class.java)
        val whitelistDao = (applicationContext as FocusTaskApp).database.whitelistDao()

        monitorJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)

                if (System.currentTimeMillis() >= endAtMs) {
                    finishSession()
                    return@launch
                }

                // The timer works without these permissions; only the blocker needs them.
                if (usageStatsManager == null || !canDrawOverlay() || !hasUsageAccess()) continue

                val pkg = withContext(Dispatchers.IO) { currentForegroundPackage(usageStatsManager) }
                    ?: continue
                val blocked = pkg !in alwaysAllowed &&
                    !withContext(Dispatchers.IO) { whitelistDao.isAppWhitelisted(pkg) }

                if (!blocked) {
                    hideOverlay() // user moved to an allowed app (e.g. pressed Home)
                } else if (overlayView == null &&
                    System.currentTimeMillis() - lastDismissedTimeMs >= repopupFrequencyMs
                ) {
                    showOverlay()
                }
            }
        }
    }

    private fun finishSession() {
        FocusSession.prefs(this).edit()
            .putBoolean(FocusSession.KEY_ACTIVE, false)
            .remove(FocusSession.KEY_PAUSED_REMAINING)
            .apply()
        hideOverlay()
        val done = NotificationCompat.Builder(this, FocusTaskApp.CHANNEL_REMINDER_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Focus session complete")
            .setContentText("Great work on: $activeTaskName. Time for a short break.")
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(DONE_NOTIFICATION_ID, done)
        ttsManager?.speak("Focus session complete. Great work. Take a short break.")
        // Give the voice a moment before shutting down.
        scope.launch {
            delay(4_000L)
            stopSelf()
        }
    }

    /** Reads usage events since the last check to find the app the user is currently in. */
    private fun currentForegroundPackage(usm: UsageStatsManager): String? {
        val now = System.currentTimeMillis()
        val start = if (lastEventQueryMs == 0L) now - 60_000L else lastEventQueryMs - 2_000L
        val events = usm.queryEvents(start, now) ?: return currentForeground
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION") // same value as ACTIVITY_RESUMED on API 29+
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentForeground = event.packageName
            }
        }
        lastEventQueryMs = now
        return currentForeground
    }

    /** Apps that must stay reachable: phone/emergency, launchers, settings and system UI. */
    private fun buildAlwaysAllowedPackages(): Set<String> {
        val result = mutableSetOf(
            packageName, "android", "com.android.systemui", "com.android.settings",
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.apps.safetyhub", "com.android.dialer", "com.google.android.dialer",
            "com.samsung.android.dialer", "com.android.incallui", "com.samsung.android.incallui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller"
        )
        val pm = packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(result) { it.activityInfo.packageName }
        pm.queryIntentActivities(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(result) { it.activityInfo.packageName }
        getSystemService(TelecomManager::class.java)?.let { telecom ->
            telecom.defaultDialerPackage?.let(result::add)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) telecom.systemDialerPackage?.let(result::add)
        }
        return result
    }

    private fun showOverlay() {
        if (overlayView != null || !canDrawOverlay()) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        val view = LayoutInflater.from(this).inflate(R.layout.layout_screen_blocker_overlay, null)
        view.findViewById<TextView>(R.id.tv_overlay_task_name).text = activeTaskName
        view.findViewById<Button>(R.id.btn_dismiss_overlay).setOnClickListener {
            lastDismissedTimeMs = System.currentTimeMillis()
            hideOverlay()
        }
        view.findViewById<Button>(R.id.btn_end_focus).setOnClickListener {
            FocusSession.prefs(this).edit()
                .putBoolean(FocusSession.KEY_ACTIVE, false)
                .remove(FocusSession.KEY_PAUSED_REMAINING)
                .apply()
            stopSelf()
        }

        try {
            windowManager?.addView(view, params)
            overlayView = view
            ttsManager?.speak("You opened an app that isn't on your list. Back to: $activeTaskName")
        } catch (e: Exception) {
            Log.w(TAG, "Could not show overlay", e) // e.g. overlay permission revoked
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager?.removeView(view)
        } catch (_: IllegalArgumentException) {
            // already removed
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        hideOverlay()
        ttsManager?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "FocusOverlayService"
        const val NOTIFICATION_ID = 4001
        private const val DONE_NOTIFICATION_ID = 4005
        const val EXTRA_TASK_NAME = "extra_task_name"
        const val EXTRA_REMAINING_MS = "extra_remaining_ms"
        private const val ACTION_STOP = "com.focustask.companion.ACTION_STOP_FOCUS"
        private const val DEFAULT_TASK = "Focus session"
        private const val DEFAULT_REPOPUP_MS = 15_000L
        private const val CHECK_INTERVAL_MS = 1_000L

        fun start(context: Context, taskName: String, remainingMs: Long) {
            val intent = Intent(context, FocusOverlayService::class.java)
                .putExtra(EXTRA_TASK_NAME, taskName)
                .putExtra(EXTRA_REMAINING_MS, remainingMs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, FocusOverlayService::class.java).setAction(ACTION_STOP))
            } catch (_: IllegalStateException) {
                // App in background and service not running: nothing to stop.
            }
        }

        fun isActive(context: Context): Boolean = FocusSession.state(context).active
    }
}
