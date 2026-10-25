package de.gedaechtnis.local.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.scale
import de.gedaechtnis.local.GedaechtnisApp
import de.gedaechtnis.local.R
import de.gedaechtnis.local.data.AppSettings
import de.gedaechtnis.local.data.db.SnippetSource
import de.gedaechtnis.local.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Schonender Hintergrunddienst für Bildschirmkontext.
 *
 * Strategie für minimalen Akku-/CPU-Verbrauch:
 *  1. **Event-basiert statt Polling** – das System weckt den Dienst nur bei UI-Änderungen.
 *  2. **Debounce** – erst erfassen, wenn die Oberfläche [DEBOUNCE_MS] lang ruhig ist (kein Scroll-Spam).
 *  3. **Drosselung** – pro App höchstens alle `captureIntervalSec` Sekunden.
 *  4. **Text-Baum zuerst** – OCR (Screenshot + ML Kit) nur, wenn eine Ansicht kaum Text liefert
 *     (z. B. PDF-Viewer, Bilder), nicht im Energiesparmodus und nicht bei niedrigem Akku.
 *  5. **Embeddings später** – rechenintensive Vektoren berechnet der [de.gedaechtnis.local.work.EmbeddingWorker].
 *
 * Datenschutz: Sperrliste, Passwortfelder, Sperrbildschirm, Tastaturen und FLAG_SECURE-Fenster
 * (Screenshot schlägt dort systemseitig fehl) werden nie erfasst.
 */
class ScreenContextService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val container by lazy { (application as GedaechtnisApp).container }
    private val ocr = OcrProcessor()

    @Volatile private var settings = AppSettings()
    private val lastCaptureByPkg = ConcurrentHashMap<String, Long>()
    private val labelCache = ConcurrentHashMap<String, String>()
    private var ignoredPackages: Set<String> = emptySet()

    private var pendingJob: Job? = null
    private var pendingSince = 0L
    private var lastOcrAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ignoredPackages = buildIgnoredPackages()
        scope.launch {
            container.settings.settings.collect {
                settings = it
                showStatusNotification(it.captureEnabled)
            }
        }
        _running.value = true
        Log.i(TAG, "ScreenContextService verbunden")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val s = settings
        if (!s.captureEnabled) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPackages || pkg in s.blacklist) return

        val now = SystemClock.elapsedRealtime()
        val last = lastCaptureByPkg[pkg] ?: 0L
        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        // Neue Ansichten dürfen früher erfasst werden, reine Inhaltsänderungen erst nach dem Intervall.
        val minGap = if (windowChanged) MIN_GAP_WINDOW_CHANGE_MS else s.effectiveIntervalMs
        if (now - last < minGap) return

        // Debounce mit Obergrenze: bei Dauer-Updates (Video, Ticker) trotzdem nach MAX_DEBOUNCE_MS erfassen.
        val active = pendingJob?.isActive == true
        if (active && now - pendingSince > MAX_DEBOUNCE_MS) return
        pendingJob?.cancel()
        if (!active) pendingSince = now
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching { capture(pkg) }.onFailure { Log.w(TAG, "Erfassung fehlgeschlagen", it) }
        }
    }

    private suspend fun capture(expectedPkg: String) {
        val s = settings
        if (isDeviceLocked()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        // Das Fenster kann sich während des Debounce geändert haben – erneut prüfen.
        if (pkg != expectedPkg || pkg in s.blacklist || pkg in ignoredPackages) return

        val windowTitle = windows.firstOrNull { it.isActive }?.title?.toString()
        val collected = NodeTextCollector.collect(root)
        var text = collected.text
        var source = SnippetSource.SCREEN_TEXT

        if (text.length < OCR_TEXT_THRESHOLD && s.effectiveOcr && canAffordOcr()) {
            val ocrText = screenshotOcr()
            if (ocrText != null && ocrText.length > text.length) {
                text = ocrText
                source = SnippetSource.SCREEN_OCR
            }
        }

        lastCaptureByPkg[pkg] = SystemClock.elapsedRealtime()
        container.repository.insertSnippet(
            packageName = pkg,
            appLabel = labelFor(pkg),
            windowTitle = windowTitle,
            text = text,
            source = source,
        )
    }

    /** Screenshot (nur API 30+, systemseitig max. ~1/s) → verkleinern → ML Kit OCR. */
    private suspend fun screenshotOcr(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOcrAt < MIN_OCR_GAP_MS) return null
        lastOcrAt = now

        val bitmap: Bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val copy = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        sw
                    }.getOrNull()
                    buffer.close()
                    cont.resume(copy)
                }

                // Schlägt u. a. bei FLAG_SECURE-Fenstern (Banking, DRM) fehl – gewollt.
                override fun onFailure(errorCode: Int) = cont.resume(null)
            })
        } ?: return null

        // Auf max. 1080 px Breite skalieren: halbiert die OCR-Zeit bei kaum Qualitätsverlust.
        val scaled = if (bitmap.width > OCR_MAX_WIDTH) {
            val f = OCR_MAX_WIDTH.toFloat() / bitmap.width
            bitmap.scale(OCR_MAX_WIDTH, (bitmap.height * f).toInt()).also { bitmap.recycle() }
        } else bitmap

        return try {
            ocr.recognize(scaled)
        } finally {
            scaled.recycle()
        }
    }

    private fun canAffordOcr(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isPowerSaveMode || !pm.isInteractive) return false
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return bm.isCharging || level !in 0..LOW_BATTERY_PERCENT
    }

    private fun isDeviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun labelFor(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    /** Eigene App, System-UI, Tastaturen und Launcher liefern nur Rauschen. */
    private fun buildIgnoredPackages(): Set<String> {
        val result = mutableSetOf(packageName, "com.android.systemui", "android")
        runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { result += it.packageName }
        }
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { result += it.activityInfo.packageName }
        }
        return result
    }

    private fun showStatusNotification(active: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, GedaechtnisApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(if (active) R.string.notification_text else R.string.notification_paused))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        _running.value = false
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        scope.cancel()
        ocr.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenContextService"
        private const val NOTIFICATION_ID = 1001
        private const val DEBOUNCE_MS = 1_500L
        private const val MAX_DEBOUNCE_MS = 6_000L
        private const val MIN_GAP_WINDOW_CHANGE_MS = 3_000L
        private const val MIN_OCR_GAP_MS = 20_000L
        private const val OCR_TEXT_THRESHOLD = 60
        private const val OCR_MAX_WIDTH = 1080
        private const val LOW_BATTERY_PERCENT = 20

        private val _running = MutableStateFlow(false)
        /** true, solange das System den Dienst gebunden hat. */
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }
}
