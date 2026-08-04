package com.stepstracker

import java.sql.Connection
import java.time.*
import java.util.UUID
import kotlin.math.*

class RunRepository(private val db:Database) {
    fun create(userId:UUID,input:CreateRunRequest):RunSummaryResponse=db.transaction { c->
        val id=UUID.fromString(input.id);val device=UUID.fromString(input.deviceId);val started=Instant.parse(input.startedAt)
        existing(c,userId,id)?.let { return@transaction it }
        val active=c.prepareStatement("SELECT 1 FROM run_sessions WHERE user_id=? AND status IN ('ACTIVE','PAUSED')").use { p->p.setObject(1,userId);p.executeQuery().next() }
        check(!active){"RUN_ALREADY_ACTIVE"}
        val weight=c.prepareStatement("SELECT weight_kg FROM user_profiles WHERE user_id=?").use { p->p.setObject(1,userId);p.executeQuery().use { r->check(r.next()){ "Profile required" };r.getDouble(1) } }
        c.prepareStatement("INSERT INTO devices(id,user_id,platform,model,last_sync_at) VALUES (?,?,'ANDROID','Run tracking',now()) ON CONFLICT(id,user_id) DO UPDATE SET last_sync_at=now()").use { p->p.setObject(1,device);p.setObject(2,userId);p.executeUpdate() }
        c.prepareStatement("INSERT INTO run_sessions(id,user_id,device_id,status,started_at,weight_kg_at_start) VALUES (?,?,?,'ACTIVE',?,?)").use { p->p.setObject(1,id);p.setObject(2,userId);p.setObject(3,device);p.setObject(4,started.atOffset(ZoneOffset.UTC));p.setDouble(5,weight);p.executeUpdate() }
        existing(c,userId,id)!!
    }

    fun checkpoint(userId:UUID,id:UUID,input:RunCheckpointRequest):RunCheckpointResponse=db.transaction { c->
        require(input.points.size<=250){"Maximum checkpoint size is 250"};require(input.status in setOf("ACTIVE","PAUSED"));require(input.activeDurationMillis>=0)
        val current=lock(c,userId,id);check(current!="COMPLETED"){"Run already completed"}
        check(current==input.status || current=="ACTIVE"&&input.status=="PAUSED" || current=="PAUSED"&&input.status=="ACTIVE"){"Invalid run transition"}
        val rejected=mutableListOf<RejectedRunPoint>()
        input.points.forEach { point->runCatching {
            require(point.sequence>=0&&point.latitude in -90.0..90.0&&point.longitude in -180.0..180.0&&point.accuracyMeters in 0f..50f)
            require(point.speedMps==null||point.speedMps in 0f..12f);require(point.bearingDegrees==null||point.bearingDegrees>=0f&&point.bearingDegrees<360f)
            val at=Instant.parse(point.recordedAt)
            c.prepareStatement("INSERT INTO run_points(session_id,sequence,recorded_at,latitude,longitude,altitude_meters,accuracy_meters,speed_mps,bearing_degrees) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(session_id,sequence) DO NOTHING").use { p->p.setObject(1,id);p.setInt(2,point.sequence);p.setObject(3,at.atOffset(ZoneOffset.UTC));p.setDouble(4,point.latitude);p.setDouble(5,point.longitude);point.altitudeMeters?.let { p.setDouble(6,it) } ?: p.setNull(6,java.sql.Types.DOUBLE);p.setFloat(7,point.accuracyMeters);point.speedMps?.let { p.setFloat(8,it) } ?: p.setNull(8,java.sql.Types.REAL);point.bearingDegrees?.let { p.setFloat(9,it) } ?: p.setNull(9,java.sql.Types.REAL);p.executeUpdate() }
        }.onFailure { rejected+=RejectedRunPoint(point.sequence,"INVALID_POINT") } }
        input.pauses.forEach { pause->c.prepareStatement("INSERT INTO run_pauses(id,session_id,paused_at,resumed_at) VALUES (?,?,?,?) ON CONFLICT(session_id,paused_at) DO UPDATE SET resumed_at=COALESCE(excluded.resumed_at,run_pauses.resumed_at)").use { p->p.setObject(1,UUID.fromString(pause.id));p.setObject(2,id);p.setObject(3,Instant.parse(pause.pausedAt).atOffset(ZoneOffset.UTC));pause.resumedAt?.let { p.setObject(4,Instant.parse(it).atOffset(ZoneOffset.UTC)) } ?: p.setNull(4,java.sql.Types.TIMESTAMP_WITH_TIMEZONE);p.executeUpdate() } }
        val last=maxSequence(c,id)
        c.prepareStatement("UPDATE run_sessions SET status=?,active_duration_ms=GREATEST(active_duration_ms,?),last_point_sequence=?,updated_at=now() WHERE id=? AND user_id=?").use { p->p.setString(1,input.status);p.setLong(2,input.activeDurationMillis);p.setInt(3,last);p.setObject(4,id);p.setObject(5,userId);p.executeUpdate() }
        RunCheckpointResponse(last,rejected)
    }

