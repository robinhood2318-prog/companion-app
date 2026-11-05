package com.focustask.companion.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_steps")
data class DailyStepEntity(
    @PrimaryKey val dateString: String, // "YYYY-MM-DD"
    val steps: Int = 0,
    val distanceMeters: Float = 0f,
    val caloriesKcal: Float = 0f,
    val activeMinutes: Int = 0
)
