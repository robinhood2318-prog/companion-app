package com.focustask.companion.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "whitelist_apps")
data class WhitelistAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isAllowed: Boolean = true
)
