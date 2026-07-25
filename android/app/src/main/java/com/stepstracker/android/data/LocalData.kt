package com.stepstracker.android.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "step_intervals", indices = [Index(value=["intervalStart"], unique=true)])
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
    @Query("SELECT COALESCE(SUM(steps),0) FROM step_intervals WHERE intervalStart >= :from AND intervalStart < :to")
    suspend fun total(from:Long,to:Long):Int
    @Query("SELECT * FROM step_intervals WHERE intervalStart >= :from AND intervalStart < :to")
    suspend fun range(from:Long,to:Long):List<StepIntervalEntity>
    @Query("SELECT MIN(intervalStart) FROM step_intervals")
    suspend fun earliest():Long?
    @Query("SELECT * FROM step_intervals WHERE synced=0 ORDER BY intervalStart LIMIT :limit")
    suspend fun pending(limit: Int = 500): List<StepIntervalEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(values: List<StepIntervalEntity>)
    @Query("SELECT * FROM step_intervals WHERE source=:source AND intervalStart=:start LIMIT 1")
    suspend fun find(source:String,start:Long):StepIntervalEntity?
    @Query("SELECT * FROM step_intervals WHERE intervalStart=:start LIMIT 1")
    suspend fun findAt(start:Long):StepIntervalEntity?
    @Transaction
    suspend fun add(entity:StepIntervalEntity) {
        val previous=findAt(entity.intervalStart)
        if(previous?.source=="HEALTH_CONNECT")return
        upsert(listOf(entity.copy(steps=(previous?.steps ?: 0)+entity.steps,synced=false)))
    }
    @Transaction
    suspend fun replaceWithHealthConnect(entity:StepIntervalEntity) {
        // Health Connect re-imports the last 30 days on every run. Re-upserting an identical slot would reset
        // synced=false and make already-uploaded intervals queue forever, so skip when the data is unchanged.
        val previous=findAt(entity.intervalStart)
        if(previous?.source=="HEALTH_CONNECT" && previous.steps==entity.steps) return
        upsert(listOf(entity))
    }
    @Query("UPDATE step_intervals SET synced=1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
    @Query("DELETE FROM step_intervals")
    suspend fun clear()
}

@Database(entities=[StepIntervalEntity::class], version=2, exportSchema=false)
abstract class StepsDatabase : RoomDatabase() {
    abstract fun intervals(): StepIntervalDao
    companion object {
        private val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM step_intervals WHERE source='STEP_COUNTER' AND EXISTS (SELECT 1 FROM step_intervals h WHERE h.intervalStart=step_intervals.intervalStart AND h.source='HEALTH_CONNECT')")
                db.execSQL("DROP INDEX IF EXISTS index_step_intervals_source_intervalStart")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_step_intervals_intervalStart ON step_intervals(intervalStart)")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, StepsDatabase::class.java, "steps.db").addMigrations(MIGRATION_1_2).build()
    }
}
