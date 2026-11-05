package com.focustask.companion.ui.focus

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.data.model.TaskEntity
import com.focustask.companion.data.model.WhitelistAppEntity
import com.focustask.companion.databinding.DialogAddTaskBinding
import com.focustask.companion.databinding.FragmentFocusBinding
import com.focustask.companion.service.FocusSession
import com.focustask.companion.ui.MainActivity
import com.focustask.companion.ui.dp
import com.focustask.companion.ui.focusOrder
import com.focustask.companion.ui.priorityColor
import com.focustask.companion.ui.sounds.SoundsActivity
import com.focustask.companion.util.canDrawOverlay
import com.focustask.companion.util.formatClock
import com.focustask.companion.util.hasUsageAccess
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Tasks & Focus: Pomodoro timer that drives the distraction blocker, plus a prioritized task list. */
class FocusFragment : Fragment() {

    private var _binding: FragmentFocusBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as FocusTaskApp).database }

    private var allTasks: List<TaskEntity> = emptyList()
    private var filter: String? = null // null = all

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFocusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnReset.setOnClickListener {
            FocusSession.reset(requireContext())
            refreshTimer()
        }
        binding.timerFrame.setOnClickListener { chooseDuration() }
        binding.btnAllowedApps.setOnClickListener { showAllowedAppsDialog() }
        binding.btnSounds.setOnClickListener { startActivity(Intent(requireContext(), SoundsActivity::class.java)) }
        binding.fabAddTask.setOnClickListener { showAddTaskDialog() }

        binding.chipGroupPriority.setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.chip_high -> "high"
                R.id.chip_medium -> "medium"
                R.id.chip_low -> "low"
                else -> null
            }
            renderTasks()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    db.taskDao().getAllTasks().collect {
                        allTasks = it.focusOrder()
                        renderTasks()
                        refreshTimer()
                    }
                }
                launch {
                    while (true) {
                        refreshTimer()
                        delay(1_000L)
                    }
                }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refreshTimer()
    }

    // ---------- Timer ----------

    private fun focusTaskName(): String =
        allTasks.firstOrNull { !it.isCompleted }?.title ?: "Focus session"

    private fun togglePlayPause() {
        val context = requireContext()
        val state = FocusSession.state(context)
        if (state.active) {
            FocusSession.pause(context)
        } else {
            try {
                FocusSession.start(context, focusTaskName())
                if (!context.canDrawOverlay() || !context.hasUsageAccess()) {
                    Toast.makeText(context, "Timer started. Finish setup to also block distracting apps.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Could not start the focus session", Toast.LENGTH_SHORT).show()
            }
        }
        binding.root.postDelayed({ if (_binding != null) refreshTimer() }, 300)
    }

    private fun refreshTimer() {
        if (_binding == null) return
        val context = requireContext()
        val state = FocusSession.state(context)
        binding.tvTimer.text = formatClock(state.remainingMs)
        binding.ringTimer.setProgressCompat((state.progress * 100).toInt(), false)
        binding.tvTimerState.text = when {
            state.active -> "Active"
            state.paused -> "Paused"
            else -> "Ready"
        }
        binding.tvTimerHint.visibility = if (state.active || state.paused) View.INVISIBLE else View.VISIBLE
        binding.btnPlayPause.setImageResource(if (state.active) R.drawable.ic_pause else R.drawable.ic_play)
        binding.tvFocusTask.text = "Focusing on: " + (if (state.active || state.paused) state.task else focusTaskName())
        binding.tvBlockerStatus.text = if (context.canDrawOverlay() && context.hasUsageAccess()) {
            "Distraction blocker is on during sessions"
        } else {
            "Distraction blocker is off: allow it in setup on the Home tab"
        }
    }

    private fun chooseDuration() {
        val state = FocusSession.state(requireContext())
        if (state.active) {
            Toast.makeText(requireContext(), "Pause or reset the timer to change its length", Toast.LENGTH_SHORT).show()
            return
        }
        val choices = FocusSession.DURATION_CHOICES_MIN
        val labels = choices.map { "$it minutes" }.toTypedArray()
        val current = choices.indexOf((state.durationMs / 60_000L).toInt())
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Session length")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                FocusSession.setDuration(requireContext(), choices[which])
                refreshTimer()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Tasks ----------

    private fun renderTasks() {
        if (_binding == null) return
        val context = requireContext()
        val list = binding.taskList
        list.removeAllViews()
        val tasks = allTasks.filter { filter == null || it.priority == filter }
        if (tasks.isEmpty()) {
            list.addView(TextView(context).apply {
                text = if (allTasks.isEmpty()) "No tasks yet. Tap \"Add Task\" to create one." else "No tasks with this priority."
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, context.dp(12), 0, context.dp(12))
            })
            return
        }
        for (task in tasks) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(2), 0, context.dp(2))
            }
            val check = CheckBox(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = task.title
                isChecked = task.isCompleted
                setTextColor(ContextCompat.getColor(context, if (task.isCompleted) R.color.text_secondary else R.color.text_primary))
                paintFlags = if (task.isCompleted) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                setOnCheckedChangeListener { _, checked ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        db.taskDao().updateTask(task.copy(isCompleted = checked))
                    }
                }
                setOnLongClickListener {
                    confirmDelete(task)
                    true
                }
            }
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(context.dp(10), context.dp(10)).apply {
                    marginStart = context.dp(8)
                    marginEnd = context.dp(8)
                }
                background = ContextCompat.getDrawable(context, R.drawable.bg_dot)
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, priorityColor(task.priority)))
                contentDescription = "${task.priority} priority"
            }
            row.addView(check)
            row.addView(dot)
            list.addView(row)
        }
    }

    private fun showAddTaskDialog() {
        val dialogBinding = DialogAddTaskBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("New task")
            .setView(dialogBinding.root)
            .setPositiveButton("Add") { _, _ ->
                val title = dialogBinding.etTaskTitle.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                val priority = when (dialogBinding.chipGroupNewPriority.checkedChipId) {
                    R.id.chip_new_high -> "high"
                    R.id.chip_new_low -> "low"
                    else -> "medium"
                }
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    db.taskDao().insertTask(TaskEntity(title = title, priority = priority))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(task: TaskEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete task?")
            .setMessage(task.title)
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { db.taskDao().deleteTask(task) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Allowed apps ----------

    private fun showAllowedAppsDialog() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val pm = context.packageManager
            val apps = withContext(Dispatchers.IO) {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first != context.packageName }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase(Locale.getDefault()) }
            }
            val allowed = withContext(Dispatchers.IO) { db.whitelistDao().getAllowedPackagesSync().toSet() }
            if (_binding == null) return@launch
            val labels = apps.map { it.second }.toTypedArray()
            val checked = BooleanArray(apps.size) { apps[it].first in allowed }

            MaterialAlertDialogBuilder(context)
                .setTitle("Apps allowed during focus")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton("Save") { _, _ ->
                    (requireActivity() as MainActivity).lifecycleScope.launch(Dispatchers.IO) {
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
