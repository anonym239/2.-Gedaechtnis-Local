package de.gedaechtnis.local.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
    val context = LocalContext.current

    var filter by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importModel(it) { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } }
    }

    val filtered = remember(apps, filter) {
        if (filter.isBlank()) apps else apps.filter { it.label.contains(filter, true) || it.packageName.contains(filter, true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
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

        // --- Lokales LLM -----------------------------------------------------------------------
        item { SectionLabel("Lokales Sprachmodell") }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    when (llm) {
                        LlmState.NOT_INSTALLED -> "Kein Modell installiert. Die Suche funktioniert trotzdem (Volltext + Embeddings)."
                        LlmState.ERROR -> "Modell konnte nicht geladen werden. Bitte ein kompatibles .task-Modell wählen."
                        else -> "Modell installiert. Es wird nur bei Fragen geladen und nach 90 s wieder entladen."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Empfohlen: Gemma 3 1B (int4, .task) aus der MediaPipe/LiteRT-Community auf Hugging Face. " +
                        "Datei einmalig herunterladen und hier auswählen – sie wird in den privaten App-Speicher kopiert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                busy?.let {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = busy == null) {
                        Text(if (llm == LlmState.NOT_INSTALLED) "Modell importieren" else "Modell ersetzen")
                    }
                    if (llm != LlmState.NOT_INSTALLED) {
                        OutlinedButton(onClick = vm::deleteModel, enabled = busy == null) { Text("Entfernen") }
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
