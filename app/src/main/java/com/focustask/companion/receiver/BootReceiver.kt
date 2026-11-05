package com.focustask.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.alarm.AlarmScheduler
import com.focustask.companion.meals.MealScheduler
import com.focustask.companion.service.StepTrackerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-schedules alarms after events that clear or shift them: reboot, app update,
 * clock/time-zone changes and the exact-alarm permission being granted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = (appContext as FocusTaskApp).database.alarmDao()
                val db = (appContext as FocusTaskApp).database
                dao.getActiveAlarmsSync().forEach { AlarmScheduler.schedule(appContext, it) }
                db.mealDao().getAllMealsSync().forEach { MealScheduler.schedule(appContext, it) }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to reschedule alarms", e)
            } finally {
                pending.finish()
            }
        }

        if (action == Intent.ACTION_BOOT_COMPLETED || action == ACTION_QUICKBOOT) {
            StepTrackerService.startIfPermitted(appContext)
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
