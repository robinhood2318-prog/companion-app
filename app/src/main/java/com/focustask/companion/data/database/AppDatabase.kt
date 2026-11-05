package com.focustask.companion.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
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

        /** v2: chapter/unit progress for deadline tasks. Keeps all existing data. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN totalParts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN doneParts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN partsLabel TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focus_task_companion.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
