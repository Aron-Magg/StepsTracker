package com.stepstracker

import kotlinx.serialization.Serializable

@Serializable data class ErrorResponse(val code: String, val message: String)
@Serializable data class RegisterRequest(val email: String, val password: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class LogoutRequest(val refreshToken: String)
@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)
@Serializable data class ProfileRequest(val weightKg: Double, val heightCm: Double, val birthDate: String, val sex: String, val timezone: String)
@Serializable data class ProfileResponse(val weightKg: Double, val heightCm: Double, val birthDate: String, val sex: String, val timezone: String)
@Serializable data class WeightEntry(val weightKg: Double, val effectiveAt: String)
@Serializable data class MeResponse(val id: String, val email: String, val profile: ProfileResponse?)

@Serializable
data class StepIntervalRequest(
    val id: String,
    val deviceId: String,
    val deviceModel: String,
    val source: String,
    val intervalStart: String,
    val intervalEnd: String,
    val steps: Int,
)

@Serializable data class StepBatchRequest(val intervals: List<StepIntervalRequest>)
@Serializable data class RejectedInterval(val id: String, val reason: String)
@Serializable data class StepBatchResponse(val acceptedIds: List<String>, val rejected: List<RejectedInterval>, val serverTime: String)
@Serializable data class StepPoint(val start: String, val steps: Long, val distanceMeters: Double, val estimatedKcal: Double)
@Serializable data class DailyPoint(val date: String, val steps: Long, val distanceMeters: Double, val estimatedKcal: Double)
@Serializable data class TimeOfDayPoint(val quarterHour: Int, val steps: Double)
@Serializable data class SummaryResponse(val steps: Long, val distanceMeters: Double, val estimatedKcal: Double, val dailyAverage: Double, val changePercent: Double?)
