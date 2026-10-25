package de.gedaechtnis.local.ai

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Ein Modell aus dem Katalog, das direkt in der App heruntergeladen werden kann. */
data class CatalogModel(
    val id: String,
    val name: String,
    val repo: String,
    val commit: String,
    val fileName: String,
    val sizeBytes: Long,
    val minRamGb: Int,
    val description: String,
    /** Hugging Face verlangt für dieses Modell ein Konto und das Akzeptieren der Lizenz (Token). */
    val gated: Boolean,
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    /** Modell gibt "Denk"-Text in <think>…</think> aus, der nicht angezeigt werden soll. */
    val reasoning: Boolean = false,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$commit/$fileName?download=true"
}

/** Ein installiertes Modell auf dem Gerät (aus dem Katalog oder selbst importiert). */
data class InstalledModel(val id: String, val name: String, val file: File, val catalog: CatalogModel?)

sealed interface DownloadState {
    data class Running(val downloaded: Long, val total: Long) : DownloadState {
        val progress: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data class Failed(val message: String, val needsToken: Boolean) : DownloadState
}

/**
 * Verwaltet die lokalen Sprachmodelle: Katalog, Download direkt in der App (über den System-
 * DownloadManager mit Fortschritt in der Benachrichtigung), Import eigener Dateien, mehrere
 * installierte Modelle und welches davon aktiv ist.
 *
 * Alle Modelle liegen im app-eigenen Speicher (Android/data/<paket>/files/models) und werden nur
 * von dieser App gelesen. Das Netzwerk wird ausschließlich für diese Downloads verwendet.
 */
class ModelManager(private val context: Context) {

