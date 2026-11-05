package com.focustask.companion.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.focustask.companion.data.model.AlarmEntity
import com.focustask.companion.receiver.AlarmReceiver
import com.focustask.companion.ui.MainActivity
import java.util.Calendar

/**
 * Schedules alarms with AlarmManager.
 *
 * daysMask bits: Monday = bit 0 ... Sunday = bit 6. A mask of 0 means "ring once".
 * The default of 31 (0b0011111) is Monday to Friday.
 */
object AlarmScheduler {

    const val ACTION_TRIGGER_ALARM = "com.focustask.companion.ACTION_TRIGGER_ALARM"
    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_IS_SNOOZE = "extra_is_snooze"

    const val MASK_ONCE = 0
    const val MASK_WEEKDAYS = 0b0011111
    const val MASK_EVERY_DAY = 0b1111111

    private const val SNOOZE_REQUEST_FLAG = 1 shl 30

    fun isRepeating(alarm: AlarmEntity): Boolean = alarm.daysMask and MASK_EVERY_DAY != 0

    /** Converts a [Calendar.DAY_OF_WEEK] value to its bit in daysMask. */
    private fun dayBit(calendarDay: Int): Int = 1 shl ((calendarDay + 5) % 7)

    fun nextTriggerMillis(alarm: AlarmEntity, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (isRepeating(alarm)) {
            repeat(7) {
                if (alarm.daysMask and dayBit(cal.get(Calendar.DAY_OF_WEEK)) != 0) return cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    /** Schedules the next occurrence, or cancels it if the alarm is disabled. Returns the trigger time. */
    fun schedule(context: Context, alarm: AlarmEntity): Long? {
        if (!alarm.isEnabled) {
            cancel(context, alarm.id)
            return null
        }
        val at = nextTriggerMillis(alarm)
        setAlarm(context, at, triggerIntent(context, alarm.id, alarm.id.toInt(), isSnooze = false))
        return at
    }

    fun scheduleSnooze(context: Context, alarm: AlarmEntity) {
        val at = System.currentTimeMillis() + alarm.snoozeMinutes.coerceIn(1, 60) * 60_000L
        setAlarm(context, at, triggerIntent(context, alarm.id, snoozeRequestCode(alarm.id), isSnooze = true))
    }

    fun cancel(context: Context, alarmId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(triggerIntent(context, alarmId, alarmId.toInt(), isSnooze = false))
        alarmManager.cancel(triggerIntent(context, alarmId, snoozeRequestCode(alarmId), isSnooze = true))
    }

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun snoozeRequestCode(alarmId: Long) = alarmId.toInt() or SNOOZE_REQUEST_FLAG

    private fun triggerIntent(context: Context, alarmId: Long, requestCode: Int, isSnooze: Boolean): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_IS_SNOOZE, isSnooze)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarm(context: Context, triggerAt: Long, operation: PendingIntent) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) {
            try {
                val showIntent = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                // setAlarmClock is exact, works in Doze and shows the alarm icon in the status bar.
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
                return
            } catch (_: SecurityException) {
                // Exact alarm permission was revoked between the check and the call.
            }
        }
        // Fallback: may fire a few minutes late, but still fires in Doze.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
    }
}
