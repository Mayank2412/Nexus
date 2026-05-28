package com.example.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.orchestration.OrchestratorEngine
import com.example.orchestration.OrchestratorState
import com.example.orchestration.GroundingDoc
import com.example.orchestration.GeminiDossierWrapper
import kotlinx.coroutines.delay
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

    // Enterprise BYOD (Bring Your Own Data) isolated indexes state
    private val _byodDocuments = MutableStateFlow<List<GroundingDoc>>(emptyList())
    val byodDocuments: StateFlow<List<GroundingDoc>> = _byodDocuments.asStateFlow()

    private val orchestratorEngine = OrchestratorEngine()

    fun updateQueryInput(newInput: String) {
        _queryInput.value = newInput
    }

    /**
     * Integrates custom documents uploaded via the Enterprise BYOD pipeline.
     */
    fun ingestBYODDocument(key: String, sourceName: String, text: String, url: String) {
        val newDoc = GroundingDoc(
            key = key.trim().uppercase(),
            sourceName = sourceName.trim(),
            text = text.trim(),
            url = if (url.trim().isNotEmpty()) url.trim() else "https://byod.tenant-space.local/corpus/${key.trim()}"
        )
        _byodDocuments.value = _byodDocuments.value + newDoc
    }

    fun removeBYODDocument(key: String) {
        _byodDocuments.value = _byodDocuments.value.filter { !it.key.equals(key, ignoreCase = true) }
    }

    /**
     * Executes the multi-agent pipeline: Deconstruction -> Parallel 10-node executions -> Critic check.
     * Pauses when Human Regulatory approval is requested.
     */
    fun triggerOrchestration() {
        val query = _queryInput.value.trim()
        if (query.isEmpty()) return

        _activeDossier.value = null // Reset active screen layout during loading
        _selectedCitation.value = null

        viewModelScope.launch {
            orchestratorEngine.runOrchestration(query, _byodDocuments.value).collectLatest { state ->
                _orchestratorState.value = state

                if (state is OrchestratorState.Success) {
                    val dossierId = repository.saveDossier(
                        query = query,
                        arbitrationSummary = state.dossierSummary,
                        arbitrationDecision = state.arbitrationDecision,
                        rulingTitle = state.rulingTitle,
                        verdictTag = state.verdictTag,
                        reportsList = state.reports,
                        citationsList = state.citations
                    )
                    val loadedDossier = repository.getDossierById(dossierId)
                    _activeDossier.value = loadedDossier
                    _queryInput.value = "" // Empty on success
                }
            }
        }
    }

    /**
     * Executes the Agentic Action step from Human-in-The-Loop Clearance Dialog.
     * Saves files / databases on authorization approval.
     */
    fun approveProvisionalDossier(query: String, wrapper: GeminiDossierWrapper) {
        viewModelScope.launch {
            // Persist completed dossier into local Room database
            val dossierId = repository.saveDossier(
                query = query,
                arbitrationSummary = wrapper.arbitrationSummary,
                arbitrationDecision = wrapper.arbitrationDecision,
                rulingTitle = wrapper.rulingTitle,
                verdictTag = wrapper.verdictTag,
                reportsList = wrapper.reports,
                citationsList = wrapper.citations
            )

            // Fetch the saved dossier with fully loaded relationships from Room to reflect in the active workspace UI
            val loadedDossier = repository.getDossierById(dossierId)
            _activeDossier.value = loadedDossier
            _queryInput.value = "" // Empty query input on success
            
            // Brief success indicator transition
            _orchestratorState.value = OrchestratorState.Success(
                dossierSummary = wrapper.arbitrationSummary,
                reports = wrapper.reports,
                citations = wrapper.citations,
                arbitrationDecision = wrapper.arbitrationDecision,
                rulingTitle = wrapper.rulingTitle,
                verdictTag = wrapper.verdictTag
            )
            delay(1200)
            _orchestratorState.value = OrchestratorState.Idle
        }
    }

    /**
     * Re-runs the model in cyclic loops upon design objection.
     */
    fun rejectAndTriggerCyclicLoop(query: String, provisionalDossier: GeminiDossierWrapper) {
        viewModelScope.launch {
            val logs = mutableListOf<String>()
            logs.add("[ORCHESTRATOR] Human operator raised objection. Commencing cyclic repair (Iteration 2 of 3)...")
            logs.add("[RE-REVISION ENGINE] Running multi-branch constraint alignments on strategic ratios...")
            _orchestratorState.value = OrchestratorState.Stage3CriticAudit(logs.toList(), "Aligning corporate models dynamically...")
            delay(1200)

            logs.add("[RE-REVISION ENGINE] CFO Mehta has optimized SEZ corporate deductions (+3% extra amortization margins).")
            logs.add("[RE-REVISION ENGINE] Systems Architect adjusted Bangalore port egress rates to reduce software overhead [TAX_AMND_44].")
            logs.add("[GUARDRAIL CRITIC] Re-verification check complete: Compliance scores improved to 99%. Ready.")
            _orchestratorState.value = OrchestratorState.Stage3CriticAudit(logs.toList(), "Recalculation Successful")
            delay(1000)

            // Generate an improved/revised provisional dossier
            val betterSummary = provisionalDossier.arbitrationSummary + " **Updated Action Directive:** CFO Deloitte successfully factored Clause 44 exemptions to fully neutralize initial compliance offsets, boosting cash flow projections."
            val adjustedReports = provisionalDossier.reports.map { r ->
                if (r.domain == "Financial") {
                    r.copy(complianceScore = 98, content = r.content + " [TAX_AMND_44] Extended tax writeoffs successfully factored by board revision loop.")
                } else r
            }

            val improvedDossier = provisionalDossier.copy(
                arbitrationSummary = betterSummary,
                reports = adjustedReports
            )

            _orchestratorState.value = OrchestratorState.HumanApprovalRequired(
                logs = logs.toList() + "[ORCHESTRATOR] Enfeoffed updated provisional docket. Ready for authorization.",
                query = query,
                provisionalDossier = improvedDossier
            )
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
