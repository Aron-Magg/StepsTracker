package com.stepstracker.android.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.stepstracker.android.data.StepsRepository
import com.stepstracker.android.data.IntervalMath
import kotlinx.coroutines.*
import java.time.*

enum class TrackingSource { HEALTH_CONNECT, STEP_COUNTER, UNAVAILABLE }

class StepTrackingManager(private val context:Context,private val repository:StepsRepository) : SensorEventListener {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val sensorManager=context.getSystemService(SensorManager::class.java)
    private val prefs=context.getSharedPreferences("step-counter",Context.MODE_PRIVATE)
    var source:TrackingSource=TrackingSource.UNAVAILABLE;private set

    suspend fun start():TrackingSource {
        stopSensor()
        val status=HealthConnectClient.getSdkStatus(context)
        if(status==HealthConnectClient.SDK_AVAILABLE) {
            val client=HealthConnectClient.getOrCreate(context)
            val permission=HealthPermission.getReadPermission(StepsRecord::class)
            if(client.permissionController.getGrantedPermissions().contains(permission)) {
                source=TrackingSource.HEALTH_CONNECT;importHealthConnect(client);return source
            }
        }
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED) {
            val sensor=sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if(sensor!=null && sensorManager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL)) { source=TrackingSource.STEP_COUNTER;return source }
        }
        source=TrackingSource.UNAVAILABLE;return source
    }

    fun healthPermissions():Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    private suspend fun importHealthConnect(client:HealthConnectClient) {
        val end=Instant.now();val start=end.minus(Duration.ofDays(30)).epochSecond.let { Instant.ofEpochSecond(it/900*900) }
        client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL),TimeRangeFilter.between(start,end),Duration.ofMinutes(15))).forEach { bucket ->
            repository.store("HEALTH_CONNECT",bucket.startTime,(bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt())
        }
    }

    override fun onSensorChanged(event:SensorEvent) {
        val current=event.values.firstOrNull()?.toLong() ?: return
        val previous=prefs.getLong("reading",current);val previousBoot=prefs.getLong("boot",android.os.SystemClock.elapsedRealtime())
        val reset=current<previous || android.os.SystemClock.elapsedRealtime()<previousBoot
        val delta=IntervalMath.sensorDelta(previous,current,reset)
        prefs.edit().putLong("reading",current).putLong("boot",android.os.SystemClock.elapsedRealtime()).apply()
        if(delta>0)scope.launch { repository.store("STEP_COUNTER",Instant.now(),delta) }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
    fun stopSensor()=sensorManager.unregisterListener(this)
}
