package com.example.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.orchestration.OrchestratorEngine
import com.example.orchestration.OrchestratorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NexusViewModel(
    application: Application,
    private val repository: NexusRepository
) : AndroidViewModel(application) {

    // Main reactive stream of historical Executive Dossiers from Room
    val historyList: StateFlow<List<ExecutiveDossier>> = repository.allDossiers
        .stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current screen layout states
    private val _queryInput = MutableStateFlow("")
    val queryInput: StateFlow<String> = _queryInput.asStateFlow()

    private val _orchestratorState = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val orchestratorState: StateFlow<OrchestratorState> = _orchestratorState.asStateFlow()

    // Represents the active dossier being viewed on screen (can be from active generation or historical selection)
    private val _activeDossier = MutableStateFlow<ExecutiveDossier?>(null)
    val activeDossier: StateFlow<ExecutiveDossier?> = _activeDossier.asStateFlow()

    // Overlay modal states
    private val _selectedCitation = MutableStateFlow<CitationEntity?>(null)
    val selectedCitation: StateFlow<CitationEntity?> = _selectedCitation.asStateFlow()

    private val orchestratorEngine = OrchestratorEngine()

    fun updateQueryInput(newInput: String) {
        _queryInput.value = newInput
    }

    /**
     * Executes the multi-agent pipeline: Deconstruction -> Especialist parallel analysis -> Critic verification check -> Rulings.
     * High-speed, robust dynamic background task. Saving to local Room DB happens securely at the final stage.
     */
    fun triggerOrchestration() {
        val query = _queryInput.value.trim()
        if (query.isEmpty()) return

        _activeDossier.value = null // Reset active screen layout during loading
        _selectedCitation.value = null

        viewModelScope.launch {
            orchestratorEngine.runOrchestration(query).collectLatest { state ->
                _orchestratorState.value = state

                if (state is OrchestratorState.Success) {
                    // Persist completed dossier into local Room database
                    val dossierId = repository.saveDossier(
                        query = query,
                        arbitrationSummary = state.dossierSummary,
                        arbitrationDecision = state.arbitrationDecision,
                        rulingTitle = state.rulingTitle,
                        verdictTag = state.verdictTag,
                        reportsList = state.reports,
                        citationsList = state.citations
                    )

                    // Fetch the saved dossier with fully loaded relationships from Room to reflect in the active workspace UI
                    val loadedDossier = repository.getDossierById(dossierId)
                    _activeDossier.value = loadedDossier
                    _queryInput.value = "" // Empty query input on success
                }
            }
        }
    }

    fun selectDossierFromHistory(dossier: ExecutiveDossier) {
        _activeDossier.value = dossier
        _orchestratorState.value = OrchestratorState.Idle // Clear current progress animation to focus on selected dossier
        _selectedCitation.value = null
    }

    fun inspectCitation(citationKey: String) {
        val currentDossier = _activeDossier.value ?: return
        val found = currentDossier.citations.firstOrNull { 
            it.citationKey.equals(citationKey, ignoreCase = true) 
        }
        if (found != null) {
            _selectedCitation.value = found
        }
    }

    fun dismissCitationInspector() {
        _selectedCitation.value = null
    }

    fun clearWorkflowInputAndResult() {
        _activeDossier.value = null
        _orchestratorState.value = OrchestratorState.Idle
        _queryInput.value = ""
    }

    fun deleteDossier(id: Long) {
        viewModelScope.launch {
            if (_activeDossier.value?.dossier?.id == id) {
                _activeDossier.value = null
            }
            repository.deleteDossier(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            _activeDossier.value = null
            _orchestratorState.value = OrchestratorState.Idle
            repository.clearHistory()
        }
    }

    // Factory Class for Dependency Injection
    class Factory(
        private val application: Application,
        private val repository: NexusRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(NexusViewModel::class.java)) {
                return NexusViewModel(application, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}


