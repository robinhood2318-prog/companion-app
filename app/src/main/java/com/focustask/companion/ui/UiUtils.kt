package com.focustask.companion.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.ColorRes
import com.focustask.companion.R
import com.focustask.companion.alarm.AlarmScheduler
import com.focustask.companion.data.model.AlarmEntity
import com.focustask.companion.data.model.TaskEntity
import java.util.Calendar

fun priorityRank(priority: String) = when (priority) {
    "high" -> 0
    "medium" -> 1
    else -> 2
}

@ColorRes
fun priorityColor(priority: String) = when (priority) {
    "high" -> R.color.accent_red
    "medium" -> R.color.accent_orange
    else -> R.color.accent_green
}

/** Incomplete tasks first, by priority, then oldest first. */
fun List<TaskEntity>.focusOrder(): List<TaskEntity> =
    sortedWith(compareBy<TaskEntity>({ it.isCompleted }, { priorityRank(it.priority) }, { it.createdAt }))

fun List<AlarmEntity>.nextAlarm(): Pair<AlarmEntity, Long>? =
    filter { it.isEnabled }
        .map { it to AlarmScheduler.nextTriggerMillis(it) }
        .minByOrNull { it.second }

fun formatTime(context: Context, hour: Int, minute: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return DateFormat.getTimeFormat(context).format(cal.time)
}

/** Splits "7:00 AM" into ("7:00", "AM"); 24-hour formats return an empty suffix. */
fun splitTime(context: Context, hour: Int, minute: Int): Pair<String, String> {
    val text = formatTime(context, hour, minute)
    val space = text.lastIndexOf(' ')
    return if (space > 0 && !DateFormat.is24HourFormat(context)) {
        text.substring(0, space) to text.substring(space + 1)
    } else {
        text to ""
    }
}

fun repeatLabel(mask: Int) = when (mask and AlarmScheduler.MASK_EVERY_DAY) {
    AlarmScheduler.MASK_ONCE -> "Once"
    AlarmScheduler.MASK_WEEKDAYS -> "Weekdays"
    AlarmScheduler.MASK_EVERY_DAY -> "Every day"
    else -> "Custom days"
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
