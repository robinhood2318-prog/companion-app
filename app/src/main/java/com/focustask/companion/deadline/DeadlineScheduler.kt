package com.focustask.companion.deadline

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.focustask.companion.data.model.TaskEntity
import java.util.Calendar

/**
 * Deadline reminders: 9:00 the day before and 9:00 on the due day.
 * They don't need minute precision, so they use inexact alarms (no special permission).
 */
object DeadlineScheduler {

    const val ACTION_DEADLINE = "com.focustask.companion.ACTION_DEADLINE_REMINDER"
    const val EXTRA_TASK_ID = "extra_task_id"
    private const val REMINDER_HOUR = 9

    fun schedule(context: Context, task: TaskEntity) {
        cancel(context, task.id)
        val due = task.dueDateTime ?: return
        if (task.isCompleted) return
        val now = System.currentTimeMillis()
        val am = context.getSystemService(AlarmManager::class.java)
        listOf(1 to 0, 0 to 1).forEach { (daysBefore, slot) ->
            val at = Calendar.getInstance().apply {
                timeInMillis = due
                add(Calendar.DAY_OF_YEAR, -daysBefore)
                set(Calendar.HOUR_OF_DAY, REMINDER_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            if (at > now) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, task.id, slot))
            }
        }
    }

    fun cancel(context: Context, taskId: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pendingIntent(context, taskId, 0))
        am.cancel(pendingIntent(context, taskId, 1))
    }

    private fun pendingIntent(context: Context, taskId: Long, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, (taskId * 2 + slot).toInt(),
            Intent(context, DeadlineReceiver::class.java)
                .setAction(ACTION_DEADLINE)
                .putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
