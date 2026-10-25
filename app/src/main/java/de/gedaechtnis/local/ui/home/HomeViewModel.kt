package de.gedaechtnis.local.ui.home

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.gedaechtnis.local.GedaechtnisApp
import de.gedaechtnis.local.ai.LlmState
import de.gedaechtnis.local.capture.ScreenContextService
import de.gedaechtnis.local.data.db.AppCount
import de.gedaechtnis.local.data.db.ContextSnippet
import de.gedaechtnis.local.work.EmbeddingWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val serviceEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val captureEnabled: Boolean = true,
    val indexedCount: Int = 0,
    val embeddedCount: Int = 0,
    val lastCaptureAt: Long? = null,
    val databaseBytes: Long = 0,
    val modelBytes: Long = 0,
    val topApps: List<AppCount> = emptyList(),
    val recent: List<ContextSnippet> = emptyList(),
    val embedderAvailable: Boolean = false,
    val llmState: LlmState = LlmState.NOT_INSTALLED,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as GedaechtnisApp).container
    private val repo = container.repository

    private val serviceEnabled = MutableStateFlow(false)
    private val sizes = MutableStateFlow(0L to 0L)

    private data class Counts(val count: Int, val embedded: Int, val last: Long?)
    private data class Status(val enabled: Boolean, val running: Boolean, val capture: Boolean, val llm: LlmState)

    private val counts = combine(repo.count, repo.embeddedCount, repo.lastCaptureAt) { c, e, l -> Counts(c, e, l) }
    private val lists = combine(repo.topApps(5), repo.recent(6)) { top, recent -> top to recent }
    private val status = combine(serviceEnabled, ScreenContextService.running, container.settings.settings, container.ai.llmState) { en, run, s, llm ->
        Status(en, run, s.captureEnabled, llm)
    }

    val state: StateFlow<HomeUiState> = combine(counts, lists, status, sizes) { c, l, st, sz ->
        HomeUiState(
            serviceEnabled = st.enabled,
            serviceRunning = st.running,
            captureEnabled = st.capture,
            llmState = st.llm,
            indexedCount = c.count,
            embeddedCount = c.embedded,
            lastCaptureAt = c.last,
            databaseBytes = sz.first,
            modelBytes = sz.second,
            topApps = l.first,
            recent = l.second,
            embedderAvailable = container.ai.isEmbedderAvailable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Beim Zurückkehren in die App aufrufen (Bedienungshilfe evtl. gerade aktiviert). */
    fun refresh() {
        serviceEnabled.value = isAccessibilityServiceEnabled()
        viewModelScope.launch(Dispatchers.IO) {
            sizes.value = repo.databaseSizeBytes() to container.ai.llmModelFile.let { if (it.exists()) it.length() else 0L }
        }
        EmbeddingWorker.runNow(getApplication())
    }

    fun setCaptureEnabled(enabled: Boolean) = viewModelScope.launch { container.settings.setCaptureEnabled(enabled) }

    fun addNote(text: String, voice: Boolean, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { repo.addNote(text, voice) } != null
        onDone(ok)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val app = getApplication<Application>()
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(app, ScreenContextService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
