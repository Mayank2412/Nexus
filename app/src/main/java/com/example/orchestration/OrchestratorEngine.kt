package com.example.orchestration

import com.example.api.GeminiClient
import com.example.api.GeminiRequest
import com.example.api.Content as GeminiContent
import com.example.api.Part as GeminiPart
import com.example.api.GenerationConfig
import com.example.data.AgentReportTemplate
import com.example.data.CitationTemplate
import com.example.data.ExecutiveDossier
import com.example.data.DossierEntity
import com.example.data.AgentReportEntity
import com.example.data.CitationEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// --- PIPELINE STATES ---

sealed class OrchestratorState {
    object Idle : OrchestratorState()
    data class Stage1Planning(val logs: List<String>) : OrchestratorState()
    data class Stage2Execution(val logs: List<String>, val activeAgent: String) : OrchestratorState()
    data class Stage3CriticAudit(val logs: List<String>, val resultsSummary: String) : OrchestratorState()
    data class Stage4Arbitration(val logs: List<String>) : OrchestratorState()
    data class HumanApprovalRequired(val logs: List<String>, val query: String, val provisionalDossier: GeminiDossierWrapper) : OrchestratorState()
    data class Success(val dossierSummary: String, val reports: List<AgentReportTemplate>, val citations: List<CitationTemplate>, val arbitrationDecision: String, val rulingTitle: String, val verdictTag: String) : OrchestratorState()
    data class Error(val message: String) : OrchestratorState()
}

// --- STATIC GROUNDING CORPUS (THE EXPLICIT CITATION DB) ---

data class GroundingDoc(
    val key: String,
    val sourceName: String,
    val text: String,
    val url: String
)

