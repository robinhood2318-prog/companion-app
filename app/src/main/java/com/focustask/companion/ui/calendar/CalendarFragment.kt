package com.focustask.companion.ui.calendar

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.data.model.TaskEntity
import com.focustask.companion.databinding.FragmentCalendarBinding
import com.focustask.companion.deadline.Deadlines
import com.focustask.companion.ui.TaskUi
import com.focustask.companion.ui.dp
import com.focustask.companion.util.VoiceSettings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Month calendar of deadlines with countdowns ("7 days left"). Deadlines are tasks with a due date. */
class CalendarFragment : Fragment() {

    private var _binding: FragmentCalendarBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as FocusTaskApp).database }

    private var tasks: List<TaskEntity> = emptyList()
    private val shownMonth: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private val selected: Calendar = Calendar.getInstance()
    private val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.getLong(STATE_SELECTED)?.takeIf { it > 0 }?.let {
            selected.timeInMillis = it
            shownMonth.timeInMillis = it
            shownMonth.set(Calendar.DAY_OF_MONTH, 1)
        }
        VoiceSettings.bindToggle(binding.btnVoice, VoiceSettings.Section.CALENDAR)
        buildWeekdayRow()
        binding.btnPrevMonth.setOnClickListener { shownMonth.add(Calendar.MONTH, -1); render() }
        binding.btnNextMonth.setOnClickListener { shownMonth.add(Calendar.MONTH, 1); render() }
        binding.btnToday.setOnClickListener {
            selected.timeInMillis = System.currentTimeMillis()
            shownMonth.timeInMillis = selected.timeInMillis
            shownMonth.set(Calendar.DAY_OF_MONTH, 1)
            render()
        }
        binding.fabAddEvent.setOnClickListener {
            TaskUi.showEditor(
                this, null,
                presetDue = TaskUi.endOfDay(
                    selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)
                )
            )
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.taskDao().getAllTasks().collect {
                    tasks = it
                    render()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SELECTED, selected.timeInMillis)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            VoiceSettings.bindToggle(binding.btnVoice, VoiceSettings.Section.CALENDAR)
            render() // countdowns change at midnight
        }
    }

    private fun key(cal: Calendar) = keyFormat.format(cal.time)
    private fun key(millis: Long) = keyFormat.format(java.util.Date(millis))

    private fun buildWeekdayRow() {
        val context = requireContext()
        val row = binding.weekdayRow
        row.removeAllViews()
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
            row.addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                text = d
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
    }

    private fun render() {
        if (_binding == null) return
        binding.tvMonth.text = DateFormat.format("MMMM yyyy", shownMonth)
        renderGrid()
        renderSelectedDay()
        renderUpcoming()
    }

    private fun renderGrid() {
        val context = requireContext()
        val grid = binding.monthGrid
        grid.removeAllViews()

        // Most urgent open deadline per day decides the dot colour.
        val dueByDay = tasks.filter { it.dueDateTime != null }.groupBy { key(it.dueDateTime!!) }
        val todayKey = key(Calendar.getInstance())
        val selectedKey = key(selected)

        val cursor = shownMonth.clone() as Calendar
        cursor.set(Calendar.DAY_OF_MONTH, 1)
        val leading = (cursor.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday-first
        val daysInMonth = cursor.getActualMaximum(Calendar.DAY_OF_MONTH)
        val cells = leading + daysInMonth
        val rows = (cells + 6) / 7

        var day = 1
        for (r in 0 until rows) {
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 7) {
                val index = r * 7 + c
                if (index < leading || day > daysInMonth) {
                    rowLayout.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, context.dp(48), 1f)
                    })
                    continue
                }
                val thisDay = day
                cursor.set(Calendar.DAY_OF_MONTH, thisDay)
                val k = key(cursor)
                val items = dueByDay[k].orEmpty()
                val open = items.filter { !it.isCompleted }
                rowLayout.addView(dayCell(thisDay, k == selectedKey, k == todayKey, items.isNotEmpty(), open))
                day++
            }
            grid.addView(rowLayout)
        }
    }

    private fun dayCell(day: Int, selectedDay: Boolean, today: Boolean, hasItems: Boolean, open: List<TaskEntity>): View {
        val context = requireContext()
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, context.dp(48), 1f)
            isClickable = true
            setOnClickListener {
                selected.timeInMillis = shownMonth.timeInMillis
                selected.set(Calendar.DAY_OF_MONTH, day)
                render()
            }
        }
        cell.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(34), context.dp(34))
            gravity = Gravity.CENTER
            text = day.toString()
            background = ContextCompat.getDrawable(context, R.drawable.bg_calendar_day)
            isSelected = selectedDay
            isActivated = today && !selectedDay
            setTextColor(
                ContextCompat.getColor(context, if (selectedDay) android.R.color.white else R.color.text_primary)
            )
            if (today) setTypeface(typeface, Typeface.BOLD)
            contentDescription = "$day" + if (hasItems) ", has deadlines" else ""
        })
        cell.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(6), context.dp(6)).apply { topMargin = context.dp(2) }
            background = ContextCompat.getDrawable(context, R.drawable.bg_dot)
            visibility = if (hasItems) View.VISIBLE else View.INVISIBLE
            val colorRes = open.mapNotNull { it.dueDateTime }.minOrNull()?.let { Deadlines.color(it) } ?: R.color.text_secondary
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
        })
        return cell
    }

    private fun renderSelectedDay() {
        val k = key(selected)
        val isToday = k == key(Calendar.getInstance())
        binding.tvSelectedDay.text = DateFormat.format("EEEE, d MMMM", selected).toString() + if (isToday) " (today)" else ""
        val items = tasks.filter { it.dueDateTime != null && key(it.dueDateTime) == k }
        fillList(binding.dayList, items, "Nothing due this day. Tap \"Add deadline\" to plan something.")
    }

    private fun renderUpcoming() {
        val upcoming = tasks
            .filter { it.dueDateTime != null && !it.isCompleted }
            .sortedBy { it.dueDateTime }
            .take(MAX_UPCOMING)
        fillList(binding.upcomingList, upcoming, "No upcoming deadlines.")
    }

    private fun fillList(list: LinearLayout, items: List<TaskEntity>, empty: String) {
        val context = requireContext()
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = empty
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, context.dp(12), 0, context.dp(12))
            })
            return
        }
        items.forEach { task ->
            list.addView(TaskUi.buildRow(
                context, task,
                onChecked = { done -> TaskUi.setCompleted(this, task, done) },
                onClick = { TaskUi.showActions(this, task) }
            ))
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val STATE_SELECTED = "selected_day"
        const val MAX_UPCOMING = 10
    }
}
