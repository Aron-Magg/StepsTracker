package com.stepstracker.android.data

import android.os.Build
import kotlinx.coroutines.flow.Flow
import java.time.*
import java.util.UUID

class StepsRepository(private val dao:StepIntervalDao,private val api:ApiClient,private val deviceId:String,private val cache:LocalCache,private val onChanged:()->Unit={}) {
    fun observeDay(day:LocalDate,zone:ZoneId=ZoneId.systemDefault()):Flow<List<StepIntervalEntity>> {
        val from=day.atStartOfDay(zone).toInstant().toEpochMilli();val to=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observe(from,to)
    }
    // Per-day step totals from the local cache, keyed by local date (yyyy-MM-dd). Lets day navigation and the
    // chart show history even before it has been synced to the server.
    suspend fun dailySteps(from:LocalDate,zone:ZoneId=ZoneId.systemDefault()):Map<String,Long> {
        val today=LocalDate.now(zone)
        val fromMs=from.atStartOfDay(zone).toInstant().toEpochMilli();val to=today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.range(fromMs,to).groupBy { Instant.ofEpochMilli(it.intervalStart).atZone(zone).toLocalDate().toString() }.mapValues { entry->entry.value.sumOf { it.steps }.toLong() }
    }
    suspend fun dailySteps(days:Int=30,zone:ZoneId=ZoneId.systemDefault()):Map<String,Long> =
        dailySteps(LocalDate.now(zone).minusDays((days-1).toLong()),zone)
    // Earliest locally-known day, so statistics can span the full range of collected data instead of a fixed window.
    suspend fun earliestDate(zone:ZoneId=ZoneId.systemDefault()):LocalDate? =
        dao.earliest()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    suspend fun store(source:String,start:Instant,steps:Int) {
        val aligned=IntervalMath.alignedEpochSeconds(start)
        val id=IntervalMath.stableId(deviceId,source,aligned)
        val entity=StepIntervalEntity(id,source,aligned*1000,(aligned+900)*1000,steps.coerceAtLeast(0))
        if(source=="STEP_COUNTER")dao.add(entity) else dao.replaceWithHealthConnect(entity)
        onChanged()
    }
    suspend fun sync():Int {
        val pending=dao.pending();if(pending.isEmpty())return 0
        val result=api.upload(UploadBatch(pending.map { UploadInterval(it.id,deviceId,"${Build.MANUFACTURER} ${Build.MODEL}",it.source,Instant.ofEpochMilli(it.intervalStart).toString(),Instant.ofEpochMilli(it.intervalEnd).toString(),it.steps) }))
        dao.markSynced(result.acceptedIds);cache.lastSyncServerTime=result.serverTime;return result.acceptedIds.size
    }
}
