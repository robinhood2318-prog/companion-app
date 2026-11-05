package com.focustask.companion.ui.home

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.data.model.TaskEntity
import com.focustask.companion.databinding.FragmentHomeBinding
import com.focustask.companion.meals.MealScheduler
import com.focustask.companion.service.AmbientAudioService
import com.focustask.companion.service.FocusSession
import com.focustask.companion.service.StepTrackerService
import com.focustask.companion.deadline.Deadlines
import com.focustask.companion.ui.MainActivity
import com.focustask.companion.ui.SubScreenActivity
import com.focustask.companion.ui.focusOrder
import com.focustask.companion.ui.nextAlarm
import com.focustask.companion.ui.sounds.SoundsActivity
import com.focustask.companion.util.formatClock
import com.focustask.companion.util.greeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date

/** Dashboard: today's focus, next alarm, steps, dinner and ambient sound at a glance. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as FocusTaskApp).database }
    private var topTask: TaskEntity? = null

    private val audioListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (_binding != null) refreshSounds()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val main = requireActivity() as MainActivity
        binding.btnSetup.setOnClickListener { main.startSetupGuide() }
        binding.cardFocus.setOnClickListener { main.selectTab(R.id.nav_focus) }
        binding.cardNextAlarm.setOnClickListener { main.selectTab(R.id.nav_alarm) }
        binding.cardSteps.setOnClickListener {
            SubScreenActivity.open(requireContext(), SubScreenActivity.SCREEN_FITNESS)
        }
        binding.cardDinner.setOnClickListener {
            SubScreenActivity.open(requireContext(), SubScreenActivity.SCREEN_MEALS)
        }
        binding.tvNextDeadline.setOnClickListener { main.selectTab(R.id.nav_calendar) }
        binding.cardSounds.setOnClickListener { startActivity(Intent(requireContext(), SoundsActivity::class.java)) }

        binding.cbTopTask.setOnCheckedChangeListener { button, checked ->
            val task = topTask ?: return@setOnCheckedChangeListener
            if (!button.isPressed) return@setOnCheckedChangeListener // ignore programmatic changes
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                db.taskDao().updateTask(task.copy(isCompleted = checked))
            }
        }
        binding.switchTakeout.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) MealScheduler.setTakeoutTonight(requireContext(), checked)
        }

        observeData()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    db.taskDao().getAllTasks().collect { tasks ->
                        val next = tasks.filter { !it.isCompleted && it.dueDateTime != null }
                            .minByOrNull { it.dueDateTime!! }
                        binding.tvNextDeadline.text = next?.let {
                            "Next deadline: ${it.title} · ${Deadlines.countdown(it.dueDateTime!!)}"
                        } ?: "No deadlines: add one on the Calendar tab"
                        topTask = tasks.focusOrder().firstOrNull { !it.isCompleted }
                        val t = topTask
                        binding.cbTopTask.isChecked = false
                        binding.cbTopTask.isEnabled = t != null
                        binding.cbTopTask.text = t?.title ?: "No open tasks: add one on the Focus tab"
                    }
                }
                launch {
                    db.alarmDao().getAllAlarms().collect { alarms ->
                        val next = alarms.nextAlarm()
                        if (next == null) {
                            binding.tvNextAlarmTime.text = "--:--"
                            binding.tvNextAlarmLabel.text = "No alarm set"
                        } else {
                            binding.tvNextAlarmTime.text = DateFormat.getTimeFormat(requireContext()).format(Date(next.second))
                            binding.tvNextAlarmLabel.text = next.first.label
                        }
                    }
                }
                launch {
                    db.stepDao().getStepsForDate(StepTrackerService.todayKey()).collect { record ->
                        val steps = record?.steps ?: 0
                        val goal = StepTrackerService.goal(requireContext())
                        binding.tvSteps.text = NumberFormat.getIntegerInstance().format(steps)
                        binding.tvStepsGoal.text = "/ ${NumberFormat.getIntegerInstance().format(goal)} steps"
                        binding.ringSteps.setProgressCompat((steps * 100 / goal).coerceAtMost(100), true)
                    }
                }
                launch {
                    db.mealDao().getAllMeals().collect { meals ->
                        val dinner = meals.firstOrNull {
                            it.dayOfWeek == MealScheduler.todayName() && it.mealType == "dinner"
                        }
                        binding.tvDinner.text = dinner?.dishName?.ifBlank { null } ?: "No dinner planned"
                    }
                }
                launch {
                    while (true) {
                        refreshFocus()
                        delay(1_000L)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AmbientAudioService.prefs(requireContext()).registerOnSharedPreferenceChangeListener(audioListener)
    }

    override fun onStop() {
        AmbientAudioService.prefs(requireContext()).unregisterOnSharedPreferenceChangeListener(audioListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refreshAll()
    }

    private fun refreshAll() {
        val now = Calendar.getInstance()
        binding.tvGreeting.text = greeting(now.get(Calendar.HOUR_OF_DAY))
        binding.tvDate.text = DateFormat.format("EEEE, d MMMM", now)
        binding.switchTakeout.isChecked = MealScheduler.isTakeoutTonight(requireContext())
        refreshSetupCard()
        refreshSounds()
        refreshFocus()
    }

    fun refreshSetupCard() {
        if (_binding == null) return
        val missing = (activity as? MainActivity)?.missingSetupSteps().orEmpty()
        binding.cardSetup.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        binding.tvSetupDetail.text = "Still needed: " + missing.joinToString { it.title }
    }

    private fun refreshFocus() {
        if (_binding == null) return
        val state = FocusSession.state(requireContext())
        binding.tvFocusTime.text = formatClock(state.remainingMs)
        binding.ringFocus.setProgressCompat((state.progress * 100).toInt(), false)
        binding.tvFocusStatus.text = when {
            state.active -> "Active: ${state.task}"
            state.paused -> "Paused"
            else -> "Ready to start"
        }
    }

    private fun refreshSounds() {
        val context = context ?: return
        val playing = AmbientAudioService.isPlaying(context)
        val sound = AmbientAudioService.soundFor(AmbientAudioService.currentType(context))
        binding.tvSoundStatus.text = if (playing) "Now playing" else "Tap to relax"
        binding.tvSoundTitle.text = if (playing) sound.title else "Ambient Soundscapes"
        binding.ivSoundArt.setImageDrawable(ContextCompat.getDrawable(context, sound.art))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