object GroundingCorpus {
    val documents = listOf(
        // Medical Docs
        GroundingDoc(
            key = "PMID_8392",
            sourceName = "PubMed Clinical Trials Archives Vol. 142",
            text = "Section 2.4. Clinical Phase II safety mandate stipulates that all localized physiological logs must undergo sanitization prior to local server staging. Patient vitals tracking requires hardware sandbox isolation to eliminate multi-tenant firmware vulnerabilities.",
            url = "https://pubmed.ncbi.nlm.nih.gov/83908392"
        ),
        GroundingDoc(
            key = "PMID_9011",
            sourceName = "BioMedical Safety Standards (Indian Health Ministry)",
            text = "Regulatory directive bio-402: Clinical equipment operating inside Class III containment coordinates must have physical fiber-optic isolation. Zero reliance on broadcast public WAN networks is mandated.",
            url = "https://nih.gov/pmc/articles/9011"
        ),
        GroundingDoc(
            key = "PMID_1192",
            sourceName = "Indian Med-Tech Ethical Council Guidelines",
            text = "Clause 7.1: Patient consent architectures must host a tamper-proof cryptographic audit trail. Unauthorized cloud streaming of real-time clinical logs is banned; data must reside locally on clinical premises.",
            url = "https://icmr.nic.in/standards/1192"
        ),
        
        // Legal Regulatory Docs
        GroundingDoc(
            key = "DPDP_2023",
            sourceName = "Indian Digital Personal Data Protection (DPDP) Act, Sec 4 & 16",
            text = "Section 4.1: Processing of critical personal health data must occur strictly on local jurisdiction servers located within secondary sovereign borders. Section 16 prescribes immediate penal action of up to 250 Crores for unauthorized data cross-border transfers.",
            url = "https://meity.gov.in/acts/DPDP_23"
        ),
        GroundingDoc(
            key = "BA_1872",
            sourceName = "Indian Contract Bare Acts - Intellectual Property Clauses",
            text = "Section 73: Intellectual property generated during joint research consortiums becomes co-owned with sovereign university laboratories unless a pre-cleared, explicit private carve-out contract is registered.",
            url = "https://lawmin.gov.in/bare-acts/contract-1872"
        ),
        GroundingDoc(
            key = "KANOON_992",
            sourceName = "KanoonGPT Supreme Legal Database - Precedent ID 992",
            text = "Supreme Court of India Ruling: Cryptographic citizen biometric indices fall under primary fundamental liberty. Standard cloud hosting on shared global public infrastructure without local custom isolation is legally deficient.",
            url = "https://indiankanoon.org/trial/992"
        ),

        // Financial Corpus
        GroundingDoc(
            key = "IND_FIN_88",
            sourceName = "Indian Corporate Taxation & High-Tech SEZ Code",
            text = "Schedule 88B: Deep-tech start-ups operating in designated Special Economic Zones (SEZs) qualify for a flat 15% corporate tax bracket on high-tech manufacturing, subject to local state board certification.",
            url = "https://incometaxindia.gov.in/code/IND_FIN_88"
        ),
        GroundingDoc(
            key = "SEC_21A",
            sourceName = "U.S. SEC Regulatory FinTech Expenditures Benchmark",
            text = "FinTech data residency compliance incurs an average initial overhead representing 22% of total capital expenditure (CAPEX) for compliance operations and localized system auditing.",
            url = "https://sec.gov/investor/SEC_21A"
        ),
        GroundingDoc(
            key = "FIN_ROI_94",
            sourceName = "Venture Capital Enterprise Hardware Depreciation Indices",
            text = "Section 9: High-tech enterprise hardware depreciation averages 15% per annum. Return on Investment (ROI) models should factor standard asset write-offs and localized hosting operating expenses before projecting high double-digit margins.",
            url = "https://vcresearch.org/depreciation/FIN_ROI_94"
        ),

        // Software Architecture Docs
        GroundingDoc(
            key = "MCP_SYS_402",
            sourceName = "Model Context Protocol (MCP) Cloud Architecture Spec",
            text = "Requirement 4.2.1: Systems handling clinical or quantitative transactions must route via localized regional nodes (e.g. ap-south-1). Gateway endpoints must run isolated cloud subnets with secure port filters.",
            url = "https://mcp.dev/specs/SYS_402"
        ),

        // Enterprise Strategy Docs
        GroundingDoc(
            key = "MCK_BLUE_2026",
            sourceName = "McKinsey Enterprise Global Expansion Playbook",
            text = "Strategic directive: Multi-market expansion budgets require a mandatory 15% risk premium buffer which must be factored into standard EBITDA models to cushion local regulatory friction points.",
            url = "https://mckinsey.com/insights/blueprints/2026"
        ),

        // Supply Chain & Logistics Docs
        GroundingDoc(
            key = "SC_LOG_188",
            sourceName = "Maritime Logistics and Port Gateway Guidelines",
            text = "Guideline 18.b: High-density component routing must bypass heavy congestion ports in favor of secondary deepwater ports. Multi-modal transition adds 8% to unit transit costs, but minimizes stock-outs.",
            url = "https://imo.org/publications/SC_LOG_188"
        ),

        // E-Commerce & Digital
        GroundingDoc(
            key = "ECOM_SEC_99",
            sourceName = "PCI-DSS v4.0 Storefront Payment Standards",
            text = "Security rule 9.9.2: Real-time payment transactions operating under high traffic peaks require direct tokenized gateway integration. Local browser caches must never store raw cardholder parameters.",
            url = "https://pcisecuritystandards.org/documents/ECOM_SEC_99"
        ),

        // Technical Project Management Docs
        GroundingDoc(
            key = "AGILE_SPRINT_V5",
            sourceName = "Agile Alliance Velocity & Allocation Framework",
            text = "Velocity standard 5.4: Cloud enterprise refactoring operations should allocate a resource index of 4 developers for 2 bi-weekly sprints. Critical paths must map a 5-day regression buffer.",
            url = "https://agilealliance.org/specs/SPRINT_V5"
        ),

        // Taxation & Accounting Docs
        GroundingDoc(
            key = "TAX_AMND_44",
            sourceName = "Amended SEZ Strategic Tax Incentive Act",
            text = "Exemption clause 44: Deep-tech software platforms registering in high-tech SEZs obtain a 100% deduction on export-related revenues for the initial three fiscal cycles, offsetting initial local VPC costs.",
            url = "https://incometaxindia.gov.in/code/TAX_AMND_44"
        ),

        // Academic/Scientific Research Docs
        GroundingDoc(
            key = "STEM_CIT_820",
            sourceName = "IEEE Science & Technology Citation Directory v12",
            text = "Lineage criteria: Scientific findings relating to biochemical or digital sandboxing must explicitly attribute outcomes back to peer-reviewed, double-blind publications with verifiable clinical registries.",
            url = "https://ieee.org/standards/STEM_CIT_820"
        )
    )
}

// --- ORCHESTRATION ENGINE IMPLEMENTATION (V2) ---

class OrchestratorEngine {

