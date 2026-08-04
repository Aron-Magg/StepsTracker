package com.stepstracker.android.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import com.stepstracker.android.data.run.*

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

@Database(entities=[StepIntervalEntity::class,RunSessionEntity::class,RunPointEntity::class,RunPauseEntity::class], version=3, exportSchema=false)
abstract class StepsDatabase : RoomDatabase() {
    abstract fun intervals(): StepIntervalDao
    abstract fun runs():RunDao
    companion object {
        private val MIGRATION_1_2=object:Migration(1,2) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM step_intervals WHERE source='STEP_COUNTER' AND EXISTS (SELECT 1 FROM step_intervals h WHERE h.intervalStart=step_intervals.intervalStart AND h.source='HEALTH_CONNECT')")
                db.execSQL("DROP INDEX IF EXISTS index_step_intervals_source_intervalStart")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_step_intervals_intervalStart ON step_intervals(intervalStart)")
            }
        }
        private val MIGRATION_2_3=object:Migration(2,3) { override fun migrate(db:SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS run_sessions (id TEXT NOT NULL PRIMARY KEY,deviceId TEXT NOT NULL,status TEXT NOT NULL,startedAt INTEGER NOT NULL,endedAt INTEGER,activeDurationMillis INTEGER NOT NULL,distanceMeters REAL NOT NULL,averageSpeedMps REAL NOT NULL,averagePaceSecondsPerKm REAL,caloriesKcal REAL NOT NULL,syncedPointSequence INTEGER NOT NULL,serverCreated INTEGER NOT NULL,serverSynced INTEGER NOT NULL,createdAt INTEGER NOT NULL,updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_run_sessions_status ON run_sessions(status)");db.execSQL("CREATE INDEX IF NOT EXISTS index_run_sessions_startedAt ON run_sessions(startedAt)")
            db.execSQL("CREATE TABLE IF NOT EXISTS run_points (sessionId TEXT NOT NULL,sequence INTEGER NOT NULL,recordedAt INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,altitudeMeters REAL,accuracyMeters REAL NOT NULL,speedMps REAL,bearingDegrees REAL,distanceFromPreviousMeters REAL NOT NULL,synced INTEGER NOT NULL,PRIMARY KEY(sessionId,sequence),FOREIGN KEY(sessionId) REFERENCES run_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_run_points_sessionId ON run_points(sessionId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS run_pauses (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,pausedAt INTEGER NOT NULL,resumedAt INTEGER,FOREIGN KEY(sessionId) REFERENCES run_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)");db.execSQL("CREATE INDEX IF NOT EXISTS index_run_pauses_sessionId ON run_pauses(sessionId)")
        } }
        fun create(context: Context) = Room.databaseBuilder(context, StepsDatabase::class.java, "steps.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
    }
}
