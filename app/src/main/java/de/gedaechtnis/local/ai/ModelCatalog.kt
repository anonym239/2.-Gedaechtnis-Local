package de.gedaechtnis.local.ai

import android.app.ActivityManager
import android.content.Context

/** Ein empfohlenes On-Device-Sprachmodell (MediaPipe-.task-Format). */
data class ModelSuggestion(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeLabel: String,
    val minRamGb: Int,
    val description: String,
    /** Download-Seite; wird im Browser geöffnet (die App selbst hat keine Internet-Berechtigung). */
    val pageUrl: String,
    val experimental: Boolean = false,
)

/**
 * Kuratierte Modelle, die mit der MediaPipe LLM Inference API laufen und Deutsch gut beherrschen.
 * Die Gemma-Lizenz muss einmalig auf Hugging Face akzeptiert werden, danach ist alles offline.
 */
object ModelCatalog {

    val models = listOf(
        ModelSuggestion(
            id = "gemma3-1b",
            name = "Gemma 3 1B (int4)",
            fileName = "gemma3-1b-it-int4.task",
            sizeLabel = "≈ 530 MB",
            minRamGb = 4,
            description = "Schnell und sparsam, läuft auf fast allen Geräten. Gute deutsche Antworten für kurze Fragen.",
            pageUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
        ),
        ModelSuggestion(
            id = "gemma3n-e2b",
            name = "Gemma 3n E2B (int4)",
            fileName = "gemma-3n-E2B-it-int4.task",
            sizeLabel = "≈ 3 GB",
            minRamGb = 8,
            description = "Deutlich besseres Verständnis längerer Zusammenhänge. Nur für Geräte mit viel RAM.",
            pageUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview",
            experimental = true,
        ),
    )

    /** Gesamter Arbeitsspeicher des Geräts in GB (gerundet). */
    fun deviceRamGb(context: Context): Int {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return Math.round(info.totalMem / (1024.0 * 1024 * 1024)).toInt()
    }

    /** Das größte Modell, das sicher in den Arbeitsspeicher dieses Geräts passt. */
    fun recommendedFor(context: Context): ModelSuggestion {
        val ram = deviceRamGb(context)
        return models.filter { !it.experimental && it.minRamGb <= ram }.maxByOrNull { it.minRamGb } ?: models.first()
    }
}
