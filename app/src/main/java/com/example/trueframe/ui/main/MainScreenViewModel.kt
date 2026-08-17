package com.example.trueframe.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trueframe.data.ProjectEntity
import com.example.trueframe.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainScreenViewModel @Inject constructor(
    projectRepository: ProjectRepository,
) : ViewModel() {

    val uiState: StateFlow<MainScreenUiState> =
        projectRepository.observeAll()
            .map<List<ProjectEntity>, MainScreenUiState> { MainScreenUiState.Success(it) }
            .catch { emit(MainScreenUiState.Error(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)
}

sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState
    data class Error(val throwable: Throwable) : MainScreenUiState
    data class Success(val projects: List<ProjectEntity>) : MainScreenUiState
}
