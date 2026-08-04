package com.stepstracker.android.tracking.run

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stepstracker.android.StepsTrackerApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

class DebugRunLocationReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val pending=goAsync();val app=context.applicationContext as StepsTrackerApp
        CoroutineScope(Dispatchers.IO).launch { try { simulationMutex.withLock { val recordedAt=intent.getStringExtra("recordedAt")?.toLongOrNull()?:System.currentTimeMillis();val run=app.runs.active.firstOrNull()?:app.runs.start(recordedAt);val lat=intent.getStringExtra("lat")?.toDoubleOrNull()?:return@withLock;val lon=intent.getStringExtra("lon")?.toDoubleOrNull()?:return@withLock;val accuracy=intent.getStringExtra("accuracy")?.toFloatOrNull()?:2f;val accepted=app.runs.addLocation(run.id,recordedAt,lat,lon,270.0,accuracy,null,null);Log.d("GPS_CLEANING","kind=${intent.getStringExtra("kind")?:"normal"} accepted=$accepted") } } finally { pending.finish() } }
    }
    companion object { const val ACTION="com.stepstracker.android.DEBUG_RUN_LOCATION";private val simulationMutex=Mutex() }
}
