package com.focustask.companion.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weekly_meals")
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayOfWeek: String, // "Monday", "Tuesday", etc.
    val mealType: String,  // "breakfast", "lunch", "dinner"
    val dishName: String,
    val reminderTimeHour: Int,
    val reminderTimeMinute: Int,
    val isTakeoutToday: Boolean = false // Toggle to mute cooking alarm without erasing dish
)
