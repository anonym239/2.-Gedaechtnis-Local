package de.gedaechtnis.local.data

import android.content.Context
import de.gedaechtnis.local.ai.AnswerComposer
import de.gedaechtnis.local.ai.AnswerSource
import de.gedaechtnis.local.ai.ComposedAnswer
import de.gedaechtnis.local.ai.LocalAiEngine
import de.gedaechtnis.local.ai.ParsedQuery
import de.gedaechtnis.local.ai.QueryParser
import de.gedaechtnis.local.ai.VectorMath
import de.gedaechtnis.local.data.db.AppCount
import de.gedaechtnis.local.data.db.AppDatabase
import de.gedaechtnis.local.data.db.ContextSnippet
import de.gedaechtnis.local.data.db.SnippetDao
import de.gedaechtnis.local.data.db.SnippetSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Ein Suchtreffer mit kombiniertem Relevanzwert. */
data class SearchHit(val snippet: ContextSnippet, val score: Double, val matchedTerms: List<String>)

data class SearchResult(
    val query: ParsedQuery,
    val hits: List<SearchHit>,
    val usedSemanticSearch: Boolean,
    /** Ausformulierte Antwort ohne LLM (sofort verfügbar). */
    val composed: ComposedAnswer? = null,
)

/**
 * Zentrale Datenzugriffsschicht. Speichert Kontext-Snippets und kombiniert für die Suche
 *  1. **Volltext** (FTS4, Präfixsuche) und
 *  2. **semantische Ähnlichkeit** (lokale Embeddings, Kosinus)
 * per Reciprocal Rank Fusion. Zeitangaben in der Frage schränken das Suchfenster ein.
 */
class ContextRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val ai: LocalAiEngine,
) {
    private val dao: SnippetDao = db.snippetDao()

    val count: Flow<Int> = dao.observeCount()
    val embeddedCount: Flow<Int> = dao.observeEmbeddedCount()
    val lastCaptureAt: Flow<Long?> = dao.observeLastTimestamp()
    fun recent(limit: Int = 20): Flow<List<ContextSnippet>> = dao.observeRecent(limit)
    fun topApps(limit: Int = 5): Flow<List<AppCount>> = dao.observeTopApps(limit)

    /**
     * Speichert ein Snippet, sofern derselbe Inhalt derselben App nicht in den letzten
     * [DEDUP_WINDOW_MS] erfasst wurde. Gibt die neue ID oder null (Duplikat) zurück.
     */
    suspend fun insertSnippet(
        packageName: String,
        appLabel: String,
        windowTitle: String?,
        text: String,
        source: SnippetSource,
        timestamp: Long = System.currentTimeMillis(),
    ): Long? = withContext(Dispatchers.IO) {
        val clean = normalize(text)
        if (clean.length < MIN_TEXT_LENGTH) return@withContext null
        val hash = VectorMath.fnv1a64(clean.lowercase())
        if (dao.existsSince(hash, packageName, timestamp - DEDUP_WINDOW_MS)) return@withContext null
        dao.insert(
            ContextSnippet(
                timestamp = timestamp,
                packageName = packageName,
                appLabel = appLabel,
                windowTitle = windowTitle?.take(200),
                text = clean.take(MAX_TEXT_LENGTH),
                source = source,
                contentHash = hash,
            ),
        )
    }

    /** Eigene Notiz oder Sprachnotiz – wird sofort eingebettet, da selten und wertvoll. */
    suspend fun addNote(text: String, voice: Boolean): Long? {
        val id = insertSnippet(
            packageName = context.packageName,
            appLabel = if (voice) "Sprachnotiz" else "Notiz",
            windowTitle = null,
            text = text,
            source = if (voice) SnippetSource.VOICE else SnippetSource.NOTE,
        ) ?: return null
        ai.embed(text)?.let { dao.setEmbedding(id, VectorMath.toBytes(it)) }
        return id
    }

    suspend fun search(rawQuery: String, limit: Int = 40): SearchResult = withContext(Dispatchers.Default) {
        val q = QueryParser.parse(rawQuery)

        // Überblicksfrage ohne Suchbegriffe ("Was habe ich heute gemacht?") → alles aus dem Zeitraum.
        if (q.keywords.isEmpty()) {
            val all = dao.inRange(q.from, q.to, SUMMARY_LIMIT)
            val hits = all.mapIndexed { rank, s -> SearchHit(s, 1.0 / (RRF_K + rank), emptyList()) }
            return@withContext SearchResult(q, hits, usedSemanticSearch = false, composed = compose(q, hits))
        }

        val rrf = HashMap<Long, Double>()
        val byId = HashMap<Long, ContextSnippet>()

        // 1) Volltext
        val match = QueryParser.toFtsMatch(q.keywords)
        if (match != null) {
            val ftsHits = runCatching { dao.searchFts(match, q.from, q.to, CANDIDATES) }.getOrDefault(emptyList())
            // Nach Anzahl getroffener Begriffe ranken; bei Gleichstand gewinnt der neuere Eintrag.
            ftsHits.sortedByDescending { termHits(it, q.keywords) }.forEachIndexed { rank, s ->
                byId[s.id] = s
                rrf.merge(s.id, 1.0 / (RRF_K + rank), Double::plus)
            }
        }

        // 2) Semantisch
        var semantic = false
        val queryVec = ai.embed(q.keywords.joinToString(" "))
        if (queryVec != null) {
            semantic = true
            val scored = dao.embeddingsInRange(q.from, q.to, VECTOR_SCAN_LIMIT)
                // Nur Vektoren desselben Verfahrens vergleichen (MediaPipe vs. Ersatz haben andere Längen).
                .filter { it.embedding.size == queryVec.size * 4 }
                .map { it.id to VectorMath.dot(queryVec, VectorMath.fromBytes(it.embedding)) }
                .filter { it.second >= MIN_SIMILARITY }
                .sortedByDescending { it.second }
                .take(CANDIDATES)
            scored.forEachIndexed { rank, (id, _) -> rrf.merge(id, 1.0 / (RRF_K + rank), Double::plus) }
            val missing = scored.map { it.first }.filterNot(byId::containsKey)
            missing.chunked(500).forEach { chunk -> dao.byIds(chunk).forEach { byId[it.id] = it } }
        }

        val hits = rrf.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (id, score) ->
                byId[id]?.let { s -> SearchHit(s, score, q.keywords.filter { s.text.contains(it, ignoreCase = true) }) }
            }
        SearchResult(q, hits, semantic, compose(q, hits))
    }

    private fun compose(q: ParsedQuery, hits: List<SearchHit>): ComposedAnswer? =
        AnswerComposer.compose(q, hits.map { AnswerSource(it.snippet.timestamp, it.snippet.appLabel, it.snippet.windowTitle, it.snippet.text) })

    /** Berechnet fehlende Embeddings in kleinen Stapeln. Gibt die Anzahl verarbeiteter Snippets zurück. */
    suspend fun embedPending(batchSize: Int = 64): Int {
        if (!ai.isEmbedderAvailable) return 0
        val pending = dao.pendingEmbedding(batchSize)
        var done = 0
        for (s in pending) {
            val vec = ai.embed("${s.appLabel} ${s.windowTitle.orEmpty()} ${s.text}") ?: break
            dao.setEmbedding(s.id, VectorMath.toBytes(vec))
            done++
        }
        return done
    }

    suspend fun applyRetention(days: Int): Int =
        dao.deleteOlderThan(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)

    suspend fun deleteForApp(pkg: String) = dao.deleteByPackage(pkg)

    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    /** Größe der Datenbankdateien (inkl. WAL) in Bytes. */
    fun databaseSizeBytes(): Long {
        val main = context.getDatabasePath(AppDatabase.NAME)
        return listOf(main, File("${main.path}-wal"), File("${main.path}-shm")).sumOf { if (it.exists()) it.length() else 0L }
    }

    private fun termHits(s: ContextSnippet, keywords: List<String>): Int {
        val text = s.text.lowercase()
        return keywords.count { text.contains(it) }
    }

    private fun normalize(text: String): String =
        text.lineSequence()
            .map { it.trim().replace(Regex("[\\t ]+"), " ") }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")

    companion object {
        private const val MIN_TEXT_LENGTH = 12
        private const val MAX_TEXT_LENGTH = 8_000
        private const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
        private const val CANDIDATES = 120
        private const val VECTOR_SCAN_LIMIT = 20_000
        private const val MIN_SIMILARITY = 0.25f
        private const val RRF_K = 60.0
        private const val SUMMARY_LIMIT = 150
    }
}
