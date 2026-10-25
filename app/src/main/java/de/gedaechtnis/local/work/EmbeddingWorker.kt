package de.gedaechtnis.local.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.gedaechtnis.local.GedaechtnisApp
import java.util.concurrent.TimeUnit

/**
 * Berechnet Embeddings für neue Snippets und wendet die Aufbewahrungsfrist an.
 * Läuft über WorkManager gebündelt im Hintergrund, nur wenn der Akku nicht schwach ist
 * (optional nur beim Laden) – so entsteht kein spürbarer Akku-Drain.
 */
class EmbeddingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as GedaechtnisApp).container
        val settings = container.settings.current()
        container.repository.applyRetention(settings.retentionDays)

        var total = 0
        // In Stapeln arbeiten und rechtzeitig aufhören, wenn das System den Worker stoppen will.
        while (!isStopped && total < MAX_PER_RUN) {
            val n = container.repository.embedPending(BATCH)
            if (n == 0) break
            total += n
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "embedding-periodic"
        private const val ONE_SHOT = "embedding-now"
        private const val BATCH = 64
        private const val MAX_PER_RUN = 2_000

        private fun constraints(onlyWhileCharging: Boolean) = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(onlyWhileCharging)
            .build()

        fun schedule(context: Context, onlyWhileCharging: Boolean) {
            val request = PeriodicWorkRequestBuilder<EmbeddingWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints(onlyWhileCharging))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Sofortiger Durchlauf, z. B. beim Öffnen der App. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.KEEP, request)
        }
    }
}
