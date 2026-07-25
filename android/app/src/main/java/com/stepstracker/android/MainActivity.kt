package com.stepstracker.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.stepstracker.android.data.*
import com.stepstracker.android.tracking.StepTrackingManager
import com.stepstracker.android.tracking.TrackingSource
import com.stepstracker.android.widget.StepWidgetProvider
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class MainActivity:ComponentActivity() {
    private lateinit var tracking:StepTrackingManager
    private val model by viewModels<AppViewModel> { AppViewModel.factory(application as StepsTrackerApp) }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);tracking=StepTrackingManager(this,(application as StepsTrackerApp).steps,(application as StepsTrackerApp).trackingSettings)
        setContent { StepsTheme { App(model,tracking) } }
    }
    override fun onStart() { super.onStart();lifecycleScope.launch { tracking.start();model.refresh();StepWidgetProvider.requestUpdate(this@MainActivity) } }
    override fun onStop() { tracking.stopSensor();super.onStop() }
}

data class UiState(val loggedIn:Boolean=false,val loading:Boolean=false,val error:String?=null,val serverUrl:String="",val me:Me?=null,val intervals:List<StepIntervalEntity> = emptyList(),val daily:List<DailyPoint> = emptyList(),val timeOfDay:List<TimePoint> = emptyList(),val weights:List<WeightEntry> = emptyList(),val tab:Int=0,val selectedDate:LocalDate=LocalDate.now(),val trackingSource:TrackingPreference=TrackingPreference.AUTO,val localDaily:Map<String,Long> = emptyMap(),val lastSync:String?=null,val rangeStart:LocalDate=LocalDate.now(),val statsRange:Int?=null)

