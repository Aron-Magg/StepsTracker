package com.stepstracker.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
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

data class UiState(val loggedIn:Boolean=false,val loading:Boolean=false,val error:String?=null,val me:Me?=null,val intervals:List<StepIntervalEntity> = emptyList(),val daily:List<DailyPoint> = emptyList(),val timeOfDay:List<TimePoint> = emptyList(),val summary:Summary?=null,val tab:Int=0)

class AppViewModel(private val app:StepsTrackerApp):ViewModel() {
    private val mutable=MutableStateFlow(UiState(loggedIn=app.session.isLoggedIn));val state=mutable.asStateFlow()
    init {
        viewModelScope.launch { app.steps.observeDay(LocalDate.now()).collect { values->mutable.update { it.copy(intervals=values) } } }
        if(app.session.isLoggedIn)refresh()
    }
    fun auth(email:String,password:String,register:Boolean)=launch { if(register)app.api.register(email,password)else app.api.login(email,password);mutable.update { it.copy(loggedIn=true) };refresh() }
    fun refresh()=launch {
        if(!app.session.isLoggedIn)return@launch
        val me=app.api.me();mutable.update { it.copy(me=me) }
        val today=LocalDate.now();val intervals=app.steps.observeDay(today).first();val daily=if(me.profile!=null)app.api.daily(today.minusDays(29).toString(),today.toString())else emptyList();val time=if(me.profile!=null)app.api.timeOfDay(today.minusDays(29).toString(),today.toString())else emptyList();val summary=if(me.profile!=null)app.api.summary(today.minusDays(6).toString(),today.toString())else null
        runCatching { app.steps.sync() };mutable.update { it.copy(intervals=intervals,daily=daily,timeOfDay=time,summary=summary) }
    }
    fun profile(weight:Double,height:Double,birth:String,sex:String)=launch { app.api.profile(ProfileRequest(weight,height,birth,sex,ZoneId.systemDefault().id));refresh() }
    fun logout()=launch { app.api.logout();mutable.value=UiState() }
    fun delete()=launch { app.api.deleteAccount();app.database.intervals().clear();mutable.value=UiState() }
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

@Composable private fun AuthScreen(state:UiState,onSubmit:(String,String,Boolean)->Unit) {
    var email by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var register by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center) {
        Icon(Icons.Default.DirectionsWalk,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
        Text("StepsTracker",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text(if(register)"Crea il tuo account" else "Bentornato",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp));OutlinedTextField(email,{email=it},Modifier.fillMaxWidth(),label={Text("Email")},singleLine=true)
        Spacer(Modifier.height(12.dp));OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("Password")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp)) }
        Button({onSubmit(email,password,register)},Modifier.fillMaxWidth().padding(top=20.dp),enabled=!state.loading&&email.isNotBlank()&&password.length>=10) { Text(if(register)"Registrati" else "Accedi") }
        TextButton({register=!register},Modifier.align(Alignment.CenterHorizontally)) { Text(if(register)"Hai già un account? Accedi" else "Non hai un account? Registrati") }
    }
}

@Composable private fun Onboarding(state:UiState,tracking:StepTrackingManager,onSave:(Double,Double,String,String)->Unit) {
    var weight by remember{mutableStateOf("")};var height by remember{mutableStateOf("")};var birth by remember{mutableStateOf("")};var sex by remember{mutableStateOf("OTHER")}
    val scope=rememberCoroutineScope()
    val activityPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){scope.launch{tracking.start()}}
    val healthPermission=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){scope.launch{tracking.start()}}
    LazyColumn(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Configura il profilo",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Questi dati servono solo per stimare distanza e kcal.") }
        item { OutlinedTextField(weight,{weight=it},Modifier.fillMaxWidth(),label={Text("Peso (kg)")}) }
        item { OutlinedTextField(height,{height=it},Modifier.fillMaxWidth(),label={Text("Altezza (cm)")}) }
        item { OutlinedTextField(birth,{birth=it},Modifier.fillMaxWidth(),label={Text("Data di nascita (AAAA-MM-GG)")}) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("FEMALE" to "Donna","MALE" to "Uomo","OTHER" to "Altro").forEach { (key,label)->FilterChip(sex==key,{sex=key},{Text(label)}) } } }
        item { OutlinedButton({healthPermission.launch(tracking.healthPermissions())},Modifier.fillMaxWidth()) { Icon(Icons.Default.Favorite,null);Spacer(Modifier.width(8.dp));Text("Autorizza Health Connect") } }
        item { TextButton({activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)}) { Text("Autorizza sensore di fallback") } }
        item { Button({onSave(weight.toDouble(),height.toDouble(),birth,sex)},Modifier.fillMaxWidth(),enabled=!state.loading&&weight.toDoubleOrNull()!=null&&height.toDoubleOrNull()!=null&&birth.length==10) { Text("Inizia") } }
        state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
    }
}

@Composable private fun MainScreen(state:UiState,tracking:StepTrackingManager,model:AppViewModel) {
    Scaffold(bottomBar={NavigationBar { listOf(Icons.Default.Home to "Oggi",Icons.Default.BarChart to "Statistiche",Icons.Default.Person to "Profilo").forEachIndexed { i,(icon,label)->NavigationBarItem(state.tab==i,{model.tab(i)},{Icon(icon,null)},label={Text(label)}) } }}) { padding->
        Box(Modifier.padding(padding)) { when(state.tab) { 0->Home(state,tracking);1->Stats(state);else->ProfileScreen(state,model) } }
    }
}

