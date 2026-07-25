package com.stepstracker

import java.sql.Connection
import java.sql.ResultSet
import java.time.*
import java.time.format.DateTimeParseException
import java.util.UUID

data class UserRecord(val id: UUID, val email: String, val passwordHash: String)

class Repository(private val db: Database, private val security: Security, private val config: AppConfig) {
    fun seedDemoUser() {
        if(userByEmail("demo@example.com")!=null)return
        db.transaction { c->
            val userId=UUID.nameUUIDFromBytes("stepstracker-demo-user".toByteArray())
            val deviceId=UUID.nameUUIDFromBytes("stepstracker-demo-device".toByteArray())
            val now=Instant.now();val zone=ZoneId.of("Europe/Zurich");val today=LocalDate.now(zone);val firstDay=today.minusMonths(2)
            c.prepareStatement("INSERT INTO users(id,email,password_hash,created_at,updated_at) VALUES (?,? ,?, ?,?) ON CONFLICT(email) DO NOTHING").use { p->p.setObject(1,userId);p.setString(2,"demo@example.com");p.setString(3,security.hashPassword("demopassword123"));p.setObject(4,firstDay.atStartOfDay(zone).toOffsetDateTime());p.setObject(5,now.atOffset(ZoneOffset.UTC));p.executeUpdate() }
            c.prepareStatement("INSERT INTO user_profiles(user_id,weight_kg,height_cm,birth_date,sex,timezone) VALUES (?,75,178,'1990-01-01','OTHER','Europe/Zurich') ON CONFLICT(user_id) DO NOTHING").use { p->p.setObject(1,userId);p.executeUpdate() }
            c.prepareStatement("INSERT INTO user_weight_history(user_id,weight_kg,effective_at) VALUES (?,75,?) ON CONFLICT(user_id,effective_at) DO NOTHING").use { p->p.setObject(1,userId);p.setObject(2,firstDay.atStartOfDay(zone).toOffsetDateTime());p.executeUpdate() }
            c.prepareStatement("INSERT INTO devices(id,user_id,platform,model,last_sync_at) VALUES (?,?,'ANDROID','Demo device',now()) ON CONFLICT(id,user_id) DO NOTHING").use { p->p.setObject(1,deviceId);p.setObject(2,userId);p.executeUpdate() }
            val stride=1.78*0.415;val random=kotlin.random.Random(20240601L)
            // Relative activity per hour of day: morning routine, commute peaks, lunch, evening.
            val hourWeight={ h:Int->when(h){6->0.4;7->1.2;8->2.0;9->1.0;10->0.8;11->0.9;12->1.6;13->1.3;14->0.7;15->0.7;16->0.9;17->1.8;18->2.2;19->1.5;20->1.0;21->0.7;22->0.3;else->0.0} }
            c.prepareStatement("INSERT INTO step_intervals(id,user_id,device_id,source,interval_start,interval_end,steps,distance_m_estimated,calories_kcal_estimated) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,device_id,interval_start) DO NOTHING").use { p->
                var day=firstDay
                while(!day.isAfter(today)) {
                    val weekend=day.dayOfWeek==DayOfWeek.SATURDAY||day.dayOfWeek==DayOfWeek.SUNDAY
                    val baseline=if(weekend)8500 else 9800
                    // ~8% of days are low-activity rest days; otherwise the total varies smoothly around the baseline.
                    val total=if(random.nextDouble()<0.08)(1000+random.nextDouble()*2000).toInt() else (baseline*(0.65+random.nextDouble()*0.6)).toInt()
                    // Spread the day's steps across waking quarter-hours (06:00–22:45) following the activity curve plus jitter.
                    val weights=(24..91).map { q->q to hourWeight(q/4)*(0.5+random.nextDouble()) }.filter { it.second>0 }
                    val sum=weights.sumOf { it.second }
                    weights.forEach { (q,w)->
                        val steps=(total*w/sum).toInt();if(steps<=0)return@forEach
                        val start=day.atTime(q/4,(q%4)*15).atZone(zone).toInstant();val distance=steps*stride
                        p.setObject(1,UUID.nameUUIDFromBytes("demo:$day:$q".toByteArray()));p.setObject(2,userId);p.setObject(3,deviceId);p.setString(4,"HEALTH_CONNECT");p.setObject(5,start.atOffset(ZoneOffset.UTC));p.setObject(6,start.plusSeconds(900).atOffset(ZoneOffset.UTC));p.setInt(7,steps);p.setDouble(8,distance);p.setDouble(9,distance/1000*75*0.75);p.addBatch()
                    }
                    day=day.plusDays(1)
                }
                p.executeBatch()
            }
        }
    }

