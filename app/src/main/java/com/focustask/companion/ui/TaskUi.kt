package com.focustask.companion.ui

import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.data.model.TaskEntity
import com.focustask.companion.databinding.DialogAddTaskBinding
import com.focustask.companion.deadline.DeadlineScheduler
import com.focustask.companion.deadline.Deadlines
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

/** Add/edit dialogs and list rows for tasks and deadlines, shared by the Focus and Calendar tabs. */
object TaskUi {

    /** End of the chosen day, so "due on Friday" means "by Friday night". */
    fun endOfDay(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
        set(year, month, day, 23, 59, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun formatDue(context: Context, due: Long): String =
        DateFormat.getMediumDateFormat(context).format(Date(due))

    /** Add a new task ([existing] = null) or edit one. [presetDue] pre-fills the deadline. */
    fun showEditor(fragment: Fragment, existing: TaskEntity?, presetDue: Long? = null) {
        val context = fragment.requireContext()
        val b = DialogAddTaskBinding.inflate(fragment.layoutInflater)
        var due: Long? = existing?.dueDateTime ?: presetDue

        fun renderDue() {
            b.btnDue.text = due?.let { "Due " + formatDue(context, it) + " · " + Deadlines.countdown(it) } ?: "No deadline"
            b.btnClearDue.visibility = if (due == null) View.GONE else View.VISIBLE
        }

        existing?.let { t ->
            b.etTaskTitle.setText(t.title)
            b.chipGroupNewPriority.check(
                when (t.priority) {
                    "high" -> R.id.chip_new_high
                    "low" -> R.id.chip_new_low
                    else -> R.id.chip_new_medium
                }
            )
            if (t.totalParts > 0) b.etParts.setText(t.totalParts.toString())
            b.etPartsLabel.setText(t.partsLabel)
        }
        renderDue()
        b.btnDue.setOnClickListener {
            val cal = Calendar.getInstance().apply { due?.let { timeInMillis = it } }
            DatePickerDialog(context, { _, y, m, d ->
                due = endOfDay(y, m, d)
                renderDue()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        b.btnClearDue.setOnClickListener {
            due = null
            renderDue()
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) "New task" else "Edit task")
            .setView(b.root)
            .setPositiveButton(if (existing == null) "Add" else "Save") { _, _ ->
                val title = b.etTaskTitle.text.toString().trim()
                if (title.isEmpty()) return@setPositiveButton
                val priority = when (b.chipGroupNewPriority.checkedChipId) {
                    R.id.chip_new_high -> "high"
                    R.id.chip_new_low -> "low"
                    else -> "medium"
                }
                val total = b.etParts.text.toString().toIntOrNull()?.coerceIn(0, 9999) ?: 0
                val label = b.etPartsLabel.text.toString().trim()
                val task = (existing ?: TaskEntity(title = title, priority = priority)).copy(
                    title = title,
                    priority = priority,
                    dueDateTime = due,
                    totalParts = total,
                    doneParts = (existing?.doneParts ?: 0).coerceAtMost(total),
                    partsLabel = label
                )
                save(fragment, task)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Tap on a task: progress, edit, complete or delete. */
    fun showActions(fragment: Fragment, task: TaskEntity) {
        val context = fragment.requireContext()
        val actions = buildList {
            if (Deadlines.hasParts(task) && task.doneParts < task.totalParts) {
                add("+1 ${Deadlines.unit(task)} done")
            }
            if (Deadlines.hasParts(task) && task.doneParts > 0) add("Undo 1 ${Deadlines.unit(task)}")
            add(if (task.isCompleted) "Mark as not done" else "Mark as done")
            add("Edit")
            add("Delete")
        }
        val message = buildString {
            task.dueDateTime?.let { append("Due ${formatDue(context, it)} · ${Deadlines.countdown(it)}") }
            if (Deadlines.hasParts(task)) {
                if (isNotEmpty()) append("\n")
                append(Deadlines.progressText(task))
                Deadlines.paceText(task)?.let { append(" · ").append(it) }
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(task.title + if (message.isNotEmpty()) "\n$message" else "")
            .setItems(actions.toTypedArray()) { _, which ->
                val action = actions[which]
                when {
                    action.startsWith("+1") -> {
                        val done = task.doneParts + 1
                        save(fragment, task.copy(doneParts = done, isCompleted = done >= task.totalParts))
                    }
                    action.startsWith("Undo") ->
                        save(fragment, task.copy(doneParts = task.doneParts - 1, isCompleted = false))
                    action == "Mark as done" -> save(fragment, task.copy(isCompleted = true))
                    action == "Mark as not done" -> save(fragment, task.copy(isCompleted = false))
                    action == "Edit" -> showEditor(fragment, task)
                    action == "Delete" -> confirmDelete(fragment, task)
                }
            }
            .show()
    }

    fun setCompleted(fragment: Fragment, task: TaskEntity, done: Boolean) {
        save(fragment, task.copy(isCompleted = done))
    }

    private fun confirmDelete(fragment: Fragment, task: TaskEntity) {
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle("Delete task?")
            .setMessage(task.title)
            .setPositiveButton("Delete") { _, _ ->
                val app = fragment.requireActivity().application as FocusTaskApp
                DeadlineScheduler.cancel(app, task.id)
                fragment.requireActivity().lifecycleScope.launch(Dispatchers.IO) {
                    app.database.taskDao().deleteTask(task)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(fragment: Fragment, task: TaskEntity) {
        val app = fragment.requireActivity().application as FocusTaskApp
        fragment.requireActivity().lifecycleScope.launch(Dispatchers.IO) {
            val id = app.database.taskDao().insertTask(task)
            DeadlineScheduler.schedule(app, task.copy(id = id))
        }
    }

    /** One task row: checkbox + title, priority dot, and deadline/progress details. */
    fun buildRow(
        context: Context,
        task: TaskEntity,
        onChecked: (Boolean) -> Unit,
        onClick: () -> Unit
    ): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(4), 0, context.dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val check = CheckBox(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = task.title
            isChecked = task.isCompleted
            setTextColor(ContextCompat.getColor(context, if (task.isCompleted) R.color.text_secondary else R.color.text_primary))
            paintFlags = if (task.isCompleted) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            setOnCheckedChangeListener { _, checked -> onChecked(checked) }
            setOnLongClickListener {
                onClick()
                true
            }
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(10), context.dp(10)).apply {
                marginStart = context.dp(8)
                marginEnd = context.dp(4)
            }
            background = ContextCompat.getDrawable(context, R.drawable.bg_dot)
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, priorityColor(task.priority)))
            contentDescription = "${task.priority} priority"
        }
        row.addView(check)
        row.addView(dot)
        column.addView(row)

        val due = task.dueDateTime
        if (due != null || Deadlines.hasParts(task)) {
            val details = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(32), 0, 0, 0)
            }
            if (due != null && !task.isCompleted) {
                details.addView(TextView(context).apply {
                    text = Deadlines.countdown(due)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, android.R.color.white))
                    setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
                    background = ContextCompat.getDrawable(context, R.drawable.bg_pill)
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, Deadlines.color(due)))
                })
            } else if (due != null) {
                details.addView(TextView(context).apply {
                    text = "Done · was due ${formatDue(context, due)}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                })
            }
            if (Deadlines.hasParts(task)) {
                details.addView(TextView(context).apply {
                    text = Deadlines.progressText(task)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(context.dp(8), 0, 0, 0)
                })
            }
            column.addView(details)

            if (Deadlines.hasParts(task)) {
                column.addView(LinearProgressIndicator(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = context.dp(32)
                        topMargin = context.dp(6)
                    }
                    max = task.totalParts
                    progress = task.doneParts.coerceAtMost(task.totalParts)
                    trackCornerRadius = context.dp(3)
                    trackThickness = context.dp(6)
                    setIndicatorColor(ContextCompat.getColor(context, R.color.accent_green))
                    trackColor = ContextCompat.getColor(context, R.color.ring_track)
                })
                if (!task.isCompleted) {
                    Deadlines.paceText(task)?.let { pace ->
                        column.addView(TextView(context).apply {
                            text = pace
                            textSize = 12f
                            setTextColor(ContextCompat.getColor(context, R.color.primary))
                            setPadding(context.dp(32), context.dp(4), 0, 0)
                        })
                    }
                }
            }
        }
        return column
    }
}