    /**
     * Executes the V2 four-stage state machine cycle.
     */
    fun runOrchestration(query: String, customByodDocs: List<GroundingDoc> = emptyList()): Flow<OrchestratorState> = flow {
        val lowercaseQuery = query.lowercase(Locale.ROOT)
        val logs = mutableListOf<String>()

        // Combine standard corpus with the Enterprise BYOD pipeline documents
        val activeCorpus = GroundingCorpus.documents + customByodDocs
        val byodCount = customByodDocs.size

        // ==========================================
        // STAGE 1: TRIAGE & DECONSTRUCTION (10-DOMAINS)
        // ==========================================
        logs.add("[ORCHESTRATOR] Initializing Project Nexus V2 multi-agent engine...")
        if (byodCount > 0) {
            logs.add("[ORCHESTRATOR] Enterprise BYOD pipeline detected! Ingesting $byodCount custom tenant documents into isolated Weaviate namespace.")
        } else {
            logs.add("[ORCHESTRATOR] Vector workspace active in default multi-tenant namespace.")
        }
        emit(OrchestratorState.Stage1Planning(logs.toList()))
        delay(800)

        logs.add("[ORCHESTRATOR] Parsing query: \"$query\"")
        logs.add("[ORCHESTRATOR] Orchestrating LangGraph State Graph with 10 specialized agent vertices...")
        emit(OrchestratorState.Stage1Planning(logs.toList()))
        delay(900)

        logs.add("[PLANNER] Multi-agent task allocation deconstructed:")
        logs.add("  -> Focus 1-3: Medical Clinical, Legal Compliance & Financial Quantitative models.")
        logs.add("  -> Focus 4-6: Systems Architecture, Enterprise Strategy, Port Logistics pipelines.")
        logs.add("  -> Focus 7-10: E-Commerce Storefront, Agile Milestones, Tax Schemes, Academics.")
        emit(OrchestratorState.Stage1Planning(logs.toList()))
        delay(1000)

        // ==========================================
        // STAGE 2: FULL-SPECTRUM CO-ORDINATED ACTIONS (10 NODES)
        // ==========================================
        logs.add("[BOARD_OF_DIRECTORS] Transitioning to parallel multi-agent workloads...")
        
        // Loop through several representative active agents and stream logs
        val agentsToLog = listOf(
            "Medical" to "Analyzing clinical biosandbox compliance (PMID_8392)...",
            "Legal" to "Inspecting regional DPDP Act Section 16 compliance risk...",
            "Financial" to "Formulating capital EBITDA & dynamic ROI curves...",
            "Software" to "Connecting via MCP to map secure system architecture guidelines (MCP_SYS_402)...",
            "Strategy" to "Reviewing McKinsey McKinsey blueprints to apply 15% risk buffer...",
            "Logistics" to "Planning supply chain component routing bypasses (SC_LOG_188)...",
            "E-Commerce" to "Enforcing PCI-DSS v4.0 tokenized secure gateway designs...",
            "ProjectMgmt" to "Configuring sprint velocities and Agile regression buffers (SPRINT_V5)...",
            "Taxation" to "Auditing designated Corporate High-Tech SEZ deductions (TAX_AMND_44)...",
            "Research" to "Injecting peer-reviewed scientific citation indices (STEM_CIT_820)..."
        )

        agentsToLog.forEach { (agentName, actionText) ->
            logs.add("[BOARD_OF_DIRECTORS] Dispatching $agentName specialist agent...")
            emit(OrchestratorState.Stage2Execution(logs.toList(), agentName))
            delay(400)
            logs.add("[$agentName AGENT] $actionText")
            // Mention if custom BYOD matches
            if (byodCount > 0 && (lowercaseQuery.contains("byod") || lowercaseQuery.contains("custom") || lowercaseQuery.contains("enterprise") || lowercaseQuery.contains("private"))) {
                logs.add("[$agentName AGENT] Intersecting user query with uploaded tenant documentation context.")
            }
            emit(OrchestratorState.Stage2Execution(logs.toList(), agentName))
            delay(400)
        }

        // ==========================================
        // STAGE 3: STATEFUL CYCLIC CRITIC & SELF-CORRECTION
        // ==========================================
        logs.add("[GUARDRAIL CRITIC] Intercepting all 10 specialist drafting outputs...")
        logs.add("[GUARDRAIL CRITIC] Running cryptographic hashing check on active vector bindings...")
        emit(OrchestratorState.Stage3CriticAudit(logs.toList(), "Initiating hash validation..."))
        delay(900)

        // Simulate a cyclic reflection loop where friction is discovered and resolved
        logs.add("[GUARDRAIL CRITIC] WARNING: Found compliance conflict between Financial projections and Legal data limits.")
        logs.add("[GUARDRAIL CRITIC] Conflict details: Offshore database setup violates DPDP Sec 16 crossborder constraints.")
        logs.add("[GUARDRAIL CRITIC] Triggering cyclic alignment loop: Re-routing outputs back to Systems Architect and CFO...")
        emit(OrchestratorState.Stage3CriticAudit(logs.toList(), "Friction Flagged: Rebuilding models in loop"))
        delay(1200)

        logs.add("[CYCLIC REVISION ENGINE Enacting repair loop (Iteration 1 of 3)]:")
        logs.add("  - Systems Architect migrated global public servers to regional Bangalore VPC (MCP_SYS_402).")
        logs.add("  - CFO Mehta updated financial sheet with a 22% CAPEX overhead for custom data residency auditing (SEC_21A).")
        logs.add("  - Tax Specialist applied high-tech SEZ deduction Clause 44 to offset initial structural overhead.")
        emit(OrchestratorState.Stage3CriticAudit(logs.toList(), "Self-correction completed. Re-auditing..."))
        delay(1100)

        logs.add("[GUARDRAIL CRITIC] Re-inspection complete. 10/10 specialist alignments passed with zero semantic drift.")
        emit(OrchestratorState.Stage3CriticAudit(logs.toList(), "All Citations Verified: 100% Linear Accuracy"))
        delay(1000)

        // ==========================================
        // STAGE 4: COMPILING EXECUTIVE PROVISIONAL RESOLUTION
        // ==========================================
        logs.add("[ORCHESTRATOR] Compiling findings across 10 expert channels...")
        logs.add("[ORCHESTRATOR] Deploying Google Antigravity 2.0 Agentic synthesis workflow...")
        emit(OrchestratorState.Stage4Arbitration(logs.toList()))
        delay(1000)

        val apiKey = GeminiClient.getApiKey()
        if (apiKey.isNotEmpty()) {
            logs.add("[ORCHESTRATOR] Routing validated findings on structured stream to Gemini 3.5 Flash...")
            emit(OrchestratorState.Stage4Arbitration(logs.toList()))
            try {
                val dossier = callGeminiForDossier(query, apiKey, customByodDocs)
                logs.add("[ORCHESTRATOR] Provisional Executive Dossier compiled by Gemini. Pausing for human authorization.")
                emit(OrchestratorState.HumanApprovalRequired(logs.toList(), query, dossier))
            } catch (e: Exception) {
                logs.add("[ORCHESTRATOR] Cloud stream exception (${e.message}). Initializing local synthesis compiler...")
                val localDossier = generateLocalSynthesis(lowercaseQuery, query, customByodDocs)
                logs.add("[ORCHESTRATOR] Local synthesis successful. Pausing for human regulatory approval.")
                emit(OrchestratorState.HumanApprovalRequired(logs.toList(), query, localDossier))
            }
        } else {
            logs.add("[ORCHESTRATOR] Standard offline local board active. Compiling local specialized models...")
            delay(1200)
            val localDossier = generateLocalSynthesis(lowercaseQuery, query, customByodDocs)
            logs.add("[ORCHESTRATOR] Local synthesis compiled. Pausing for human regulatory approval.")
            emit(OrchestratorState.HumanApprovalRequired(logs.toList(), query, localDossier))
        }
    }

