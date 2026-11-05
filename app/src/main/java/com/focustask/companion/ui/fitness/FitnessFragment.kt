package com.focustask.companion.ui.fitness

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.data.model.DailyStepEntity
import com.focustask.companion.databinding.FragmentFitnessBinding
import com.focustask.companion.service.StepTrackerService
import com.focustask.companion.ui.SubScreenActivity
import com.focustask.companion.ui.dp
import com.focustask.companion.util.VoiceSettings
import com.focustask.companion.util.hasActivityRecognition
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** Walk & Fitness: today's steps against a goal, distance, calories and the last 7 days. */
class FitnessFragment : Fragment() {

    private var _binding: FragmentFitnessBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as FocusTaskApp).database }
    private var todaySteps = 0
    private var today: DailyStepEntity? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFitnessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.cardRing.setOnClickListener { editGoal() }
        if (requireActivity() is SubScreenActivity) {
            binding.btnBack.visibility = View.VISIBLE
            binding.btnBack.setOnClickListener { requireActivity().finish() }
        }
        VoiceSettings.bindToggle(binding.btnVoice, VoiceSettings.Section.FITNESS)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    db.stepDao().getStepsForDate(StepTrackerService.todayKey()).collect {
                        today = it
                        renderToday()
                    }
                }
                launch {
                    db.stepDao().getStepsSince(StepTrackerService.dayKey(6)).collect { renderHistory(it) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderToday()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) renderToday()
    }

    private fun renderToday() {
        if (_binding == null) return
        val context = requireContext()
        val record = today
        todaySteps = record?.steps ?: 0
        val goal = StepTrackerService.goal(context)
        val number = NumberFormat.getIntegerInstance()
        binding.tvSteps.text = number.format(todaySteps)
        binding.tvGoal.text = "/ ${number.format(goal)} steps"
        binding.ringSteps.setProgressCompat((todaySteps * 100 / goal).coerceAtMost(100), true)
        binding.tvDistance.text = String.format(Locale.getDefault(), "%.1f km", (record?.distanceMeters ?: 0f) / 1000f)
        binding.tvCalories.text = String.format(Locale.getDefault(), "%.0f kcal", record?.caloriesKcal ?: 0f)
        binding.tvPermissionNote.visibility = if (context.hasActivityRecognition()) View.GONE else View.VISIBLE
        binding.tvPermissionNote.text = "Allow \"Physical activity\" (Home > Finish setup) to count steps."
    }

    private fun renderHistory(records: List<DailyStepEntity>) {
        if (_binding == null) return
        val byDate = records.associateBy { it.dateString }
        val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val labelFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val values = mutableListOf<Int>()
        val labels = mutableListOf<String>()
        for (daysAgo in 6 downTo 0) {
            val key = StepTrackerService.dayKey(daysAgo)
            values += byDate[key]?.steps ?: 0
            labels += keyFormat.parse(key)?.let { labelFormat.format(it) } ?: ""
        }
        binding.chartHistory.setData(values, labels)
        binding.tvWeekTotal.text = "${NumberFormat.getIntegerInstance().format(values.sum())} steps in the last 7 days"
    }

    private fun editGoal() {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(StepTrackerService.goal(context).toString())
            setSelection(text.length)
        }
        val frame = android.widget.FrameLayout(context).apply {
            setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Daily step goal")
            .setMessage("Between 1,000 and 50,000 steps")
            .setView(frame)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().toIntOrNull()?.let { StepTrackerService.setGoal(context, it) }
                renderToday()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