class AppViewModel(private val app:StepsTrackerApp):ViewModel() {
    private val mutable=MutableStateFlow(UiState(loggedIn=app.session.isLoggedIn,serverUrl=app.server.baseUrl,trackingSource=app.trackingSettings.preference,me=app.cache.me,weights=app.cache.weights,lastSync=app.cache.lastSyncServerTime));val state=mutable.asStateFlow()
    init {
        viewModelScope.launch { app.steps.observeDay(LocalDate.now()).collect { values->mutable.update { it.copy(intervals=values) } } }
        if(app.session.isLoggedIn)refresh()
    }
    fun auth(email:String,password:String,register:Boolean,serverUrl:String)=launch { switchServer(serverUrl);if(register)app.api.register(email,password)else app.api.login(email,password);mutable.update { it.copy(loggedIn=true,serverUrl=app.server.baseUrl) };refresh() }
    fun refresh()=launch {
        if(!app.session.isLoggedIn)return@launch
        val today=LocalDate.now()
        // Range and today's numbers come from the local DB, so they are available with no connection.
        val earliest=app.steps.earliestDate() ?: today.minusDays(29)
        mutable.update { it.copy(localDaily=app.steps.dailySteps(earliest),rangeStart=earliest) }
        // Push pending intervals first, then remember when the server last accepted our data.
        runCatching { app.steps.sync() };mutable.update { it.copy(lastSync=app.cache.lastSyncServerTime) }
        // Backend stats are aggregated per day; keep the download inside the 366-day server limit.
        val queryStart=if(earliest.isBefore(today.minusDays(365)))today.minusDays(365) else earliest
        val me=app.api.me();app.cache.me=me;mutable.update { it.copy(me=me) }
        val intervals=app.steps.observeDay(today).first()
        val daily=if(me.profile!=null)app.api.daily(queryStart.toString(),today.toString())else emptyList()
        val time=if(me.profile!=null)app.api.timeOfDay(queryStart.toString(),today.toString())else emptyList()
        val weights=if(me.profile!=null)app.api.weightHistory()else emptyList();if(me.profile!=null)app.cache.weights=weights
        mutable.update { it.copy(intervals=intervals,daily=daily,timeOfDay=time,weights=weights,localDaily=app.steps.dailySteps(earliest)) }
    }
    fun profile(weight:Double,height:Double,birth:String,sex:String)=launch { app.api.profile(ProfileRequest(weight,height,birth,sex,ZoneId.systemDefault().id));refresh() }
    // Log today's weight, but only persist when it actually changes (mirrors the backend's >=0.005 guard).
    fun logWeight(weight:Double)=launch { val p=state.value.me?.profile ?: return@launch;if(kotlin.math.abs(p.weightKg-weight)>=0.005){ app.api.profile(ProfileRequest(weight,p.heightCm,p.birthDate,p.sex,p.timezone));refresh() } }
    // Remove a mistaken weigh-in; the backend repairs calories and resets the current weight to the latest remaining entry.
    fun deleteWeight(effectiveAt:String)=launch { app.api.deleteWeight(effectiveAt);refresh() }
    // Edit a weigh-in's value (wrong entry); the backend repairs calories for the affected range.
    fun editWeight(effectiveAt:String,weightKg:Double)=launch { app.api.updateWeight(effectiveAt,weightKg);refresh() }
    fun logout()=launch { app.api.logout();app.cache.clear();mutable.value=UiState(serverUrl=app.server.baseUrl,trackingSource=app.trackingSettings.preference) }
    fun delete()=launch { app.api.deleteAccount();app.database.intervals().clear();app.cache.clear();mutable.value=UiState(serverUrl=app.server.baseUrl,trackingSource=app.trackingSettings.preference) }
    fun changeServer(value:String)=launch { switchServer(value);mutable.value=UiState(serverUrl=app.server.baseUrl,trackingSource=app.trackingSettings.preference) }
    fun setTrackingSource(value:TrackingPreference){app.trackingSettings.preference=value;mutable.update { it.copy(trackingSource=value) }}
    fun statsRange(days:Int?){mutable.update { it.copy(statsRange=days) }}
    private suspend fun switchServer(value:String) { if(app.server.save(value)){app.session.clear();app.cache.clear();app.database.intervals().clear()} }
    fun tab(value:Int){mutable.update { it.copy(tab=value) }}
    fun shiftDay(days:Long)=mutable.update { st->val today=LocalDate.now();val target=st.selectedDate.plusDays(days);if(target.isAfter(today)||target.isBefore(st.rangeStart))st else st.copy(selectedDate=target) }
    fun selectDate(date:LocalDate)=mutable.update { st->val today=LocalDate.now();if(date.isAfter(today)||date.isBefore(st.rangeStart))st else st.copy(selectedDate=date) }
    private fun launch(block:suspend()->Unit)=viewModelScope.launch { mutable.update { it.copy(loading=true,error=null) };runCatching { block() }.onFailure { e->mutable.update { it.copy(error=e.message) } };mutable.update { it.copy(loading=false) } }
    companion object { fun factory(app:StepsTrackerApp)=object:ViewModelProvider.Factory { override fun <T:ViewModel> create(c:Class<T>):T=AppViewModel(app) as T } }
}

@Composable fun App(model:AppViewModel,tracking:StepTrackingManager) {
    val state by model.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(),color=Color.Transparent,contentColor=MaterialTheme.colorScheme.onBackground) {
        GradientBackground {
            when { !state.loggedIn->AuthScreen(state,model::auth);state.me?.profile==null->Onboarding(state,tracking,model::profile);else->MainScreen(state,tracking,model) }
        }
    }
}

// Dark green-tinted gradient (light in day mode) that all screens sit on, matching the glass look.
@Composable private fun GradientBackground(content:@Composable BoxScope.()->Unit) {
    val colors=if(isSystemInDarkTheme())listOf(Color(0xFF12291F),Color(0xFF0B0F0D)) else listOf(Color(0xFFDCEFE4),Color(0xFFF3FAF5))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colors)),content=content)
}

