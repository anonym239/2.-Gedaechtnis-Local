package de.gedaechtnis.local.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Verfügbarkeit des deutschen Offline-Sprachmodells. */
enum class OfflineSpeechStatus { INSTALLED, DOWNLOADING, DOWNLOADABLE, UNSUPPORTED, UNKNOWN }

/**
 * Sprachnotizen über die Android-Spracherkennung. Ab Android 12 wird – wenn vorhanden – die rein
 * lokale On-Device-Erkennung genutzt; sonst wird per EXTRA_PREFER_OFFLINE das Offline-Modell bevorzugt.
 * Es wird kein Audio gespeichert, nur der erkannte Text. Muss auf dem Main-Thread verwendet werden.
 */
class VoiceNoteRecognizer(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        stop()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(listener)
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    companion object {
        private const val LANGUAGE = "de-DE"

        private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)

        /** Prüft (ab Android 13), ob das deutsche Modell für die On-Device-Erkennung installiert ist. Main-Thread. */
        suspend fun offlineStatus(context: Context): OfflineSpeechStatus {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return OfflineSpeechStatus.UNKNOWN
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return OfflineSpeechStatus.UNSUPPORTED
            return checkSupport(context)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private suspend fun checkSupport(context: Context): OfflineSpeechStatus = suspendCancellableCoroutine { cont ->
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            cont.invokeOnCancellation { r.destroy() }
            r.checkRecognitionSupport(recognitionIntent(), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    fun List<String>.hasGerman() = any { it.equals(LANGUAGE, true) || it.startsWith("de", true) }
                    val status = when {
                        support.installedOnDeviceLanguages.hasGerman() -> OfflineSpeechStatus.INSTALLED
                        support.pendingOnDeviceLanguages.hasGerman() -> OfflineSpeechStatus.DOWNLOADING
                        support.supportedOnDeviceLanguages.hasGerman() -> OfflineSpeechStatus.DOWNLOADABLE
                        else -> OfflineSpeechStatus.UNSUPPORTED
                    }
                    r.destroy()
                    cont.resume(status)
                }

                override fun onError(error: Int) {
                    r.destroy()
                    cont.resume(OfflineSpeechStatus.UNKNOWN)
                }
            })
        }

        /**
         * Lädt das deutsche Offline-Sprachmodell über den Systemdienst (ab Android 13).
         * Auf älteren Versionen werden die System-Einstellungen für die Spracheingabe geöffnet.
         */
        fun requestOfflineModel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                r.triggerModelDownload(recognitionIntent())
                r.destroy()
            } else {
                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onError("Nichts erkannt") else onResult(text)
            stop()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(onPartial)
        }

        override fun onError(error: Int) {
            onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nichts erkannt"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Offline-Sprachpaket fehlt (Systemeinstellungen → Spracherkennung)"
                    else -> "Spracherkennung fehlgeschlagen ($error)"
                },
            )
            stop()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