    fun complete(userId:UUID,id:UUID,input:CompleteRunRequest):RunSummaryResponse=db.transaction { c->
        val status=lock(c,userId,id);existing(c,userId,id)?.takeIf { status=="COMPLETED" }?.let { return@transaction it }
        require(input.activeDurationMillis>=0&&input.lastPointSequence>=-1)
        if(input.lastPointSequence>=0){ val missing=c.prepareStatement("SELECT s FROM generate_series(0,?) s LEFT JOIN run_points p ON p.session_id=? AND p.sequence=s WHERE p.sequence IS NULL ORDER BY s LIMIT 1").use { p->p.setInt(1,input.lastPointSequence);p.setObject(2,id);p.executeQuery().use { r->if(r.next())r.getInt(1) else null } };check(missing==null){"INCOMPLETE_CHECKPOINT:$missing"} }
        val points=points(c,id);val pauseWindows=c.prepareStatement("SELECT paused_at,resumed_at FROM run_pauses WHERE session_id=?").use { p->p.setObject(1,id);p.executeQuery().use { r->buildList { while(r.next())add(r.getObject(1,OffsetDateTime::class.java).toInstant() to r.getObject(2,OffsetDateTime::class.java)?.toInstant()) } } };var distance=0.0
        points.zipWithNext().forEach { (a,b)->val crossesPause=pauseWindows.any { (start,end)->!start.isAfter(b.first)&&(end==null||!end.isBefore(a.first)) };val dt=Duration.between(a.first,b.first).seconds;if(!crossesPause&&dt in 1..300){val d=haversine(a.second,a.third,b.second,b.third);if(d/dt<=12)distance+=d} }
        val seconds=input.activeDurationMillis/1000.0;val speed=if(seconds>0)distance/seconds else 0.0;val pace=if(distance>=50)seconds/(distance/1000) else null
        val weight=c.prepareStatement("SELECT weight_kg_at_start FROM run_sessions WHERE id=?").use { p->p.setObject(1,id);p.executeQuery().use { r->r.next();r.getDouble(1) } }
        c.prepareStatement("UPDATE run_sessions SET status='COMPLETED',ended_at=?,active_duration_ms=?,distance_meters=?,average_speed_mps=?,average_pace_seconds_per_km=?,calories_kcal=?,last_point_sequence=?,updated_at=now() WHERE id=? AND user_id=?").use { p->p.setObject(1,Instant.parse(input.endedAt).atOffset(ZoneOffset.UTC));p.setLong(2,input.activeDurationMillis);p.setDouble(3,distance);p.setDouble(4,speed);if(pace==null)p.setNull(5,java.sql.Types.DOUBLE) else p.setDouble(5,pace);p.setDouble(6,distance/1000*weight);p.setInt(7,input.lastPointSequence);p.setObject(8,id);p.setObject(9,userId);p.executeUpdate() }
        existing(c,userId,id)!!
    }

    fun list(userId:UUID,from:LocalDate?,to:LocalDate?,limit:Int,cursor:Instant?):RunListResponse=db.query { c->
        val sql="SELECT * FROM run_sessions WHERE user_id=? AND status='COMPLETED' AND (?::date IS NULL OR started_at>=?::date) AND (?::date IS NULL OR started_at<?::date+interval '1 day') AND (?::timestamptz IS NULL OR started_at<?::timestamptz) ORDER BY started_at DESC LIMIT ?"
        val values=c.prepareStatement(sql).use { p->p.setObject(1,userId);p.setString(2,from?.toString());p.setString(3,from?.toString());p.setString(4,to?.toString());p.setString(5,to?.toString());p.setString(6,cursor?.toString());p.setString(7,cursor?.toString());p.setInt(8,limit+1);p.executeQuery().use { r->buildList { while(r.next())add(summary(r)) } } }
        RunListResponse(values.take(limit),if(values.size>limit)values[limit-1].startedAt else null)
    }
    fun detail(userId:UUID,id:UUID):RunDetailResponse?=db.query { c->val s=existing(c,userId,id)?:return@query null;RunDetailResponse(s,pointResponses(c,id),pauses(c,id)) }
    fun delete(userId:UUID,id:UUID):Boolean=db.query { c->c.prepareStatement("DELETE FROM run_sessions WHERE id=? AND user_id=?").use { p->p.setObject(1,id);p.setObject(2,userId);p.executeUpdate()>0 } }

