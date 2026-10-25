package de.gedaechtnis.local.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gedaechtnis.local.GedaechtnisApp
import de.gedaechtnis.local.ai.LlmState
import de.gedaechtnis.local.ai.ModelCatalog
import de.gedaechtnis.local.ai.ModelSuggestion
import de.gedaechtnis.local.capture.OfflineSpeechStatus
import de.gedaechtnis.local.capture.VoiceNoteRecognizer
import de.gedaechtnis.local.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String, val sensitive: Boolean)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as GedaechtnisApp).container
    private val repo = container.settings

    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val llmState: StateFlow<LlmState> = container.ai.llmState

    private val _apps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val apps: StateFlow<List<InstalledApp>> = _apps.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** Text einer laufenden Langzeitaktion (z. B. Modell-Import) oder null. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    val deviceRamGb: Int = ModelCatalog.deviceRamGb(app)
    val recommendedModel: ModelSuggestion = ModelCatalog.recommendedFor(app)

    private val _speechStatus = MutableStateFlow(OfflineSpeechStatus.UNKNOWN)
    val speechStatus: StateFlow<OfflineSpeechStatus> = _speechStatus.asStateFlow()

    init {
        viewModelScope.launch { _apps.value = loadInstalledApps() }
        refreshSpeechStatus()
    }

    /** Läuft auf dem Main-Thread (Vorgabe der SpeechRecognizer-API). */
    fun refreshSpeechStatus() = viewModelScope.launch {
        _speechStatus.value = VoiceNoteRecognizer.offlineStatus(getApplication())
    }

    fun downloadOfflineSpeech() {
        VoiceNoteRecognizer.requestOfflineModel(getApplication())
        _speechStatus.value = OfflineSpeechStatus.DOWNLOADING
    }

    fun setCapture(v: Boolean) = launch { repo.setCaptureEnabled(v) }
    fun setOcr(v: Boolean) = launch { repo.setOcrEnabled(v) }
    fun setDataSaver(v: Boolean) = launch { repo.setDataSaver(v) }
    fun setInterval(sec: Int) = launch { repo.setCaptureInterval(sec) }
    fun setRetention(days: Int) = launch { repo.setRetentionDays(days) }
    fun setEmbedOnlyCharging(v: Boolean) = launch { repo.setEmbedOnlyWhileCharging(v) }

    /** Sperren löscht zusätzlich bereits erfasste Daten dieser App. */
    fun setBlocked(pkg: String, blocked: Boolean) = launch {
        repo.setBlacklisted(pkg, blocked)
        if (blocked) container.repository.deleteForApp(pkg)
    }

    fun importModel(uri: Uri, onResult: (String) -> Unit) = launch {
        _busy.value = "Modell wird importiert …"
        val result = container.ai.importLlmModel(uri)
        _busy.value = null
        onResult(result.fold({ "Modell importiert (${it / (1024 * 1024)} MB)" }, { "Import fehlgeschlagen: ${it.message}" }))
    }

    fun deleteModel() = launch { container.ai.deleteLlmModel() }

    fun deleteAllData(onDone: () -> Unit) = launch {
        container.repository.deleteAll()
        onDone()
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    @Suppress("DEPRECATION") // ResolveInfoFlags erst ab API 33; minSdk ist 30.
    private suspend fun loadInstalledApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = getApplication<Application>().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != getApplication<Application>().packageName }
            .distinctBy { it.first }
            .map { (pkg, label) -> InstalledApp(pkg, label, AppSettings.looksSensitive(pkg, label)) }
            .sortedWith(compareByDescending<InstalledApp> { it.sensitive }.thenBy { it.label.lowercase() })
            .toList()
    }
}