@Composable private fun Home(state:UiState,tracking:StepTrackingManager) {
    val steps=state.intervals.sumOf { it.steps };val profile=state.me!!.profile!!;val stride=profile.heightCm/100*(if(profile.sex=="FEMALE")0.413 else 0.415);val distance=steps*stride;val kcal=distance/1000*profile.weightKg*0.75
    LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("Oggi",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text(LocalDate.now().toString(),color=MaterialTheme.colorScheme.onSurfaceVariant) }
        item { MetricCard("Passi",steps.toString(),Icons.Default.DirectionsWalk) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f)){MetricCard("Distanza","%.2f km".format(distance/1000),Icons.Default.Route)};Box(Modifier.weight(1f)){MetricCard("kcal stimate","%.0f".format(kcal),Icons.Default.LocalFireDepartment)} } }
        item { Card { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("Raccolta",fontWeight=FontWeight.Bold);Text(tracking.source.name.replace('_',' '));Text(if(state.intervals.any{!it.synced})"Sincronizzazione in attesa" else "Dati sincronizzati",color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
}

@Composable private fun MetricCard(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector) { Card { Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) { Icon(icon,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Column { Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) } } } }

@Composable private fun Stats(state:UiState) { LazyColumn(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
    item { Text("Statistiche",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold) }
    state.summary?.let { s->item { MetricCard("Media giornaliera","%.0f passi".format(s.dailyAverage),Icons.Default.TrendingUp) } }
    item { Card { Column(Modifier.padding(16.dp)) { Text("Ultimi 30 giorni",fontWeight=FontWeight.Bold);Spacer(Modifier.height(16.dp));StepChart(state.daily,Modifier.fillMaxWidth().height(180.dp)) } } }
    item { Card { Column(Modifier.padding(16.dp)) { Text("Momenti della giornata",fontWeight=FontWeight.Bold);Text("Media passi per quarto d'ora",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(12.dp));QuarterChart(state.timeOfDay,Modifier.fillMaxWidth().height(140.dp)) } } }
    items(state.daily.takeLast(7).reversed()) { Text("${it.date}   ${it.steps} passi · %.0f kcal stimate".format(it.estimatedKcal)) }
} }

@Composable private fun StepChart(points:List<DailyPoint>,modifier:Modifier) { val primary=MaterialTheme.colorScheme.primary;Canvas(modifier) { if(points.isEmpty())return@Canvas;val max=points.maxOf { it.steps }.coerceAtLeast(1);val dx=size.width/(points.size.coerceAtLeast(2)-1);points.zipWithNext().forEachIndexed { i,(a,b)->drawLine(primary,Offset(i*dx,size.height-a.steps.toFloat()/max*size.height),Offset((i+1)*dx,size.height-b.steps.toFloat()/max*size.height),5f) } } }
@Composable private fun QuarterChart(points:List<TimePoint>,modifier:Modifier) { val color=MaterialTheme.colorScheme.secondary;Canvas(modifier) { val max=(points.maxOfOrNull { it.steps } ?: 1.0).coerceAtLeast(1.0);points.forEach { p->val x=p.quarterHour/96f*size.width;drawLine(color,Offset(x,size.height),Offset(x,size.height-(p.steps/max*size.height).toFloat()),size.width/110) } } }

@Composable private fun ProfileScreen(state:UiState,model:AppViewModel) { var confirm by remember{mutableStateOf(false)};var edit by remember{mutableStateOf(false)};Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    Text("Profilo",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text(state.me?.email.orEmpty());state.me?.profile?.let { Text("${it.weightKg} kg · ${it.heightCm} cm");Text("Fuso orario: ${it.timezone}") }
    OutlinedButton({edit=true},Modifier.fillMaxWidth()) { Text("Modifica dati fisici") };OutlinedButton(model::logout,Modifier.fillMaxWidth()) { Text("Esci") };TextButton({confirm=true},Modifier.fillMaxWidth()) { Text("Elimina account",color=MaterialTheme.colorScheme.error) }
    if(confirm)AlertDialog({confirm=false},{Button({model.delete();confirm=false}){Text("Elimina")}},dismissButton={TextButton({confirm=false}){Text("Annulla")}},title={Text("Eliminare l'account?")},text={Text("Tutti i dati verranno cancellati definitivamente.")})
    if(edit)EditProfileDialog(state.me!!.profile!!,{edit=false}) { weight,height->val p=state.me.profile!!;model.profile(weight,height,p.birthDate,p.sex);edit=false }
} }

@Composable private fun EditProfileDialog(profile:Profile,onDismiss:()->Unit,onSave:(Double,Double)->Unit) { var weight by remember{mutableStateOf(profile.weightKg.toString())};var height by remember{mutableStateOf(profile.heightCm.toString())};AlertDialog(onDismiss,{Button({onSave(weight.toDouble(),height.toDouble())},enabled=weight.toDoubleOrNull()!=null&&height.toDoubleOrNull()!=null){Text("Salva")}},dismissButton={TextButton(onDismiss){Text("Annulla")}},title={Text("Dati fisici")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(weight,{weight=it},label={Text("Peso (kg)")});OutlinedTextField(height,{height=it},label={Text("Altezza (cm)")})}}) }

@Composable private fun StepsTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF006C4C),secondary=Color(0xFF4D6358),surface=Color(0xFFF8FBF8)),content=content) }
