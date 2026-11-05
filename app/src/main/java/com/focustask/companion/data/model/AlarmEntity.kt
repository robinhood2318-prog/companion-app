package com.focustask.companion.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String,
    val daysMask: Int = 31, // Bitmask for Mon-Sun
    val isEnabled: Boolean = true,
    val isVoiceCalloutEnabled: Boolean = true,
    val customAudioUri: String? = null, // Content URI from SAF for personal MP3
    val snoozeMinutes: Int = 10,
    val dismissChallenge: String = "none" // "none", "math", "steps"
)
