package com.focustask.companion.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A task or a calendar deadline (a task with [dueDateTime]).
 * Optional progress: e.g. totalParts = 12, partsLabel = "chapters", doneParts = 3.
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val priority: String, // "high", "medium", "low"
    val dueDateTime: Long? = null,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val totalParts: Int = 0,
    val doneParts: Int = 0,
    val partsLabel: String = ""
)
