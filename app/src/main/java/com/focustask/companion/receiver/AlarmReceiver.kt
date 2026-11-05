package com.focustask.companion.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.alarm.AlarmRingService
import com.focustask.companion.alarm.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives alarm triggers (not exported; only our own PendingIntents can reach it). */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_TRIGGER_ALARM) return
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (alarmId < 0) return
        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_SNOOZE, false)

        val pending = goAsync()
        val appContext = context.applicationContext
        val dao = (appContext as FocusTaskApp).database.alarmDao()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val alarm = dao.getAlarmById(alarmId) ?: return@launch // deleted meanwhile
                if (!alarm.isEnabled && !isSnooze) return@launch

                if (!isSnooze) {
                    if (AlarmScheduler.isRepeating(alarm)) {
                        AlarmScheduler.schedule(appContext, alarm)
                    } else {
                        dao.updateAlarm(alarm.copy(isEnabled = false))
                    }
                }
                try {
                    AlarmRingService.start(appContext, alarm)
                } catch (e: Exception) {
                    // e.g. ForegroundServiceStartNotAllowedException after an inexact fallback alarm
                    Log.w("AlarmReceiver", "Ringing service blocked; showing notification", e)
                    AlarmRingService.showFallbackNotification(appContext, alarm)
                }
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Failed to ring alarm $alarmId", e)
            } finally {
                pending.finish()
            }
        }
    }
}
