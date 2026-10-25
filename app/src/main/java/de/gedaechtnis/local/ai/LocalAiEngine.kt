package de.gedaechtnis.local.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import de.gedaechtnis.local.data.db.ContextSnippet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Welches Verfahren die semantische Suche gerade nutzt. */
enum class EmbedderKind { MEDIAPIPE, FALLBACK }

/** Zustand des generativen Modells für die UI. */
enum class LlmState { NOT_INSTALLED, READY, LOADING, LOADED, ERROR }

/**
 * Kapselt die gesamte On-Device-KI:
 *  - **Embeddings** über MediaPipe TextEmbedder (Universal Sentence Encoder, ~6 MB) mit robustem
 *    Ersatzverfahren ([HashingEmbedder]), falls MediaPipe auf dem Gerät nicht startet.
 *  - **Textgenerierung** über Google **LiteRT-LM** mit dem in [ModelManager] aktiven Modell
 *    (GPU, Fallback CPU). Antworten werden Stück für Stück gestreamt.
 *
 * Ressourcenschonung: Das Sprachmodell wird erst bei einer Frage geladen und nach
 * [LLM_IDLE_RELEASE_MS] Inaktivität oder bei Speicherdruck wieder freigegeben.
 */
class LocalAiEngine(private val context: Context, private val models: ModelManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val embedderMutex = Mutex()
    private var embedder: TextEmbedder? = null
    @Volatile private var embedderFailed = false

    private val llmMutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelId: String? = null
    private var releaseJob: Job? = null

    private val _llmState = MutableStateFlow(if (models.activeModel() != null) LlmState.READY else LlmState.NOT_INSTALLED)
    val llmState: StateFlow<LlmState> = _llmState.asStateFlow()

    /** Letzte Fehlermeldung beim Laden des Sprachmodells. */
    @Volatile var llmError: String? = null
        private set

    private val hasEmbedderAsset: Boolean by lazy {
        runCatching { context.assets.list("")?.contains(EMBEDDER_ASSET) == true }.getOrDefault(false)
    }

    /** Semantische Suche ist immer verfügbar – notfalls über das Ersatzverfahren. */
    val isEmbedderAvailable: Boolean = true

    private val _embedderKind = MutableStateFlow(if (hasEmbedderAsset) EmbedderKind.MEDIAPIPE else EmbedderKind.FALLBACK)
    val embedderKind: StateFlow<EmbedderKind> = _embedderKind.asStateFlow()

    /** Fehlermeldung, falls MediaPipe nicht starten konnte (für die Anzeige im Dashboard). */
    @Volatile var embedderError: String? = null
        private set

    init {
        // Wechselt der Nutzer das aktive Modell oder löscht es, das geladene sofort freigeben.
        scope.launch {
            models.activeId.collect { id ->
                if (id != loadedModelId) releaseLlm()
                if (_llmState.value != LlmState.LOADING) {
                    _llmState.value = if (id != null) LlmState.READY else LlmState.NOT_INSTALLED
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Embeddings
    // ------------------------------------------------------------------------------------------

    /**
     * Liefert einen L2-normalisierten Vektor. Primär MediaPipe (Universal Sentence Encoder);
     * kann das Modell nicht geladen werden, greift das robuste [HashingEmbedder]-Ersatzverfahren.
     * Vektoren beider Verfahren haben unterschiedliche Längen und werden nie miteinander verglichen.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext null
        val input = text.take(MAX_EMBED_CHARS)
        embedderMutex.withLock {
            val e = if (hasEmbedderAsset && !embedderFailed) embedder ?: createEmbedder() else null
            if (e != null) {
                runCatching {
                    VectorMath.normalize(e.embed(input).embeddingResult().embeddings()[0].floatEmbedding())
                }.onFailure {
                    Log.w(TAG, "Embedding fehlgeschlagen – nutze Ersatzverfahren", it)
                    markEmbedderFailed(it)
                }.getOrNull()?.let { return@withLock it }
            }
            HashingEmbedder.embed(input)
        }
    }

    private fun createEmbedder(): TextEmbedder? = runCatching {
        val options = TextEmbedder.TextEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(EMBEDDER_ASSET).build())
            .build()
        TextEmbedder.createFromOptions(context, options).also { embedder = it }
    }.onFailure {
        Log.e(TAG, "TextEmbedder konnte nicht geladen werden", it)
        markEmbedderFailed(it)
    }.getOrNull()

    private fun markEmbedderFailed(t: Throwable) {
        embedderFailed = true
        embedderError = describe(t)
        _embedderKind.value = EmbedderKind.FALLBACK
    }

    // ------------------------------------------------------------------------------------------
    // Generative Antwort (RAG) mit LiteRT-LM
    // ------------------------------------------------------------------------------------------

    /**
     * Beantwortet [question] ausschließlich anhand der übergebenen Kontext-Snippets und liefert den
     * bisher erzeugten Antworttext fortlaufend (Streaming). Leerer Flow, wenn kein Modell aktiv ist.
     */
    fun answerStream(
        question: String,
        contexts: List<ContextSnippet>,
        summary: Boolean = false,
        periodLabel: String? = null,
    ): Flow<String> = flow {
        val model = models.activeModel() ?: return@flow
        if (contexts.isEmpty()) return@flow
        llmMutex.withLock {
            releaseJob?.cancel()
            val eng = engine?.takeIf { loadedModelId == model.id } ?: load(model) ?: return@withLock
            val cat = model.catalog
            val sampler = SamplerConfig(
                topK = cat?.topK ?: 40,
                topP = cat?.topP ?: 0.9,
                temperature = cat?.temperature ?: 0.7,
            )
            try {
                eng.createConversation(
                    ConversationConfig(systemInstruction = Contents.of(systemPrompt(summary, periodLabel)), samplerConfig = sampler),
                ).use { conversation ->
                    val raw = StringBuilder()
                    conversation.sendMessageAsync(userPrompt(question, contexts, summary, contextBudget(model)))
                        .collect { chunk ->
                            raw.append(chunk.toString())
                            emit(cleanOutput(raw.toString(), cat?.reasoning == true))
                        }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "LLM-Generierung fehlgeschlagen", t)
                llmError = describe(t)
            } finally {
                scheduleRelease()
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun load(model: InstalledModel): Engine? {
        releaseEngineUnsafe()
        val minRam = model.catalog?.minRamGb ?: 4
        if (models.deviceRamGb < minRam - 1 || isLowOnMemory()) {
            llmError = "Zu wenig Arbeitsspeicher für ${model.name} (empfohlen ab $minRam GB)."
            _llmState.value = LlmState.ERROR
            return null
        }
        _llmState.value = LlmState.LOADING
        // Erst GPU (schnell, entlastet die CPU), dann CPU als Fallback.
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            val result = runCatching {
                Engine(
                    EngineConfig(
                        modelPath = model.file.absolutePath,
                        backend = backend,
                        maxNumTokens = maxTokens(model),
                        cacheDir = context.cacheDir.absolutePath,
                    ),
                ).also { it.initialize() }
            }
            result.getOrNull()?.let {
                Log.i(TAG, "LLM ${model.name} geladen (Backend: ${backend.name})")
                engine = it
                loadedModelId = model.id
                llmError = null
                _llmState.value = LlmState.LOADED
                return it
            }
            Log.w(TAG, "Backend ${backend.name} nicht verfügbar", result.exceptionOrNull())
            llmError = result.exceptionOrNull()?.let(::describe)
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

    /** Gibt den Speicher des Sprachmodells frei (auch bei onTrimMemory). */
    suspend fun releaseLlm() = llmMutex.withLock { releaseEngineUnsafe() }

    private fun releaseEngineUnsafe() {
        runCatching { engine?.close() }
        engine = null
        loadedModelId = null
        if (_llmState.value == LlmState.LOADED || _llmState.value == LlmState.LOADING) {
            _llmState.value = if (models.activeModel() != null) LlmState.READY else LlmState.NOT_INSTALLED
        }
    }

    private fun isLowOnMemory(): Boolean {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.lowMemory
    }

    private fun maxTokens(model: InstalledModel): Int = if (model.catalog?.id == "gemma3-1b") 1024 else 2048

    /** Wie viele Zeichen Kontext ins Prompt-Fenster passen (≈ 3 Zeichen pro Token, Rest für die Antwort). */
    private fun contextBudget(model: InstalledModel): Int = ((maxTokens(model) - 600) * 3).coerceAtLeast(800)

    private fun systemPrompt(summary: Boolean, periodLabel: String?): String {
        val task = if (summary) {
            "Fasse in 3 bis 5 kurzen, vollständigen Sätzen zusammen, womit sich der Nutzer " +
                "${periodLabel?.lowercase() ?: "in diesem Zeitraum"} beschäftigt hat. Nenne die wichtigsten Themen und Apps."
        } else {
            "Beantworte die Frage in 1 bis 3 vollständigen Sätzen. Formuliere die Antwort selbst, zitiere nur kurze, " +
                "entscheidende Stellen und nenne App und Zeitpunkt der Quelle. Wenn die Notizen die Antwort nicht enthalten, sage das ehrlich."
        }
        return "Du bist ein persönlicher Gedächtnis-Assistent. Dir liegen Notizen vor, die automatisch vom Bildschirm " +
            "des Nutzers erfasst wurden. Antworte immer auf Deutsch in der Du-Form, freundlich und präzise. " +
            "Erfinde nichts und gib keine langen Rohtexte wieder. $task"
    }

    private fun userPrompt(question: String, contexts: List<ContextSnippet>, summary: Boolean, budgetChars: Int): String {
        val fmt = SimpleDateFormat("EEEE, dd.MM. HH:mm", Locale.GERMANY)
        // Für Zusammenfassungen gleichmäßig über den Zeitraum verteilte Ausschnitte, sonst die besten Treffer.
        val picked = if (summary) {
            val chrono = contexts.sortedBy { it.timestamp }
            val step = maxOf(1, chrono.size / MAX_SUMMARY_SNIPPETS)
            chrono.filterIndexed { i, _ -> i % step == 0 }.take(MAX_SUMMARY_SNIPPETS)
        } else contexts.take(6)
        val perSnippet = if (summary) 220 else 600

        val sb = StringBuilder()
        var budget = budgetChars
        picked.forEachIndexed { i, s ->
            if (budget <= 0) return@forEachIndexed
            val text = s.text.replace(Regex("\\s+"), " ").take(minOf(perSnippet, budget))
            budget -= text.length
            sb.append("[${i + 1}] ${fmt.format(Date(s.timestamp))} · ${s.appLabel}")
            s.windowTitle?.takeIf { it.isNotBlank() }?.let { sb.append(" · ").append(it) }
            sb.append('\n').append(text).append("\n\n")
        }
        return "Notizen:\n$sb\nFrage: $question"
    }

    companion object {
        private const val TAG = "LocalAiEngine"
        const val EMBEDDER_ASSET = "universal_sentence_encoder.tflite"
        private const val MAX_EMBED_CHARS = 2000
        private const val MAX_SUMMARY_SNIPPETS = 12
        private const val LLM_IDLE_RELEASE_MS = 90_000L

        /** Entfernt "Denk"-Abschnitte von Reasoning-Modellen; solange sie noch laufen, wird nichts angezeigt. */
        fun cleanOutput(raw: String, reasoning: Boolean): String {
            var text = raw
            val end = text.lastIndexOf("</think>")
            if (end >= 0) text = text.substring(end + "</think>".length)
            else if (reasoning || text.trimStart().startsWith("<think>")) return ""
            return text.trim()
        }

        /** Lesbare Fehlerbeschreibung inklusive der eigentlichen Ursache. */
        fun describe(t: Throwable): String {
            val root = generateSequence(t) { it.cause }.last()
            val outer = "${t.javaClass.simpleName}: ${t.message ?: "–"}"
            return (if (root !== t) "$outer → ${root.javaClass.simpleName}: ${root.message ?: "–"}" else outer).take(300)
        }
    }
}
