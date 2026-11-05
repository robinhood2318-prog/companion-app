package com.focustask.companion.deadline

import androidx.annotation.ColorRes
import com.focustask.companion.R
import com.focustask.companion.data.model.TaskEntity
import java.util.Calendar
import kotlin.math.ceil

/** Countdown text, colours and study pace for tasks that have a deadline. */
object Deadlines {

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Whole calendar days from today to the due date (negative = overdue). */
    fun daysLeft(due: Long, now: Long = System.currentTimeMillis()): Int {
        val diff = startOfDay(due) - startOfDay(now)
        return Math.round(diff / 86_400_000.0).toInt() // round handles daylight-saving shifts
    }

    fun countdown(due: Long): String {
        val d = daysLeft(due)
        return when {
            d < -1 -> "${-d} days overdue"
            d == -1 -> "1 day overdue"
            d == 0 -> "Due today"
            d == 1 -> "Due tomorrow"
            else -> "$d days left"
        }
    }

    @ColorRes
    fun color(due: Long): Int {
        val d = daysLeft(due)
        return when {
            d <= 1 -> R.color.accent_red
            d <= 6 -> R.color.accent_orange
            else -> R.color.accent_green
        }
    }

    fun hasParts(task: TaskEntity) = task.totalParts > 0

    fun unit(task: TaskEntity) = task.partsLabel.ifBlank { "parts" }

    fun progressText(task: TaskEntity): String =
        "${task.doneParts.coerceAtMost(task.totalParts)}/${task.totalParts} ${unit(task)}"

    /** e.g. "~2 chapters a day to finish on time". */
    fun paceText(task: TaskEntity): String? {
        val due = task.dueDateTime ?: return null
        if (!hasParts(task)) return null
        val remaining = task.totalParts - task.doneParts
        if (remaining <= 0) return "All ${unit(task)} done"
        val days = (daysLeft(due) + 1).coerceAtLeast(1) // include today
        val perDay = ceil(remaining.toDouble() / days).toInt()
        return "~$perDay ${unit(task)} a day to finish on time"
    }
}
