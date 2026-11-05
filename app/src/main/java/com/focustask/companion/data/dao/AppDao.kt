package com.focustask.companion.data.dao

import androidx.room.*
import com.focustask.companion.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY isCompleted ASC, createdAt DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE isCompleted = 0 AND dueDateTime IS NOT NULL")
    suspend fun getOpenDeadlinesSync(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Delete
    suspend fun deleteTask(task: TaskEntity)
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC")
    fun getAllAlarms(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE isEnabled = 1")
    suspend fun getActiveAlarmsSync(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id LIMIT 1")
    suspend fun getAlarmById(id: Long): AlarmEntity?

    @Query("SELECT COUNT(*) FROM alarms WHERE customAudioUri = :uri")
    suspend fun countAlarmsUsingSound(uri: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: AlarmEntity): Long

    @Update
    suspend fun updateAlarm(alarm: AlarmEntity)

    @Delete
    suspend fun deleteAlarm(alarm: AlarmEntity)
}

@Dao
interface MealDao {
    @Query("SELECT * FROM weekly_meals WHERE dayOfWeek = :day")
    fun getMealsForDay(day: String): Flow<List<MealEntity>>

    @Query("SELECT * FROM weekly_meals")
    suspend fun getAllMealsSync(): List<MealEntity>

    @Query("SELECT * FROM weekly_meals")
    fun getAllMeals(): Flow<List<MealEntity>>

    @Query("SELECT * FROM weekly_meals WHERE id = :id LIMIT 1")
    suspend fun getMealById(id: Long): MealEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeal(meal: MealEntity): Long

    @Delete
    suspend fun deleteMeal(meal: MealEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMeals(meals: List<MealEntity>)

    @Query("UPDATE weekly_meals SET isTakeoutToday = :isTakeout WHERE id = :id")
    suspend fun setTakeoutStatus(id: Long, isTakeout: Boolean)
}

@Dao
interface StepDao {
    @Query("SELECT * FROM daily_steps WHERE dateString = :date LIMIT 1")
    fun getStepsForDate(date: String): Flow<DailyStepEntity?>

    @Query("SELECT * FROM daily_steps WHERE dateString >= :fromDate ORDER BY dateString ASC")
    fun getStepsSince(fromDate: String): Flow<List<DailyStepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stepRecord: DailyStepEntity)
}

@Dao
interface WhitelistDao {
    @Query("SELECT * FROM whitelist_apps WHERE isAllowed = 1")
    fun getAllAllowedApps(): Flow<List<WhitelistAppEntity>>

    @Query("SELECT packageName FROM whitelist_apps WHERE isAllowed = 1")
    suspend fun getAllowedPackagesSync(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM whitelist_apps WHERE packageName = :pkg AND isAllowed = 1)")
    suspend fun isAppWhitelisted(pkg: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: WhitelistAppEntity)

    @Query("DELETE FROM whitelist_apps WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    @Delete
    suspend fun deleteApp(app: WhitelistAppEntity)
}
