package com.focustask.companion.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.focustask.companion.R
import com.focustask.companion.ui.fitness.FitnessFragment
import com.focustask.companion.ui.meals.MealsFragment

/** Full-screen host for screens opened from the More tab (Fitness, Weekly Menu). */
class SubScreenActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply {
            id = R.id.sub_screen_container
            setBackgroundColor(ContextCompat.getColor(this@SubScreenActivity, R.color.header_start))
        }
        setContentView(container)

        WindowCompat.getInsetsController(window, container).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            val fragment = when (intent.getStringExtra(EXTRA_SCREEN)) {
                SCREEN_MEALS -> MealsFragment()
                else -> FitnessFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.sub_screen_container, fragment)
                .commit()
        }
    }

    companion object {
        private const val EXTRA_SCREEN = "screen"
        const val SCREEN_FITNESS = "fitness"
        const val SCREEN_MEALS = "meals"

        fun open(context: Context, screen: String) {
            context.startActivity(Intent(context, SubScreenActivity::class.java).putExtra(EXTRA_SCREEN, screen))
        }
    }
}
