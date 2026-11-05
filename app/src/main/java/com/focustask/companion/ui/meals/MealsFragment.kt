package com.focustask.companion.ui.meals

import android.app.TimePickerDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focustask.companion.FocusTaskApp
import com.focustask.companion.R
import com.focustask.companion.data.model.MealEntity
import com.focustask.companion.databinding.DialogMealEditBinding
import com.focustask.companion.databinding.FragmentMealsBinding
import com.focustask.companion.meals.MealScheduler
import com.focustask.companion.ui.dp
import com.focustask.companion.ui.formatTime
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Weekly Cooking Menu: plan breakfast, lunch and dinner for each day, with cooking reminders. */
class MealsFragment : Fragment() {

    private var _binding: FragmentMealsBinding? = null
    private val binding get() = _binding!!
    private val db by lazy { (requireActivity().application as FocusTaskApp).database }

    private var meals: List<MealEntity> = emptyList()
    private var selectedDay = MealScheduler.todayIndex()

    private val cardColors = listOf(
        R.color.card_peach, R.color.card_mint, R.color.card_sky, R.color.card_rose,
        R.color.card_lemon, R.color.card_lavender, R.color.card_mint
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMealsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        selectedDay = savedInstanceState?.getInt(STATE_DAY, selectedDay) ?: selectedDay
        binding.switchTakeout.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) {
                MealScheduler.setTakeoutTonight(requireContext(), checked)
                render()
            }
        }
        buildDayStrip()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.mealDao().getAllMeals().collect {
                    meals = it
                    render()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_DAY, selectedDay)
    }

    override fun onResume() {
        super.onResume()
        binding.switchTakeout.isChecked = MealScheduler.isTakeoutTonight(requireContext())
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.switchTakeout.isChecked = MealScheduler.isTakeoutTonight(requireContext())
        }
    }

    // ---------- Day selector ----------

    private fun buildDayStrip() {
        val context = requireContext()
        val strip = binding.dayStrip
        strip.removeAllViews()
        MealScheduler.DAYS.forEachIndexed { index, day ->
            strip.addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, context.dp(40), 1f).apply {
                    marginStart = context.dp(2)
                    marginEnd = context.dp(2)
                }
                gravity = Gravity.CENTER
                text = day.take(3)
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_on_header))
                setTypeface(typeface, Typeface.BOLD)
                background = ContextCompat.getDrawable(context, R.drawable.bg_day_chip)
                contentDescription = day
                setOnClickListener {
                    selectedDay = index
                    render()
                }
            })
        }
    }

    // ---------- Rendering ----------

    private fun mealFor(day: String, type: String) =
        meals.firstOrNull { it.dayOfWeek == day && it.mealType == type }

    private fun render() {
        if (_binding == null) return
        val context = requireContext()
        for (i in 0 until binding.dayStrip.childCount) {
            binding.dayStrip.getChildAt(i).isSelected = i == selectedDay
        }
        val day = MealScheduler.DAYS[selectedDay]
        val isToday = selectedDay == MealScheduler.todayIndex()
        binding.tvDayTitle.text = if (isToday) "$day (today)" else day
        binding.cardTakeout.visibility = if (isToday) View.VISIBLE else View.GONE

        // Selected day's three meals
        val list = binding.dayMeals
        list.removeAllViews()
        for (type in MealScheduler.MEAL_TYPES) {
            val meal = mealFor(day, type)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, context.dp(10), 0, context.dp(10))
                setOnClickListener { editMeal(day, type) }
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(context).apply {
                text = MealScheduler.mealLabel(type)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
            })
            texts.addView(TextView(context).apply {
                text = meal?.dishName?.ifBlank { null } ?: "Tap to add a dish"
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, if (meal == null) R.color.text_secondary else R.color.text_primary))
                if (meal != null) setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(texts)
            if (meal != null) {
                row.addView(TextView(context).apply {
                    text = formatTime(context, meal.reminderTimeHour, meal.reminderTimeMinute)
                    setTextColor(ContextCompat.getColor(context, R.color.primary))
                })
            }
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
            })
            list.addView(row)
        }

        // Week overview: dinner per day in a 2-column grid
        val grid = binding.weekGrid
        grid.removeAllViews()
        MealScheduler.DAYS.chunked(2).forEachIndexed { rowIndex, pair ->
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { col, dayName ->
                val index = rowIndex * 2 + col
                rowLayout.addView(dinnerCard(dayName, index))
            }
            if (pair.size == 1) {
                rowLayout.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            grid.addView(rowLayout)
        }
    }

    private fun dinnerCard(dayName: String, index: Int): View {
        val context = requireContext()
        val dinner = mealFor(dayName, "dinner")
        val takeout = index == MealScheduler.todayIndex() && MealScheduler.isTakeoutTonight(context)
        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            }
            radius = context.dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, cardColors[index]))
            strokeWidth = if (index == selectedDay) context.dp(2) else 0
            strokeColor = ContextCompat.getColor(context, R.color.accent_green)
            setContentPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
            setOnClickListener {
                selectedDay = index
                render()
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = dayName.take(3) + ":"
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    textSize = 13f
                })
                addView(TextView(context).apply {
                    text = when {
                        takeout -> "Takeout"
                        dinner != null && dinner.dishName.isNotBlank() -> dinner.dishName
                        else -> "Not planned"
                    }
                    textSize = 16f
                    maxLines = 2
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setTypeface(typeface, Typeface.BOLD)
                })
            })
        }
    }

    // ---------- Editing ----------

    private fun editMeal(day: String, type: String) {
        val context = requireContext()
        val existing = mealFor(day, type)
        val dialogBinding = DialogMealEditBinding.inflate(layoutInflater)
        var (hour, minute) = existing?.let { it.reminderTimeHour to it.reminderTimeMinute }
            ?: MealScheduler.defaultTime(type)
        dialogBinding.etDish.setText(existing?.dishName.orEmpty())
        dialogBinding.btnTime.text = formatTime(context, hour, minute)
        dialogBinding.btnTime.setOnClickListener {
            TimePickerDialog(context, { _, h, m ->
                hour = h
                minute = m
                dialogBinding.btnTime.text = formatTime(context, h, m)
            }, hour, minute, DateFormat.is24HourFormat(context)).show()
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("$day ${MealScheduler.mealLabel(type).lowercase()}")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val dish = dialogBinding.etDish.text.toString().trim()
                saveMeal(existing, day, type, dish, hour, minute)
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Remove") { _, _ -> deleteMeal(existing) }
        }
        builder.show()
    }

    private fun saveMeal(existing: MealEntity?, day: String, type: String, dish: String, hour: Int, minute: Int) {
        val appContext = requireContext().applicationContext
        if (dish.isEmpty()) {
            existing?.let { deleteMeal(it) }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val meal = (existing ?: MealEntity(
                dayOfWeek = day, mealType = type, dishName = dish,
                reminderTimeHour = hour, reminderTimeMinute = minute
            )).copy(dishName = dish, reminderTimeHour = hour, reminderTimeMinute = minute)
            val id = withContext(Dispatchers.IO) { db.mealDao().insertMeal(meal) }
            MealScheduler.schedule(appContext, meal.copy(id = id))
        }
    }

    private fun deleteMeal(meal: MealEntity) {
        val appContext = requireContext().applicationContext
        MealScheduler.cancel(appContext, meal.id)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { db.mealDao().deleteMeal(meal) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val STATE_DAY = "selected_day"
    }
}
