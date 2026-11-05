package com.focustask.companion.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.Random

/**
 * Generates soothing noise on the fly (no audio files needed):
 *  - "brown": deep, ocean-like rumble
 *  - "pink":  soft, rain-like hiss
 *  - "white": flat hiss
 */
class NoisePlayer(private val type: String) {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ generate() }, "NoisePlayer").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun generate() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e("NoisePlayer", "AudioTrack unavailable", e)
            running = false
            return
        }

        val random = Random()
        val buffer = ShortArray(2048)
        var brown = 0.0
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        try {
            track.play()
            while (running) {
                for (i in buffer.indices) {
                    val white = random.nextDouble() * 2.0 - 1.0
                    val sample = when (type) {
                        "brown" -> {
                            brown = (brown + 0.02 * white) / 1.02
                            brown * 3.5
                        }
                        "pink" -> { // Paul Kellet's economy pink-noise filter
                            b0 = 0.99765 * b0 + white * 0.0990460
                            b1 = 0.96300 * b1 + white * 0.2965164
                            b2 = 0.57000 * b2 + white * 1.0526913
                            (b0 + b1 + b2 + white * 0.1848) * 0.11
                        }
                        else -> white * 0.5
                    }
                    buffer[i] = (sample.coerceIn(-1.0, 1.0) * VOLUME * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buffer, 0, buffer.size)
            }
        } finally {
            try { track.stop() } catch (_: IllegalStateException) {}
            track.release()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 22_050
        private const val VOLUME = 0.35
    }
}
