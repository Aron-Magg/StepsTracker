package com.stepstracker

import java.sql.Connection
import java.sql.ResultSet
import java.time.*
import java.time.format.DateTimeParseException
import java.util.UUID

data class UserRecord(val id: UUID, val email: String, val passwordHash: String)

class Repository(private val db: Database, private val security: Security, private val config: AppConfig) {
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
        c.prepareStatement("""INSERT INTO user_profiles(user_id,weight_kg,height_cm,birth_date,sex,timezone) VALUES (?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET weight_kg=excluded.weight_kg,height_cm=excluded.height_cm,birth_date=excluded.birth_date,sex=excluded.sex,timezone=excluded.timezone,updated_at=now()""").use { p ->
            p.setObject(1,userId); p.setDouble(2,value.weightKg); p.setDouble(3,value.heightCm); p.setObject(4,LocalDate.parse(value.birthDate)); p.setString(5,value.sex); p.setString(6,value.timezone); p.executeUpdate()
        }
        recalculate(c, userId, value)
    }

    private fun recalculate(c: Connection, userId: UUID, profile: ProfileRequest) {
        val stride = profile.heightCm / 100.0 * if (profile.sex == "FEMALE") 0.413 else 0.415
        c.prepareStatement("UPDATE step_intervals SET distance_m_estimated=steps*?,calories_kcal_estimated=(steps*?/1000.0)*?*0.75,updated_at=now() WHERE user_id=?").use { p ->
            p.setDouble(1,stride); p.setDouble(2,stride); p.setDouble(3,profile.weightKg); p.setObject(4,userId); p.executeUpdate()
        }
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
                c.prepareStatement("""INSERT INTO step_intervals(id,user_id,device_id,source,interval_start,interval_end,steps,distance_m_estimated,calories_kcal_estimated) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,device_id,source,interval_start) DO UPDATE SET steps=excluded.steps,distance_m_estimated=excluded.distance_m_estimated,calories_kcal_estimated=excluded.calories_kcal_estimated,updated_at=now()""").use { p ->
                    val distance = item.steps * stride
                    p.setObject(1,id);p.setObject(2,userId);p.setObject(3,device);p.setString(4,item.source);p.setObject(5,start.atOffset(ZoneOffset.UTC));p.setObject(6,end.atOffset(ZoneOffset.UTC));p.setInt(7,item.steps);p.setDouble(8,distance);p.setDouble(9,distance/1000*profile.weightKg*0.75);p.executeUpdate()
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
