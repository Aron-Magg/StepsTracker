package com.stepstracker.android.tracking.run

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.*

interface RunLocationSource { fun start(callback:(Location)->Unit);fun stop() }
class FusedRunLocationSource(context:Context):RunLocationSource {
    private val client=LocationServices.getFusedLocationProviderClient(context)
    private var callback:LocationCallback?=null
    @SuppressLint("MissingPermission") override fun start(consumer:(Location)->Unit) { if(callback!=null)return;val cb=object:LocationCallback(){override fun onLocationResult(result:LocationResult){result.locations.forEach(consumer)}};callback=cb;client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,2000).setMinUpdateDistanceMeters(3f).setMinUpdateIntervalMillis(1000).build(),cb,android.os.Looper.getMainLooper()) }
    override fun stop(){callback?.let(client::removeLocationUpdates);callback=null}
}
