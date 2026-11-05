package com.focustask.companion.ui

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.alarm.AlarmRingService
import com.focustask.companion.alarm.AlarmScheduler
import com.focustask.companion.databinding.ActivityAlarmAlertBinding
import com.focustask.companion.util.hasActivityRecognition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen alarm UI. Sound and vibration come from [AlarmRingService]; this screen only
 * stops them once the dismiss challenge is solved or the alarm is snoozed.
 */
class AlarmAlertActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityAlarmAlertBinding
    private var alarmId = -1L
    private var mathAnswer = 0
    private var stepsTaken = 0
    private var sensorManager: SensorManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        binding = ActivityAlarmAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Back must not bypass the challenge (the alarm would keep ringing with no screen).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@AlarmAlertActivity, "Solve the challenge or snooze", Toast.LENGTH_SHORT).show()
            }
        })

        binding.btnSnoozeAlarm.setOnClickListener { snooze() }
        bind(intent, isNewLaunch = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent, isNewLaunch = true)
    }

    private fun bind(intent: Intent, isNewLaunch: Boolean) {
        alarmId = intent.getLongExtra(AlarmRingService.EXTRA_ALARM_ID, -1L)
        val label = intent.getStringExtra(AlarmRingService.EXTRA_LABEL) ?: "Alarm"
        val challenge = intent.getStringExtra(AlarmRingService.EXTRA_CHALLENGE) ?: "none"
        val snoozeMinutes = intent.getIntExtra(AlarmRingService.EXTRA_SNOOZE_MINUTES, 10)

        binding.tvAlarmLabel.text = label
        binding.btnSnoozeAlarm.text = "Snooze (${snoozeMinutes}m)"

        // Opened from the fallback notification: the ringing service is not running yet.
        if (isNewLaunch && !intent.getBooleanExtra(AlarmRingService.EXTRA_FROM_SERVICE, false)) {
            try {
                AlarmRingService.startFromScreen(this, intent)
            } catch (_: Exception) {
                // Screen is in the foreground, so this should not fail; the UI still works.
            }
        }
        setupChallenge(challenge)
    }

    private fun setupChallenge(requested: String) {
        stopStepSensor()
        binding.layoutMathChallenge.visibility = View.GONE
        binding.layoutStepChallenge.visibility = View.GONE
        binding.tvChallengeNote.visibility = View.GONE

        var challenge = requested
        if (challenge == "steps" && !startStepSensor()) {
            challenge = "math"
            binding.tvChallengeNote.text =
                "Step challenge needs a step sensor and the Physical activity permission, so solve this instead."
            binding.tvChallengeNote.visibility = View.VISIBLE
        }

        when (challenge) {
            "math" -> {
                binding.layoutMathChallenge.visibility = View.VISIBLE
                val a = (15..50).random()
                val b = (15..50).random()
                mathAnswer = a + b
                binding.tvMathProblem.text = "$a + $b = ?"
                binding.etMathAnswer.setText("")
                binding.btnDismissAlarm.setOnClickListener {
                    if (binding.etMathAnswer.text.toString().trim().toIntOrNull() == mathAnswer) {
                        dismiss()
                    } else {
                        Toast.makeText(this, "Incorrect, try again", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "steps" -> {
                binding.layoutStepChallenge.visibility = View.VISIBLE
                stepsTaken = 0
                updateStepText()
                binding.btnDismissAlarm.setOnClickListener {
                    if (stepsTaken >= REQUIRED_STEPS) dismiss()
                    else Toast.makeText(this, "Walk $REQUIRED_STEPS steps to dismiss", Toast.LENGTH_SHORT).show()
                }
            }
            else -> binding.btnDismissAlarm.setOnClickListener { dismiss() }
        }
    }

    /** Returns false when the step challenge can't run on this device. */
    private fun startStepSensor(): Boolean {
        if (!hasActivityRecognition()) return false
        val sm = getSystemService(SensorManager::class.java) ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return false
        sensorManager = sm
        return sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopStepSensor() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
        stepsTaken += event.values.size.coerceAtLeast(1)
        updateStepText()
        if (stepsTaken >= REQUIRED_STEPS) dismiss()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateStepText() {
        binding.tvStepCount.text = "${stepsTaken.coerceAtMost(REQUIRED_STEPS)} / $REQUIRED_STEPS steps"
    }

    private fun snooze() {
        val id = alarmId
        val dao = (application as FocusTaskApp).database.alarmDao()
        lifecycleScope.launch {
            val alarm = withContext(Dispatchers.IO) { dao.getAlarmById(id) }
            if (alarm != null) {
                AlarmScheduler.scheduleSnooze(applicationContext, alarm)
                Toast.makeText(applicationContext, "Snoozed for ${alarm.snoozeMinutes} min", Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }
    }

    private fun dismiss() {
        stopStepSensor()
        AlarmRingService.stop(this)
        finish()
    }

    private fun showOverLockScreen() {
        // Shows above the lock screen without unlocking the device.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        stopStepSensor()
        super.onDestroy()
    }

    companion object {
        private const val REQUIRED_STEPS = 15
    }
}
