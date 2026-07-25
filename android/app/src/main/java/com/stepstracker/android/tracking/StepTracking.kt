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
import com.stepstracker.android.data.TrackingPreference
import com.stepstracker.android.data.TrackingSettings
import kotlinx.coroutines.*
import java.time.*

enum class TrackingSource { HEALTH_CONNECT, STEP_COUNTER, UNAVAILABLE }

// Turns a raw TYPE_STEP_COUNTER reading (cumulative since boot) into per-interval deltas, persisting the
// baseline so foreground and background samples share the same state. Reused by the live listener and the
// background worker so the widget can update without the app being open.
class StepCounterAccumulator(context:Context,private val repository:StepsRepository) {
    private val prefs=context.getSharedPreferences("step-counter",Context.MODE_PRIVATE)
    suspend fun record(current:Long) {
        val bootMillis=System.currentTimeMillis()-android.os.SystemClock.elapsedRealtime()
        val previous=if(prefs.contains("reading"))prefs.getLong("reading",current) else null
        val reset=kotlin.math.abs(bootMillis-prefs.getLong("boot",bootMillis))>5000
        val bootedToday=Instant.ofEpochMilli(bootMillis).atZone(ZoneId.systemDefault()).toLocalDate()==LocalDate.now()
        val delta=IntervalMath.stepCounterDelta(previous,current,reset,bootedToday)
        prefs.edit().putLong("reading",current).putLong("boot",bootMillis).apply()
        if(delta>0)repository.store("STEP_COUNTER",Instant.now(),delta)
    }
}

// Registers the step counter, waits for a single reading, then unregisters. Used by the background worker.
suspend fun sampleStepCounter(context:Context,repository:StepsRepository) {
    if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACTIVITY_RECOGNITION)!=PackageManager.PERMISSION_GRANTED)return
    val sensorManager=context.getSystemService(SensorManager::class.java) ?: return
    val sensor=sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
    val value=suspendCancellableCoroutine<Long?> { cont->
        val listener=object:SensorEventListener {
            override fun onSensorChanged(event:SensorEvent) { sensorManager.unregisterListener(this);if(cont.isActive)cont.resumeWith(Result.success(event.values.firstOrNull()?.toLong())) }
            override fun onAccuracyChanged(s:Sensor?,a:Int)=Unit
        }
        cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
        if(!sensorManager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_FASTEST) && cont.isActive)cont.resumeWith(Result.success(null))
    }
    if(value!=null)StepCounterAccumulator(context,repository).record(value)
}

class StepTrackingManager(private val context:Context,private val repository:StepsRepository,private val settings:TrackingSettings) : SensorEventListener {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val sensorManager=context.getSystemService(SensorManager::class.java)
    private val accumulator=StepCounterAccumulator(context,repository)
    var source:TrackingSource=TrackingSource.UNAVAILABLE;private set

    suspend fun start():TrackingSource {
        stopSensor()
        val preference=settings.preference
        if(preference!=TrackingPreference.DEVICE_SENSOR && HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE) {
            val granted=runCatching {
                val client=HealthConnectClient.getOrCreate(context)
                val permission=HealthPermission.getReadPermission(StepsRecord::class)
                client.permissionController.getGrantedPermissions().contains(permission)
            }.getOrDefault(false)
            if(granted) {
                source=TrackingSource.HEALTH_CONNECT;runCatching { HealthConnectImporter.importAvailable(context,repository) };return source
            }
        }
        if(preference!=TrackingPreference.HEALTH_CONNECT && ContextCompat.checkSelfPermission(context,Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED) {
            val sensor=sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if(sensor!=null && sensorManager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL)) { source=TrackingSource.STEP_COUNTER;return source }
        }
        source=TrackingSource.UNAVAILABLE;return source
    }

    fun healthPermissions():Set<String> = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    override fun onSensorChanged(event:SensorEvent) {
        val current=event.values.firstOrNull()?.toLong() ?: return
        scope.launch { accumulator.record(current) }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
    fun stopSensor()=sensorManager.unregisterListener(this)
}

object HealthConnectImporter {
    suspend fun importAvailable(context:Context,repository:StepsRepository):Int {
        if(HealthConnectClient.getSdkStatus(context)!=HealthConnectClient.SDK_AVAILABLE)return 0
        val client=HealthConnectClient.getOrCreate(context)
        val permission=HealthPermission.getReadPermission(StepsRecord::class)
        if(permission !in client.permissionController.getGrantedPermissions())return 0
        val end=Instant.now();val start=end.minus(Duration.ofDays(30)).epochSecond.let { Instant.ofEpochSecond(it/900*900) }
        var total=0
        client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL),TimeRangeFilter.between(start,end),Duration.ofMinutes(15))).forEach { bucket ->
            val steps=(bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
            repository.store("HEALTH_CONNECT",bucket.startTime,steps);total+=steps
        }
        return total
    }
}
