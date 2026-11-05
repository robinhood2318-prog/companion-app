package com.focustask.companion.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Pomodoro-style focus session state, shared by the Focus screen, the dashboard and
 * [FocusOverlayService]. Stored in SharedPreferences so it survives process death.
 */
object FocusSession {

    const val PREFS = "focus_session"
    const val KEY_ACTIVE = "active"
    const val KEY_TASK = "task"
    const val KEY_REPOPUP = "repopup_ms"
    const val KEY_END_AT = "end_at"
    const val KEY_DURATION = "duration_ms"
    const val KEY_PAUSED_REMAINING = "paused_remaining"

    const val DEFAULT_DURATION_MS = 25 * 60_000L
    val DURATION_CHOICES_MIN = listOf(15, 25, 45, 60, 90)

    data class State(
        val active: Boolean,
        val paused: Boolean,
        val task: String,
        val durationMs: Long,
        val remainingMs: Long
    ) {
        val progress: Float get() = if (durationMs <= 0) 0f else remainingMs.toFloat() / durationMs
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(context: Context): State {
        val p = prefs(context)
        val active = p.getBoolean(KEY_ACTIVE, false)
        val duration = p.getLong(KEY_DURATION, DEFAULT_DURATION_MS)
        val paused = p.getLong(KEY_PAUSED_REMAINING, -1L)
        val remaining = when {
            active -> (p.getLong(KEY_END_AT, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
            paused >= 0 -> paused
            else -> duration
        }
        return State(active, !active && paused >= 0, p.getString(KEY_TASK, "") ?: "", duration, remaining)
    }

    fun setDuration(context: Context, minutes: Int) {
        prefs(context).edit()
            .putLong(KEY_DURATION, minutes * 60_000L)
            .remove(KEY_PAUSED_REMAINING)
            .apply()
    }

    /** Starts or resumes the timer (and the distraction blocker when its permissions are granted). */
    fun start(context: Context, task: String) {
        val st = state(context)
        val remaining = if (st.paused && st.remainingMs > 0) st.remainingMs else st.durationMs
        FocusOverlayService.start(context, task, remaining)
    }

    fun pause(context: Context) {
        val st = state(context)
        if (!st.active) return
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_PAUSED_REMAINING, st.remainingMs)
            .apply()
        FocusOverlayService.stop(context)
    }

    fun reset(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, false)
            .remove(KEY_PAUSED_REMAINING)
            .apply()
        FocusOverlayService.stop(context)
    }
}
