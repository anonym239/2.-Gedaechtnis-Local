package de.gedaechtnis.local.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Offline-Texterkennung mit ML Kit (gebündeltes lateinisches Modell, keine Play-Services-Downloads).
 * Läuft auf dem ML-Kit-eigenen Hintergrund-Thread; der Aufrufer wird nur suspendiert.
 */
class OcrProcessor {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                // Blöcke in Lesereihenfolge (oben → unten, links → rechts) zusammensetzen.
                val text = result.textBlocks
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .joinToString("\n") { it.text }
                cont.resume(text.ifBlank { null })
            }
            .addOnFailureListener { cont.resume(null) }
    }

    fun close() = recognizer.close()
}
