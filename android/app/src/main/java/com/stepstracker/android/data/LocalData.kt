package com.stepstracker.android.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "step_intervals", indices = [Index(value=["source","intervalStart"], unique=true)])
data class StepIntervalEntity(
    @PrimaryKey val id: String,
    val source: String,
    val intervalStart: Long,
    val intervalEnd: Long,
    val steps: Int,
    val synced: Boolean = false,
)

@Dao
interface StepIntervalDao {
    @Query("SELECT * FROM step_intervals WHERE intervalStart >= :from AND intervalStart < :to ORDER BY intervalStart")
    fun observe(from: Long, to: Long): Flow<List<StepIntervalEntity>>
    @Query("SELECT * FROM step_intervals WHERE synced=0 ORDER BY intervalStart LIMIT :limit")
    suspend fun pending(limit: Int = 500): List<StepIntervalEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(values: List<StepIntervalEntity>)
    @Query("SELECT * FROM step_intervals WHERE source=:source AND intervalStart=:start LIMIT 1")
    suspend fun find(source:String,start:Long):StepIntervalEntity?
    @Transaction
    suspend fun add(entity:StepIntervalEntity) {
        val previous=find(entity.source,entity.intervalStart)
        upsert(listOf(entity.copy(steps=(previous?.steps ?: 0)+entity.steps,synced=false)))
    }
    @Query("UPDATE step_intervals SET synced=1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
    @Query("DELETE FROM step_intervals")
    suspend fun clear()
}

@Database(entities=[StepIntervalEntity::class], version=1, exportSchema=false)
abstract class StepsDatabase : RoomDatabase() {
    abstract fun intervals(): StepIntervalDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, StepsDatabase::class.java, "steps.db").build()
    }
}
