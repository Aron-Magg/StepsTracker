package com.stepstracker.android.data.run

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="run_sessions",indices=[Index("status"),Index("startedAt")])
data class RunSessionEntity(@PrimaryKey val id:String,val deviceId:String,val status:String,val startedAt:Long,val endedAt:Long?=null,val activeDurationMillis:Long=0,val distanceMeters:Double=0.0,val averageSpeedMps:Double=0.0,val averagePaceSecondsPerKm:Double?=null,val caloriesKcal:Double=0.0,val syncedPointSequence:Int=-1,val serverCreated:Boolean=false,val serverSynced:Boolean=false,val createdAt:Long=System.currentTimeMillis(),val updatedAt:Long=System.currentTimeMillis())

@Entity(tableName="run_points",primaryKeys=["sessionId","sequence"],foreignKeys=[ForeignKey(entity=RunSessionEntity::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sessionId")])
data class RunPointEntity(val sessionId:String,val sequence:Int,val recordedAt:Long,val latitude:Double,val longitude:Double,val altitudeMeters:Double?=null,val accuracyMeters:Float,val speedMps:Float?=null,val bearingDegrees:Float?=null,val distanceFromPreviousMeters:Double=0.0,val synced:Boolean=false)

@Entity(tableName="run_pauses",foreignKeys=[ForeignKey(entity=RunSessionEntity::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE)],indices=[Index("sessionId")])
data class RunPauseEntity(@PrimaryKey val id:String,val sessionId:String,val pausedAt:Long,val resumedAt:Long?=null)

data class RunWithDetails(@Embedded val session:RunSessionEntity,@Relation(parentColumn="id",entityColumn="sessionId") val points:List<RunPointEntity>,@Relation(parentColumn="id",entityColumn="sessionId") val pauses:List<RunPauseEntity>)

@Dao interface RunDao {
    @Query("SELECT * FROM run_sessions WHERE status IN ('ACTIVE','PAUSED') ORDER BY startedAt DESC LIMIT 1") fun observeActive():Flow<RunSessionEntity?>
    @Query("SELECT * FROM run_sessions WHERE status IN ('ACTIVE','PAUSED') ORDER BY startedAt DESC LIMIT 1") suspend fun active():RunSessionEntity?
    @Query("SELECT * FROM run_sessions ORDER BY startedAt DESC") fun observeHistory():Flow<List<RunSessionEntity>>
    @Query("SELECT * FROM run_sessions WHERE id=:id") suspend fun session(id:String):RunSessionEntity?
    @Transaction @Query("SELECT * FROM run_sessions WHERE id=:id") fun observeDetails(id:String):Flow<RunWithDetails?>
    @Query("SELECT * FROM run_points WHERE sessionId=:id ORDER BY sequence") fun observePoints(id:String):Flow<List<RunPointEntity>>
    @Query("SELECT * FROM run_points WHERE sessionId=:id ORDER BY sequence") suspend fun points(id:String):List<RunPointEntity>
    @Query("SELECT * FROM run_points WHERE sessionId=:id AND synced=0 ORDER BY sequence LIMIT :limit") suspend fun pendingPoints(id:String,limit:Int=250):List<RunPointEntity>
    @Query("SELECT * FROM run_pauses WHERE sessionId=:id ORDER BY pausedAt") suspend fun pauses(id:String):List<RunPauseEntity>
    @Query("SELECT * FROM run_sessions WHERE serverSynced=0 OR serverCreated=0 ORDER BY startedAt") suspend fun pendingSessions():List<RunSessionEntity>
    @Upsert suspend fun putSession(value:RunSessionEntity)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putPoint(value:RunPointEntity)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putPause(value:RunPauseEntity)
    @Query("UPDATE run_pauses SET resumedAt=:at WHERE id=:id") suspend fun resumePause(id:String,at:Long)
    @Query("UPDATE run_points SET synced=1 WHERE sessionId=:id AND sequence<=:sequence") suspend fun markPointsSynced(id:String,sequence:Int)
    @Query("UPDATE run_points SET synced=1 WHERE sessionId=:id AND sequence=:sequence") suspend fun markPointSynced(id:String,sequence:Int)
    @Query("UPDATE run_sessions SET serverCreated=1,updatedAt=:now WHERE id=:id") suspend fun markCreated(id:String,now:Long=System.currentTimeMillis())
    @Query("UPDATE run_sessions SET syncedPointSequence=:sequence,updatedAt=:now WHERE id=:id") suspend fun markCheckpoint(id:String,sequence:Int,now:Long=System.currentTimeMillis())
    @Query("UPDATE run_sessions SET serverSynced=1,serverCreated=1,updatedAt=:now WHERE id=:id") suspend fun markCompleted(id:String,now:Long=System.currentTimeMillis())
    @Query("UPDATE run_sessions SET activeDurationMillis=:duration,updatedAt=:now WHERE id=:id AND status='ACTIVE'") suspend fun updateActiveDuration(id:String,duration:Long,now:Long=System.currentTimeMillis())
    @Query("DELETE FROM run_sessions WHERE id=:id") suspend fun delete(id:String)
    @Query("DELETE FROM run_sessions") suspend fun clear()
}
