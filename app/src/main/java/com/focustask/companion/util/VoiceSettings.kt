package com.focustask.companion.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.ImageButton
import android.widget.Toast
import com.focustask.companion.R
import java.util.Locale

/** Per-section text-to-speech switches. Every section speaks by default. */
object VoiceSettings {

    enum class Section(val key: String, val label: String) {
        FOCUS("voice_focus", "Focus reminders"),
        CALENDAR("voice_calendar", "Deadline reminders"),
        ALARMS("voice_alarms", "Alarm callouts"),
        FITNESS("voice_fitness", "Step goal announcements"),
        MEALS("voice_meals", "Cooking reminders")
    }

    private const val PREFS = "voice_settings"

    fun isEnabled(context: Context, section: Section): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(section.key, true)

    fun setEnabled(context: Context, section: Section, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(section.key, enabled).apply()
    }

    /** Wires a header speaker button: shows the current state and toggles it on tap. */
    fun bindToggle(button: ImageButton, section: Section) {
        fun render() {
            val on = isEnabled(button.context, section)
            button.setImageResource(if (on) R.drawable.ic_volume else R.drawable.ic_volume_off)
            button.contentDescription = "${section.label}: voice ${if (on) "on" else "off"}"
            button.alpha = if (on) 1f else 0.6f
        }
        render()
        button.setOnClickListener {
            val on = !isEnabled(button.context, section)
            setEnabled(button.context, section, on)
            render()
            Toast.makeText(button.context, "${section.label}: voice ${if (on) "on" else "off"}", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Speaks one short sentence from a BroadcastReceiver, then calls [onDone]
 * (used to finish goAsync()). Gives up after 9 s to stay inside the receiver time limit.
 */
object QuickSpeaker {
    fun speak(context: Context, text: String, onDone: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            var tts: TextToSpeech? = null
            var finished = false
            fun finish() {
                if (finished) return
                finished = true
                tts?.shutdown()
                onDone()
            }
            main.postDelayed({ finish() }, 9_000L)
            tts = TextToSpeech(context.applicationContext) { status ->
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    finish()
                    return@TextToSpeech
                }
                val result = engine.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { finish() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { finish() } }
                })
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quick_speaker")
            }
        }
    }
}
