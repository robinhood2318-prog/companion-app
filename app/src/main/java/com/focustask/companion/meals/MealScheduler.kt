package com.focustask.companion.meals

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.focustask.companion.data.model.MealEntity
import com.focustask.companion.service.StepTrackerService
import java.util.Calendar

/**
 * Weekly cooking reminders. They don't need to be exact, so they use inexact alarms
 * (no special permission). "Takeout tonight" skips today's dinner reminder only.
 */
object MealScheduler {

    const val ACTION_MEAL_REMINDER = "com.focustask.companion.ACTION_MEAL_REMINDER"
    const val EXTRA_MEAL_ID = "extra_meal_id"

    val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val MEAL_TYPES = listOf("breakfast", "lunch", "dinner")

    private const val PREFS = "meals"
    private const val KEY_TAKEOUT_DATE = "takeout_date"

    fun defaultTime(mealType: String): Pair<Int, Int> = when (mealType) {
        "breakfast" -> 7 to 30
        "lunch" -> 12 to 0
        else -> 18 to 30
    }

    fun mealLabel(mealType: String) = mealType.replaceFirstChar { it.uppercase() }

    /** Index into [DAYS] for today (Monday = 0). */
    fun todayIndex(): Int = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun todayName(): String = DAYS[todayIndex()]

    private fun calendarDay(dayName: String): Int {
        val index = DAYS.indexOf(dayName).coerceAtLeast(0)
        return if (index == 6) Calendar.SUNDAY else index + 2
    }

    fun nextTriggerMillis(meal: MealEntity, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, meal.reminderTimeHour)
            set(Calendar.MINUTE, meal.reminderTimeMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target = calendarDay(meal.dayOfWeek)
        repeat(8) {
            if (cal.get(Calendar.DAY_OF_WEEK) == target && cal.timeInMillis > now) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun schedule(context: Context, meal: MealEntity) {
        if (meal.dishName.isBlank()) {
            cancel(context, meal.id)
            return
        }
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMillis(meal), pendingIntent(context, meal.id))
    }

    fun cancel(context: Context, mealId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, mealId))
    }

    private fun pendingIntent(context: Context, mealId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, mealId.toInt(),
            Intent(context, MealReminderReceiver::class.java)
                .setAction(ACTION_MEAL_REMINDER)
                .putExtra(EXTRA_MEAL_ID, mealId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun isTakeoutTonight(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAKEOUT_DATE, null) == StepTrackerService.todayKey()

    fun setTakeoutTonight(context: Context, takeout: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (takeout) putString(KEY_TAKEOUT_DATE, StepTrackerService.todayKey()) else remove(KEY_TAKEOUT_DATE)
        }.apply()
    }
}