// Translucent rounded card with a hairline border — the glass surface used across the app.
@Composable private fun GlassCard(modifier:Modifier=Modifier,content:@Composable ()->Unit) =
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=0.55f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.onSurface.copy(alpha=0.10f)),content=content)

@Composable private fun AuthScreen(state:UiState,onSubmit:(String,String,Boolean,String)->Unit) {
    var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var confirmation by remember{mutableStateOf("")};var register by remember{mutableStateOf(false)};var serverUrl by remember(state.serverUrl){mutableStateOf(state.serverUrl)}
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center) {
        Image(painterResource(R.drawable.stepstracker_logo),"StepsTracker logo",Modifier.size(72.dp))
        Text("StepsTracker",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text(if(register)"Create your account" else "Welcome back",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp));OutlinedTextField(serverUrl,{serverUrl=it},Modifier.fillMaxWidth(),label={Text("Server URL")},supportingText={Text("Example: http://192.168.1.10:8080/")},singleLine=true)
        Spacer(Modifier.height(24.dp));OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text("Email")},singleLine=true)
        Spacer(Modifier.height(12.dp));OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Password")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        if(register) { Spacer(Modifier.height(12.dp));OutlinedTextField(confirmation,{confirmation=it},Modifier.fillMaxWidth(),label={Text("Confirm password")},visualTransformation=PasswordVisualTransformation(),singleLine=true) }
        if(register&&confirmation.isNotEmpty()&&password!=confirmation)Text("Passwords do not match",color=MaterialTheme.colorScheme.error)
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp)) }
        Button({onSubmit(email,password,register,serverUrl)},Modifier.fillMaxWidth().padding(top=20.dp),enabled=!state.loading&&serverUrl.isNotBlank()&&email.isNotBlank()&&password.length>=4&&(!register||password==confirmation)) { Text(if(register)"Sign up" else "Sign in") }
        TextButton({register=!register},Modifier.align(Alignment.CenterHorizontally)) { Text(if(register)"Already have an account? Sign in" else "Don't have an account? Sign up") }
    }
}

@Composable private fun Onboarding(state:UiState,tracking:StepTrackingManager,onSave:(Double,Double,String,String)->Unit) {
    var weight by remember{mutableStateOf("")};var height by remember{mutableStateOf("")};var birth by remember{mutableStateOf("")};var sex by remember{mutableStateOf("OTHER")}
    val scope=rememberCoroutineScope()
    val activityPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){scope.launch{tracking.start()}}
    val healthPermission=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){scope.launch{tracking.start()}}
    LazyColumn(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Set up your profile",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("This information is used only to estimate distance and calories.") }
        item { OutlinedTextField(weight,{weight=it},Modifier.fillMaxWidth(),label={Text("Weight (kg)")}) }
        item { OutlinedTextField(height,{height=it},Modifier.fillMaxWidth(),label={Text("Height (cm)")}) }
        item { OutlinedTextField(birth,{birth=it},Modifier.fillMaxWidth(),label={Text("Date of birth (YYYY-MM-DD)")}) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("FEMALE" to "Female","MALE" to "Male","OTHER" to "Other").forEach { (key,label)->FilterChip(sex==key,{sex=key},{Text(label)}) } } }
        item { OutlinedButton({healthPermission.launch(tracking.healthPermissions())},Modifier.fillMaxWidth()) { Icon(Icons.Default.Favorite,null);Spacer(Modifier.width(8.dp));Text("Allow Health Connect") } }
        item { TextButton({activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)}) { Text("Allow fallback sensor") } }
        item { Button({onSave(weight.toDouble(),height.toDouble(),birth,sex)},Modifier.fillMaxWidth(),enabled=!state.loading&&weight.toDoubleOrNull()!=null&&height.toDoubleOrNull()!=null&&birth.length==10) { Text("Get started") } }
        state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
    }
}

