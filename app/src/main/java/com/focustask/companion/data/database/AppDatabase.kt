package com.focustask.companion.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.focustask.companion.data.dao.*
import com.focustask.companion.data.model.*

@Database(
    entities = [
        TaskEntity::class,
        AlarmEntity::class,
        MealEntity::class,
        DailyStepEntity::class,
        WhitelistAppEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao
    abstract fun alarmDao(): AlarmDao
    abstract fun mealDao(): MealDao
    abstract fun stepDao(): StepDao
    abstract fun whitelistDao(): WhitelistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focus_task_companion.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
