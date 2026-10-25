package de.gedaechtnis.local.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    /** Globaler Schalter: Aufzeichnung aktiv oder pausiert. */
    val captureEnabled: Boolean = true,
    /** OCR-Fallback per Screenshot für textarme Ansichten (PDFs, Bilder). */
    val ocrEnabled: Boolean = true,
    /** Datensparmodus: kein OCR, längere Intervalle, Embeddings nur beim Laden. */
    val dataSaver: Boolean = false,
    /** Minimaler Abstand zwischen zwei Erfassungen derselben App in Sekunden. */
    val captureIntervalSec: Int = 15,
    /** Aufbewahrungsdauer in Tagen; ältere Einträge werden automatisch gelöscht. */
    val retentionDays: Int = 30,
    /** Embeddings (rechenintensiv) nur berechnen, während das Gerät lädt. */
    val embedOnlyWhileCharging: Boolean = false,
    /** Paketnamen, die niemals erfasst werden. */
    val blacklist: Set<String> = DEFAULT_BLACKLIST,
) {
    /** Effektives Intervall unter Berücksichtigung des Datensparmodus. */
    val effectiveIntervalMs: Long get() = (if (dataSaver) captureIntervalSec * 3 else captureIntervalSec) * 1000L
    val effectiveOcr: Boolean get() = ocrEnabled && !dataSaver

    companion object {
        /** Vorbelegte Sperrliste: Banking, Bezahldienste, Passwortmanager, Authenticator. */
        val DEFAULT_BLACKLIST = setOf(
            "com.paypal.android.p2pmobile",
            "com.starfinanz.smob.android.sfinanzstatus",
            "com.starfinanz.mobile.android.pushtan",
            "de.dkb.portalapp",
            "com.db.pwcc.dbmobile",
            "de.commerzbanking.mobil",
            "de.ingdiba.bankingapp",
            "de.number26.android",
            "com.comdirect.app",
            "de.consorsbank",
            "de.fiducia.smartphone.android.banking.vr",
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "keepass2android.keepass2android",
            "com.lastpass.lpandroid",
        )

        /** Heuristik für Apps, die standardmäßig gesperrt sein sollten. */
        private val SENSITIVE_HINTS = listOf("bank", "banking", "sparkasse", "finanz", "paypal", "wallet", "password", "passwort", "authenticator", "vault", "pushtan", "securego")

        fun looksSensitive(packageName: String, label: String): Boolean {
            val haystack = "$packageName $label".lowercase()
            return SENSITIVE_HINTS.any { haystack.contains(it) }
        }
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val CAPTURE = booleanPreferencesKey("capture_enabled")
        val OCR = booleanPreferencesKey("ocr_enabled")
        val SAVER = booleanPreferencesKey("data_saver")
        val INTERVAL = intPreferencesKey("capture_interval_sec")
        val RETENTION = intPreferencesKey("retention_days")
        val CHARGING = booleanPreferencesKey("embed_only_charging")
        val BLACKLIST = stringSetPreferencesKey("blacklist")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            captureEnabled = this[Keys.CAPTURE] ?: d.captureEnabled,
            ocrEnabled = this[Keys.OCR] ?: d.ocrEnabled,
            dataSaver = this[Keys.SAVER] ?: d.dataSaver,
            captureIntervalSec = this[Keys.INTERVAL] ?: d.captureIntervalSec,
            retentionDays = this[Keys.RETENTION] ?: d.retentionDays,
            embedOnlyWhileCharging = this[Keys.CHARGING] ?: d.embedOnlyWhileCharging,
            blacklist = this[Keys.BLACKLIST] ?: d.blacklist,
        )
    }

    suspend fun setCaptureEnabled(v: Boolean) = context.dataStore.edit { it[Keys.CAPTURE] = v }
    suspend fun setOcrEnabled(v: Boolean) = context.dataStore.edit { it[Keys.OCR] = v }
    suspend fun setDataSaver(v: Boolean) = context.dataStore.edit { it[Keys.SAVER] = v }
    suspend fun setCaptureInterval(sec: Int) = context.dataStore.edit { it[Keys.INTERVAL] = sec.coerceIn(5, 300) }
    suspend fun setRetentionDays(days: Int) = context.dataStore.edit { it[Keys.RETENTION] = days.coerceIn(1, 365) }
    suspend fun setEmbedOnlyWhileCharging(v: Boolean) = context.dataStore.edit { it[Keys.CHARGING] = v }

    suspend fun setBlacklisted(pkg: String, blocked: Boolean) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.BLACKLIST] ?: AppSettings.DEFAULT_BLACKLIST
        prefs[Keys.BLACKLIST] = if (blocked) current + pkg else current - pkg
    }
}
