package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class NexusRepository(private val nexusDao: NexusDao) {

    val allDossiers: Flow<List<ExecutiveDossier>> = nexusDao.getAllDossiersFlow()

    suspend fun getDossierById(id: Long): ExecutiveDossier? = withContext(Dispatchers.IO) {
        nexusDao.getDossierById(id)
    }

    suspend fun saveDossier(
        query: String,
        arbitrationSummary: String,
        arbitrationDecision: String,
        rulingTitle: String,
        verdictTag: String,
        reportsList: List<AgentReportTemplate>,
        citationsList: List<CitationTemplate>
    ): Long = withContext(Dispatchers.IO) {
        // 1. Insert parent dossier
        val dossierId = nexusDao.insertDossier(
            DossierEntity(
                queryText = query,
                arbitrationSummary = arbitrationSummary,
                arbitrationDecision = arbitrationDecision,
                rulingTitle = rulingTitle,
                verdictTag = verdictTag
            )
        )

        // 2. Insert reports associated with parent
        reportsList.forEach { report ->
            nexusDao.insertAgentReport(
                AgentReportEntity(
                    dossierId = dossierId,
                    domain = report.domain,
                    agentName = report.agentName,
                    statusBadge = report.statusBadge,
                    content = report.content,
                    complianceScore = report.complianceScore,
                    frictionsFound = report.frictionsFound
                )
            )
        }

        // 3. Insert citations associated with parent
        val citationEntities = citationsList.map { cit ->
            CitationEntity(
                dossierId = dossierId,
                citationKey = cit.citationKey,
                sourceName = cit.sourceName,
                sourceText = cit.sourceText,
                url = cit.url,
                verified = cit.verified
            )
        }
        nexusDao.insertCitations(citationEntities)

        dossierId
    }

    suspend fun deleteDossier(id: Long) = withContext(Dispatchers.IO) {
        nexusDao.deleteDossierById(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        nexusDao.clearAllDossiers()
    }
}

// Simple template classes used for mapping outputs from high-level orchestration pipeline to save blocks
data class AgentReportTemplate(
    val domain: String,
    val agentName: String,
    val statusBadge: String,
    val content: String,
    val complianceScore: Int,
    val frictionsFound: String
)

data class CitationTemplate(
    val citationKey: String,
    val sourceName: String,
    val sourceText: String,
    val url: String,
    val verified: Boolean
)
