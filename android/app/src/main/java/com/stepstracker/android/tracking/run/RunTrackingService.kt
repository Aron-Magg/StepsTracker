package com.stepstracker.android.tracking.run

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.stepstracker.android.MainActivity
import com.stepstracker.android.StepsTrackerApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

class RunTrackingService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);private lateinit var source:RunLocationSource;private var checkpointJob:Job?=null;private var clockJob:Job?=null
    override fun onCreate(){super.onCreate();source=FusedRunLocationSource(this);channel();foreground("Preparing GPS…")}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int { val app=application as StepsTrackerApp;scope.launch { when(intent?.action){null->app.runs.active.firstOrNull()?.let{track(it.id)}?:stopSelf();ACTION_START->{val run=app.runs.active.firstOrNull()?:app.runs.start();track(run.id)};ACTION_PAUSE->app.runs.active.firstOrNull()?.let { app.runs.pause(it.id);notify("Run paused") };ACTION_RESUME->app.runs.active.firstOrNull()?.let { app.runs.resume(it.id);track(it.id) };ACTION_FINISH->app.runs.active.firstOrNull()?.let { source.stop();app.runs.finish(it.id);stopSelf() };ACTION_SYNC->app.runs.syncAll()} };return START_STICKY }
    private fun track(id:String){source.stop();checkpointJob?.cancel();clockJob?.cancel();checkpointJob=scope.launch { while(isActive){delay(30_000);runCatching{(application as StepsTrackerApp).runs.sync(id)}} };clockJob=scope.launch { while(isActive){delay(1000);val app=application as StepsTrackerApp;app.runs.tick(id);app.database.runs().session(id)?.let{notify("%.2f km · %s".format(it.distanceMeters/1000,format(it.activeDurationMillis)))}} };source.start { location->scope.launch { val app=application as StepsTrackerApp;val accepted=app.runs.addLocation(id,location.time,location.latitude,location.longitude,location.altitude.takeIf{location.hasAltitude()},location.accuracy,location.speed.takeIf{location.hasSpeed()},location.bearing.takeIf{location.hasBearing()});if(accepted&&app.database.runs().pendingPoints(id).size>=20)runCatching{app.runs.sync(id)} }}}
    private fun foreground(text:String){ServiceCompat.startForeground(this,NOTIFICATION_ID,notification(text),if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)}
    private fun notify(text:String)=getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(text))
    private fun notification(text:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(value:String,icon:Int,label:String):NotificationCompat.Action {
            val pending=PendingIntent.getService(this,value.hashCode(),Intent(this,RunTrackingService::class.java).setAction(value),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action(icon,label,pending)
        }
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(com.stepstracker.android.R.drawable.stepstracker_logo).setContentTitle("Run tracking active").setContentText(text).setOngoing(true).setContentIntent(open).addAction(action(ACTION_PAUSE,android.R.drawable.ic_media_pause,"Pause")).addAction(action(ACTION_RESUME,android.R.drawable.ic_media_play,"Resume")).addAction(action(ACTION_FINISH,android.R.drawable.ic_menu_close_clear_cancel,"Finish")).build()
    }
    private fun channel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Run tracking",NotificationManager.IMPORTANCE_LOW))}
    private fun format(ms:Long)="%02d:%02d:%02d".format(ms/3600000,(ms/60000)%60,(ms/1000)%60)
    override fun onBind(intent:Intent?)=null
    override fun onDestroy(){source.stop();checkpointJob?.cancel();clockJob?.cancel();scope.cancel();super.onDestroy()}
    companion object { const val ACTION_START="run.START";const val ACTION_PAUSE="run.PAUSE";const val ACTION_RESUME="run.RESUME";const val ACTION_FINISH="run.FINISH";const val ACTION_SYNC="run.SYNC";private const val CHANNEL="run_tracking";private const val NOTIFICATION_ID=4301;fun send(context:Context,action:String){val i=Intent(context,RunTrackingService::class.java).setAction(action);if(action==ACTION_START)context.startForegroundService(i) else context.startService(i)} }
}
