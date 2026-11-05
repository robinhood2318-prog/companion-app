package com.focustask.companion.ui.focus

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.focustask.companion.R
import com.focustask.companion.databinding.ActivityFocusSettingsBinding
import com.focustask.companion.service.FocusSettings
import com.focustask.companion.util.VoiceSettings

/** Customise how the "get back to your task" reminder behaves. */
class FocusSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFocusSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFocusSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { finish() }

        // Style
        binding.toggleStyle.check(
            if (FocusSettings.style(this) == FocusSettings.STYLE_BANNER) R.id.btn_style_banner else R.id.btn_style_full
        )
        binding.toggleStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                FocusSettings.setStyle(
                    this, if (checkedId == R.id.btn_style_banner) FocusSettings.STYLE_BANNER else FocusSettings.STYLE_FULL
                )
            }
        }

        // Pop-up frequency
        bindChoiceSlider(
            slider = binding.sliderRepopup,
            choices = FocusSettings.REPOPUP_CHOICES_SEC,
            current = FocusSettings.repopupSec(this),
            describe = { "Shows again ${FocusSettings.describeSeconds(it).lowercase()} after you leave or dismiss it" },
            label = binding.tvRepopupValue,
            save = { FocusSettings.setRepopupSec(this, it) }
        )
        // Grace period
        bindChoiceSlider(
            slider = binding.sliderGrace,
            choices = FocusSettings.GRACE_CHOICES_SEC,
            current = FocusSettings.graceSec(this),
            describe = {
                if (it == 0) "Appears as soon as you open an app that isn't allowed"
                else "Lets you use an app that isn't allowed for ${FocusSettings.describeSeconds(it)} first"
            },
            label = binding.tvGraceValue,
            save = { FocusSettings.setGraceSec(this, it) }
        )
        // Snooze
        bindChoiceSlider(
            slider = binding.sliderSnooze,
            choices = FocusSettings.SNOOZE_CHOICES_MIN,
            current = FocusSettings.snoozeMin(this),
            describe = { if (it == 0) "No snooze button" else "Snooze pauses the reminder for $it min" },
            label = binding.tvSnoozeValue,
            save = { FocusSettings.setSnoozeMin(this, it) }
        )

        // Message
        binding.etMessage.setText(FocusSettings.message(this))
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                FocusSettings.setMessage(this@FocusSettingsActivity, s?.toString().orEmpty())
            }
        })

        // Opacity
        val opacity = FocusSettings.opacityPct(this)
        binding.sliderOpacity.value = (opacity / 5 * 5).toFloat().coerceIn(50f, 100f)
        binding.tvOpacityValue.text = "$opacity%"
        binding.sliderOpacity.addOnChangeListener { _, value, fromUser ->
            binding.tvOpacityValue.text = "${value.toInt()}%"
            if (fromUser) FocusSettings.setOpacityPct(this, value.toInt())
        }

        // Back action
        binding.rgBackAction.check(
            if (FocusSettings.backAction(this) == FocusSettings.BACK_APP) R.id.rb_back_app else R.id.rb_back_home
        )
        binding.rgBackAction.setOnCheckedChangeListener { _, id ->
            FocusSettings.setBackAction(this, if (id == R.id.rb_back_app) FocusSettings.BACK_APP else FocusSettings.BACK_HOME)
        }

        // Switches
        binding.switchStrict.isChecked = FocusSettings.strict(this)
        binding.switchStrict.setOnCheckedChangeListener { _, v -> FocusSettings.setStrict(this, v) }
        binding.switchVibrate.isChecked = FocusSettings.vibrate(this)
        binding.switchVibrate.setOnCheckedChangeListener { _, v -> FocusSettings.setVibrate(this, v) }
        binding.switchVoice.isChecked = VoiceSettings.isEnabled(this, VoiceSettings.Section.FOCUS)
        binding.switchVoice.setOnCheckedChangeListener { _, v ->
            VoiceSettings.setEnabled(this, VoiceSettings.Section.FOCUS, v)
        }
    }

    /** A slider that steps through a fixed list of values (by index). */
    private fun bindChoiceSlider(
        slider: com.google.android.material.slider.Slider,
        choices: List<Int>,
        current: Int,
        describe: (Int) -> String,
        label: android.widget.TextView,
        save: (Int) -> Unit
    ) {
        val index = choices.indexOf(current).takeIf { it >= 0 }
            ?: choices.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: (choices.size - 1)
        slider.valueFrom = 0f
        slider.valueTo = (choices.size - 1).toFloat()
        slider.stepSize = 1f
        slider.value = index.toFloat()
        label.text = describe(choices[index])
        slider.addOnChangeListener { _, value, fromUser ->
            val v = choices[value.toInt().coerceIn(0, choices.size - 1)]
            label.text = describe(v)
            if (fromUser) save(v)
        }
    }
}