    fun createUser(email: String, hash: String): UserRecord = db.query { c ->
        val id = UUID.randomUUID()
        c.prepareStatement("INSERT INTO users(id,email,password_hash) VALUES (?,?,?)").use { p ->
            p.setObject(1, id); p.setString(2, email); p.setString(3, hash); p.executeUpdate()
        }
        UserRecord(id, email, hash)
    }

    fun userByEmail(email: String): UserRecord? = db.query { c ->
        c.prepareStatement("SELECT id,email,password_hash FROM users WHERE email=? AND disabled_at IS NULL").use { p ->
            p.setString(1, email); p.executeQuery().use { r -> if (r.next()) UserRecord(r.getObject(1, UUID::class.java), r.getString(2), r.getString(3)) else null }
        }
    }

    fun userById(id: UUID): UserRecord? = db.query { c ->
        c.prepareStatement("SELECT id,email,password_hash FROM users WHERE id=? AND disabled_at IS NULL").use { p ->
            p.setObject(1, id); p.executeQuery().use { r -> if (r.next()) UserRecord(r.getObject(1, UUID::class.java), r.getString(2), r.getString(3)) else null }
        }
    }

    fun issueTokens(userId: UUID): TokenResponse {
        val refresh = security.refreshToken()
        db.query { c -> c.prepareStatement("INSERT INTO refresh_tokens(id,user_id,token_hash,expires_at) VALUES (?,?,?,?)").use { p ->
            p.setObject(1, UUID.randomUUID()); p.setObject(2, userId); p.setString(3, security.tokenHash(refresh)); p.setObject(4, security.refreshExpiry().atOffset(ZoneOffset.UTC)); p.executeUpdate()
        }}
        return TokenResponse(security.accessToken(userId), refresh, security.accessSeconds)
    }

    fun rotateToken(token: String): Pair<UUID, TokenResponse>? = db.transaction { c ->
        val userId = c.prepareStatement("SELECT user_id FROM refresh_tokens WHERE token_hash=? AND revoked_at IS NULL AND expires_at>now() FOR UPDATE").use { p ->
            p.setString(1, security.tokenHash(token)); p.executeQuery().use { r -> if (r.next()) r.getObject(1, UUID::class.java) else null }
        } ?: return@transaction null
        c.prepareStatement("UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=?").use { p -> p.setString(1, security.tokenHash(token)); p.executeUpdate() }
        val next = security.refreshToken()
        c.prepareStatement("INSERT INTO refresh_tokens(id,user_id,token_hash,expires_at) VALUES (?,?,?,?)").use { p ->
            p.setObject(1, UUID.randomUUID()); p.setObject(2, userId); p.setString(3, security.tokenHash(next)); p.setObject(4, security.refreshExpiry().atOffset(ZoneOffset.UTC)); p.executeUpdate()
        }
        userId to TokenResponse(security.accessToken(userId), next, security.accessSeconds)
    }

    fun revoke(token: String) = db.query { c -> c.prepareStatement("UPDATE refresh_tokens SET revoked_at=now() WHERE token_hash=? AND revoked_at IS NULL").use { p -> p.setString(1, security.tokenHash(token)); p.executeUpdate() } }

    fun profile(userId: UUID): ProfileResponse? = db.query { c -> c.prepareStatement("SELECT weight_kg,height_cm,birth_date,sex,timezone FROM user_profiles WHERE user_id=?").use { p ->
        p.setObject(1, userId); p.executeQuery().use { r -> if (r.next()) ProfileResponse(r.getDouble(1), r.getDouble(2), r.getString(3), r.getString(4), r.getString(5)) else null }
    }}

