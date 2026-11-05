package com.focustask.companion.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.alarm.AlarmScheduler
import com.focustask.companion.data.model.AlarmEntity
import com.focustask.companion.data.model.WhitelistAppEntity
import com.focustask.companion.databinding.ActivityMainBinding
import com.focustask.companion.databinding.DialogAlarmDetailsBinding
import com.focustask.companion.service.AmbientAudioService
import com.focustask.companion.service.FocusOverlayService
import com.focustask.companion.service.StepTrackerService
import com.focustask.companion.util.canPostNotifications
import com.focustask.companion.util.hasActivityRecognition
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db by lazy { (application as FocusTaskApp).database }

    /** Alarm waiting for a sound file from the picker (survives rotation). */
    private var pendingSoundAlarmId = NO_ALARM
    private var pickingAmbientSound = false

    private val runtimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSetup()
            StepTrackerService.startIfPermitted(this)
        }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onAudioPicked(uri) }

    private data class SetupStep(val name: String, val missing: () -> Boolean, val fix: () -> Unit)

    private val setupSteps: List<SetupStep> by lazy {
        listOf(
            SetupStep("Notifications (needed for alarms)", { !canPostNotifications() }) { requestRuntimePermissions() },
            SetupStep("Physical activity (step counting)", { !hasActivityRecognition() }) { requestRuntimePermissions() },
            SetupStep("Exact alarms", { !AlarmScheduler.canScheduleExact(this) }) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
                }
            },
            SetupStep("Full-screen alarms", { !canUseFullScreenIntent() }) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri()))
                }
            },
            SetupStep("Display over other apps (focus blocker)", { !Settings.canDrawOverlays(this) }) {
                openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
            },
            SetupStep("Usage access (focus blocker)", { !hasUsageStatsPermission() }) {
                openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35+ draws edge-to-edge: keep content clear of the system bars and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        pendingSoundAlarmId = savedInstanceState?.getLong(STATE_SOUND_ALARM, NO_ALARM) ?: NO_ALARM
        pickingAmbientSound = savedInstanceState?.getBoolean(STATE_PICK_AMBIENT, false) ?: false

        setupFocusSection()
        setupAlarmSection()
        setupAmbientSection()
        observeData()

        if (savedInstanceState == null) requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        refreshFocusButtons()
        StepTrackerService.startIfPermitted(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SOUND_ALARM, pendingSoundAlarmId)
        outState.putBoolean(STATE_PICK_AMBIENT, pickingAmbientSound)
    }

    // ---------- Setup / permissions ----------

    private fun refreshSetup() {
        val missing = setupSteps.filter { it.missing() }
        if (missing.isEmpty()) {
            binding.tvSetupStatus.text = "Everything is set up."
            binding.btnGrantNext.visibility = View.GONE
        } else {
            binding.tvSetupStatus.text = "Still needed:\n" + missing.joinToString("\n") { "• ${it.name}" }
            binding.btnGrantNext.visibility = View.VISIBLE
            binding.btnGrantNext.text = "Allow: ${missing.first().name}"
            binding.btnGrantNext.setOnClickListener { missing.first().fix() }
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canPostNotifications()) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasActivityRecognition()) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }
        if (wanted.isEmpty()) return

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val askedBefore = prefs.getBoolean(KEY_ASKED_RUNTIME, false)
        val canAskAgain = wanted.any { shouldShowRequestPermissionRationale(it) }
        if (askedBefore && !canAskAgain) {
            // Android won't show the dialog again; send the user to the app's settings page.
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
        } else {
            prefs.edit().putBoolean(KEY_ASKED_RUNTIME, true).apply()
            runtimePermissions.launch(wanted.toTypedArray())
        }
    }

    private fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun packageUri(): Uri = Uri.parse("package:$packageName")

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
        }
    }

    // ---------- Focus ----------

    private fun setupFocusSection() {
        binding.btnStartFocus.setOnClickListener {
            if (!Settings.canDrawOverlays(this) || !hasUsageStatsPermission()) {
                toast("Allow \"Display over other apps\" and \"Usage access\" first")
                refreshSetup()
                return@setOnClickListener
            }
            val task = binding.etTaskName.text.toString().trim()
            try {
                FocusOverlayService.start(this, task)
                toast("Focus session started")
            } catch (e: Exception) {
                toast("Could not start focus session")
            }
            binding.root.postDelayed({ refreshFocusButtons() }, 300)
        }
        binding.btnStopFocus.setOnClickListener {
            FocusOverlayService.stop(this)
            binding.root.postDelayed({ refreshFocusButtons() }, 300)
        }
        binding.btnAllowedApps.setOnClickListener { showAllowedAppsDialog() }
    }

    private fun refreshFocusButtons() {
        val active = FocusOverlayService.isActive(this)
        binding.btnStartFocus.isEnabled = !active
        binding.btnStopFocus.isEnabled = active
    }

    private fun showAllowedAppsDialog() {
        lifecycleScope.launch {
            val pm = packageManager
            val apps = withContext(Dispatchers.IO) {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != packageName }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase(Locale.getDefault()) }
            }
            val allowed = withContext(Dispatchers.IO) { db.whitelistDao().getAllowedPackagesSync().toSet() }
            val labels = apps.map { it.second }.toTypedArray()
            val checked = BooleanArray(apps.size) { apps[it].first in allowed }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Apps allowed during focus")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Save") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val dao = db.whitelistDao()
                        apps.forEachIndexed { i, (pkg, label) ->
                            if (checked[i]) dao.insertApp(WhitelistAppEntity(pkg, label)) else dao.deleteByPackage(pkg)
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ---------- Alarms ----------

    private fun setupAlarmSection() {
        binding.btnAddAlarm.setOnClickListener {
            val now = Calendar.getInstance()
            TimePickerDialog(
                this,
                { _, hour, minute -> showAlarmDetailsDialog(hour, minute) },
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(this)
            ).show()
        }
    }

    private fun showAlarmDetailsDialog(hour: Int, minute: Int) {
        val dialogBinding = DialogAlarmDetailsBinding.inflate(layoutInflater)
        fun build(): AlarmEntity {
            val mask = when (dialogBinding.rgRepeat.checkedRadioButtonId) {
                R.id.rb_weekdays -> AlarmScheduler.MASK_WEEKDAYS
                R.id.rb_every_day -> AlarmScheduler.MASK_EVERY_DAY
                else -> AlarmScheduler.MASK_ONCE
            }
            val challenge = when (dialogBinding.rgChallenge.checkedRadioButtonId) {
                R.id.rb_math -> "math"
                R.id.rb_steps -> "steps"
                else -> "none"
            }
            return AlarmEntity(
                hour = hour,
                minute = minute,
                label = dialogBinding.etLabel.text.toString().trim().ifBlank { "Alarm" },
                daysMask = mask,
                isVoiceCalloutEnabled = dialogBinding.cbVoice.isChecked,
                dismissChallenge = challenge
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Alarm at ${formatTime(hour, minute)}")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ -> saveAlarm(build(), pickSound = false) }
            .setNeutralButton("Save & choose sound") { _, _ -> saveAlarm(build(), pickSound = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAlarm(alarm: AlarmEntity, pickSound: Boolean) {
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { db.alarmDao().insertAlarm(alarm) }
            val saved = alarm.copy(id = id)
            AlarmScheduler.schedule(applicationContext, saved)?.let { toast("Alarm set for ${describeNext(it)}") }
            if (!AlarmScheduler.canScheduleExact(this@MainActivity)) {
                toast("Allow exact alarms so this rings on time")
            }
            if (pickSound) {
                pendingSoundAlarmId = id
                pickingAmbientSound = false
                launchAudioPicker()
            }
        }
    }

    private fun renderAlarms(alarms: List<AlarmEntity>) {
        val list = binding.alarmList
        list.removeAllViews()
        if (alarms.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No alarms yet."
                setTextColor(getColor(R.color.text_secondary))
            })
            return
        }
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (alarm in alarms) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            val text = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(getColor(R.color.text_primary))
                text = buildString {
                    append(formatTime(alarm.hour, alarm.minute)).append("  ").append(alarm.label)
                    append("\n").append(repeatLabel(alarm.daysMask))
                    if (alarm.dismissChallenge != "none") append(" · ").append(alarm.dismissChallenge)
                    if (alarm.customAudioUri != null) append(" · custom sound")
                }
            }
            val toggle = MaterialSwitch(this).apply {
                isChecked = alarm.isEnabled
                contentDescription = "Enable alarm"
                setOnCheckedChangeListener { _, enabled -> setAlarmEnabled(alarm, enabled) }
            }
            val delete = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                background = null
                contentDescription = "Delete alarm"
                setOnClickListener { confirmDelete(alarm) }
            }
            row.addView(text)
            row.addView(toggle)
            row.addView(delete)
            list.addView(row)
        }
    }

    private fun setAlarmEnabled(alarm: AlarmEntity, enabled: Boolean) {
        val updated = alarm.copy(isEnabled = enabled)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.alarmDao().updateAlarm(updated) }
            AlarmScheduler.schedule(applicationContext, updated)?.let { toast("Next: ${describeNext(it)}") }
        }
    }

    private fun confirmDelete(alarm: AlarmEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete alarm?")
            .setMessage("${formatTime(alarm.hour, alarm.minute)} ${alarm.label}")
            .setPositiveButton("Delete") { _, _ ->
                AlarmScheduler.cancel(applicationContext, alarm.id)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.alarmDao().deleteAlarm(alarm)
                        alarm.customAudioUri?.let { releaseSoundIfUnused(it) }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Gives back long-term file access once no alarm uses the file. */
    private suspend fun releaseSoundIfUnused(uri: String) {
        if (db.alarmDao().countAlarmsUsingSound(uri) == 0) {
            try {
                contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // permission was already gone
            }
        }
    }

    private fun repeatLabel(mask: Int) = when (mask and AlarmScheduler.MASK_EVERY_DAY) {
        AlarmScheduler.MASK_ONCE -> "Once"
        AlarmScheduler.MASK_WEEKDAYS -> "Weekdays"
        AlarmScheduler.MASK_EVERY_DAY -> "Every day"
        else -> "Custom days"
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return DateFormat.getTimeFormat(this).format(cal.time)
    }

    private fun describeNext(millis: Long): String {
        val date = Date(millis)
        return DateFormat.getMediumDateFormat(this).format(date) + " " + DateFormat.getTimeFormat(this).format(date)
    }

    // ---------- Sound files ----------

    private fun launchAudioPicker() {
        try {
            pickAudio.launch(arrayOf("audio/*"))
        } catch (_: Exception) {
            toast("No file picker available")
        }
    }

    private fun onAudioPicked(uri: Uri?) {
        val alarmId = pendingSoundAlarmId
        val forAmbient = pickingAmbientSound
        pendingSoundAlarmId = NO_ALARM
        pickingAmbientSound = false
        if (uri == null) return

        // Keep read access after reboots, otherwise the alarm would lose its sound.
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            toast("That file can't be kept available; pick it from Files or Downloads")
            return
        }

        if (forAmbient) {
            startAmbient(AmbientAudioService.TYPE_CUSTOM, uri.toString())
        } else if (alarmId != NO_ALARM) {
            lifecycleScope.launch(Dispatchers.IO) {
                val dao = db.alarmDao()
                val alarm = dao.getAlarmById(alarmId) ?: return@launch
                val oldUri = alarm.customAudioUri
                dao.updateAlarm(alarm.copy(customAudioUri = uri.toString()))
                if (oldUri != null && oldUri != uri.toString()) releaseSoundIfUnused(oldUri)
            }
        }
    }

    // ---------- Ambient sound ----------

    private fun setupAmbientSection() {
        binding.chipBrown.setOnClickListener { startAmbient(AmbientAudioService.TYPE_BROWN) }
        binding.chipPink.setOnClickListener { startAmbient(AmbientAudioService.TYPE_PINK) }
        binding.chipWhite.setOnClickListener { startAmbient(AmbientAudioService.TYPE_WHITE) }
        binding.chipCustom.setOnClickListener {
            pickingAmbientSound = true
            pendingSoundAlarmId = NO_ALARM
            launchAudioPicker()
        }
        binding.chipStopAudio.setOnClickListener { AmbientAudioService.stop(this) }
    }

    private fun startAmbient(type: String, uri: String? = null) {
        try {
            AmbientAudioService.start(this, type, uri)
        } catch (_: Exception) {
            toast("Could not start audio")
        }
    }

    // ---------- Data ----------

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { db.alarmDao().getAllAlarms().collect { renderAlarms(it) } }
                launch {
                    db.whitelistDao().getAllAllowedApps().collect { apps ->
                        binding.tvAllowedSummary.text = if (apps.isEmpty()) {
                            "No apps allowed yet: every app except phone, settings and the home screen will be blocked."
                        } else {
                            "Allowed: " + apps.joinToString { it.appName }
                        }
                    }
                }
                launch {
                    db.stepDao().getStepsForDate(StepTrackerService.todayKey()).collect { record ->
                        binding.tvSteps.text = if (!hasActivityRecognition()) {
                            "Allow physical activity access to count steps."
                        } else if (record == null) {
                            "0 steps"
                        } else {
                            String.format(
                                Locale.getDefault(), "%d steps · %.2f km · %.0f kcal",
                                record.steps, record.distanceMeters / 1000f, record.caloriesKcal
                            )
                        }
                    }
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val NO_ALARM = -1L
        private const val STATE_SOUND_ALARM = "pending_sound_alarm"
        private const val STATE_PICK_AMBIENT = "picking_ambient"
        private const val PREFS = "main_ui"
        private const val KEY_ASKED_RUNTIME = "asked_runtime_permissions"
    }
}