    companion object {
        /**
         * Kuratierte LiteRT-LM-Modelle (.litertlm), getestet von Googles "AI Edge Gallery".
         * Feste Commit-Hashes garantieren, dass genau die geprüfte Datei geladen wird.
         */
        val CATALOG = listOf(
            CatalogModel(
                id = "qwen2.5-1.5b",
                name = "Qwen 2.5 1.5B Instruct",
                repo = "litert-community/Qwen2.5-1.5B-Instruct",
                commit = "19edb84c69a0212f29a6ef17ba0d6f278b6a1614",
                fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                sizeBytes = 1_597_931_520,
                minRamGb = 6,
                description = "Bester Allrounder: gutes Deutsch, schnelle Antworten. Ohne Konto direkt installierbar.",
                gated = false,
                topK = 20, topP = 0.8, temperature = 0.7,
            ),
            CatalogModel(
                id = "gemma3-1b",
                name = "Gemma 3 1B",
                repo = "litert-community/Gemma3-1B-IT",
                commit = "42d538a932e8d5b12e6b3b455f5572560bd60b2c",
                fileName = "gemma3-1b-it-int4.litertlm",
                sizeBytes = 584_417_280,
                minRamGb = 4,
                description = "Klein und sehr sparsam – ideal für ältere Geräte. Benötigt einen Hugging-Face-Token.",
                gated = true,
                topK = 64, topP = 0.95, temperature = 0.8,
            ),
            CatalogModel(
                id = "deepseek-r1-1.5b",
                name = "DeepSeek R1 Distill 1.5B",
                repo = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
                commit = "e34bb88632342d1f9640bad579a45134eb1cf988",
                fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
                sizeBytes = 1_833_451_520,
                minRamGb = 6,
                description = "\"Denkt\" vor der Antwort nach – gründlicher, aber langsamer. Ohne Konto installierbar.",
                gated = false,
                topK = 64, topP = 0.95, temperature = 0.6,
                reasoning = true,
            ),
            CatalogModel(
                id = "gemma3n-e2b",
                name = "Gemma 3n E2B",
                repo = "google/gemma-3n-E2B-it-litert-lm",
                commit = "ba9ca88da013b537b6ed38108be609b8db1c3a16",
                fileName = "gemma-3n-E2B-it-int4.litertlm",
                sizeBytes = 3_655_827_456,
                minRamGb = 8,
                description = "Das klügste Modell: versteht längere Zusammenhänge am besten. Benötigt viel RAM und einen Token.",
                gated = true,
                topK = 64, topP = 0.95, temperature = 0.8,
            ),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    /** Speicherort aller Modelle (app-eigen, ohne Speicherberechtigung). */
    val modelsDir: File = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _activeId = MutableStateFlow(resolveActiveId(_installed.value))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private var pollJob: Job? = null

    var hfToken: String
        get() = prefs.getString("hf_token", "").orEmpty()
        set(value) = prefs.edit().putString("hf_token", value.trim()).apply()

    init {
        migrateLegacyModel()
        if (pendingDownloads().isNotEmpty()) startPolling()
    }

    val deviceRamGb: Int by lazy {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        Math.round(info.totalMem / (1024.0 * 1024 * 1024)).toInt()
    }

    /** Das beste Katalogmodell ohne Token, das in den Arbeitsspeicher dieses Geräts passt. */
    val recommended: CatalogModel
        get() = CATALOG.filter { !it.gated && it.minRamGb <= deviceRamGb && !it.reasoning }.maxByOrNull { it.sizeBytes }
            ?: CATALOG.minBy { it.minRamGb }

    fun activeModel(): InstalledModel? = _installed.value.firstOrNull { it.id == _activeId.value }

    fun setActive(id: String) {
        prefs.edit().putString("active", id).apply()
        _activeId.value = id
    }

    fun freeSpaceBytes(): Long = runCatching { StatFs(modelsDir.path).availableBytes }.getOrDefault(0L)

    // ------------------------------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------------------------------

    /** Startet den Download. Gibt eine Fehlermeldung zurück oder null bei Erfolg. */
    fun download(model: CatalogModel): String? {
        if (_downloads.value[model.id] is DownloadState.Running) return null
        if (model.gated && hfToken.isBlank()) {
            _downloads.value = _downloads.value + (model.id to DownloadState.Failed("Für dieses Modell wird ein Hugging-Face-Token benötigt.", true))
            return "Token benötigt"
        }
        if (freeSpaceBytes() < model.sizeBytes + 200L * 1024 * 1024) {
            return "Nicht genug freier Speicher (benötigt ${model.sizeBytes / 1_000_000} MB)."
        }
        val target = File(modelsDir, model.fileName)
        target.delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("${model.name} wird geladen")
            .setDescription("Lokales Sprachmodell für Zweit-Gedächtnis")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        if (model.gated) request.addRequestHeader("Authorization", "Bearer $hfToken")
        val downloadId = runCatching { downloadManager.enqueue(request) }.getOrElse { return "Download konnte nicht gestartet werden: ${it.message}" }
        prefs.edit().putLong("dl_${model.id}", downloadId).apply()
        _downloads.value = _downloads.value + (model.id to DownloadState.Running(0, model.sizeBytes))
        startPolling()
        return null
    }

    fun cancelDownload(model: CatalogModel) {
        val id = prefs.getLong("dl_${model.id}", -1)
        if (id >= 0) downloadManager.remove(id)
        prefs.edit().remove("dl_${model.id}").apply()
        File(modelsDir, model.fileName).delete()
        _downloads.value = _downloads.value - model.id
    }

    fun clearError(modelId: String) {
        if (_downloads.value[modelId] is DownloadState.Failed) _downloads.value = _downloads.value - modelId
    }

    private fun pendingDownloads(): Map<CatalogModel, Long> =
        CATALOG.mapNotNull { m -> prefs.getLong("dl_${m.id}", -1).takeIf { it >= 0 }?.let { m to it } }.toMap()

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val pending = pendingDownloads()
                if (pending.isEmpty()) break
                pending.forEach { (model, id) -> updateDownload(model, id) }
                delay(1000)
            }
        }
    }