    fun saveProfile(userId: UUID, value: ProfileRequest) = db.transaction { c ->
        val previousWeight = c.prepareStatement("SELECT weight_kg FROM user_profiles WHERE user_id=?").use { p ->
            p.setObject(1,userId);p.executeQuery().use { r->if(r.next())r.getDouble(1) else null }
        }
        c.prepareStatement("""INSERT INTO user_profiles(user_id,weight_kg,height_cm,birth_date,sex,timezone) VALUES (?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET weight_kg=excluded.weight_kg,height_cm=excluded.height_cm,birth_date=excluded.birth_date,sex=excluded.sex,timezone=excluded.timezone,updated_at=now()""").use { p ->
            p.setObject(1,userId); p.setDouble(2,value.weightKg); p.setDouble(3,value.heightCm); p.setObject(4,LocalDate.parse(value.birthDate)); p.setString(5,value.sex); p.setString(6,value.timezone); p.executeUpdate()
        }
        if(previousWeight == null || kotlin.math.abs(previousWeight-value.weightKg) >= 0.005) {
            c.prepareStatement("INSERT INTO user_weight_history(user_id,weight_kg,effective_at) VALUES (?,?,now())").use { p->p.setObject(1,userId);p.setDouble(2,value.weightKg);p.executeUpdate() }
            // "Last measurement of the day": the new weight is the most recent one, so recompute calories for every
            // interval from the start of today (in the user's timezone) onward using it. Earlier days keep the
            // weight that was effective when they were ingested (weightAt), so their calories stay historically correct.
            val zone=runCatching { ZoneId.of(value.timezone) }.getOrDefault(ZoneOffset.UTC)
            val dayStart=LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime()
            c.prepareStatement("UPDATE step_intervals SET calories_kcal_estimated=distance_m_estimated/1000*?*0.75,updated_at=now() WHERE user_id=? AND interval_start>=?").use { p->
                p.setDouble(1,value.weightKg);p.setObject(2,userId);p.setObject(3,dayStart);p.executeUpdate()
            }
        }
    }

    fun weightHistory(userId:UUID):List<WeightEntry> = db.query { c->c.prepareStatement("SELECT weight_kg,effective_at FROM user_weight_history WHERE user_id=? ORDER BY effective_at DESC").use { p->p.setObject(1,userId);p.executeQuery().use { r->buildList { while(r.next())add(WeightEntry(r.getDouble(1),r.getObject(2,OffsetDateTime::class.java).toInstant().toString())) } } } }

    private fun weightAt(c:Connection,userId:UUID,instant:Instant,fallback:Double):Double = c.prepareStatement("SELECT weight_kg FROM user_weight_history WHERE user_id=? AND effective_at<=? ORDER BY effective_at DESC LIMIT 1").use { p->
        p.setObject(1,userId);p.setObject(2,instant.atOffset(ZoneOffset.UTC));p.executeQuery().use { r->if(r.next())r.getDouble(1) else fallback }
    }

    // Remove a mistaken weigh-in and repair the timeline: the segment [deleted, nextEntry) now uses whatever weight
    // was effective just before it (or the profile weight), so recompute those intervals' calories. Also keep the
    // profile's current weight pointing at the latest remaining entry.
    // Edit a weigh-in's value: the segment [effectiveAt, nextEntry) is governed by it, so recompute those
    // intervals' calories with the new weight, and keep the profile's current weight at the latest entry.
    fun updateWeight(userId:UUID, effectiveAt:Instant, weightKg:Double):Boolean = db.transaction { c ->
        val at=effectiveAt.atOffset(ZoneOffset.UTC)
        val updated=c.prepareStatement("UPDATE user_weight_history SET weight_kg=? WHERE user_id=? AND effective_at=?").use { p->p.setDouble(1,weightKg);p.setObject(2,userId);p.setObject(3,at);p.executeUpdate() }
        if(updated==0) return@transaction false
        val next=c.prepareStatement("SELECT min(effective_at) FROM user_weight_history WHERE user_id=? AND effective_at>?").use { p->p.setObject(1,userId);p.setObject(2,at);p.executeQuery().use { r->if(r.next())r.getObject(1,OffsetDateTime::class.java) else null } }
        val sql=StringBuilder("UPDATE step_intervals SET calories_kcal_estimated=distance_m_estimated/1000*?*0.75,updated_at=now() WHERE user_id=? AND interval_start>=?")
        if(next!=null)sql.append(" AND interval_start<?")
        c.prepareStatement(sql.toString()).use { p->p.setDouble(1,weightKg);p.setObject(2,userId);p.setObject(3,at);if(next!=null)p.setObject(4,next);p.executeUpdate() }
        c.prepareStatement("SELECT weight_kg FROM user_weight_history WHERE user_id=? ORDER BY effective_at DESC LIMIT 1").use { p->p.setObject(1,userId);p.executeQuery().use { r->if(r.next()){ val latest=r.getDouble(1);c.prepareStatement("UPDATE user_profiles SET weight_kg=?,updated_at=now() WHERE user_id=?").use { u->u.setDouble(1,latest);u.setObject(2,userId);u.executeUpdate() } } } }
        true
    }

