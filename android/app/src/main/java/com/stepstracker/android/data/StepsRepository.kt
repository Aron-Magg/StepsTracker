package com.stepstracker.android.data

import android.os.Build
import kotlinx.coroutines.flow.Flow
import java.time.*
import java.util.UUID

class StepsRepository(private val dao:StepIntervalDao,private val api:ApiClient,private val deviceId:String) {
    fun observeDay(day:LocalDate,zone:ZoneId=ZoneId.systemDefault()):Flow<List<StepIntervalEntity>> {
        val from=day.atStartOfDay(zone).toInstant().toEpochMilli();val to=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observe(from,to)
    }
    suspend fun store(source:String,start:Instant,steps:Int) {
        val aligned=IntervalMath.alignedEpochSeconds(start)
        val id=IntervalMath.stableId(deviceId,source,aligned)
        val entity=StepIntervalEntity(id,source,aligned*1000,(aligned+900)*1000,steps.coerceAtLeast(0))
        if(source=="STEP_COUNTER")dao.add(entity) else dao.upsert(listOf(entity))
    }
    suspend fun sync():Int {
        val pending=dao.pending();if(pending.isEmpty())return 0
        val result=api.upload(UploadBatch(pending.map { UploadInterval(it.id,deviceId,"${Build.MANUFACTURER} ${Build.MODEL}",it.source,Instant.ofEpochMilli(it.intervalStart).toString(),Instant.ofEpochMilli(it.intervalEnd).toString(),it.steps) }))
        dao.markSynced(result.acceptedIds);return result.acceptedIds.size
    }
}
