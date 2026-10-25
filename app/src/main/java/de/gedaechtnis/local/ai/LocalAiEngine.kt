package de.gedaechtnis.local.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import de.gedaechtnis.local.data.db.ContextSnippet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zustand des generativen Modells für die UI. */
enum class LlmState { NOT_INSTALLED, READY, LOADING, LOADED, ERROR }

/**
 * Kapselt die gesamte On-Device-KI:
 *  - **Embeddings** über MediaPipe TextEmbedder (Universal Sentence Encoder, ~6 MB, CPU, < 5 ms/Text).
 *  - **Textgenerierung** über die MediaPipe LLM Inference API (z. B. Gemma 3 1B int4) auf GPU, Fallback CPU.
 *
 * Ressourcenschonung:
 *  - Beide Modelle werden *lazy* geladen.
 *  - Das LLM (mehrere hundert MB RAM) wird nach [LLM_IDLE_RELEASE_MS] Inaktivität wieder freigegeben.
 *  - Vor dem Laden wird geprüft, ob genügend freier Arbeitsspeicher vorhanden ist.
 */
class LocalAiEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val embedderMutex = Mutex()
    private var embedder: TextEmbedder? = null
    private var embedderFailed = false

    private val llmMutex = Mutex()
    private var llm: LlmInference? = null
    private var releaseJob: Job? = null

    private val _llmState = MutableStateFlow(if (llmModelFile.exists()) LlmState.READY else LlmState.NOT_INSTALLED)
    val llmState: StateFlow<LlmState> = _llmState.asStateFlow()

    val llmModelFile: File get() = File(context.filesDir, "models/$LLM_FILE_NAME")

    val isEmbedderAvailable: Boolean by lazy {
        runCatching { context.assets.list("")?.contains(EMBEDDER_ASSET) == true }.getOrDefault(false)
    }

    // ------------------------------------------------------------------------------------------
    // Embeddings
    // ------------------------------------------------------------------------------------------

    /** Liefert einen L2-normalisierten Vektor oder null, wenn kein Embedding-Modell verfügbar ist. */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.Default) {
        if (!isEmbedderAvailable || embedderFailed || text.isBlank()) return@withContext null
        embedderMutex.withLock {
            val e = embedder ?: createEmbedder() ?: return@withLock null
            runCatching {
                val raw = e.embed(text.take(MAX_EMBED_CHARS))
                    .embeddingResult().embeddings()[0].floatEmbedding()
                VectorMath.normalize(raw)
            }.onFailure { Log.w(TAG, "Embedding fehlgeschlagen", it) }.getOrNull()
        }
    }

    private fun createEmbedder(): TextEmbedder? = runCatching {
        val options = TextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(EMBEDDER_ASSET).build())
            .build()
        TextEmbedder.createFromOptions(context, options).also { embedder = it }
    }.onFailure {
        embedderFailed = true
        Log.e(TAG, "TextEmbedder konnte nicht geladen werden", it)
    }.getOrNull()

    // ------------------------------------------------------------------------------------------
    // Generative Antwort (RAG)
    // ------------------------------------------------------------------------------------------

    /**
     * Beantwortet [question] ausschließlich anhand der übergebenen Kontext-Snippets.
     * Gibt null zurück, wenn kein LLM installiert ist oder zu wenig RAM frei ist.
     */
    suspend fun answer(question: String, contexts: List<ContextSnippet>): String? = withContext(Dispatchers.Default) {
        if (contexts.isEmpty() || !llmModelFile.exists()) return@withContext null
        llmMutex.withLock {
            releaseJob?.cancel()
            val engine = llm ?: loadLlm() ?: return@withLock null
            try {
                engine.generateResponse(buildPrompt(question, contexts)).trim()
            } catch (t: Throwable) {
                Log.e(TAG, "LLM-Generierung fehlgeschlagen", t)
                null
            } finally {
                scheduleRelease()
            }
        }
    }

    private fun loadLlm(): LlmInference? {
        if (!hasEnoughMemoryFor(llmModelFile.length())) {
            Log.w(TAG, "Zu wenig freier RAM für das LLM – nutze extraktive Antwort.")
            return null
        }
        _llmState.value = LlmState.LOADING
        // Erst GPU (schnell, entlastet CPU), dann CPU als Fallback.
        for (backend in listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)) {
            val result = runCatching {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(llmModelFile.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setMaxTopK(40)
                    .setPreferredBackend(backend)
                    .build()
                LlmInference.createFromOptions(context, options)
            }
            result.getOrNull()?.let {
                Log.i(TAG, "LLM geladen (Backend: $backend)")
                llm = it
                _llmState.value = LlmState.LOADED
                return it
            }
            Log.w(TAG, "LLM-Backend $backend nicht verfügbar", result.exceptionOrNull())
        }
        _llmState.value = LlmState.ERROR
        return null
    }

    private fun scheduleRelease() {
        releaseJob = scope.launch {
            delay(LLM_IDLE_RELEASE_MS)
            releaseLlm()
        }
    }

    /** Gibt den LLM-Speicher frei (wird auch bei onTrimMemory aufgerufen). */
    suspend fun releaseLlm() = llmMutex.withLock {
        llm?.close()
        llm = null
        _llmState.value = if (llmModelFile.exists()) LlmState.READY else LlmState.NOT_INSTALLED
    }

    private fun hasEnoughMemoryFor(modelBytes: Long): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        if (info.lowMemory) return false
        // Faustregel: Modellgröße + 300 MB Puffer für KV-Cache und Laufzeit.
        return info.availMem > modelBytes + 300L * 1024 * 1024
    }

    private fun buildPrompt(question: String, contexts: List<ContextSnippet>): String {
        val fmt = SimpleDateFormat("EEEE, dd.MM. HH:mm", Locale.GERMANY)
        val sb = StringBuilder()
        var budget = MAX_CONTEXT_CHARS
        contexts.forEachIndexed { i, s ->
            if (budget <= 0) return@forEachIndexed
            val text = s.text.replace(Regex("\\s+"), " ").take(minOf(600, budget))
            budget -= text.length
            sb.append("[${i + 1}] ${fmt.format(Date(s.timestamp))} · ${s.appLabel}")
            s.windowTitle?.takeIf { it.isNotBlank() }?.let { sb.append(" · ").append(it) }
            sb.append('\n').append(text).append("\n\n")
        }
        // Gemma-Chat-Template; andere Modelle ignorieren die Marker weitgehend.
        return """<start_of_turn>user
Du bist ein persönliches Gedächtnis. Beantworte die Frage kurz auf Deutsch und NUR anhand der folgenden Notizen vom Bildschirm des Nutzers. Nenne App und Zeitpunkt der Quelle als [Nummer]. Wenn die Notizen die Antwort nicht enthalten, sage das ehrlich.

Notizen:
$sb
Frage: $question<end_of_turn>
<start_of_turn>model
"""
    }

    // ------------------------------------------------------------------------------------------
    // Modellverwaltung
    // ------------------------------------------------------------------------------------------

    /** Kopiert ein vom Nutzer gewähltes Modell (.task/.bin) in den privaten App-Speicher. */
    suspend fun importLlmModel(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            releaseLlm()
            val target = llmModelFile
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$LLM_FILE_NAME.part")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Datei kann nicht geöffnet werden" }
                tmp.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
            }
            check(tmp.length() > 1024 * 1024) { "Datei ist zu klein für ein LLM-Modell" }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "Modell konnte nicht gespeichert werden" }
            _llmState.value = LlmState.READY
            target.length()
        }
    }

    suspend fun deleteLlmModel() {
        releaseLlm()
        llmModelFile.delete()
        _llmState.value = LlmState.NOT_INSTALLED
    }

    companion object {
        private const val TAG = "LocalAiEngine"
        const val EMBEDDER_ASSET = "universal_sentence_encoder.tflite"
        const val LLM_FILE_NAME = "llm.task"
        private const val MAX_EMBED_CHARS = 2000
        private const val MAX_TOKENS = 1280
        private const val MAX_CONTEXT_CHARS = 2800
        private const val LLM_IDLE_RELEASE_MS = 90_000L
    }
}