@Composable private fun MainScreen(state:UiState,tracking:StepTrackingManager,model:AppViewModel) {
    val scope=rememberCoroutineScope()
    val activityPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ scope.launch { tracking.start();model.refresh() } }
    val healthPermission=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){ scope.launch { tracking.start();model.refresh() } }
    val onSourceChange:(TrackingPreference)->Unit={ pref->model.setTrackingSource(pref);scope.launch {
        if(tracking.start()==TrackingSource.UNAVAILABLE) {
            if(pref==TrackingPreference.DEVICE_SENSOR)activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION) else healthPermission.launch(tracking.healthPermissions())
        } else model.refresh()
    } }
    var showWeight by remember{mutableStateOf(false)}
    val haze=remember{HazeState()}
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) { when(state.tab) { 0->Home(state,tracking,model);1->Stats(state,model::statsRange);else->ProfileScreen(state,model,onSourceChange) } }
        FloatingActionButton({showWeight=true},Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=104.dp),containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary) { Icon(Icons.Default.MonitorWeight,"Log weight") }
        GlassPillNav(state.tab,model::tab,haze,Modifier.align(Alignment.BottomCenter).padding(bottom=24.dp))
    }
    if(showWeight)state.me?.profile?.let { LogWeightDialog(it.weightKg,{showWeight=false}) { w->model.logWeight(w);showWeight=false } }
}

// Floating pill navigation with a real backdrop blur (Haze) over the scrolling content behind it.
@Composable private fun GlassPillNav(selected:Int,onSelect:(Int)->Unit,haze:HazeState,modifier:Modifier=Modifier) {
    val items=listOf(Icons.Default.Home to "Today",Icons.Default.BarChart to "Stats",Icons.Default.Person to "Profile")
    val bg=MaterialTheme.colorScheme.background
    Surface(modifier.clip(RoundedCornerShape(32.dp)).hazeChild(haze){ backgroundColor=bg;blurRadius=24.dp },shape=RoundedCornerShape(32.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=0.45f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.onSurface.copy(alpha=0.14f))) {
        Row(Modifier.padding(horizontal=10.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i,(icon,label)->
                val tint=if(selected==i)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.clip(RoundedCornerShape(22.dp)).clickable{onSelect(i)}.padding(horizontal=18.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(icon,label,tint=tint);Text(label,style=MaterialTheme.typography.labelSmall,color=tint)
                }
            }
        }
    }
}

@Composable private fun Home(state:UiState,tracking:StepTrackingManager,model:AppViewModel) {
    val today=LocalDate.now();val selected=state.selectedDate;val isToday=selected==today
    val profile=state.me!!.profile!!;val stride=profile.heightCm/100*(if(profile.sex=="FEMALE")0.413 else 0.415)
    val days=(java.time.temporal.ChronoUnit.DAYS.between(state.rangeStart,today)+1).toInt().coerceAtLeast(1)
    val window=(0 until days).map { state.rangeStart.plusDays(it.toLong()) };val byDate=state.daily.associateBy { it.date }
    val serverPoint=byDate[selected.toString()];val localSteps=state.localDaily[selected.toString()]
    val steps:Long=when { isToday->state.intervals.sumOf { it.steps }.toLong();localSteps!=null->localSteps;else->serverPoint?.steps ?: 0L }
    val fromLocal=isToday||localSteps!=null
    val distance=if(fromLocal)steps*stride else serverPoint?.distanceMeters ?: 0.0
    val kcal=if(fromLocal)distance/1000*profile.weightKg*0.75 else serverPoint?.estimatedKcal ?: 0.0
    val kcals=window.map { d->val ls=state.localDaily[d.toString()];if(ls!=null)ls*stride/1000*profile.weightKg*0.75 else byDate[d.toString()]?.estimatedKcal ?: 0.0 }
    val selectedIndex=window.indexOf(selected).let { if(it<0)window.lastIndex else it }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=20.dp,bottom=130.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { DayNavigator(selected,today,state.rangeStart,{model.shiftDay(-1)},{model.shiftDay(1)}) }
        item { MetricCard("Steps",steps.toString(),Icons.Default.DirectionsWalk) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f)){MetricCard("Distance","%.2f km".format(distance/1000),Icons.Default.Route)};Box(Modifier.weight(1f)){MetricCard("Estimated kcal","%.0f".format(kcal),Icons.Default.LocalFireDepartment)} } }
        item { GlassCard { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("Estimated kcal · last $days days",fontWeight=FontWeight.Bold);Text("Drag or tap the chart to browse days",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(14.dp));DailyNavChart(kcals,selectedIndex,{ model.selectDate(window[it]) },Modifier.fillMaxWidth().height(160.dp)) } } }
        if(isToday)item { GlassCard { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("Collection",fontWeight=FontWeight.Bold);Text(tracking.source.name.replace('_',' '));if(tracking.source==TrackingSource.UNAVAILABLE)Text("No permission granted — pick a data source in Profile",color=MaterialTheme.colorScheme.error) else Text(if(state.intervals.any{!it.synced})"Sync pending" else "Data synchronized",color=MaterialTheme.colorScheme.onSurfaceVariant);lastSyncLabel(state.lastSync)?.let { Text("Last update: $it",color=MaterialTheme.colorScheme.onSurfaceVariant) } } } }
    }
}

