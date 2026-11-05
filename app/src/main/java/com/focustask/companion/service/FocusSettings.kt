package com.focustask.companion.service

import android.content.Context
import android.content.SharedPreferences

/** User-adjustable behaviour of the "get back to your task" screen reminder. */
object FocusSettings {

    private const val PREFS = "focus_reminder_settings"
    private const val KEY_STYLE = "style"
    private const val KEY_REPOPUP_SEC = "repopup_sec"
    private const val KEY_GRACE_SEC = "grace_sec"
    private const val KEY_SNOOZE_MIN = "snooze_min"
    private const val KEY_OPACITY = "opacity_pct"
    private const val KEY_MESSAGE = "message"
    private const val KEY_STRICT = "strict"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_BACK_ACTION = "back_action"

    const val STYLE_FULL = "full"
    const val STYLE_BANNER = "banner"
    const val BACK_HOME = "home"
    const val BACK_APP = "app"
    const val DEFAULT_MESSAGE = "Get back to your task!"

    val REPOPUP_CHOICES_SEC = listOf(5, 10, 15, 30, 60, 120, 300, 600)
    val GRACE_CHOICES_SEC = listOf(0, 5, 10, 30, 60, 120)
    val SNOOZE_CHOICES_MIN = listOf(0, 1, 2, 5, 10, 15)

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun style(c: Context) = prefs(c).getString(KEY_STYLE, STYLE_FULL) ?: STYLE_FULL
    fun repopupSec(c: Context) = prefs(c).getInt(KEY_REPOPUP_SEC, 15)
    fun graceSec(c: Context) = prefs(c).getInt(KEY_GRACE_SEC, 0)
    fun snoozeMin(c: Context) = prefs(c).getInt(KEY_SNOOZE_MIN, 5)
    fun opacityPct(c: Context) = prefs(c).getInt(KEY_OPACITY, 94)
    fun message(c: Context) = prefs(c).getString(KEY_MESSAGE, null)?.ifBlank { null } ?: DEFAULT_MESSAGE
    fun strict(c: Context) = prefs(c).getBoolean(KEY_STRICT, false)
    fun vibrate(c: Context) = prefs(c).getBoolean(KEY_VIBRATE, true)
    fun backAction(c: Context) = prefs(c).getString(KEY_BACK_ACTION, BACK_HOME) ?: BACK_HOME

    fun setStyle(c: Context, v: String) = prefs(c).edit().putString(KEY_STYLE, v).apply()
    fun setRepopupSec(c: Context, v: Int) = prefs(c).edit().putInt(KEY_REPOPUP_SEC, v).apply()
    fun setGraceSec(c: Context, v: Int) = prefs(c).edit().putInt(KEY_GRACE_SEC, v).apply()
    fun setSnoozeMin(c: Context, v: Int) = prefs(c).edit().putInt(KEY_SNOOZE_MIN, v).apply()
    fun setOpacityPct(c: Context, v: Int) = prefs(c).edit().putInt(KEY_OPACITY, v.coerceIn(50, 100)).apply()
    fun setMessage(c: Context, v: String) = prefs(c).edit().putString(KEY_MESSAGE, v.take(80)).apply()
    fun setStrict(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_STRICT, v).apply()
    fun setVibrate(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_VIBRATE, v).apply()
    fun setBackAction(c: Context, v: String) = prefs(c).edit().putString(KEY_BACK_ACTION, v).apply()

    fun describeSeconds(sec: Int): String = when {
        sec == 0 -> "Immediately"
        sec < 60 -> "$sec seconds"
        sec == 60 -> "1 minute"
        else -> "${sec / 60} minutes"
    }
}
