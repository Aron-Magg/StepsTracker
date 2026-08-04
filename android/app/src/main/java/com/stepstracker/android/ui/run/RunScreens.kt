package com.stepstracker.android.ui.run

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.stepstracker.android.StepsTrackerApp
import com.stepstracker.android.R
import com.stepstracker.android.data.run.*
import com.stepstracker.android.tracking.run.RunTrackingService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun RunsRoot(){val context=LocalContext.current;val app=context.applicationContext as StepsTrackerApp;val model:RunViewModel=viewModel(factory=RunViewModel.factory(app));val state by model.state.collectAsStateWithLifecycle();var pendingStart by remember{mutableStateOf(false)}
    val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted||Build.VERSION.SDK_INT<33)RunTrackingService.send(context,RunTrackingService.ACTION_START)}
    val location=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->if(grants[Manifest.permission.ACCESS_FINE_LOCATION]==true){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else RunTrackingService.send(context,RunTrackingService.ACTION_START)};pendingStart=false}
    val start={pendingStart=true;location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
    when{state.selected!=null->RunDetail(state.selected!!,{model.select(null)},{model.delete(state.selected!!.session.id)});state.active!=null->ActiveRun(state.active!!,app.database.runs().observePoints(state.active!!.id).collectAsStateWithLifecycle(emptyList()).value);else->RunList(state.history,start,model::select,pendingStart)} }

@Composable private fun RunList(history:List<RunSessionEntity>,start:()->Unit,select:(String)->Unit,loading:Boolean){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp,20.dp,20.dp,130.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Runs",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text("GPS tracking continues with the screen off.",color=MaterialTheme.colorScheme.onSurfaceVariant);Button(start,Modifier.fillMaxWidth().padding(top=12.dp),enabled=!loading){Icon(Icons.Default.DirectionsRun,null);Spacer(Modifier.width(8.dp));Text("Start run")}};if(history.none{it.status=="COMPLETED"})item{Text("No completed runs yet",color=MaterialTheme.colorScheme.onSurfaceVariant)};items(history.filter{it.status=="COMPLETED"},key={it.id}){run->Card(Modifier.fillMaxWidth().clickable{select(run.id)}){Column(Modifier.padding(16.dp)){Text(date(run.startedAt),fontWeight=FontWeight.Bold);Text("%.2f km · %s · %s/km".format(run.distanceMeters/1000,duration(run.activeDurationMillis),pace(run.averagePaceSecondsPerKm)));Text(if(run.serverSynced)"Synchronized" else "Sync pending",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}}

@Composable private fun ActiveRun(run:RunSessionEntity,points:List<RunPointEntity>){val context=LocalContext.current;var confirm by remember{mutableStateOf(false)};val recent=points.takeLastWhile{points.last().recordedAt-it.recordedAt<=10_000};val recentSeconds=recent.firstOrNull()?.let{(recent.last().recordedAt-it.recordedAt)/1000.0}?:0.0;val currentSpeed=if(recentSeconds>0)recent.sumOf{it.distanceFromPreviousMeters}/recentSeconds else 0.0;Column(Modifier.fillMaxSize().padding(bottom=120.dp)){RunMap(points,Modifier.fillMaxWidth().weight(1f));Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(if(run.status=="PAUSED")"Run paused" else "Run in progress",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(points.lastOrNull()?.let{"GPS ±${it.accuracyMeters.toInt()} m · ${if(run.serverSynced)"synchronized" else "recording locally"}"}?:"Waiting for an accurate GPS signal",color=MaterialTheme.colorScheme.onSurfaceVariant);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Distance","%.2f km".format(run.distanceMeters/1000));Metric("Time",duration(run.activeDurationMillis));Metric("Avg pace",pace(run.averagePaceSecondsPerKm))};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Speed","%.1f km/h".format(currentSpeed*3.6));Metric("Current pace",if(currentSpeed>=0.5)pace(1000/currentSpeed) else "--:--");Metric("Avg speed","%.1f km/h".format(run.averageSpeedMps*3.6))};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){Button({RunTrackingService.send(context,if(run.status=="ACTIVE")RunTrackingService.ACTION_PAUSE else RunTrackingService.ACTION_RESUME)},Modifier.weight(1f)){Icon(if(run.status=="ACTIVE")Icons.Default.Pause else Icons.Default.PlayArrow,null);Text(if(run.status=="ACTIVE")"Pause" else "Resume")};OutlinedButton({confirm=true},Modifier.weight(1f)){Icon(Icons.Default.Stop,null);Text("Finish")}}}};if(confirm)AlertDialog({confirm=false},title={Text("Finish this run?")},text={Text("The remaining GPS points will be synchronized in the background.")},confirmButton={Button({confirm=false;RunTrackingService.send(context,RunTrackingService.ACTION_FINISH)}){Text("Finish")}},dismissButton={TextButton({confirm=false}){Text("Cancel")}})}

