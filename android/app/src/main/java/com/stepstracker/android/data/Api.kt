package com.stepstracker.android.data

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class Credentials(val email:String,val password:String)
@Serializable data class RefreshRequest(val refreshToken:String)
@Serializable data class LogoutRequest(val refreshToken:String)
@Serializable data class Tokens(val accessToken:String,val refreshToken:String,val expiresInSeconds:Long)
@Serializable data class ProfileRequest(val weightKg:Double,val heightCm:Double,val birthDate:String,val sex:String,val timezone:String)
@Serializable data class Profile(val weightKg:Double,val heightCm:Double,val birthDate:String,val sex:String,val timezone:String)
@Serializable data class WeightEntry(val weightKg:Double,val effectiveAt:String)
@Serializable data class Me(val id:String,val email:String,val profile:Profile?=null)
@Serializable data class UploadInterval(val id:String,val deviceId:String,val deviceModel:String,val source:String,val intervalStart:String,val intervalEnd:String,val steps:Int)
@Serializable data class UploadBatch(val intervals:List<UploadInterval>)
@Serializable data class Rejection(val id:String,val reason:String)
@Serializable data class BatchResult(val acceptedIds:List<String>,val rejected:List<Rejection>,val serverTime:String)
@Serializable data class DailyPoint(val date:String,val steps:Long,val distanceMeters:Double,val estimatedKcal:Double)
@Serializable data class TimePoint(val quarterHour:Int,val steps:Double)
@Serializable data class Summary(val steps:Long,val distanceMeters:Double,val estimatedKcal:Double,val dailyAverage:Double,val changePercent:Double?)
@Serializable data class CreateRunRequest(val id:String,val deviceId:String,val startedAt:String)
@Serializable data class RunPointDto(val sequence:Int,val recordedAt:String,val latitude:Double,val longitude:Double,val altitudeMeters:Double?=null,val accuracyMeters:Float,val speedMps:Float?=null,val bearingDegrees:Float?=null)
@Serializable data class RunPauseDto(val id:String,val pausedAt:String,val resumedAt:String?=null)
@Serializable data class RunCheckpointRequest(val status:String,val activeDurationMillis:Long,val pauses:List<RunPauseDto>,val points:List<RunPointDto>)
@Serializable data class CompleteRunRequest(val endedAt:String,val activeDurationMillis:Long,val lastPointSequence:Int)
@Serializable data class RejectedRunPoint(val sequence:Int,val reason:String)
@Serializable data class RunCheckpointResult(val lastAcceptedSequence:Int,val rejected:List<RejectedRunPoint>)
@Serializable data class RunSummaryDto(val id:String,val status:String,val startedAt:String,val endedAt:String?=null,val activeDurationMillis:Long,val elapsedDurationMillis:Long,val distanceMeters:Double,val averageSpeedMps:Double,val averagePaceSecondsPerKm:Double?=null,val caloriesKcal:Double,val lastPointSequence:Int)
@Serializable data class RunDetailDto(val summary:RunSummaryDto,val points:List<RunPointDto>,val pauses:List<RunPauseDto>)
@Serializable data class RunListDto(val items:List<RunSummaryDto>,val nextCursor:String?=null)

class ApiClient(private val session: SessionStore,private val server:ServerSettings) {
    private val client=HttpClient(OkHttp) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys=true }) } }
    private suspend inline fun <reified T> request(path:String, block:HttpRequestBuilder.()->Unit={}):T {
        var response=client.request(server.baseUrl+path) { session.accessToken?.let { bearerAuth(it) }; block() }
        if(response.status==HttpStatusCode.Unauthorized && session.refreshToken!=null && refresh()) response=client.request(server.baseUrl+path) { bearerAuth(session.accessToken!!); block() }
        if(!response.status.isSuccess()) error("HTTP ${response.status.value}: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun register(email:String,password:String)=request<Tokens>("api/v1/auth/register") { method=HttpMethod.Post;contentType(ContentType.Application.Json);setBody(Credentials(email,password)) }.also(session::save)
    suspend fun login(email:String,password:String)=request<Tokens>("api/v1/auth/login") { method=HttpMethod.Post;contentType(ContentType.Application.Json);setBody(Credentials(email,password)) }.also(session::save)
    private suspend fun refresh():Boolean = runCatching {
        val result=client.post(server.baseUrl+"api/v1/auth/refresh") { contentType(ContentType.Application.Json);setBody(RefreshRequest(session.refreshToken!!)) }
        if(!result.status.isSuccess()) false else { session.save(result.body());true }
    }.getOrDefault(false)
    suspend fun logout() { session.refreshToken?.let { runCatching { client.post(server.baseUrl+"api/v1/auth/logout") { contentType(ContentType.Application.Json);setBody(LogoutRequest(it)) } } };session.clear() }
    suspend fun me()=request<Me>("api/v1/me")
    suspend fun profile(value:ProfileRequest)=request<Profile>("api/v1/me/profile") { method=HttpMethod.Put;contentType(ContentType.Application.Json);setBody(value) }
    suspend fun weightHistory()=request<List<WeightEntry>>("api/v1/me/weight-history")
    suspend fun deleteWeight(effectiveAt:String) { request<Unit>("api/v1/me/weight-history?effectiveAt=${effectiveAt.encodeURLParameter()}") { method=HttpMethod.Delete } }
    suspend fun updateWeight(effectiveAt:String,weightKg:Double) { request<Unit>("api/v1/me/weight-history?effectiveAt=${effectiveAt.encodeURLParameter()}&weightKg=$weightKg") { method=HttpMethod.Put } }
    suspend fun upload(value:UploadBatch)=request<BatchResult>("api/v1/steps/batch") { method=HttpMethod.Post;contentType(ContentType.Application.Json);setBody(value) }
    suspend fun daily(from:String,to:String)=request<List<DailyPoint>>("api/v1/stats/daily?from=$from&to=$to")
    suspend fun timeOfDay(from:String,to:String)=request<List<TimePoint>>("api/v1/stats/time-of-day?from=$from&to=$to")
    suspend fun summary(from:String,to:String)=request<Summary>("api/v1/stats/summary?from=$from&to=$to")
    suspend fun createRun(value:CreateRunRequest)=request<RunSummaryDto>("api/v1/runs") { method=HttpMethod.Post;contentType(ContentType.Application.Json);setBody(value) }
    suspend fun checkpointRun(id:String,value:RunCheckpointRequest)=request<RunCheckpointResult>("api/v1/runs/$id/checkpoint") { method=HttpMethod.Put;contentType(ContentType.Application.Json);setBody(value) }
    suspend fun completeRun(id:String,value:CompleteRunRequest)=request<RunSummaryDto>("api/v1/runs/$id/complete") { method=HttpMethod.Post;contentType(ContentType.Application.Json);setBody(value) }
    suspend fun runs()=request<RunListDto>("api/v1/runs?limit=100")
    suspend fun run(id:String)=request<RunDetailDto>("api/v1/runs/$id")
    suspend fun deleteRun(id:String)=request<Unit>("api/v1/runs/$id") { method=HttpMethod.Delete }
    suspend fun deleteAccount() { request<Unit>("api/v1/me") { method=HttpMethod.Delete };session.clear() }
}
