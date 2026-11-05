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
import android.provider.Settings
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
 * Watches which app is in the foreground during a focus session and covers apps that are not
 * on the allowed list with a reminder overlay. Phone, emergency, launcher and system apps are
 * never blocked.
 */
class FocusOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var monitorJob: Job? = null
    private var ttsManager: TtsManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var activeTaskName = DEFAULT_TASK
    private var repopupFrequencyMs = DEFAULT_REPOPUP_MS
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
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent != null) {
            activeTaskName = intent.getStringExtra(EXTRA_TASK_NAME)?.take(80)?.ifBlank { null } ?: DEFAULT_TASK
            repopupFrequencyMs = intent.getLongExtra(EXTRA_REPOPUP_FREQ, DEFAULT_REPOPUP_MS)
                .coerceIn(5_000L, 30 * 60_000L)
            prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_TASK, activeTaskName)
                .putLong(KEY_REPOPUP, repopupFrequencyMs)
                .apply()
        } else {
            // Restarted by the system after being killed: resume only if a session was active.
            if (!prefs.getBoolean(KEY_ACTIVE, false)) {
                stopSelf()
                return START_NOT_STICKY
            }
            activeTaskName = prefs.getString(KEY_TASK, DEFAULT_TASK) ?: DEFAULT_TASK
            repopupFrequencyMs = prefs.getLong(KEY_REPOPUP, DEFAULT_REPOPUP_MS)
        }

        try {
            startForegroundCompat(NOTIFICATION_ID, buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start focus guardian", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startPhoneUsageMonitoring()
        return START_STICKY
    }

    private fun buildForegroundNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FocusOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, FocusTaskApp.CHANNEL_FOCUS_ID)
            .setContentTitle("Study Guardian active")
            .setContentText("Focusing on: $activeTaskName")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "End session", stopIntent)
            .build()
    }

    private fun startPhoneUsageMonitoring() {
        monitorJob?.cancel()
        val usageStatsManager = getSystemService(UsageStatsManager::class.java) ?: return
        val whitelistDao = (applicationContext as FocusTaskApp).database.whitelistDao()

        monitorJob = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
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
        if (overlayView != null || !Settings.canDrawOverlays(this)) return

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
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
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
        const val EXTRA_TASK_NAME = "extra_task_name"
        const val EXTRA_REPOPUP_FREQ = "extra_repopup_freq"
        private const val ACTION_STOP = "com.focustask.companion.ACTION_STOP_FOCUS"
        private const val PREFS = "focus_session"
        private const val KEY_ACTIVE = "active"
        private const val KEY_TASK = "task"
        private const val KEY_REPOPUP = "repopup_ms"
        private const val DEFAULT_TASK = "Current study goal"
        private const val DEFAULT_REPOPUP_MS = 15_000L
        private const val CHECK_INTERVAL_MS = 1_500L

        fun start(context: Context, taskName: String) {
            val intent = Intent(context, FocusOverlayService::class.java)
                .putExtra(EXTRA_TASK_NAME, taskName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FocusOverlayService::class.java).setAction(ACTION_STOP))
        }

        fun isActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)
    }
}