@Composable private fun RunDetail(detail:RunWithDetails,back:()->Unit,delete:()->Unit){var confirm by remember{mutableStateOf(false)};Column(Modifier.fillMaxSize().padding(bottom=110.dp)){RunMap(detail.points,Modifier.fillMaxWidth().height(310.dp));LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Row(verticalAlignment=Alignment.CenterVertically){IconButton(back){Icon(Icons.Default.ArrowBack,"Back")};Text("Run details",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)}};item{Text(date(detail.session.startedAt));Text("%.2f km · ${duration(detail.session.activeDurationMillis)}".format(detail.session.distanceMeters/1000),style=MaterialTheme.typography.titleLarge);Text("Average pace ${pace(detail.session.averagePaceSecondsPerKm)} /km");Text("Average speed %.1f km/h".format(detail.session.averageSpeedMps*3.6));Text("Estimated %.0f kcal".format(detail.session.caloriesKcal));Text("${detail.pauses.size} pauses")};item{OutlinedButton({confirm=true}){Icon(Icons.Default.Delete,null);Text("Delete run")}}}};if(confirm)AlertDialog({confirm=false},title={Text("Delete this run?")},confirmButton={Button(delete){Text("Delete")}},dismissButton={TextButton({confirm=false}){Text("Cancel")}})}

@Composable private fun RunMap(points:List<RunPointEntity>,modifier:Modifier){
    val path=points.map{LatLng(it.latitude,it.longitude)}
    Box(modifier){
        AndroidView(
            factory={context->MapView(context).also{view->
                view.setTag(R.id.run_map_path,path)
                view.getMapAsync{map->map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")){style->
                    @Suppress("UNCHECKED_CAST")
                    val latest=view.getTag(R.id.run_map_path) as? List<LatLng>?:emptyList()
                    updateRunMap(view,map,style,latest)
                }}
            }},
            update={view->view.setTag(R.id.run_map_path,path);view.getMapAsync{map->map.style?.let{updateRunMap(view,map,it,path)}}},
            modifier=Modifier.fillMaxSize()
        )
        if(path.isEmpty())Surface(Modifier.align(Alignment.Center),shape=MaterialTheme.shapes.medium){Text("Waiting for GPS…",Modifier.padding(12.dp))}
    }
}
private fun updateRunMap(view:MapView,map:org.maplibre.android.maps.MapLibreMap,style:Style,path:List<LatLng>){
    listOf("run-route","run-start","run-finish").forEach(style::removeLayer)
    listOf("run-route-source","run-start-source","run-finish-source").forEach(style::removeSource)
    if(path.isEmpty())return
    val points=path.map{Point.fromLngLat(it.longitude,it.latitude)}
    if(points.size>1){style.addSource(GeoJsonSource("run-route-source",Feature.fromGeometry(LineString.fromLngLats(points))));style.addLayer(LineLayer("run-route","run-route-source").withProperties(lineColor("#356AE6"),lineWidth(5f)))}
    style.addSource(GeoJsonSource("run-start-source",Feature.fromGeometry(points.first())));style.addLayer(CircleLayer("run-start","run-start-source").withProperties(circleColor("#25B45B"),circleRadius(8f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(2f)))
    style.addSource(GeoJsonSource("run-finish-source",Feature.fromGeometry(points.last())));style.addLayer(CircleLayer("run-finish","run-finish-source").withProperties(circleColor("#E53935"),circleRadius(8f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(2f)))
    view.post { if(path.size==1)map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(path.first()).zoom(16.0).build())) else map.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(path).build(),96)) }
}
@Composable private fun Metric(label:String,value:String){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,fontWeight=FontWeight.Bold)}}
private fun duration(ms:Long)="%02d:%02d:%02d".format(ms/3600000,(ms/60000)%60,(ms/1000)%60)
private fun pace(value:Double?)=value?.let{"%d:%02d".format((it/60).toInt(),it.toInt()%60)}?:"--:--"
private fun date(ms:Long)=DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
