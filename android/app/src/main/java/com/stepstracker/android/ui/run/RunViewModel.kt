package com.stepstracker.android.ui.run

import androidx.lifecycle.*
import com.stepstracker.android.StepsTrackerApp
import com.stepstracker.android.data.run.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RunUiState(val active:RunSessionEntity?=null,val history:List<RunSessionEntity> = emptyList(),val selected:RunWithDetails?=null,val error:String?=null)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunViewModel(private val app:StepsTrackerApp):ViewModel() {
    private val selectedId=MutableStateFlow<String?>(null)
    val state:StateFlow<RunUiState> = combine(
        app.runs.active,
        app.runs.history,
        selectedId.flatMapLatest { id->if(id==null)flowOf(null) else app.runs.details(id) },
    ) { active,history,selected->RunUiState(active,history,selected) }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),RunUiState())
    init { viewModelScope.launch { runCatching { app.runs.refreshHistory() } } }
    fun select(id:String?){selectedId.value=id;if(id!=null)viewModelScope.launch { runCatching { app.runs.hydrate(id) } }}
    fun delete(id:String)=viewModelScope.launch { app.runs.delete(id);selectedId.value=null }
    companion object { fun factory(app:StepsTrackerApp)=object:ViewModelProvider.Factory { override fun <T:ViewModel> create(modelClass:Class<T>):T=RunViewModel(app) as T } }
}
