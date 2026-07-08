package com.stepstracker.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MainActivity:ComponentActivity() {
    private lateinit var tracking:StepTrackingManager
    private val model by viewModels<AppViewModel> { AppViewModel.factory(application as StepsTrackerApp) }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);tracking=StepTrackingManager(this,(application as StepsTrackerApp).steps)
        setContent { StepsTheme { App(model,tracking) } }
    }
    override fun onStart() { super.onStart();lifecycleScope.launch { tracking.start();model.refresh() } }
    override fun onStop() { tracking.stopSensor();super.onStop() }
}

data class UiState(val loggedIn:Boolean=false,val loading:Boolean=false,val error:String?=null,val serverUrl:String="",val me:Me?=null,val intervals:List<StepIntervalEntity> = emptyList(),val daily:List<DailyPoint> = emptyList(),val timeOfDay:List<TimePoint> = emptyList(),val weights:List<WeightEntry> = emptyList(),val summary:Summary?=null,val tab:Int=0)

class AppViewModel(private val app:StepsTrackerApp):ViewModel() {
    private val mutable=MutableStateFlow(UiState(loggedIn=app.session.isLoggedIn,serverUrl=app.server.baseUrl));val state=mutable.asStateFlow()
    init {
        viewModelScope.launch { app.steps.observeDay(LocalDate.now()).collect { values->mutable.update { it.copy(intervals=values) } } }
        if(app.session.isLoggedIn)refresh()
    }
    fun auth(email:String,password:String,register:Boolean,serverUrl:String)=launch { switchServer(serverUrl);if(register)app.api.register(email,password)else app.api.login(email,password);mutable.update { it.copy(loggedIn=true,serverUrl=app.server.baseUrl) };refresh() }
    fun refresh()=launch {
        if(!app.session.isLoggedIn)return@launch
        val me=app.api.me();mutable.update { it.copy(me=me) }
        val today=LocalDate.now();val intervals=app.steps.observeDay(today).first();val daily=if(me.profile!=null)app.api.daily(today.minusDays(29).toString(),today.toString())else emptyList();val time=if(me.profile!=null)app.api.timeOfDay(today.minusDays(29).toString(),today.toString())else emptyList();val weights=if(me.profile!=null)app.api.weightHistory()else emptyList();val summary=if(me.profile!=null)app.api.summary(today.minusDays(6).toString(),today.toString())else null
        runCatching { app.steps.sync() };mutable.update { it.copy(intervals=intervals,daily=daily,timeOfDay=time,weights=weights,summary=summary) }
    }
    fun profile(weight:Double,height:Double,birth:String,sex:String)=launch { app.api.profile(ProfileRequest(weight,height,birth,sex,ZoneId.systemDefault().id));refresh() }
    fun logout()=launch { app.api.logout();mutable.value=UiState(serverUrl=app.server.baseUrl) }
    fun delete()=launch { app.api.deleteAccount();app.database.intervals().clear();mutable.value=UiState(serverUrl=app.server.baseUrl) }
    fun changeServer(value:String)=launch { switchServer(value);mutable.value=UiState(serverUrl=app.server.baseUrl) }
    private suspend fun switchServer(value:String) { if(app.server.save(value)){app.session.clear();app.database.intervals().clear()} }
    fun tab(value:Int){mutable.update { it.copy(tab=value) }}
    private fun launch(block:suspend()->Unit)=viewModelScope.launch { mutable.update { it.copy(loading=true,error=null) };runCatching { block() }.onFailure { e->mutable.update { it.copy(error=e.message) } };mutable.update { it.copy(loading=false) } }
    companion object { fun factory(app:StepsTrackerApp)=object:ViewModelProvider.Factory { override fun <T:ViewModel> create(c:Class<T>):T=AppViewModel(app) as T } }
}

@Composable fun App(model:AppViewModel,tracking:StepTrackingManager) {
    val state by model.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        when { !state.loggedIn->AuthScreen(state,model::auth);state.me?.profile==null->Onboarding(state,tracking,model::profile);else->MainScreen(state,tracking,model) }
    }
}

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
        Button({onSubmit(email,password,register,serverUrl)},Modifier.fillMaxWidth().padding(top=20.dp),enabled=!state.loading&&serverUrl.isNotBlank()&&email.isNotBlank()&&password.length>=10&&(!register||password==confirmation)) { Text(if(register)"Sign up" else "Sign in") }
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
    Scaffold(bottomBar={NavigationBar { listOf(Icons.Default.Home to "Today",Icons.Default.BarChart to "Statistics",Icons.Default.Person to "Profile").forEachIndexed { i,(icon,label)->NavigationBarItem(state.tab==i,{model.tab(i)},{Icon(icon,null)},label={Text(label)}) } }}) { padding->
        Box(Modifier.padding(padding)) { when(state.tab) { 0->Home(state,tracking);1->Stats(state);else->ProfileScreen(state,model) } }
    }
}

