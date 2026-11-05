package com.focustask.companion.ui.sounds

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.focustask.companion.R
import com.focustask.companion.databinding.ActivitySoundsBinding
import com.focustask.companion.service.AmbientAudioService
import com.focustask.companion.ui.dp
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/** Ambient Soundscapes: pick a generated soundscape or your own audio file. */
class SoundsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySoundsBinding
    private val cardButtons = mutableMapOf<String, FloatingActionButton>()

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onAudioPicked(uri) }

    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySoundsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNowPlay.setOnClickListener {
            if (AmbientAudioService.isPlaying(this)) {
                AmbientAudioService.stop(this)
            } else {
                play(AmbientAudioService.currentType(this))
            }
        }
        binding.sliderVolume.value = (AmbientAudioService.volume(this) * 100).coerceIn(0f, 100f)
        binding.sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) AmbientAudioService.setVolume(this, value / 100f)
        }
        buildGrid()
    }

    override fun onStart() {
        super.onStart()
        AmbientAudioService.prefs(this).registerOnSharedPreferenceChangeListener(stateListener)
        refresh()
    }

    override fun onStop() {
        AmbientAudioService.prefs(this).unregisterOnSharedPreferenceChangeListener(stateListener)
        super.onStop()
    }

    private fun buildGrid() {
        val grid = binding.soundGrid
        grid.removeAllViews()
        AmbientAudioService.SOUNDS.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { sound -> row.addView(soundCard(sound)) }
            if (pair.size == 1) {
                row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            }
            grid.addView(row)
        }
    }

    private fun soundCard(sound: AmbientAudioService.Sound): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(this@SoundsActivity, R.color.surface))
            setOnClickListener { toggle(sound.type) }
            if (sound.type == AmbientAudioService.TYPE_CUSTOM) {
                setOnLongClickListener {
                    launchPicker()
                    true
                }
            }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(ImageView(this).apply {
            setImageResource(sound.art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96))
            contentDescription = null
        })
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = sound.title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@SoundsActivity, R.color.text_primary))
        })
        texts.addView(TextView(this).apply {
            text = sound.subtitle
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SoundsActivity, R.color.text_secondary))
        })
        val button = FloatingActionButton(this).apply {
            size = FloatingActionButton.SIZE_MINI
            setImageResource(R.drawable.ic_play)
            compatElevation = 0f
            contentDescription = "Play ${sound.title}"
            setOnClickListener { toggle(sound.type) }
        }
        cardButtons[sound.type] = button
        footer.addView(texts)
        footer.addView(button)
        column.addView(footer)
        card.addView(column)
        return card
    }

    private fun toggle(type: String) {
        val playingThis = AmbientAudioService.isPlaying(this) && AmbientAudioService.currentType(this) == type
        if (playingThis) AmbientAudioService.stop(this) else play(type)
    }

    private fun play(type: String) {
        if (type == AmbientAudioService.TYPE_CUSTOM) {
            val last = AmbientAudioService.lastCustomUri(this)
            if (last == null) {
                launchPicker()
                return
            }
            start(type, last)
        } else {
            start(type, null)
        }
    }

    private fun start(type: String, uri: String?) {
        try {
            AmbientAudioService.start(this, type, uri)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not start audio", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPicker() {
        try {
            pickAudio.launch(arrayOf("audio/*"))
        } catch (_: Exception) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onAudioPicked(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            Toast.makeText(this, "That file can't be kept available; pick it from Files or Downloads", Toast.LENGTH_LONG).show()
            return
        }
        start(AmbientAudioService.TYPE_CUSTOM, uri.toString())
    }

    private fun refresh() {
        val playing = AmbientAudioService.isPlaying(this)
        val type = AmbientAudioService.currentType(this)
        val sound = AmbientAudioService.soundFor(type)
        binding.tvNowStatus.text = if (playing) "Now Playing" else "Paused"
        binding.tvNowTitle.text = sound.title
        binding.tvNowSubtitle.text = sound.subtitle
        binding.ivNowArt.setImageResource(sound.art)
        binding.btnNowPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        cardButtons.forEach { (t, button) ->
            button.setImageResource(if (playing && t == type) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }
}
