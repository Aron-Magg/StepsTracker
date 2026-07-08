package com.stepstracker

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.mkammerer.argon2.Argon2Factory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.UUID

class Security(private val config: AppConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    private val random = SecureRandom()

    fun hashPassword(password: String): String = Argon2Factory.create().hash(3, 65536, 1, password.toCharArray())
    fun verifyPassword(hash: String, password: String): Boolean = Argon2Factory.create().verify(hash, password.toCharArray())
    fun accessToken(userId: UUID): String = JWT.create()
        .withIssuer(ISSUER).withAudience(AUDIENCE).withSubject(userId.toString())
        .withExpiresAt(Date.from(Instant.now().plus(config.accessMinutes, ChronoUnit.MINUTES)))
        .sign(algorithm)
    fun verifier() = JWT.require(algorithm).withIssuer(ISSUER).withAudience(AUDIENCE).build()
    fun refreshToken(): String = ByteArray(48).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun tokenHash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    fun refreshExpiry(): Instant = Instant.now().plus(config.refreshDays, ChronoUnit.DAYS)
    val accessSeconds get() = config.accessMinutes * 60

    companion object { const val ISSUER = "stepstracker"; const val AUDIENCE = "stepstracker-android" }
}

