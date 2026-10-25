package de.gedaechtnis.local

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import de.gedaechtnis.local.ai.LocalAiEngine
import de.gedaechtnis.local.data.ContextRepository
import de.gedaechtnis.local.data.SettingsRepository
import de.gedaechtnis.local.data.UiStateStore
import de.gedaechtnis.local.data.db.AppDatabase
import de.gedaechtnis.local.work.EmbeddingWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** Einfacher manueller DI-Container – keine Reflection, kein Start-Overhead. */
class AppContainer(app: Application) {
    val database: AppDatabase by lazy { AppDatabase.create(app) }
    val ai: LocalAiEngine by lazy { LocalAiEngine(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val repository: ContextRepository by lazy { ContextRepository(app, database, ai) }
    val uiState: UiStateStore by lazy { UiStateStore(app) }
}

class GedaechtnisApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()

        // Hintergrund-Indexierung an die Einstellung "nur beim Laden" koppeln.
        appScope.launch {
            container.settings.settings
                .distinctUntilChangedBy { it.embedOnlyWhileCharging || it.dataSaver }
                .collect { EmbeddingWorker.schedule(this@GedaechtnisApp, it.embedOnlyWhileCharging || it.dataSaver) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Bei Speicherdruck sofort das große LLM freigeben.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appScope.launch { container.ai.releaseLlm() }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_STATUS = "status"
    }
}