@Composable private fun Home(state:UiState,tracking:StepTrackingManager) {
    val steps=state.intervals.sumOf { it.steps };val profile=state.me!!.profile!!;val stride=profile.heightCm/100*(if(profile.sex=="FEMALE")0.413 else 0.415);val distance=steps*stride;val kcal=distance/1000*profile.weightKg*0.75
    LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("Today",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text(LocalDate.now().toString(),color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { MetricCard("Steps",steps.toString(),Icons.Default.DirectionsWalk) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f)){MetricCard("Distance","%.2f km".format(distance/1000),Icons.Default.Route)};Box(Modifier.weight(1f)){MetricCard("Estimated kcal","%.0f".format(kcal),Icons.Default.LocalFireDepartment)} } }
        item { Card { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("Collection",fontWeight=FontWeight.Bold);Text(tracking.source.name.replace('_',' '));Text(if(state.intervals.any{!it.synced})"Sync pending" else "Data synchronized",color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
}

@Composable private fun MetricCard(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector) { Card { Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) { Icon(icon,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Column { Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) } } } }

@Composable private fun Stats(state:UiState) { LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Text("Statistics",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold) }
    state.summary?.let { s->item { MetricCard("Daily average","%.0f steps".format(s.dailyAverage),Icons.Default.TrendingUp) } }
    item { Card { Column(Modifier.padding(16.dp)) { Text("Last 30 days",fontWeight=FontWeight.Bold);Spacer(Modifier.height(16.dp));StepChart(state.daily,Modifier.fillMaxWidth().height(180.dp)) } } }
    item { Card { Column(Modifier.padding(16.dp)) { Text("Time of day",fontWeight=FontWeight.Bold);Text("Average steps per quarter hour",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));QuarterChart(state.timeOfDay,Modifier.fillMaxWidth().height(140.dp)) } } }
    items(state.daily.takeLast(7).reversed()) { Text("${it.date}   ${it.steps} steps · %.0f estimated kcal".format(it.estimatedKcal)) }
} }

@Composable private fun StepChart(points:List<DailyPoint>,modifier:Modifier) { val primary=MaterialTheme.colorScheme.primary;Canvas(modifier) { if(points.isEmpty())return@Canvas;val max=points.maxOf { it.steps }.coerceAtLeast(1);val dx=size.width/(points.size.coerceAtLeast(2)-1);points.zipWithNext().forEachIndexed { i,(a,b)->drawLine(primary,Offset(i*dx,size.height-a.steps.toFloat()/max*size.height),Offset((i+1)*dx,size.height-b.steps.toFloat()/max*size.height),5f) } } }
@Composable private fun QuarterChart(points:List<TimePoint>,modifier:Modifier) { val color=MaterialTheme.colorScheme.secondary;Canvas(modifier) { val max=(points.maxOfOrNull { it.steps } ?: 1.0).coerceAtLeast(1.0);points.forEach { p->val x=p.quarterHour/96f*size.width;drawLine(color,Offset(x,size.height),Offset(x,size.height-(p.steps/max*size.height).toFloat()),size.width/110) } } }

@Composable private fun ProfileScreen(state:UiState,model:AppViewModel) { var confirm by remember{mutableStateOf(false)};var edit by remember{mutableStateOf(false)};var editServer by remember{mutableStateOf(false)};Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    Text("Profile",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text(state.me?.email.orEmpty());state.me?.profile?.let { Text("${it.weightKg} kg · ${it.heightCm} cm");Text("Time zone: ${it.timezone}") }
    if(state.weights.isNotEmpty()) { Text("Weight history",fontWeight=FontWeight.Bold);state.weights.take(5).forEach { Text("${it.weightKg} kg · ${it.effectiveAt.take(10)}",color=MaterialTheme.colorScheme.onSurfaceVariant) } }
    Text("Server: ${state.serverUrl}",color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedButton({editServer=true},Modifier.fillMaxWidth()) { Text("Change server") };OutlinedButton({edit=true},Modifier.fillMaxWidth()) { Text("Edit physical data") };OutlinedButton(model::logout,Modifier.fillMaxWidth()) { Text("Sign out") };TextButton({confirm=true},Modifier.fillMaxWidth()) { Text("Delete account",color=MaterialTheme.colorScheme.error) }
    if(confirm)AlertDialog({confirm=false},{Button({model.delete();confirm=false}){Text("Delete")}},dismissButton={TextButton({confirm=false}){Text("Cancel")}},title={Text("Delete account?")},text={Text("All data will be permanently deleted.")})
    if(edit)EditProfileDialog(state.me!!.profile!!,{edit=false}) { weight,height->val p=state.me.profile!!;model.profile(weight,height,p.birthDate,p.sex);edit=false }
    if(editServer)ServerDialog(state.serverUrl,{editServer=false}) { model.changeServer(it);editServer=false }
} }

@Composable private fun ServerDialog(current:String,onDismiss:()->Unit,onSave:(String)->Unit) { var value by remember{mutableStateOf(current)};AlertDialog(onDismiss,{Button({onSave(value)},enabled=value.isNotBlank()){Text("Save and sign out")}},dismissButton={TextButton(onDismiss){Text("Cancel")}},title={Text("Server URL")},text={OutlinedTextField(value,{value=it},singleLine=true,label={Text("Base URL")},supportingText={Text("Changing server clears the local session and cache.")})}) }

@Composable private fun EditProfileDialog(profile:Profile,onDismiss:()->Unit,onSave:(Double,Double)->Unit) { var weight by remember{mutableStateOf(profile.weightKg.toString())};var height by remember{mutableStateOf(profile.heightCm.toString())};AlertDialog(onDismiss,{Button({onSave(weight.toDouble(),height.toDouble())},enabled=weight.toDoubleOrNull()!=null&&height.toDoubleOrNull()!=null){Text("Save")}},dismissButton={TextButton(onDismiss){Text("Cancel")}},title={Text("Physical data")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(weight,{weight=it},label={Text("Weight (kg)")});OutlinedTextField(height,{height=it},label={Text("Height (cm)")})}}) }

@Composable private fun StepsTheme(content: @Composable () -> Unit) { val colors=if(isSystemInDarkTheme())darkColorScheme(primary=Color(0xFF62DDA8),secondary=Color(0xFFB4CCBE),surface=Color(0xFF101512),background=Color(0xFF101512))else lightColorScheme(primary=Color(0xFF006C4C),secondary=Color(0xFF4D6358),surface=Color(0xFFF8FBF8));MaterialTheme(colorScheme=colors,content=content) }
