package com.focustask.companion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.focustask.companion.R
import com.focustask.companion.alarm.AlarmScheduler
import com.focustask.companion.databinding.ActivityMainBinding
import com.focustask.companion.service.StepTrackerService
import com.focustask.companion.ui.alarm.AlarmsFragment
import com.focustask.companion.ui.calendar.CalendarFragment
import com.focustask.companion.ui.focus.FocusFragment
import com.focustask.companion.ui.home.HomeFragment
import com.focustask.companion.ui.more.MoreFragment
import com.focustask.companion.util.canDrawOverlay
import com.focustask.companion.util.canPostNotifications
import com.focustask.companion.util.canUseFullScreenAlarms
import com.focustask.companion.util.hasActivityRecognition
import com.focustask.companion.util.hasUsageAccess
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** One permission the app needs, with a plain explanation shown before asking. */
    class SetupStep(
        val key: String,
        val title: String,
        val why: String,
        val isRuntime: Boolean,
        val missing: () -> Boolean,
        val fix: () -> Unit
    )

    // ----- Guided setup state (survives rotation) -----
    private var guideRunning = false
    private var awaitingReturn = false
    private val skippedThisRun = mutableSetOf<String>()
    private var currentStepKey: String? = null
    private var guideDialog: AlertDialog? = null

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            StepTrackerService.startIfPermitted(this)
            onStepReturned()
        }

    val setupSteps: List<SetupStep> by lazy {
        listOf(
            SetupStep(
                "notifications", "Notifications",
                "Alarms, focus timers and cooking reminders show up as notifications. Without this, alarms can't appear on your screen.",
                isRuntime = true,
                missing = { !canPostNotifications() },
                fix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            ),
            SetupStep(
                "activity", "Physical activity",
                "Counts your steps for the fitness tracker and the \"walk to dismiss\" alarm challenge.",
                isRuntime = true,
                missing = { !hasActivityRecognition() },
                fix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requestPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }
            ),
            SetupStep(
                "exact_alarms", "Alarms & reminders",
                "Lets alarms ring at the exact minute you set. On the next screen, turn on \"Allow setting alarms and reminders\", then come back.",
                isRuntime = false,
                missing = { !AlarmScheduler.canScheduleExact(this) },
                fix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
                    }
                }
            ),
            SetupStep(
                "full_screen", "Full-screen alarms",
                "Lets the alarm screen appear even when your phone is locked. Turn it on for FocusTask Companion, then come back.",
                isRuntime = false,
                missing = { !canUseFullScreenAlarms() },
                fix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri()))
                    }
                }
            ),
            SetupStep(
                "overlay", "Display over other apps",
                "Shows the \"Get back to your task\" reminder over distracting apps during a focus session. Turn it on, then come back.",
                isRuntime = false,
                missing = { !canDrawOverlay() },
                fix = { openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())) }
            ),
            SetupStep(
                "usage", "Usage access",
                "Lets the focus blocker see which app is open. Find FocusTask Companion in the list, turn it on, then come back.",
                isRuntime = false,
                missing = { !hasUsageAccess() },
                fix = { openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            )
        )
    }

    fun missingSetupSteps(): List<SetupStep> = setupSteps.filter { it.missing() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge: the header colour sits behind the status bar; white status-bar icons.
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        if (savedInstanceState == null) {
            val tx = supportFragmentManager.beginTransaction()
            TABS.forEach { (id, tag) ->
                val fragment = createFragment(id)
                tx.add(R.id.fragment_container, fragment, tag)
                if (id != R.id.nav_home) tx.hide(fragment)
            }
            tx.commitNow()
        } else {
            guideRunning = savedInstanceState.getBoolean(STATE_GUIDE_RUNNING)
            awaitingReturn = savedInstanceState.getBoolean(STATE_AWAITING)
            currentStepKey = savedInstanceState.getString(STATE_CURRENT_STEP)
            savedInstanceState.getStringArray(STATE_SKIPPED)?.let { skippedThisRun.addAll(it) }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        // First launch: walk through every permission, one at a time.
        if (savedInstanceState == null && !prefs().getBoolean(KEY_GUIDE_DONE, false)) {
            binding.root.post { startSetupGuide(showIntro = true) }
        } else if (guideRunning && !awaitingReturn) {
            binding.root.post { showNextGuideStep() }
        }
    }

    override fun onResume() {
        super.onResume()
        StepTrackerService.startIfPermitted(this)
        if (guideRunning && awaitingReturn) {
            // Back from a system settings page.
            onStepReturned()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_GUIDE_RUNNING, guideRunning)
        outState.putBoolean(STATE_AWAITING, awaitingReturn)
        outState.putString(STATE_CURRENT_STEP, currentStepKey)
        outState.putStringArray(STATE_SKIPPED, skippedThisRun.toTypedArray())
    }

    override fun onDestroy() {
        guideDialog?.dismiss()
        guideDialog = null
        super.onDestroy()
    }

    // ---------- Navigation ----------

    fun selectTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    private fun showTab(itemId: Int) {
        val tag = TABS.firstOrNull { it.first == itemId }?.second ?: return
        val tx = supportFragmentManager.beginTransaction()
        TABS.forEach { (_, t) ->
            val f = supportFragmentManager.findFragmentByTag(t) ?: return@forEach
            if (t == tag) tx.show(f) else tx.hide(f)
        }
        tx.commit()
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.nav_focus -> FocusFragment()
        R.id.nav_calendar -> CalendarFragment()
        R.id.nav_alarm -> AlarmsFragment()
        R.id.nav_more -> MoreFragment()
        else -> HomeFragment()
    }

    // ---------- Guided setup ----------

    /** Walks through every missing permission. Also used by the dashboard's "Finish setup" card. */
    fun startSetupGuide(showIntro: Boolean = false) {
        skippedThisRun.clear()
        guideRunning = true
        awaitingReturn = false
        val missing = missingSetupSteps()
        if (missing.isEmpty()) {
            finishGuide(showDone = !showIntro)
            return
        }
        if (showIntro) {
            showGuideDialog(
                MaterialAlertDialogBuilder(this)
                    .setTitle("Welcome to FocusTask")
                    .setMessage(
                        "To make alarms, the focus blocker and step counting work, the app needs " +
                            "${missing.size} permissions. We'll go through them one at a time and " +
                            "explain each one. You can skip any of them."
                    )
                    .setCancelable(false)
                    .setPositiveButton("Let's go") { _, _ -> showNextGuideStep() }
                    .setNegativeButton("Later") { _, _ -> stopGuide() }
            )
        } else {
            showNextGuideStep()
        }
    }

    private fun showNextGuideStep() {
        if (!guideRunning || isFinishing || isDestroyed) return
        val remaining = setupSteps.filter { it.missing() && it.key !in skippedThisRun }
        val step = remaining.firstOrNull()
        if (step == null) {
            finishGuide(showDone = true)
            return
        }
        currentStepKey = step.key
        val number = setupSteps.indexOf(step) + 1
        showGuideDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle("Step $number of ${setupSteps.size}: ${step.title}")
                .setMessage(step.why)
                .setCancelable(false)
                .setPositiveButton(if (step.isRuntime) "Allow" else "Open settings") { _, _ ->
                    awaitingReturn = !step.isRuntime
                    step.fix()
                }
                .setNegativeButton("Skip") { _, _ ->
                    skippedThisRun.add(step.key)
                    showNextGuideStep()
                }
                .setNeutralButton("Later") { _, _ -> stopGuide() }
        )
    }

    /** Called after a permission prompt closes or the user returns from Settings. */
    private fun onStepReturned() {
        if (!guideRunning) return
        awaitingReturn = false
        val step = setupSteps.firstOrNull { it.key == currentStepKey }
        // If the user didn't grant it, move on rather than asking again in a loop.
        if (step != null && step.missing()) skippedThisRun.add(step.key)
        showNextGuideStep()
    }

    private fun stopGuide() {
        guideRunning = false
        awaitingReturn = false
        prefs().edit().putBoolean(KEY_GUIDE_DONE, true).apply()
        refreshVisibleScreens()
    }

    private fun finishGuide(showDone: Boolean) {
        val stillMissing = missingSetupSteps()
        stopGuide()
        if (!showDone) return
        val message = if (stillMissing.isEmpty()) {
            "Everything is set up. Enjoy FocusTask!"
        } else {
            "Setup finished. Skipped: ${stillMissing.joinToString { it.title }}. " +
                "You can allow them any time from the \"Finish setup\" card on the Home screen."
        }
        showGuideDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle("All done")
                .setMessage(message)
                .setPositiveButton("OK", null)
        )
    }

    private fun showGuideDialog(builder: MaterialAlertDialogBuilder) {
        guideDialog?.dismiss()
        guideDialog = builder.show()
    }

    private fun refreshVisibleScreens() {
        (supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment)?.refreshSetupCard()
    }

    private fun packageUri(): Uri = Uri.parse("package:$packageName")

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
            } catch (_: Exception) {
                awaitingReturn = false
                onStepReturned()
            }
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "main_ui"
        private const val KEY_GUIDE_DONE = "setup_guide_done"
        private const val STATE_GUIDE_RUNNING = "guide_running"
        private const val STATE_AWAITING = "guide_awaiting"
        private const val STATE_CURRENT_STEP = "guide_step"
        private const val STATE_SKIPPED = "guide_skipped"

        const val TAG_HOME = "tab_home"
        private val TABS = listOf(
            R.id.nav_home to TAG_HOME,
            R.id.nav_focus to "tab_focus",
            R.id.nav_calendar to "tab_calendar",
            R.id.nav_alarm to "tab_alarm",
            R.id.nav_more to "tab_more"
        )
    }
}
