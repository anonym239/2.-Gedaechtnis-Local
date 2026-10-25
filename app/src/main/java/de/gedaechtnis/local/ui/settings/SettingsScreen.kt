package de.gedaechtnis.local.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.text.input.PasswordVisualTransformation
import de.gedaechtnis.local.ai.CatalogModel
import de.gedaechtnis.local.ai.DownloadState
import de.gedaechtnis.local.ai.InstalledModel
import de.gedaechtnis.local.ai.ModelManager
import de.gedaechtnis.local.ui.components.formatBytes
import de.gedaechtnis.local.capture.OfflineSpeechStatus
import de.gedaechtnis.local.ui.components.StatusDot
import de.gedaechtnis.local.ui.theme.SuccessGreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.gedaechtnis.local.ai.LlmState
import de.gedaechtnis.local.ui.components.GlassCard
import de.gedaechtnis.local.ui.components.SectionLabel
import de.gedaechtnis.local.ui.theme.WarningAmber
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(contentPadding: PaddingValues, vm: SettingsViewModel = viewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val llm by vm.llmState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val speech by vm.speechStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val ramGb = vm.deviceRamGb
    val installed by vm.installedModels.collectAsStateWithLifecycle()
    val activeId by vm.activeModelId.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val savedToken by vm.hfToken.collectAsStateWithLifecycle()
    var tokenInput by remember(savedToken) { mutableStateOf(savedToken) }
    var confirmDeleteModel by remember { mutableStateOf<InstalledModel?>(null) }

    var filter by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importModel(it) { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } }
    }

    val filtered = remember(apps, filter) {
        if (filter.isBlank()) apps else apps.filter { it.label.contains(filter, true) || it.packageName.contains(filter, true) }
    }

    LazyColumn(
        // Abstände zu Status- und Navigationsleiste außerhalb der Liste: nichts rutscht darunter.
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Einstellungen", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(4.dp)) }

        // --- Erfassung -------------------------------------------------------------------------
        item { SectionLabel("Erfassung") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SwitchRow("Aufzeichnung aktiv", "Globaler Schalter für die Bildschirmerfassung", s.captureEnabled, vm::setCapture)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(
                    "OCR-Fallback",
                    "Screenshot-Texterkennung für PDFs & Bilder (ML Kit, offline)",
                    s.ocrEnabled,
                    vm::setOcr,
                    enabled = !s.dataSaver,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(
                    "Datensparmodus",
                    "Kein OCR, 3× längere Intervalle, Indexierung nur beim Laden",
                    s.dataSaver,
                    vm::setDataSaver,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(
                    "Vektorisierung nur beim Laden",
                    "Semantische Indexierung nur am Ladegerät durchführen",
                    s.embedOnlyWhileCharging || s.dataSaver,
                    vm::setEmbedOnlyCharging,
                    enabled = !s.dataSaver,
                )
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                DebouncedSlider(
                    title = "Aufzeichnungsintervall",
                    valueLabel = { "${it.roundToInt()} s pro App" },
                    value = s.captureIntervalSec.toFloat(),
                    range = 5f..120f,
                    steps = 22,
                    onCommit = { vm.setInterval(it.roundToInt()) },
                )
                Spacer(Modifier.height(8.dp))
                DebouncedSlider(
                    title = "Aufbewahrung",
                    valueLabel = { "${it.roundToInt()} Tage" },
                    value = s.retentionDays.toFloat(),
                    range = 1f..90f,
                    steps = 88,
                    onCommit = { vm.setRetention(it.roundToInt()) },
                )
            }
        }

        // --- Lokale Sprachmodelle ---------------------------------------------------------------
        item { SectionLabel("Lokale Sprachmodelle (Antworten)") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Modelle werden direkt hier installiert und laufen danach komplett offline. " +
                        "Du kannst mehrere installieren und jederzeit wechseln.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Dein Gerät: $ramGb GB RAM · frei: ${formatBytes(vm.freeSpaceBytes())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (llm == LlmState.ERROR) {
                    Text(
                        "Fehler beim Laden: ${vm.llmError ?: "unbekannt"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                busy?.let {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        items(ModelManager.CATALOG, key = { it.id }) { model ->
            val installedModel = installed.firstOrNull { it.catalog?.id == model.id }
            CatalogModelCard(
                model = model,
                installed = installedModel,
                active = installedModel != null && installedModel.id == activeId,
                recommended = model.id == vm.recommendedModel.id,
                fitsDevice = model.minRamGb <= ramGb,
                download = downloads[model.id],
                onInstall = { vm.install(model) { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } },
                onCancel = { vm.cancelInstall(model) },
                onActivate = { installedModel?.let(vm::activate) },
                onDelete = { installedModel?.let { confirmDeleteModel = it } },
                onDismissError = { vm.dismissError(model) },
            )
        }
        // Selbst importierte Modelle (nicht aus dem Katalog)
        items(installed.filter { it.catalog == null }, key = { it.id }) { m ->
            GlassCard(Modifier.fillMaxWidth(), accent = if (m.id == activeId) MaterialTheme.colorScheme.primary else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium)
                        Text("Eigene Datei · ${formatBytes(m.file.length())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (m.id == activeId) Badge("Aktiv", SuccessGreen)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.id != activeId) Button(onClick = { vm.activate(m) }) { Text("Verwenden") }
                    OutlinedButton(onClick = { confirmDeleteModel = m }) { Text("Löschen") }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Hugging-Face-Token (nur für Gemma-Modelle)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Google stellt Gemma nur nach Annahme der Lizenz bereit. Qwen und DeepSeek brauchen keinen Token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("hf_…") },
                    visualTransformation = PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = {
                            vm.saveToken(tokenInput)
                            Toast.makeText(context, "Token gespeichert", Toast.LENGTH_SHORT).show()
                        }) { Text("Speichern") }
                    },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = busy == null) {
                    Text("Eigene .litertlm-Datei importieren")
                }
            }
        }

        // --- Offline-Spracherkennung -----------------------------------------------------------
        item { SectionLabel("Offline-Spracherkennung (Sprachnotizen)") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                val (text, ok) = when (speech) {
                    OfflineSpeechStatus.INSTALLED -> "Deutsches Offline-Sprachpaket ist installiert." to true
                    OfflineSpeechStatus.DOWNLOADING -> "Sprachpaket wird heruntergeladen … (kann einige Minuten dauern)" to true
                    OfflineSpeechStatus.DOWNLOADABLE -> "Deutsches Offline-Sprachpaket ist noch nicht installiert." to false
                    OfflineSpeechStatus.UNSUPPORTED -> "Dieses Gerät bietet keine reine On-Device-Erkennung. Lade in den System-Einstellungen das deutsche Offline-Paket der Spracheingabe." to false
                    OfflineSpeechStatus.UNKNOWN -> "Status unbekannt. Über den Button kannst du das deutsche Offline-Paket laden bzw. prüfen." to false
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (ok) SuccessGreen else WarningAmber, Modifier.size(8.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                Text(
                    "Die Erkennung läuft über den Spracherkennungsdienst des Systems. Mit installiertem Paket funktionieren Sprachnotizen ohne Internet und schneller.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (speech != OfflineSpeechStatus.INSTALLED) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::downloadOfflineSpeech) { Text("Sprachpaket laden") }
                        OutlinedButton(onClick = { vm.refreshSpeechStatus() }) { Text("Erneut prüfen") }
                    }
                }
            }
        }

        // --- Datenschutz-Sperrliste ------------------------------------------------------------
        item { SectionLabel("Datenschutz-Sperrliste") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Gesperrte Apps werden nie erfasst; bereits gespeicherte Daten werden beim Sperren gelöscht. " +
                        "Banking-, Bezahl- und Passwort-Apps sind vorausgewählt und oben markiert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("App suchen") },
                )
            }
        }
        items(filtered, key = { it.packageName }) { app ->
            val blocked = app.packageName in s.blacklist
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.setBlocked(app.packageName, !blocked) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        if (app.sensitive) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Warning, "sensibel", Modifier.padding(top = 2.dp).width(14.dp), tint = WarningAmber)
                        }
                    }
                    Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(checked = blocked, onCheckedChange = { vm.setBlocked(app.packageName, it) })
            }
        }

        // --- Gefahrenzone ----------------------------------------------------------------------
        item { SectionLabel("Daten") }
        item {
            GlassCard(Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.error) {
                Text("Alle erfassten Daten unwiderruflich löschen.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Gedächtnis löschen") }
            }
        }
    }

    confirmDeleteModel?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDeleteModel = null },
            title = { Text("${m.name} löschen?") },
            text = { Text("Die Modelldatei (${formatBytes(m.file.length())}) wird vom Gerät entfernt. Du kannst sie jederzeit erneut installieren.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteModel(m); confirmDeleteModel = null }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteModel = null }) { Text("Abbrechen") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Wirklich alles löschen?") },
            text = { Text("Alle Snippets, Notizen und Vektoren werden entfernt. Dies kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteAllData { Toast.makeText(context, "Gedächtnis gelöscht", Toast.LENGTH_SHORT).show() }
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun CatalogModelCard(
    model: CatalogModel,
    installed: InstalledModel?,
    active: Boolean,
    recommended: Boolean,
    fitsDevice: Boolean,
    download: DownloadState?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), accent = if (active) MaterialTheme.colorScheme.primary else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(formatBytes(model.sizeBytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowBadges {
            if (active) Badge("Aktiv", SuccessGreen)
            if (recommended) Badge("Für dein Gerät empfohlen", MaterialTheme.colorScheme.primary)
            if (model.gated) Badge("Token nötig", WarningAmber)
            if (model.reasoning) Badge("Denkt nach", MaterialTheme.colorScheme.secondary)
            if (!fitsDevice) Badge("Ab ${model.minRamGb} GB RAM", MaterialTheme.colorScheme.error)
        }
        Text(model.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        when {
            download is DownloadState.Running -> {
                LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        "${(download.progress * 100).toInt()} % · ${formatBytes(download.downloaded)} von ${formatBytes(download.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("Abbrechen") }
                }
            }
            installed != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!active) Button(onClick = onActivate) { Text("Verwenden") }
                OutlinedButton(onClick = onDelete) { Text("Löschen") }
            }
            else -> {
                if (download is DownloadState.Failed) {
                    Text(download.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDismissError(); onInstall() }) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (download is DownloadState.Failed) "Erneut versuchen" else "Installieren")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowBadges(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) { content() }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Slider, der erst beim Loslassen speichert – vermeidet DataStore-Schreibstürme. */
@Composable
private fun DebouncedSlider(
    title: String,
    valueLabel: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(valueLabel(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local) },
        valueRange = range,
        steps = steps,
    )
}