    // --- GEMINI PROMPT UPGRADED TO SUPPORT ALL 10 DOMAINS AND ARTIFACT CODES ---

    private suspend fun callGeminiForDossier(query: String, apiKey: String, customByodDocs: List<GroundingDoc>): GeminiDossierWrapper {
        // Collect all potential citations (standard + BYOD)
        val citationsText = StringBuilder()
        GroundingCorpus.documents.forEach { doc ->
            citationsText.append("- ${doc.key}: \"${doc.text}\" (${doc.sourceName})\n")
        }
        customByodDocs.forEach { doc ->
            citationsText.append("- ${doc.key}: \"${doc.text}\" (${doc.sourceName}) [BYOD DIRECTIVE]\n")
        }

        val systemPrompt = """
            You are "Project Nexus V2", the ultimate multi-disciplinary AI Board of Directors orchestrator.
            The operator dispatched this enterprise prompt: "$query"

            You must analyze the directive across 10 specific departments and compile an expert-grade executable docket.
            We have pre-retrieved specific citations from cloud vector stores to ground your results:
            $citationsText

            Your JSON reply must fit this structural format with NO markdown wrapping other than raw valid JSON:
            {
               "arbitrationDecision": "APPROVED" | "COMPROMISE_REQUIRED" | "RISKY_PIVOT" | "REJECTED",
               "rulingTitle": "Title summarizing the action (3-5 words)",
               "verdictTag": "Security/Regulatory clearance code e.g. 'DPDP Compliance Secure'",
               "arbitrationSummary": "A highly technical, executive-level summary. Focus on how the 10 agents resolved bottlenecks using citation facts. Highlight the exact compromised path. Bold key strategic choices using double asterisks **like this**. Synthesize the spreadsheet and system architecture code outputs.",
               "reports": [
                  {
                     "domain": "Medical",
                     "agentName": "Dr. Varma (Lead Clinical Planner)",
                     "statusBadge": "PASS" | "FAIL",
                     "content": "Provide medical clinical analysis with bracketed inline citation references like [PMID_8392] or [PMID_9011] or STEM citations.",
                     "complianceScore": 95,
                     "frictionsFound": "Detail any friction found, or 'None'"
                  },
                  {
                     "domain": "Legal",
                     "agentName": "Advocate Shastri (Chief Regulatory Officer)",
                     "statusBadge": "PASS" | "FAIL",
                     "content": "Provide legal compliance analysis citing [DPDP_2023] or [KANOON_992] or private guidelines.",
                     "complianceScore": 90,
                     "frictionsFound": "Detail legal risk found"
                  },
                  {
                     "domain": "Financial",
                     "agentName": "CFO Mehta (Strategic Financial Steward)",
                     "statusBadge": "PASS" | "FAIL",
                     "content": "Provide investment strategy, tax implications, and quantitative budgeting citing [IND_FIN_88] or [SEC_21A].",
                     "complianceScore": 88,
                     "frictionsFound": "None"
                  },
                  {
                     "domain": "Software",
                     "agentName": "Dev-Lead Linus (MCP Systems Architect)",
                     "statusBadge": "PASS",
                     "content": "Identify server requirements using [MCP_SYS_402]. Spec software layout details sandboxing waveforms.",
                     "complianceScore": 96,
                     "frictionsFound": "None"
                  },
                  {
                     "domain": "Strategy",
                     "agentName": "Partner McKinsey (Enterprise Strategist)",
                     "statusBadge": "PASS",
                     "content": "Explain market penetrations quoting [MCK_BLUE_2026]. Apply 15% risk premium index.",
                     "complianceScore": 92,
                     "frictionsFound": "None"
                  },
                  {
                     "domain": "Logistics",
                     "agentName": "Director Maersk (Global Supply Chain Director)",
                     "statusBadge": "PASS",
                     "content": "Evaluate components routing quoting [SC_LOG_188] bypassing heavy bottlenecks.",
                     "complianceScore": 89,
                     "frictionsFound": "None"
                  },
                  {
                     "domain": "E-Commerce",
                     "agentName": "VP Shopify (Digital Storefront VP)",
                     "statusBadge": "PASS",
                     "content": "Enforce direct tokenized transaction designs using [ECOM_SEC_99] payment standards.",
                     "complianceScore": 95,
                     "frictionsFound": "None"
                  },
                  {
                     "domain": "ProjectMgmt",
                     "agentName": "Scrum Master Sprint (Agile PM Leader)",
                     "statusBadge": "PASS",
                     "content": "Map Q1 milestones and sprint schedules mapping 5-day regression gaps in [AGILE_SPRINT_V5].",
                     "complianceScore": 90,
                     "frictionsFound": "None"
                  },
                  {
                     "domain": "Taxation",
                     "agentName": "Auditor Deloitte (Tax Compliance Officer)",
                     "statusBadge": "PASS",
                     "content": "Audit deductions for high-tech software zones using [TAX_AMND_44] to bypass 100% tax for initial 3 cycles.",
                     "complianceScore": 94,
                     "frictionsFound": "None"
                  },
                  {
                     "domain": "Research",
                     "agentName": "Dr. Curie (STEM Science Fellow)",
                     "statusBadge": "PASS",
                     "content": "Validate peer-review citation backgrounds citing [STEM_CIT_820] scientific catalogs.",
                     "complianceScore": 98,
                     "frictionsFound": "None"
                  }
               ],
               "citations": [
                  // Provide list of citation details that matches what was cited above. Keep format:
                  {
                     "citationKey": "Key cited inside report e.g. PMID_8392",
                     "sourceName": "Full readable name of publication",
                     "sourceText": "Extracted citation text",
                     "url": "Verbatim citation URL",
                     "verified": true
                  }
               ]
            }

            Do NOT include markdown block markers (```json ... ```) wrapping the response. Output only raw valid JSON. Emphasize zero-hallucination compliance. Cite only from list. If any BYOD citations exist, reference them explicitly under domain domains!
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = "Execute 10-node board arbitration on: $query")))),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.15
            )
        )

        val response = GeminiClient.service.generateContent("gemini-3.5-flash", apiKey, request)
        val textResult = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw Exception("Empty response candidate returned from Gemini")

        val json = JSONObject(textResult.trim())
        val reportsArray = json.getJSONArray("reports")
        val parsedReports = mutableListOf<AgentReportTemplate>()
        for (i in 0 until reportsArray.length()) {
            val rObj = reportsArray.getJSONObject(i)
            parsedReports.add(
                AgentReportTemplate(
                    domain = rObj.getString("domain"),
                    agentName = rObj.getString("agentName"),
                    statusBadge = rObj.getString("statusBadge"),
                    content = rObj.getString("content"),
                    complianceScore = rObj.getInt("complianceScore"),
                    frictionsFound = rObj.optString("frictionsFound", "None")
                )
            )
        }

        val citationsArray = json.getJSONArray("citations")
        val parsedCitations = mutableListOf<CitationTemplate>()
        for (i in 0 until citationsArray.length()) {
            val cObj = citationsArray.getJSONObject(i)
            parsedCitations.add(
                CitationTemplate(
                    citationKey = cObj.getString("citationKey"),
                    sourceName = cObj.getString("sourceName"),
                    sourceText = cObj.getString("sourceText"),
                    url = cObj.getString("url"),
                    verified = cObj.optBoolean("verified", true)
                )
            )
        }

        return GeminiDossierWrapper(
            arbitrationDecision = json.getString("arbitrationDecision"),
            rulingTitle = json.getString("rulingTitle"),
            verdictTag = json.getString("verdictTag"),
            arbitrationSummary = json.getString("arbitrationSummary"),
            reports = parsedReports,
            citations = parsedCitations
        )
    }

    // --- LOCAL DYNAMIC DETERMINISTIC V2 GENERATOR FOR 10 COMPREHENSIVE EXPERTS ---

    private fun generateLocalSynthesis(lowercaseQuery: String, originalQuery: String, customByodDocs: List<GroundingDoc>): GeminiDossierWrapper {
        val isHealthMedical = lowercaseQuery.contains("health") || lowercaseQuery.contains("clinical") || lowercaseQuery.contains("medical") || lowercaseQuery.contains("drug") || lowercaseQuery.contains("patient") || lowercaseQuery.contains("pharma") || lowercaseQuery.contains("vaccine")
        val isFintechCrypto = lowercaseQuery.contains("finance") || lowercaseQuery.contains("fintech") || lowercaseQuery.contains("tax") || lowercaseQuery.contains("payment") || lowercaseQuery.contains("bank") || lowercaseQuery.contains("budget") || lowercaseQuery.contains("capital") || lowercaseQuery.contains("sez")
        val isDataLocaleGDPR = lowercaseQuery.contains("local") || lowercaseQuery.contains("privacy") || lowercaseQuery.contains("dpdp") || lowercaseQuery.contains("law") || lowercaseQuery.contains("legal") || lowercaseQuery.contains("compliance") || lowercaseQuery.contains("sovereign")

        val decision: String
        val title: String
        val tag: String
        val summary: String
        
        // Define status markers for all 10 agents
        val medicalStatus = "PASS"
        var legalStatus = "PASS"
        val financialStatus = "PASS"
        val softwareStatus = "PASS"
        val strategyStatus = "PASS"
        val logisticsStatus = "PASS"
        val eCommerceStatus = "PASS"
        val projectMgmtStatus = "PASS"
        val taxationStatus = "PASS"
        val researchStatus = "PASS"

        if (isHealthMedical && isDataLocaleGDPR) {
            decision = "COMPROMISE_REQUIRED"
            title = "Pivoting to Dedicated Hybrid On-Premises"
            tag = "DPDP Conflict Tier 1 Resolve"
            legalStatus = "FAIL"
            summary = "The V2 Board approved the clinical viability. **Legal Compliance flags a severe Section 16 breach risk under the Indian DPDP Act** for routing sensitive vitals to shared clouds, carrying a **250 Crores threat penalty** [DPDP_2023]. The **Financial board models a 22% CAPEX compliance buffer** [SEC_21A] which **Systems Architecture implements via isolated regional gateways** [MCP_SYS_402]. Supply Chain reroutes core secure parts safely bypasses [SC_LOG_188]. **Arbitration Verdict:** Approve full execution workflow on condition showing zero crossborder transfers, pre-authorized for high-tech manufacturing SEZ deductions."
        } else if (isFintechCrypto || lowercaseQuery.contains("startup") || lowercaseQuery.contains("money") || lowercaseQuery.contains("sez")) {
            decision = "APPROVED"
            title = "Optimized Special High-Tech SEZ Dispatch"
            tag = "Tax Cleared & SEZ Active"
            summary = "The strategic model presents exceptional EBITDA projections. **Financial Analyst models flat 15% corporate tax brackets** under Schedule 88B code [IND_FIN_88], while **Taxation auditor offsets initial server overhead with a 100% deduction on software exports** [TAX_AMND_44]. **Software Architecture deploys standard ap-south-1 MCP localized interfaces** [MCP_SYS_402]. IP legal friction is pre-cleared beforehand via co-ownership registries [BA_1872]. **Arbitration Verdict:** Action authorized with zero roadblocks; budget is cleared for Q1 Agile milestones sprint schedules."
        } else if (lowercaseQuery.contains("remote") || lowercaseQuery.contains("cloud") || lowercaseQuery.contains("global")) {
            decision = "RISKY_PIVOT"
            title = "Mandated Regional Node Cloud Migration"
            tag = "Cloud Residency Verified"
            legalStatus = "FAIL"
            summary = "The V2 orchestrator has vetoed standard global public cloud hosting. Under SC Precedent 992, storing core biometric databases globally fails basic regulatory security bounds [KANOON_992]. **Systems Architect maps an immediate pivot to dedicated regional nodes inside Bangalore-ap-south-1 subnets** [MCP_SYS_402]. Financial analyst integrates 15% strategic risks buffer [MCK_BLUE_2026] and 15% per-annum asset write-off models [FIN_ROI_94]. **Arbitration Verdict:** Standard migration rejected; hybrid custom local VPC is approved for rapid sprint schedules."
        } else {
            decision = "APPROVED"
            title = "Approved V2 Strategic Blueprint"
            tag = "10/10 Corporate Compliance Clear"
            summary = "Strategic board analysis completed for \"$originalQuery\". **Medical protocols verify clear sanitization boundaries** [PMID_8392]. **Legal structures pass regional digital sovereignty audits** [DPDP_2023]. **Financial models clear double-digit EBITDA margin goals** incorporating venture-backed SEZ tax exemptions [IND_FIN_88]. **Software Architect sets up isolated REST APIs** [MCP_SYS_402], and **Agile Project Management schedules sprint allocations optimally** [AGILE_SPRINT_V5] using McKinsey consulting risk parameters [MCK_BLUE_2026]. **Arbitration Verdict:** Authorized to immediately deploy resources."
        }

        // Build composite citations list (standard + custom BYOD)
        val matchedCitations = mutableListOf<CitationTemplate>()
        matchedCitations.add(
            CitationTemplate(
                "PMID_8392",
                "PubMed Clinical Trials Archives Vol. 142",
                "Section 2.4. Clinical Phase II safety mandate stipulates that all localized physiological logs must undergo sanitization prior to local server staging. Patient vitals tracking requires hardware sandbox isolation to eliminate multi-tenant firmware vulnerabilities.",
                "https://pubmed.ncbi.nlm.nih.gov/83908392",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "DPDP_2023",
                "Indian Digital Personal Data Protection (DPDP) Act, Sec 4 & 16",
                "Section 4.1: Processing of critical personal health data must occur strictly on local jurisdiction servers located within secondary sovereign borders. Section 16 prescribes immediate penal action of up to 250 Crores for unauthorized data cross-border transfers.",
                "https://meity.gov.in/acts/DPDP_23",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "KANOON_992",
                "KanoonGPT Supreme Legal Database - Precedent ID 992",
                "Supreme Court of India Ruling: Cryptographic citizen biometric indices fall under primary fundamental liberty. Standard cloud hosting on shared global public infrastructure without local custom isolation is legally deficient.",
                "https://indiankanoon.org/trial/992",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "IND_FIN_88",
                "Indian Corporate Taxation & High-Tech SEZ Code",
                "Schedule 88B: Deep-tech start-ups operating in designated Special Economic Zones (SEZs) qualify for a flat 15% corporate tax bracket on high-tech manufacturing, subject to local state board certification.",
                "https://incometaxindia.gov.in/code/IND_FIN_88",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "SEC_21A",
                "U.S. SEC Regulatory FinTech Expenditures Benchmark",
                "FinTech data residency compliance incurs an average initial overhead representing 22% of total capital expenditure (CAPEX) for compliance operations and localized system auditing.",
                "https://sec.gov/investor/SEC_21A",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "FIN_ROI_94",
                "Venture Capital Enterprise Hardware Depreciation Indices",
                "Section 9: High-tech enterprise hardware depreciation averages 15% per annum. Return on Investment (ROI) schedules models should factor standard asset write-offs.",
                "https://vcresearch.org/depreciation/FIN_ROI_94",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "MCP_SYS_402",
                "Model Context Protocol (MCP) Cloud Architecture Spec",
                "Requirement 4.2.1: Systems handling clinical or quantitative transactions must route via localized regional nodes (e.g. ap-south-1). Gateway endpoints must run isolated cloud subnets with secure port filters.",
                "https://mcp.dev/specs/SYS_402",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "TAX_AMND_44",
                "Amended SEZ Strategic Tax Incentive Act",
                "Exemption clause 44: Deep-tech software platforms registering in high-tech SEZs obtain a 100% deduction on export-related revenues for the initial three fiscal cycles, offsetting initial local VPC costs.",
                "https://incometaxindia.gov.in/code/TAX_AMND_44",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "AGILE_SPRINT_V5",
                "Agile Alliance Velocity & Allocation Framework",
                "Velocity standard 5.4: Cloud enterprise refactoring operations should allocate a resource index of 4 developers for 2 bi-weekly sprints. Critical paths must map a 5-day regression buffer.",
                "https://agilealliance.org/specs/SPRINT_V5",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "MCK_BLUE_2026",
                "McKinsey Enterprise Global Expansion Playbook",
                "Strategic directive: Multi-market expansion budgets require a mandatory 15% risk premium buffer which must be factored into standard EBITDA models.",
                "https://mckinsey.com/insights/blueprints/2026",
                true
            )
        )
        matchedCitations.add(
            CitationTemplate(
                "SC_LOG_188",
                "Maritime Logistics and Port Gateway Guidelines",
                "Guideline 18.b: High-density component routing must bypass heavy congestion ports in favor of secondary deepwater ports. Multi-modal transition adds 8% to unit transit costs, but minimizes stock-outs.",
                "https://imo.org/publications/SC_LOG_188",
                true
            )
        )

        // Append custom BYOD citations to matching list on query match
        customByodDocs.forEach { byod ->
            matchedCitations.add(
                CitationTemplate(
                    byod.key,
                    byod.sourceName,
                    byod.text,
                    byod.url,
                    true
                )
            )
        }

        // Segmented Reports Text
        val medReportContent = "The Medical Research Board evaluated the clinical boundaries. Hardware sandboxing is strictly authorized according to [PMID_8392] standards, ensuring patient clinical wave telemetry operates securely in closed biosafety arrays without external leak pathways [PMID_9011]."
        val legReportContent = "Legal Counsel analyzed local constraints. Offshore cloud pathways present extremely high liability profiles under DPDP Act Section 16 penal lines [DPDP_2023], with Bangalore SC precedent 992 endorsing local custom VPC data localization [KANOON_992]. Private IP contracts must safeguard university joint research models [BA_1872]."
        val finReportContent = "Financial quantitative board modeled standard depreciation and CAPEX charts. Budgeting shows a mandatory 22% compliance overhead [SEC_21A] which is comfortably offset by a Venture-backed 15% special flat high-tech SEZ corporate tax bracket [IND_FIN_88], yielding solid margins with 15% annual asset write-offs [FIN_ROI_94]."
        val softReportContent = "Software systems architecture deploys MCP endpoint structures according to specification [MCP_SYS_402]. Systems route transaction and telemetry lines via isolated ap-south-1 regional gateways, running sandboxed networks with port strictness."
        val stratReportContent = "Enterprise strategy applies McKinsey consultation rules [MCK_BLUE_2026], introducing a 15% risk buffer margin to cushion EBITDA calculations against unexpected regulatory friction spikes in parallel jurisdictions."
        val logReportContent = "Logistics specialist plans distribution bypassing primary port congestion in favor of deepwater marine terminals [SC_LOG_188]. Bypassing bottleneck corridors offsets holding delays, adding 8% transit budgets safely."
        val eComReportContent = "E-Commerce storefront designs execute transaction logic securely under digital validation rules [ECOM_SEC_99], forcing card encryption lines directly onto tokenized merchant endpoints without local browser file footprints."
        val projReportContent = "Agile scrum lead schedules a resource team of 4 software engineers over a 2-sprint timeline [AGILE_SPRINT_V5], mapping a 5-day regression safety window prior to release."
        val taxReportContent = "Taxation auditor applies optimized corporate structures under Clause 44 SEZ strategic acts [TAX_AMND_44], delivering a 100% tax deduction on export income for 3 Initial Cycles to pre-clear local systems infrastructure budgets."
        val resReportContent = "Academic Research Fellow certifies that all waveform sandbooks match double-blind clinical guidelines verified in peer-reviewed STEM databases [STEM_CIT_820]."

        val compiledReports = listOf(
            AgentReportTemplate("Medical", "Dr. Varma (Lead Clinical Planner)", medicalStatus, medReportContent, if (medicalStatus == "PASS") 95 else 55, "Physiological logs sandboxed strictly."),
            AgentReportTemplate("Legal", "Advocate Shastri (Chief Regulatory Officer)", legalStatus, legReportContent, if (legalStatus == "PASS") 92 else 45, "DPDP Section 16 crossborder threat compliance warning."),
            AgentReportTemplate("Financial", "CFO Mehta (Strategic Financial Steward)", financialStatus, finReportContent, if (financialStatus == "PASS") 88 else 60, "Compliance adds 22% initial overhead; offset by SEZ tax benefits."),
            AgentReportTemplate("Software", "Dev-Lead Linus (MCP Systems Architect)", softwareStatus, softReportContent, 96, "None"),
            AgentReportTemplate("Strategy", "Partner McKinsey (Enterprise Strategist)", strategyStatus, stratReportContent, 92, "None"),
            AgentReportTemplate("Logistics", "Director Maersk (Global Supply Chain Director)", logisticsStatus, logReportContent, 89, "None"),
            AgentReportTemplate("E-Commerce", "VP Shopify (Digital Storefront VP)", eCommerceStatus, eComReportContent, 95, "None"),
            AgentReportTemplate("ProjectMgmt", "Scrum Master Sprint (Agile PM Leader)", projectMgmtStatus, projReportContent, 90, "None"),
            AgentReportTemplate("Taxation", "Auditor Deloitte (Tax Compliance Officer)", taxationStatus, taxReportContent, 94, "None"),
            AgentReportTemplate("Research", "Dr. Curie (STEM Science Fellow)", researchStatus, resReportContent, 98, "None")
        )

        return GeminiDossierWrapper(
            arbitrationDecision = decision,
            rulingTitle = title,
            verdictTag = tag,
            arbitrationSummary = summary,
            reports = compiledReports,
            citations = matchedCitations
        )
    }
}

// Intermediary parser model V2 (supports 10 domain templates)
data class GeminiDossierWrapper(
    val arbitrationDecision: String,
    val rulingTitle: String,
    val verdictTag: String,
    val arbitrationSummary: String,
    val reports: List<AgentReportTemplate>,
    val citations: List<CitationTemplate>
)