@Composable private fun DayNavigator(selected:LocalDate,today:LocalDate,minDate:LocalDate,onPrevious:()->Unit,onNext:()->Unit) {
    val label=when(selected){today->"Today";today.minusDays(1)->"Yesterday";else->selected.toString()}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        IconButton(onPrevious,enabled=selected.isAfter(minDate)) { Icon(Icons.Default.ChevronLeft,"Previous day") }
        Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(label,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text(selected.toString(),color=MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onNext,enabled=selected.isBefore(today)) { Icon(Icons.Default.ChevronRight,"Next day") }
    }
}

@Composable private fun DailyNavChart(values:List<Double>,selectedIndex:Int,onSelectIndex:(Int)->Unit,modifier:Modifier) {
    val primary=MaterialTheme.colorScheme.primary;val faint=primary.copy(alpha=0.28f);val n=values.size
    Canvas(modifier
        .pointerInput(n){ detectTapGestures { pos->if(n>0)onSelectIndex((pos.x/size.width*n).toInt().coerceIn(0,n-1)) } }
        .pointerInput(n){ detectHorizontalDragGestures { change,_->if(n>0)onSelectIndex((change.position.x/size.width*n).toInt().coerceIn(0,n-1)) } }
    ) {
        if(n==0)return@Canvas
        val max=(values.maxOrNull() ?: 0.0).coerceAtLeast(1.0);val slot=size.width/n;val barW=slot*0.6f
        values.forEachIndexed { i,v->
            val h=(v/max*size.height).toFloat();val x=slot*i+(slot-barW)/2
            drawRect(if(i==selectedIndex)primary else faint,Offset(x,size.height-h),Size(barW,h))
        }
    }
}

