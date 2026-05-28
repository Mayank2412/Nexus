package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- 1. ROOM ENTITIES ---

@Entity(tableName = "dossiers")
data class DossierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queryText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val arbitrationSummary: String,
    val arbitrationDecision: String, // APPROVED, COMPROMISE_REQUIRED, RISKY_PIVOT, REJECTED
    val rulingTitle: String,
    val verdictTag: String
)

@Entity(
    tableName = "agent_reports",
    foreignKeys = [
        ForeignKey(
            entity = DossierEntity::class,
            parentColumns = ["id"],
            childColumns = ["dossierId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("dossierId")]
)
data class AgentReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dossierId: Long,
    val domain: String, // Medical, Legal, Financial
    val agentName: String,
    val statusBadge: String, // PASS, FAIL
    val content: String, // Report text with citations [KEY]
    val complianceScore: Int,
    val frictionsFound: String // Semi-colon separated or text
)

@Entity(
    tableName = "citations",
    foreignKeys = [
        ForeignKey(
            entity = DossierEntity::class,
            parentColumns = ["id"],
            childColumns = ["dossierId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("dossierId")]
)
data class CitationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dossierId: Long,
    val citationKey: String, // e.g. "PMID_8392" or "SEC_34"
    val sourceName: String, // Name of publication or case file
    val sourceText: String, // Raw extracted text chunk
    val url: String,
    val verified: Boolean
)

// --- 2. MULTI-ENTITY COMPOSITE RELATION ---

data class ExecutiveDossier(
    @Embedded val dossier: DossierEntity,
    @Relation(parentColumn = "id", entityColumn = "dossierId")
    val reports: List<AgentReportEntity>,
    @Relation(parentColumn = "id", entityColumn = "dossierId")
    val citations: List<CitationEntity>
)

// --- 3. DAO INTERFACE ---

@Dao
interface NexusDao {
    @Transaction
    @Query("SELECT * FROM dossiers ORDER BY timestamp DESC")
    fun getAllDossiersFlow(): Flow<List<ExecutiveDossier>>

    @Transaction
    @Query("SELECT * FROM dossiers WHERE id = :id")
    suspend fun getDossierById(id: Long): ExecutiveDossier?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDossier(dossier: DossierEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgentReport(report: AgentReportEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCitations(citations: List<CitationEntity>)

    @Query("DELETE FROM dossiers WHERE id = :id")
    suspend fun deleteDossierById(id: Long)

    @Query("DELETE FROM dossiers")
    suspend fun clearAllDossiers()
}

// --- 4. DATABASE CLASS ---

@Database(
    entities = [DossierEntity::class, AgentReportEntity::class, CitationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NexusDatabase : RoomDatabase() {
    abstract fun nexusDao(): NexusDao

    companion object {
        @Volatile
        private var INSTANCE: NexusDatabase? = null

        fun getDatabase(context: android.content.Context): NexusDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NexusDatabase::class.java,
                    "nexus_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
