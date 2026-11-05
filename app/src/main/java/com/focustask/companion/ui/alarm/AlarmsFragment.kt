package com.focustask.companion.ui.alarm

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.alarm.AlarmScheduler
import com.focustask.companion.data.model.AlarmEntity
import com.focustask.companion.databinding.DialogAlarmDetailsBinding
import com.focustask.companion.databinding.FragmentAlarmsBinding
import com.focustask.companion.ui.dp
import com.focustask.companion.util.VoiceSettings
import com.focustask.companion.ui.formatTime
import com.focustask.companion.ui.nextAlarm
import com.focustask.companion.ui.repeatLabel
import com.focustask.companion.ui.splitTime
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/** Smart Alarm: next alarm, its dismiss challenge, and the full alarm list. */
class AlarmsFragment : Fragment() {

    private var _binding: FragmentAlarmsBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as FocusTaskApp).database }

    private var heroAlarm: AlarmEntity? = null
    private var pendingSoundAlarmId = NO_ALARM

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onSoundPicked(uri) }

    private data class Challenge(val key: String, val title: String, val subtitle: String,
                                 @DrawableRes val icon: Int, @ColorRes val color: Int)

    private val challenges = listOf(
        Challenge("none", "Just tap Dismiss", "No challenge", R.drawable.ic_alarm, R.color.card_lavender),
        Challenge("math", "Solve Math", "e.g. 27 + 38 = ?", R.drawable.ic_add, R.color.card_mint),
        Challenge("steps", "Walking Steps", "Walk 15 steps to dismiss", R.drawable.ic_walk, R.color.card_peach)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSoundAlarmId = savedInstanceState?.getLong(STATE_SOUND_ALARM, NO_ALARM) ?: NO_ALARM
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SOUND_ALARM, pendingSoundAlarmId)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlarmsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.fabAddAlarm.setOnClickListener { addAlarm() }
        VoiceSettings.bindToggle(binding.btnVoice, VoiceSettings.Section.ALARMS)
        binding.switchHero.setOnCheckedChangeListener { button, enabled ->
            val alarm = heroAlarm ?: return@setOnCheckedChangeListener
            if (button.isPressed) setAlarmEnabled(alarm, enabled)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.alarmDao().getAllAlarms().collect { render(it) }
            }
        }
    }

    // ---------- Rendering ----------

    private fun render(alarms: List<AlarmEntity>) {
        if (_binding == null) return
        val context = requireContext()
        val next = alarms.nextAlarm()
        // Show the next enabled alarm; if all are off, show the first one so it can be switched on.
        heroAlarm = next?.first ?: alarms.firstOrNull()
        val hero = heroAlarm

        if (hero == null) {
            binding.tvHeroTitle.text = "No alarms yet"
            binding.tvHeroTime.text = "--:--"
            binding.tvHeroAmpm.text = ""
            binding.tvHeroLabel.text = "Tap \"Add alarm\" to create one"
            binding.switchHero.visibility = View.INVISIBLE
        } else {
            val (time, suffix) = splitTime(context, hero.hour, hero.minute)
            binding.tvHeroTitle.text = if (next != null) {
                "Next Alarm · " + DateFormat.format("EEE", Date(next.second))
            } else {
                "Alarm (off)"
            }
            binding.tvHeroTime.text = time
            binding.tvHeroAmpm.text = suffix
            binding.tvHeroLabel.text = hero.label
            binding.switchHero.visibility = View.VISIBLE
            binding.switchHero.isChecked = hero.isEnabled
        }
        renderChallenges(hero)
        renderList(alarms)
    }

    private fun renderChallenges(hero: AlarmEntity?) {
        val context = requireContext()
        val container = binding.challengeOptions
        container.removeAllViews()
        binding.tvChallengeTitle.visibility = if (hero == null) View.GONE else View.VISIBLE
        if (hero == null) return

        for (c in challenges) {
            val selected = hero.dismissChallenge == c.key
            val card = MaterialCardView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = context.dp(10) }
                radius = context.dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(context, c.color))
                strokeWidth = if (selected) context.dp(2) else 0
                strokeColor = ContextCompat.getColor(context, R.color.primary)
                setContentPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
                isClickable = true
                isFocusable = true
                setOnClickListener { setChallenge(hero, c.key) }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(ImageView(context).apply {
                setImageResource(c.icon)
                setColorFilter(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(context.dp(28), context.dp(28))
            })
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = context.dp(14) }
            }
            texts.addView(TextView(context).apply {
                text = c.title
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            texts.addView(TextView(context).apply {
                text = c.subtitle
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
            row.addView(texts)
            row.addView(TextView(context).apply {
                text = if (selected) "Selected" else ""
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(row)
            container.addView(card)
        }
    }

    private fun renderList(alarms: List<AlarmEntity>) {
        val context = requireContext()
        val list = binding.alarmList
        list.removeAllViews()
        if (alarms.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "No alarms yet."
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, context.dp(12), 0, context.dp(12))
            })
            return
        }
        for (alarm in alarms) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(8), 0, context.dp(8))
                setOnClickListener { showAlarmOptions(alarm) }
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(context).apply {
                text = formatTime(context, alarm.hour, alarm.minute)
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, if (alarm.isEnabled) R.color.text_primary else R.color.text_secondary))
            })
            texts.addView(TextView(context).apply {
                text = buildString {
                    append(alarm.label).append(" · ").append(repeatLabel(alarm.daysMask))
                    if (alarm.customAudioUri != null) append(" · custom sound")
                }
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
            val toggle = MaterialSwitch(context).apply {
                isChecked = alarm.isEnabled
                contentDescription = "Enable alarm"
                setOnCheckedChangeListener { _, enabled -> setAlarmEnabled(alarm, enabled) }
            }
            row.addView(texts)
            row.addView(toggle)
            list.addView(row)
        }
    }

    // ---------- Actions ----------

    private fun setChallenge(alarm: AlarmEntity, key: String) {
        if (alarm.dismissChallenge == key) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            db.alarmDao().updateAlarm(alarm.copy(dismissChallenge = key))
        }
    }

    private fun setAlarmEnabled(alarm: AlarmEntity, enabled: Boolean) {
        val updated = alarm.copy(isEnabled = enabled)
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.alarmDao().updateAlarm(updated) }
            AlarmScheduler.schedule(appContext, updated)?.let { toast("Next: ${describe(it)}") }
        }
    }

    private fun addAlarm() {
        val now = Calendar.getInstance()
        TimePickerDialog(
            requireContext(),
            { _, hour, minute -> showAlarmDetailsDialog(hour, minute) },
            now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(requireContext())
        ).show()
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
                label = dialogBinding.etLabel.text.toString().trim().ifBlank { "Wake up!" },
                daysMask = mask,
                isVoiceCalloutEnabled = dialogBinding.cbVoice.isChecked,
                dismissChallenge = challenge
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Alarm at ${formatTime(requireContext(), hour, minute)}")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ -> saveAlarm(build(), pickSound = false) }
            .setNeutralButton("Save & choose sound") { _, _ -> saveAlarm(build(), pickSound = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAlarm(alarm: AlarmEntity, pickSound: Boolean) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) { db.alarmDao().insertAlarm(alarm) }
            AlarmScheduler.schedule(appContext, alarm.copy(id = id))?.let { toast("Alarm set for ${describe(it)}") }
            if (!AlarmScheduler.canScheduleExact(appContext)) toast("Allow exact alarms so this rings on time")
            if (pickSound) {
                pendingSoundAlarmId = id
                launchPicker()
            }
        }
    }

    private fun showAlarmOptions(alarm: AlarmEntity) {
        val options = buildList {
            add("Change sound")
            if (alarm.customAudioUri != null) add("Use default alarm sound")
            add("Delete alarm")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${formatTime(requireContext(), alarm.hour, alarm.minute)} ${alarm.label}")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Change sound" -> {
                        pendingSoundAlarmId = alarm.id
                        launchPicker()
                    }
                    "Use default alarm sound" -> updateSound(alarm.id, null)
                    "Delete alarm" -> confirmDelete(alarm)
                }
            }
            .show()
    }

    private fun confirmDelete(alarm: AlarmEntity) {
        val appContext = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete alarm?")
            .setMessage("${formatTime(requireContext(), alarm.hour, alarm.minute)} ${alarm.label}")
            .setPositiveButton("Delete") { _, _ ->
                AlarmScheduler.cancel(appContext, alarm.id)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    db.alarmDao().deleteAlarm(alarm)
                    alarm.customAudioUri?.let { releaseSoundIfUnused(it) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Sound files ----------

    private fun launchPicker() {
        try {
            pickAudio.launch(arrayOf("audio/*"))
        } catch (_: Exception) {
            toast("No file picker available")
        }
    }

    private fun onSoundPicked(uri: Uri?) {
        val alarmId = pendingSoundAlarmId
        pendingSoundAlarmId = NO_ALARM
        if (uri == null || alarmId == NO_ALARM) return
        try {
            // Keep read access after reboots, otherwise the alarm would lose its sound.
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            toast("That file can't be kept available; pick it from Files or Downloads")
            return
        }
        updateSound(alarmId, uri.toString())
    }

    private fun updateSound(alarmId: Long, uri: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = db.alarmDao()
            val alarm = dao.getAlarmById(alarmId) ?: return@launch
            val old = alarm.customAudioUri
            dao.updateAlarm(alarm.copy(customAudioUri = uri))
            if (old != null && old != uri) releaseSoundIfUnused(old)
        }
    }

    /** Gives back long-term file access once no alarm uses the file. */
    private suspend fun releaseSoundIfUnused(uri: String) {
        if (db.alarmDao().countAlarmsUsingSound(uri) == 0) {
            try {
                requireContext().applicationContext.contentResolver
                    .releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // already released
            }
        }
    }

    private fun describe(millis: Long): String {
        val date = Date(millis)
        val context = requireContext()
        return DateFormat.format("EEE", date).toString() + " " + DateFormat.getTimeFormat(context).format(date)
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val NO_ALARM = -1L
        const val STATE_SOUND_ALARM = "pending_sound_alarm"
    }
}