    fun deleteWeight(userId:UUID, effectiveAt:Instant):Boolean = db.transaction { c ->
        val at=effectiveAt.atOffset(ZoneOffset.UTC)
        val fallback=profile(userId)?.weightKg ?: 0.0
        val prevWeight=c.prepareStatement("SELECT weight_kg FROM user_weight_history WHERE user_id=? AND effective_at<? ORDER BY effective_at DESC LIMIT 1").use { p->p.setObject(1,userId);p.setObject(2,at);p.executeQuery().use { r->if(r.next())r.getDouble(1) else null } }
        val next=c.prepareStatement("SELECT min(effective_at) FROM user_weight_history WHERE user_id=? AND effective_at>?").use { p->p.setObject(1,userId);p.setObject(2,at);p.executeQuery().use { r->if(r.next())r.getObject(1,OffsetDateTime::class.java) else null } }
        val deleted=c.prepareStatement("DELETE FROM user_weight_history WHERE user_id=? AND effective_at=?").use { p->p.setObject(1,userId);p.setObject(2,at);p.executeUpdate() }
        if(deleted==0) return@transaction false
        val effWeight=prevWeight ?: fallback
        val sql=StringBuilder("UPDATE step_intervals SET calories_kcal_estimated=distance_m_estimated/1000*?*0.75,updated_at=now() WHERE user_id=? AND interval_start>=?")
        if(next!=null)sql.append(" AND interval_start<?")
        c.prepareStatement(sql.toString()).use { p->p.setDouble(1,effWeight);p.setObject(2,userId);p.setObject(3,at);if(next!=null)p.setObject(4,next);p.executeUpdate() }
        c.prepareStatement("SELECT weight_kg FROM user_weight_history WHERE user_id=? ORDER BY effective_at DESC LIMIT 1").use { p->p.setObject(1,userId);p.executeQuery().use { r->if(r.next()){ val latest=r.getDouble(1);c.prepareStatement("UPDATE user_profiles SET weight_kg=?,updated_at=now() WHERE user_id=?").use { u->u.setDouble(1,latest);u.setObject(2,userId);u.executeUpdate() } } } }
        true
    }

    fun deleteUser(userId: UUID) = db.query { c -> c.prepareStatement("DELETE FROM users WHERE id=?").use { p -> p.setObject(1,userId); p.executeUpdate() } }

