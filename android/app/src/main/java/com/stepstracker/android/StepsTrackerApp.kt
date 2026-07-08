package com.stepstracker.android

import android.app.Application
import android.provider.Settings
import androidx.work.*
import com.stepstracker.android.data.*
import java.util.concurrent.TimeUnit

class StepsTrackerApp:Application() {
    lateinit var database:StepsDatabase;lateinit var session:SessionStore;lateinit var api:ApiClient;lateinit var steps:StepsRepository
    override fun onCreate() {
        super.onCreate();database=StepsDatabase.create(this);session=SessionStore(this);api=ApiClient(session)
        val deviceId=Settings.Secure.getString(contentResolver,Settings.Secure.ANDROID_ID)
        steps=StepsRepository(database.intervals(),api,deviceId)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("steps-sync",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

class SyncWorker(context:android.content.Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=runCatching { (applicationContext as StepsTrackerApp).steps.sync();Result.success() }.getOrElse { Result.retry() }
}

