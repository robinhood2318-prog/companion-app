package com.focustask.companion.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Small wrapper around TextToSpeech that queues text spoken before the engine is ready. Main thread only. */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var initFailed = false
    private val pendingSpeechQueue = mutableListOf<String>()

    override fun onInit(status: Int) {
        val engine = tts ?: return // released before init finished
        if (status == TextToSpeech.SUCCESS) {
            val result = engine.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
            isInitialized = true
            pendingSpeechQueue.forEach { speak(it) }
        } else {
            initFailed = true
            Log.e("TtsManager", "TextToSpeech initialization failed: $status")
        }
        pendingSpeechQueue.clear()
    }

    fun speak(text: String) {
        if (initFailed || tts == null) return
        if (!isInitialized) {
            if (pendingSpeechQueue.size < MAX_PENDING) pendingSpeechQueue.add(text)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "CALLOUT_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        pendingSpeechQueue.clear()
    }

    private companion object {
        const val MAX_PENDING = 5
    }
}
