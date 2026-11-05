package com.focustask.companion.ui.more

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.focustask.companion.R
import com.focustask.companion.databinding.FragmentMoreBinding
import com.focustask.companion.ui.MainActivity
import com.focustask.companion.ui.SubScreenActivity
import com.focustask.companion.ui.dp
import com.focustask.companion.ui.focus.FocusSettingsActivity
import com.focustask.companion.ui.sounds.SoundsActivity
import com.focustask.companion.util.VoiceSettings
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/** Hub for Fitness, Weekly Menu, Sounds and settings (the bottom bar fits only five tabs). */
class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    private data class Entry(
        val title: String, val subtitle: String,
        @DrawableRes val icon: Int, @ColorRes val color: Int,
        val open: () -> Unit
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        val entries = listOf(
            Entry("Walk & Fitness", "Steps, distance, calories, 7-day history", R.drawable.ic_walk, R.color.card_mint) {
                SubScreenActivity.open(context, SubScreenActivity.SCREEN_FITNESS)
            },
            Entry("Weekly Cooking Menu", "Plan meals, cooking reminders, takeout", R.drawable.ic_restaurant, R.color.card_peach) {
                SubScreenActivity.open(context, SubScreenActivity.SCREEN_MEALS)
            },
            Entry("Ambient Soundscapes", "Beach waves, forest birds, rain, your audio", R.drawable.ic_music, R.color.card_sky) {
                startActivity(Intent(context, SoundsActivity::class.java))
            },
            Entry("Screen Reminder Settings", "Pop-up frequency, style, snooze, strict mode", R.drawable.ic_settings, R.color.card_lavender) {
                startActivity(Intent(context, FocusSettingsActivity::class.java))
            },
            Entry("Permissions & Setup", "Run the guided permission setup again", R.drawable.ic_apps, R.color.card_rose) {
                (requireActivity() as MainActivity).startSetupGuide()
            }
        )
        entries.forEach { binding.moreList.addView(entryCard(it)) }
        renderVoiceSwitches()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) renderVoiceSwitches()
    }

    override fun onResume() {
        super.onResume()
        renderVoiceSwitches()
    }

    /** All voice switches in one place (each tab also has its own speaker button). */
    private fun renderVoiceSwitches() {
        val context = requireContext()
        val list = binding.voiceList
        list.removeAllViews()
        VoiceSettings.Section.entries.forEach { section ->
            list.addView(MaterialSwitch(context).apply {
                text = section.label
                isChecked = VoiceSettings.isEnabled(context, section)
                setOnCheckedChangeListener { _, on -> VoiceSettings.setEnabled(context, section, on) }
            })
        }
    }

    private fun entryCard(e: Entry): View {
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = context.dp(10) }
            radius = context.dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, e.color))
            setContentPadding(context.dp(16), context.dp(14), context.dp(12), context.dp(14))
            setOnClickListener { e.open() }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(context).apply {
            setImageResource(e.icon)
            setColorFilter(ContextCompat.getColor(context, R.color.primary))
            layoutParams = LinearLayout.LayoutParams(context.dp(28), context.dp(28))
        })
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = context.dp(14) }
        }
        texts.addView(TextView(context).apply {
            text = e.title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        texts.addView(TextView(context).apply {
            text = e.subtitle
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        })
        row.addView(texts)
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
        })
        card.addView(row)
        return card
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
