package com.stepstracker.android

import android.app.Application
import android.provider.Settings
import androidx.work.*
import com.stepstracker.android.data.*
import com.stepstracker.android.tracking.HealthConnectImporter
import com.stepstracker.android.tracking.sampleStepCounter
import com.stepstracker.android.widget.StepWidgetProvider
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class StepsTrackerApp:Application() {
    lateinit var database:StepsDatabase;lateinit var session:SessionStore;lateinit var cache:LocalCache;lateinit var server:ServerSettings;lateinit var trackingSettings:TrackingSettings;lateinit var api:ApiClient;lateinit var steps:StepsRepository
    override fun onCreate() {
        super.onCreate();database=StepsDatabase.create(this);session=SessionStore(this);cache=LocalCache(this);server=ServerSettings(this);trackingSettings=TrackingSettings(this);api=ApiClient(session,server)
        // ANDROID_ID is a 16-hex-char string, not a UUID; the backend does UUID.fromString(deviceId) and would
        // reject every interval. Derive a stable UUID from it so uploads are accepted.
        val androidId=Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID) ?: "stepstracker-device"
        val deviceId=java.util.UUID.nameUUIDFromBytes(androidId.toByteArray()).toString()
        steps=StepsRepository(database.intervals(),api,deviceId,cache) { StepWidgetProvider.requestUpdate(this) }
        // No network constraint: the worker also collects steps and refreshes the widget, which must happen offline too.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("steps-sync",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).build())
    }
}

class SyncWorker(context:android.content.Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as StepsTrackerApp
        runCatching {
            if(app.trackingSettings.preference==TrackingPreference.DEVICE_SENSOR)withTimeoutOrNull(6000){ sampleStepCounter(applicationContext,app.steps) }
            else HealthConnectImporter.importAvailable(applicationContext,app.steps)
        }
        runCatching { app.steps.sync() }
        StepWidgetProvider.requestUpdate(applicationContext)
        return Result.success()
    }
}
