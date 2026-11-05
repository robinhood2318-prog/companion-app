package com.focustask.companion.deadline

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.ui.MainActivity
import com.focustask.companion.util.QuickSpeaker
import com.focustask.companion.util.VoiceSettings
import com.focustask.companion.util.notifyIfAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shows "due tomorrow" / "due today" reminders (not exported). */
class DeadlineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeadlineScheduler.ACTION_DEADLINE) return
        val taskId = intent.getLongExtra(DeadlineScheduler.EXTRA_TASK_ID, -1L)
        if (taskId < 0) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var speaking = false
            try {
                val task = (appContext as FocusTaskApp).database.taskDao().getTaskById(taskId) ?: return@launch
                val due = task.dueDateTime ?: return@launch
                if (task.isCompleted) return@launch

                val countdown = Deadlines.countdown(due)
                val detail = buildString {
                    append(countdown)
                    if (Deadlines.hasParts(task)) {
                        append(" · ").append(Deadlines.progressText(task))
                        Deadlines.paceText(task)?.let { append(" · ").append(it) }
                    }
                }
                val open = PendingIntent.getActivity(
                    appContext, 2, Intent(appContext, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(appContext, FocusTaskApp.CHANNEL_REMINDER_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                    .setContentTitle(task.title)
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                appContext.notifyIfAllowed(NOTIFICATION_BASE + taskId.toInt(), notification)

                if (VoiceSettings.isEnabled(appContext, VoiceSettings.Section.CALENDAR)) {
                    speaking = true
                    QuickSpeaker.speak(appContext, "Reminder: ${task.title}. $countdown.") { pending.finish() }
                }
            } catch (e: Exception) {
                Log.e("DeadlineReceiver", "Deadline reminder failed", e)
            } finally {
                if (!speaking) pending.finish()
            }
        }
    }

    private companion object {
        const val NOTIFICATION_BASE = 7000
    }
}