    private fun lock(c:Connection,userId:UUID,id:UUID)=c.prepareStatement("SELECT status FROM run_sessions WHERE id=? AND user_id=? FOR UPDATE").use { p->p.setObject(1,id);p.setObject(2,userId);p.executeQuery().use { r->check(r.next()){ "Run not found" };r.getString(1) } }
    private fun existing(c:Connection,userId:UUID,id:UUID)=c.prepareStatement("SELECT * FROM run_sessions WHERE id=? AND user_id=?").use { p->p.setObject(1,id);p.setObject(2,userId);p.executeQuery().use { r->if(r.next())summary(r) else null } }
    private fun summary(r:java.sql.ResultSet):RunSummaryResponse { val start=r.getObject("started_at",OffsetDateTime::class.java).toInstant();val end=r.getObject("ended_at",OffsetDateTime::class.java)?.toInstant();val pace=r.getDouble("average_pace_seconds_per_km").let { if(r.wasNull())null else it };return RunSummaryResponse(r.getObject("id").toString(),r.getString("status"),start.toString(),end?.toString(),r.getLong("active_duration_ms"),end?.let { Duration.between(start,it).toMillis() } ?: Duration.between(start,Instant.now()).toMillis(),r.getDouble("distance_meters"),r.getDouble("average_speed_mps"),pace,r.getDouble("calories_kcal"),r.getInt("last_point_sequence")) }
    private fun maxSequence(c:Connection,id:UUID)=c.prepareStatement("SELECT COALESCE(max(sequence),-1) FROM run_points WHERE session_id=?").use { p->p.setObject(1,id);p.executeQuery().use { r->r.next();r.getInt(1) } }
    private fun points(c:Connection,id:UUID)=c.prepareStatement("SELECT recorded_at,latitude,longitude FROM run_points WHERE session_id=? ORDER BY sequence").use { p->p.setObject(1,id);p.executeQuery().use { r->buildList { while(r.next())add(Triple(r.getObject(1,OffsetDateTime::class.java).toInstant(),r.getDouble(2),r.getDouble(3))) } } }
    private fun pointResponses(c:Connection,id:UUID)=c.prepareStatement("SELECT * FROM run_points WHERE session_id=? ORDER BY sequence").use { p->p.setObject(1,id);p.executeQuery().use { r->buildList { while(r.next())add(RunPointResponse(r.getInt("sequence"),r.getObject("recorded_at",OffsetDateTime::class.java).toInstant().toString(),r.getDouble("latitude"),r.getDouble("longitude"),r.getDouble("altitude_meters").let{if(r.wasNull())null else it},r.getFloat("accuracy_meters"),r.getFloat("speed_mps").let{if(r.wasNull())null else it},r.getFloat("bearing_degrees").let{if(r.wasNull())null else it})) } } }
    private fun pauses(c:Connection,id:UUID)=c.prepareStatement("SELECT * FROM run_pauses WHERE session_id=? ORDER BY paused_at").use { p->p.setObject(1,id);p.executeQuery().use { r->buildList { while(r.next())add(RunPauseResponse(r.getObject("id").toString(),r.getObject("paused_at",OffsetDateTime::class.java).toInstant().toString(),r.getObject("resumed_at",OffsetDateTime::class.java)?.toInstant()?.toString())) } } }
    private fun haversine(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double { val earth=6371000.0;val p1=Math.toRadians(aLat);val p2=Math.toRadians(bLat);val dp=Math.toRadians(bLat-aLat);val dl=Math.toRadians(bLon-aLon);val x=sin(dp/2).pow(2)+cos(p1)*cos(p2)*sin(dl/2).pow(2);return earth*2*atan2(sqrt(x),sqrt(1-x)) }
}