    private fun updateDownload(model: CatalogModel, downloadId: Long) {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return
        cursor.use { c ->
            if (!c.moveToFirst()) {
                prefs.edit().remove("dl_${model.id}").apply()
                _downloads.value = _downloads.value - model.id
                return
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: model.sizeBytes
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove("dl_${model.id}").apply()
                    _downloads.value = _downloads.value - model.id
                    refreshInstalled()
                    // Erstes installiertes Modell automatisch aktivieren.
                    if (activeModel() == null) setActive(model.id)
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove("dl_${model.id}").apply()
                    File(modelsDir, model.fileName).delete()
                    val needsToken = reason == 401 || reason == 403
                    val msg = when {
                        needsToken -> "Zugriff verweigert ($reason): Token fehlt/ungültig oder Lizenz auf Hugging Face nicht akzeptiert."
                        reason == DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Nicht genug Speicherplatz."
                        reason == DownloadManager.ERROR_CANNOT_RESUME -> "Download abgebrochen – bitte erneut versuchen."
                        else -> "Download fehlgeschlagen (Code $reason). Internetverbindung prüfen."
                    }
                    _downloads.value = _downloads.value + (model.id to DownloadState.Failed(msg, needsToken))
                }
                else -> _downloads.value = _downloads.value + (model.id to DownloadState.Running(done, total))
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Installierte Modelle
    // ------------------------------------------------------------------------------------------

    fun refreshInstalled() {
        _installed.value = scanInstalled()
        if (_activeId.value == null || _installed.value.none { it.id == _activeId.value }) {
            _activeId.value = resolveActiveId(_installed.value)
        }
    }

    private fun scanInstalled(): List<InstalledModel> {
        val downloading = pendingDownloads().keys.map { it.fileName }.toSet()
        return modelsDir.listFiles().orEmpty()
            .filter { it.isFile && it.length() > 1_000_000 && (it.name.endsWith(".litertlm") || it.name.endsWith(".task")) }
            .filter { it.name !in downloading }
            .map { f ->
                val cat = CATALOG.firstOrNull { it.fileName == f.name }
                InstalledModel(id = cat?.id ?: "file:${f.name}", name = cat?.name ?: f.nameWithoutExtension, file = f, catalog = cat)
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun resolveActiveId(list: List<InstalledModel>): String? {
        val saved = prefs.getString("active", null)
        return saved?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
    }

    suspend fun delete(model: InstalledModel) = withContext(Dispatchers.IO) {
        model.file.delete()
        refreshInstalled()
    }

    /** Importiert eine eigene .litertlm-Datei (z. B. per USB kopiert) in den Modell-Ordner. */
    suspend fun import(uri: Uri): Result<InstalledModel> = withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?: "import_${System.currentTimeMillis()}.litertlm"
            require(name.endsWith(".litertlm") || name.endsWith(".task")) { "Bitte eine .litertlm-Datei wählen" }
            val target = File(modelsDir, name)
            val tmp = File(modelsDir, "$name.part")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Datei kann nicht geöffnet werden" }
                tmp.outputStream().use { input.copyTo(it, bufferSize = 1 shl 20) }
            }
            check(tmp.length() > 1_000_000) { "Datei ist zu klein für ein Sprachmodell" }
            target.delete()
            check(tmp.renameTo(target)) { "Modell konnte nicht gespeichert werden" }
            refreshInstalled()
            val installed = _installed.value.first { it.file == target }
            if (activeModel() == null) setActive(installed.id)
            installed
        }
    }

    /** Ältere Versionen speicherten ein einzelnes Modell unter files/models/llm.task – übernehmen. */
    private fun migrateLegacyModel() {
        val legacy = File(context.filesDir, "models/llm.task")
        if (legacy.exists() && modelsDir != legacy.parentFile) {
            val target = File(modelsDir, "importiert.task")
            if (!target.exists()) runCatching { legacy.copyTo(target) }
            legacy.delete()
            refreshInstalled()
        }
    }
}