    fun ingest(userId: UUID, input: StepBatchRequest): StepBatchResponse {
        require(input.intervals.size <= 500) { "Maximum batch size is 500" }
        val profile = profile(userId) ?: throw IllegalStateException("Profile required")
        val stride = profile.heightCm / 100.0 * if (profile.sex == "FEMALE") 0.413 else 0.415
        val accepted = mutableListOf<String>(); val rejected = mutableListOf<RejectedInterval>()
        db.transaction { c -> input.intervals.forEach { item ->
            try {
                val id = UUID.fromString(item.id); val device = UUID.fromString(item.deviceId)
                val start = Instant.parse(item.intervalStart); val end = Instant.parse(item.intervalEnd)
                require(item.source in setOf("HEALTH_CONNECT", "STEP_COUNTER")); require(item.steps in 0..100000)
                require(Duration.between(start,end).toMinutes() == 15L && start.epochSecond % 900 == 0L)
                c.prepareStatement("INSERT INTO devices(id,user_id,platform,model,last_sync_at) VALUES (?,?,'ANDROID',?,now()) ON CONFLICT(id,user_id) DO UPDATE SET model=excluded.model,last_sync_at=now()").use { p -> p.setObject(1,device);p.setObject(2,userId);p.setString(3,item.deviceModel.take(128));p.executeUpdate() }
                c.prepareStatement("""INSERT INTO step_intervals(id,user_id,device_id,source,interval_start,interval_end,steps,distance_m_estimated,calories_kcal_estimated) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,device_id,interval_start) DO UPDATE SET source=CASE WHEN excluded.source='HEALTH_CONNECT' THEN excluded.source ELSE step_intervals.source END,steps=CASE WHEN excluded.source='HEALTH_CONNECT' OR step_intervals.source!='HEALTH_CONNECT' THEN excluded.steps ELSE step_intervals.steps END,distance_m_estimated=CASE WHEN excluded.source='HEALTH_CONNECT' OR step_intervals.source!='HEALTH_CONNECT' THEN excluded.distance_m_estimated ELSE step_intervals.distance_m_estimated END,calories_kcal_estimated=CASE WHEN excluded.source='HEALTH_CONNECT' OR step_intervals.source!='HEALTH_CONNECT' THEN excluded.calories_kcal_estimated ELSE step_intervals.calories_kcal_estimated END,updated_at=now()""").use { p ->
                    val distance = item.steps * stride
                    val intervalWeight = weightAt(c,userId,start,profile.weightKg)
                    p.setObject(1,id);p.setObject(2,userId);p.setObject(3,device);p.setString(4,item.source);p.setObject(5,start.atOffset(ZoneOffset.UTC));p.setObject(6,end.atOffset(ZoneOffset.UTC));p.setInt(7,item.steps);p.setDouble(8,distance);p.setDouble(9,distance/1000*intervalWeight*0.75);p.executeUpdate()
                }
                accepted += item.id
            } catch (e: Exception) { rejected += RejectedInterval(item.id, "INVALID_INTERVAL") }
        }}
        return StepBatchResponse(accepted,rejected,Instant.now().toString())
    }

    fun steps(userId: UUID, from: Instant, to: Instant): List<StepPoint> = db.query { c -> c.prepareStatement("SELECT interval_start,steps,distance_m_estimated,calories_kcal_estimated FROM step_intervals WHERE user_id=? AND interval_start>=? AND interval_start<? ORDER BY interval_start").use { p ->
        p.setObject(1,userId);p.setObject(2,from.atOffset(ZoneOffset.UTC));p.setObject(3,to.atOffset(ZoneOffset.UTC));p.executeQuery().use { r -> buildList { while(r.next()) add(StepPoint(r.getObject(1,OffsetDateTime::class.java).toInstant().toString(),r.getLong(2),r.getDouble(3),r.getDouble(4))) } }
    }}

    fun daily(userId: UUID, from: LocalDate, to: LocalDate, timezone: String): List<DailyPoint> = db.query { c -> c.prepareStatement("""SELECT (interval_start AT TIME ZONE ?)::date d,sum(steps),sum(distance_m_estimated),sum(calories_kcal_estimated) FROM step_intervals WHERE user_id=? AND (interval_start AT TIME ZONE ?)::date BETWEEN ? AND ? GROUP BY d ORDER BY d""").use { p ->
        p.setString(1,timezone);p.setObject(2,userId);p.setString(3,timezone);p.setObject(4,from);p.setObject(5,to);p.executeQuery().use { r -> buildList { while(r.next()) add(DailyPoint(r.getString(1),r.getLong(2),r.getDouble(3),r.getDouble(4))) } }
    }}

    fun timeOfDay(userId: UUID, from: LocalDate, to: LocalDate, timezone: String): List<TimeOfDayPoint> = db.query { c -> c.prepareStatement("""SELECT extract(hour from interval_start AT TIME ZONE ?)*4+floor(extract(minute from interval_start AT TIME ZONE ?)/15) q,avg(steps) FROM step_intervals WHERE user_id=? AND (interval_start AT TIME ZONE ?)::date BETWEEN ? AND ? GROUP BY q ORDER BY q""").use { p ->
        p.setString(1,timezone);p.setString(2,timezone);p.setObject(3,userId);p.setString(4,timezone);p.setObject(5,from);p.setObject(6,to);p.executeQuery().use { r -> buildList { while(r.next()) add(TimeOfDayPoint(r.getInt(1),r.getDouble(2))) } }
    }}
}
