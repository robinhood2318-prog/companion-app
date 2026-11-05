package com.focustask.companion.meals

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.ui.MainActivity
import com.focustask.companion.util.notifyIfAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shows the weekly cooking reminder (not exported; only our own PendingIntents reach it). */
class MealReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MealScheduler.ACTION_MEAL_REMINDER) return
        val mealId = intent.getLongExtra(MealScheduler.EXTRA_MEAL_ID, -1L)
        if (mealId < 0) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val meal = (appContext as FocusTaskApp).database.mealDao().getMealById(mealId) ?: return@launch
                MealScheduler.schedule(appContext, meal) // same time next week

                val skip = meal.mealType == "dinner" &&
                    meal.dayOfWeek == MealScheduler.todayName() &&
                    MealScheduler.isTakeoutTonight(appContext)
                if (skip || meal.dishName.isBlank()) return@launch

                val open = PendingIntent.getActivity(
                    appContext, 0, Intent(appContext, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(appContext, FocusTaskApp.CHANNEL_REMINDER_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("${MealScheduler.mealLabel(meal.mealType)}: time to cook")
                    .setContentText(meal.dishName)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                appContext.notifyIfAllowed(NOTIFICATION_BASE + mealId.toInt(), notification)
            } catch (e: Exception) {
                Log.e("MealReminderReceiver", "Meal reminder failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val NOTIFICATION_BASE = 6000
    }
}
