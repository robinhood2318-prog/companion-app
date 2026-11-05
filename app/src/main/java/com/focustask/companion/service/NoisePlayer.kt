package com.focustask.companion.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * Generates ambient soundscapes on the fly (no audio files needed):
 *  - "waves":  beach waves (brown noise with a slow swell)
 *  - "forest": soft wind with bird chirps
 *  - "pink":   gentle rain
 *  - "white":  white noise
 *  - "brown":  deep rumble
 */
class NoisePlayer(private val type: String, initialVolume: Float) {

    @Volatile private var running = false
    @Volatile var volume: Float = initialVolume.coerceIn(0f, 1f)
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
        var sampleIndex = 0L

        // Bird chirp state
        var chirpLeft = 0
        var chirpLen = 1
        var chirpPhase = 0.0
        var chirpF0 = 3000.0
        var chirpF1 = 4000.0
        var followUps = 0

        try {
            track.play()
            while (running) {
                for (i in buffer.indices) {
                    val white = random.nextDouble() * 2.0 - 1.0
                    val t = sampleIndex.toDouble() / SAMPLE_RATE
                    val sample = when (type) {
                        "waves" -> {
                            brown = (brown + 0.02 * white) / 1.02
                            // Slow swell every ~8 s, like waves rolling in
                            val swell = 0.25 + 0.75 * (0.5 + 0.5 * sin(2 * PI * t / 8.0)).let { it * it }
                            brown * 3.5 * swell
                        }
                        "forest" -> {
                            b0 = 0.99765 * b0 + white * 0.0990460
                            b1 = 0.96300 * b1 + white * 0.2965164
                            b2 = 0.57000 * b2 + white * 1.0526913
                            var s = (b0 + b1 + b2 + white * 0.1848) * 0.03 // soft wind
                            if (chirpLeft <= 0) {
                                val startChirp = if (followUps > 0) random.nextDouble() < 12.0 / SAMPLE_RATE
                                else random.nextDouble() < 0.6 / SAMPLE_RATE
                                if (startChirp) {
                                    if (followUps > 0) followUps-- else followUps = random.nextInt(4)
                                    chirpLen = (SAMPLE_RATE * (0.05 + random.nextDouble() * 0.1)).toInt()
                                    chirpLeft = chirpLen
                                    chirpF0 = 2500.0 + random.nextDouble() * 2000.0
                                    chirpF1 = chirpF0 + (random.nextDouble() - 0.3) * 2500.0
                                }
                            }
                            if (chirpLeft > 0) {
                                val progress = 1.0 - chirpLeft.toDouble() / chirpLen
                                val freq = chirpF0 + (chirpF1 - chirpF0) * progress
                                chirpPhase += 2 * PI * freq / SAMPLE_RATE
                                s += 0.35 * sin(chirpPhase) * sin(PI * progress)
                                chirpLeft--
                            }
                            s
                        }
                        "pink" -> { // Paul Kellet's economy pink-noise filter
                            b0 = 0.99765 * b0 + white * 0.0990460
                            b1 = 0.96300 * b1 + white * 0.2965164
                            b2 = 0.57000 * b2 + white * 1.0526913
                            (b0 + b1 + b2 + white * 0.1848) * 0.11
                        }
                        "brown" -> {
                            brown = (brown + 0.02 * white) / 1.02
                            brown * 3.5
                        }
                        else -> white * 0.5
                    }
                    sampleIndex++
                    buffer[i] = (sample.coerceIn(-1.0, 1.0) * MAX_GAIN * volume * Short.MAX_VALUE).toInt().toShort()
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
        private const val MAX_GAIN = 0.6
    }
}