@Composable private fun MetricCard(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector) { GlassCard { Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) { Icon(icon,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Column { Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) } } } }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Stats(state:UiState,onRange:(Int?)->Unit) {
    val today=LocalDate.now()
    val start=(state.statsRange?.let { today.minusDays((it-1).toLong()) } ?: state.rangeStart).let { if(it.isBefore(state.rangeStart))state.rangeStart else it }
    // Merge local (offline/unsynced) and server daily totals, exactly like Home, so stats render before a sync too.
    val profile=state.me?.profile;val stride=if(profile!=null)profile.heightCm/100*(if(profile.sex=="FEMALE")0.413 else 0.415) else 0.0;val weight=profile?.weightKg ?: 0.0
    val server=state.daily.associateBy { it.date }
    val n=(java.time.temporal.ChronoUnit.DAYS.between(start,today)+1).toInt().coerceAtLeast(1)
    val daily=(0 until n).mapNotNull { i->val d=start.plusDays(i.toLong());val key=d.toString();val ls=state.localDaily[key];val sp=server[key];when { ls!=null->{ val dist=ls*stride;DailyPoint(key,ls,dist,dist/1000*weight*0.75) };sp!=null->sp;else->null } }
    val rangeLabel=state.statsRange?.let { "Last $it days" } ?: "All time"
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=20.dp,bottom=130.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("Statistics",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold) }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(7 to "7d",30 to "30d",90 to "90d",null to "All").forEach { (d,l)->FilterChip(state.statsRange==d,{onRange(d)},{Text(l)}) } } }
        if(daily.isNotEmpty())item { MetricCard("Daily average","%.0f steps".format(daily.sumOf { it.steps }.toDouble()/daily.size),Icons.Default.TrendingUp) }
        item { GlassCard { Column(Modifier.padding(16.dp)) { Text("Steps · $rangeLabel",fontWeight=FontWeight.Bold);Spacer(Modifier.height(16.dp));StepChart(daily,Modifier.fillMaxWidth().height(180.dp)) } } }
        if(state.weights.size>=2)item { GlassCard { Column(Modifier.padding(16.dp)) { Text("Weight",fontWeight=FontWeight.Bold);Text("%.1f kg → %.1f kg".format(state.weights.last().weightKg,state.weights.first().weightKg),color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(16.dp));WeightChart(state.weights.reversed(),Modifier.fillMaxWidth().height(160.dp)) } } }
        item { GlassCard { Column(Modifier.padding(16.dp)) { Text("Time of day",fontWeight=FontWeight.Bold);Text("Average steps per quarter hour",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));QuarterChart(state.timeOfDay,Modifier.fillMaxWidth().height(140.dp)) } } }
    }
}

@Composable private fun StepChart(points:List<DailyPoint>,modifier:Modifier) { val primary=MaterialTheme.colorScheme.primary;Canvas(modifier) { if(points.isEmpty())return@Canvas;val max=points.maxOf { it.steps }.coerceAtLeast(1);val dx=size.width/(points.size.coerceAtLeast(2)-1);points.zipWithNext().forEachIndexed { i,(a,b)->drawLine(primary,Offset(i*dx,size.height-a.steps.toFloat()/max*size.height),Offset((i+1)*dx,size.height-b.steps.toFloat()/max*size.height),5f) } } }
@Composable private fun QuarterChart(points:List<TimePoint>,modifier:Modifier) { val color=MaterialTheme.colorScheme.secondary;Canvas(modifier) { val max=(points.maxOfOrNull { it.steps } ?: 1.0).coerceAtLeast(1.0);points.forEach { p->val x=p.quarterHour/96f*size.width;drawLine(color,Offset(x,size.height),Offset(x,size.height-(p.steps/max*size.height).toFloat()),size.width/110) } } }
// Weight line chart scaled to the observed [min,max] so small changes stay visible. Expects chronological order.
// When onSelectIndex is provided the chart is interactive: tap/drag to pick a point (highlighted).
@Composable private fun WeightChart(points:List<WeightEntry>,modifier:Modifier,selectedIndex:Int=-1,onSelectIndex:((Int)->Unit)?=null) {
    val primary=MaterialTheme.colorScheme.primary;val n=points.size
    var base=modifier
    if(onSelectIndex!=null&&n>0)base=base.pointerInput(n){ detectTapGestures { pos->onSelectIndex((pos.x/size.width*(n-1)).roundToInt().coerceIn(0,n-1)) } }.pointerInput(n){ detectHorizontalDragGestures { change,_->onSelectIndex((change.position.x/size.width*(n-1)).roundToInt().coerceIn(0,n-1)) } }
    Canvas(base) {
        if(n<2)return@Canvas
        val ws=points.map { it.weightKg };val min=ws.min();val span=(ws.max()-min).coerceAtLeast(0.5);val dx=size.width/(n-1)
        val y={ w:Double->(size.height-((w-min)/span*size.height)).toFloat() }
        points.zipWithNext().forEachIndexed { i,(a,b)->drawLine(primary,Offset(i*dx,y(a.weightKg)),Offset((i+1)*dx,y(b.weightKg)),6f) }
        points.forEachIndexed { i,p->val c=Offset(i*dx,y(p.weightKg));if(i==selectedIndex)drawCircle(primary.copy(alpha=0.25f),28f,c);drawCircle(primary,if(i==selectedIndex)13f else 7f,c) }
    }
}

// Card section with a title, used to keep the Profile screen tidy.
@Composable private fun SectionCard(title:String,content:@Composable ColumnScope.()->Unit) = GlassCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium);content() } }
@Composable private fun InfoRow(label:String,value:String) = Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) { Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,fontWeight=FontWeight.Medium) }

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ProfileScreen(state:UiState,model:AppViewModel,onSourceChange:(TrackingPreference)->Unit) { var confirm by remember{mutableStateOf(false)};var edit by remember{mutableStateOf(false)};var editServer by remember{mutableStateOf(false)};Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,top=20.dp,bottom=130.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    Text("Profile",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
    SectionCard("Account") {
        InfoRow("Email",state.me?.email.orEmpty())
        state.me?.profile?.let { InfoRow("Weight & height","${it.weightKg} kg · ${it.heightCm} cm");InfoRow("Time zone",it.timezone) }
        lastSyncLabel(state.lastSync)?.let { InfoRow("Last update",it) }
    }
    if(state.weights.isNotEmpty())SectionCard("Weight") {
        val chrono=state.weights.reversed()
        var sel by remember(state.weights){ mutableStateOf(chrono.lastIndex) }
        var editing by remember(state.weights){ mutableStateOf<WeightEntry?>(null) }
        if(chrono.size>=2)WeightChart(chrono,Modifier.fillMaxWidth().height(140.dp),sel) { sel=it }
        (chrono.getOrNull(sel) ?: chrono.lastOrNull())?.let { w->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Column { Text("${w.weightKg} kg",fontWeight=FontWeight.Medium);Text(w.effectiveAt.take(10),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
            Row { IconButton({editing=w},enabled=!state.loading) { Icon(Icons.Default.Edit,"Edit weigh-in",tint=MaterialTheme.colorScheme.primary) };IconButton({model.deleteWeight(w.effectiveAt)},enabled=!state.loading) { Icon(Icons.Default.Delete,"Delete weigh-in",tint=MaterialTheme.colorScheme.error) } }
        } }
        if(chrono.size>=2)Text("Tap a point on the chart to select it, then edit or delete",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        editing?.let { w->LogWeightDialog(w.weightKg,{editing=null},"Edit weigh-in") { nw->model.editWeight(w.effectiveAt,nw);editing=null } }
    }
    SectionCard("Data source") {
        Text("Health Connect aggregates whole-day steps; the device sensor reads the phone's step counter directly.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(TrackingPreference.AUTO to "Automatic",TrackingPreference.HEALTH_CONNECT to "Health Connect",TrackingPreference.DEVICE_SENSOR to "Device sensor").forEach { (pref,label)->FilterChip(state.trackingSource==pref,{onSourceChange(pref)},{Text(label)}) } }
    }
    SectionCard("Server & account") {
        Text("Server: ${state.serverUrl}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton({editServer=true},Modifier.fillMaxWidth()) { Text("Change server") };OutlinedButton({edit=true},Modifier.fillMaxWidth()) { Text("Edit physical data") };OutlinedButton(model::logout,Modifier.fillMaxWidth()) { Text("Sign out") };TextButton({confirm=true},Modifier.fillMaxWidth()) { Text("Delete account",color=MaterialTheme.colorScheme.error) }
    }
    if(confirm)AlertDialog({confirm=false},{Button({model.delete();confirm=false}){Text("Delete")}},dismissButton={TextButton({confirm=false}){Text("Cancel")}},title={Text("Delete account?")},text={Text("All data will be permanently deleted.")})
    if(edit)EditProfileDialog(state.me!!.profile!!,{edit=false}) { weight,height->val p=state.me.profile!!;model.profile(weight,height,p.birthDate,p.sex);edit=false }
    if(editServer)ServerDialog(state.serverUrl,{editServer=false}) { model.changeServer(it);editServer=false }
} }

@Composable private fun ServerDialog(current:String,onDismiss:()->Unit,onSave:(String)->Unit) { var value by remember{mutableStateOf(current)};AlertDialog(onDismiss,{Button({onSave(value)},enabled=value.isNotBlank()){Text("Save and sign out")}},dismissButton={TextButton(onDismiss){Text("Cancel")}},title={Text("Server URL")},text={OutlinedTextField(value,{value=it},singleLine=true,label={Text("Base URL")},supportingText={Text("Changing server clears the local session and cache.")})}) }

@Composable private fun EditProfileDialog(profile:Profile,onDismiss:()->Unit,onSave:(Double,Double)->Unit) { var weight by remember{mutableStateOf(profile.weightKg.toString())};var height by remember{mutableStateOf(profile.heightCm.toString())};AlertDialog(onDismiss,{Button({onSave(weight.toDouble(),height.toDouble())},enabled=weight.toDoubleOrNull()!=null&&height.toDoubleOrNull()!=null){Text("Save")}},dismissButton={TextButton(onDismiss){Text("Cancel")}},title={Text("Physical data")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(weight,{weight=it},label={Text("Weight (kg)")});OutlinedTextField(height,{height=it},label={Text("Height (cm)")})}}) }

// Log today's weight. Single field defaulting to the current weight; the ViewModel only saves when it changes.
@Composable private fun LogWeightDialog(current:Double,onDismiss:()->Unit,title:String="Log today's weight",onSave:(Double)->Unit) { var weight by remember{mutableStateOf(current.toString())};AlertDialog(onDismiss,{Button({onSave(weight.toDouble())},enabled=weight.toDoubleOrNull()!=null){Text("Save")}},dismissButton={TextButton(onDismiss){Text("Cancel")}},title={Text(title)},text={OutlinedTextField(weight,{weight=it},singleLine=true,label={Text("Weight (kg)")})}) }

// Human-readable local time of the last successful sync (BatchResult.serverTime), or null if never synced.
private fun lastSyncLabel(iso:String?):String? = iso?.let { runCatching { val t=Instant.parse(it).atZone(ZoneId.systemDefault());val time="%02d:%02d".format(t.hour,t.minute);if(t.toLocalDate()==LocalDate.now())"today $time" else "${t.toLocalDate()} $time" }.getOrNull() }

@Composable private fun StepsTheme(content: @Composable () -> Unit) {
    val colors=if(isSystemInDarkTheme())
        darkColorScheme(primary=Color(0xFF5FE0A5),onPrimary=Color(0xFF003824),secondary=Color(0xFF9FD9BC),surface=Color(0xFF141C18),onSurface=Color(0xFFE2EDE6),background=Color(0xFF0B0F0D),onBackground=Color(0xFFE2EDE6),surfaceVariant=Color(0xFF1E2823),onSurfaceVariant=Color(0xFFAAC1B3))
    else
        lightColorScheme(primary=Color(0xFF006C4C),onPrimary=Color.White,secondary=Color(0xFF4D6358),surface=Color(0xFFF3FAF5),onSurface=Color(0xFF0F1512),background=Color(0xFFEFF6F1),onBackground=Color(0xFF0F1512),surfaceVariant=Color(0xFFDCE7DF),onSurfaceVariant=Color(0xFF41493F))
    MaterialTheme(colorScheme=colors,content=content)
}
