package de.gedaechtnis.local.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import de.gedaechtnis.local.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.gedaechtnis.local.ai.EmbedderKind
import de.gedaechtnis.local.ai.LlmState
import de.gedaechtnis.local.capture.VoiceNoteRecognizer
import de.gedaechtnis.local.data.db.ContextSnippet
import de.gedaechtnis.local.ui.components.GlassCard
import de.gedaechtnis.local.ui.components.NeonGradient
import de.gedaechtnis.local.ui.components.SectionLabel
import de.gedaechtnis.local.ui.components.StatusDot
import de.gedaechtnis.local.ui.components.formatBytes
import de.gedaechtnis.local.ui.components.label
import de.gedaechtnis.local.ui.theme.NeonCyan
import de.gedaechtnis.local.ui.theme.NeonViolet
import de.gedaechtnis.local.ui.theme.SuccessGreen
import de.gedaechtnis.local.ui.theme.WarningAmber

@Composable
fun HomeScreen(contentPadding: PaddingValues, vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    LazyColumn(
        // Statusleisten-Abstand außerhalb der Liste, damit beim Scrollen nichts darunter rutscht.
        modifier = Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header() }
        item {
            ServiceStatusCard(
                state = state,
                onOpenAccessibility = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onToggleCapture = vm::setCaptureEnabled,
            )
        }
        item { StatsRow(state) }
        item { AiStatusCard(state) }
        item { QuickNoteCard(onSave = { text, voice, done -> vm.addNote(text, voice, done) }) }

        if (state.topApps.isNotEmpty()) {
            item { SectionLabel("Meist erfasste Apps") }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    val max = state.topApps.maxOf { it.count }.coerceAtLeast(1)
                    state.topApps.forEach { app ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(app.appLabel, Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            LinearProgressIndicator(
                                progress = { app.count.toFloat() / max },
                                modifier = Modifier.weight(1f).height(6.dp),
                                color = NeonViolet,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                            Text("${app.count}", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            item { SectionLabel("Zuletzt erfasst") }
            items(state.recent, key = { it.id }) { RecentItem(it) }
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // App-Icon im Kopfbereich (Vordergrund-Ebene des Adaptive Icons auf dem Icon-Hintergrund).
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1E1E36), Color(0xFF07081C)))),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Zweit-Gedächtnis",
                style = MaterialTheme.typography.headlineMedium.merge(TextStyle(brush = NeonGradient)),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Icon(Icons.Default.Shield, null, Modifier.size(14.dp), tint = SuccessGreen)
                Spacer(Modifier.width(6.dp))
                Text(
                    "100 % lokal · keine Internet-Berechtigung",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(state: HomeUiState, onOpenAccessibility: () -> Unit, onToggleCapture: (Boolean) -> Unit) {
    val (color, title, subtitle) = when {
        !state.serviceEnabled -> Triple(MaterialTheme.colorScheme.error, "Inaktiv", "Bedienungshilfe ist nicht aktiviert.")
        !state.captureEnabled -> Triple(WarningAmber, "Pausiert", "Es wird gerade nichts erfasst.")
        state.serviceRunning -> Triple(SuccessGreen, "Aktiv", "Bildschirmkontext wird lokal indexiert.")
        else -> Triple(WarningAmber, "Wird gestartet …", "Dienst ist aktiviert und verbindet sich.")
    }
    GlassCard(Modifier.fillMaxWidth(), accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Hintergrunddienst: $title", style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.lastCaptureAt?.let {
                    Text(
                        "Letzte Erfassung " + DateUtils.getRelativeTimeSpanString(it).toString().lowercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.serviceEnabled) {
                Switch(checked = state.captureEnabled, onCheckedChange = onToggleCapture)
            }
        }
        if (!state.serviceEnabled) {
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text("Bedienungshilfe aktivieren")
            }
            Text(
                "Einstellungen → Bedienungshilfen → Installierte Apps → Zweit-Gedächtnis. " +
                    "Bei sideloaded Apps ggf. zuerst in den App-Infos „Eingeschränkte Einstellungen zulassen“.",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatsRow(state: HomeUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Ereignisse", "%,d".format(state.indexedCount).replace(',', '.'), null, NeonViolet, Modifier.weight(1f))
        val (size, unit) = formatBytes(state.databaseBytes).split(' ').let { it[0] to it.getOrElse(1) { "" } }
        StatTile("Speicher", size, unit, NeonCyan, Modifier.weight(1f))
        val pct = if (state.indexedCount == 0) 0 else state.embeddedCount * 100 / state.indexedCount
        StatTile("Vektorisiert", "$pct", "%", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
    }
}

/** Kennzahl mit kleinerer Einheit, damit auch "104,4 MB" auf schmale Kacheln passt. */
@Composable
private fun StatTile(label: String, value: String, unit: String?, accent: Color, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(
                buildAnnotatedString {
                    append(value)
                    if (!unit.isNullOrEmpty()) {
                        withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)) { append("\u00A0$unit") }
                    }
                },
                // Längere Werte etwas kleiner setzen, damit Zahl + Einheit immer in eine Zeile passen.
                style = MaterialTheme.typography.titleLarge.copy(fontSize = if (value.length + (unit?.length ?: 0) > 6) 18.sp else 21.sp),
                color = accent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AiStatusCard(state: HomeUiState) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = NeonCyan)
            Spacer(Modifier.width(10.dp))
            Text("Lokale KI", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        when (state.embedderKind) {
            EmbedderKind.MEDIAPIPE -> AiRow("Semantische Suche", "Bereit (MediaPipe, offline im App-Paket)", true)
            EmbedderKind.FALLBACK -> AiRow(
                "Semantische Suche",
                "Ersatzverfahren aktiv (Wortteil-Abgleich)" + (state.embedderError?.let { "\nMediaPipe-Fehler: $it" } ?: ""),
                true,
            )
        }
        val (llmText, ok) = when (state.llmState) {
            LlmState.NOT_INSTALLED -> "Kein Modell installiert – Empfehlung unter Einstellungen → Lokales Sprachmodell" to false
            LlmState.READY -> "bereit (${formatBytes(state.modelBytes)}, lädt bei Bedarf)" to true
            LlmState.LOADING -> "wird geladen …" to true
            LlmState.LOADED -> "im Speicher (${formatBytes(state.modelBytes)})" to true
            LlmState.ERROR -> "Fehler beim Laden" to false
        }
        AiRow("Antwort-Generierung (LLM)", llmText, ok)
    }
}

@Composable
private fun AiRow(label: String, value: String, ok: Boolean) {
    // Bezeichnung und Status untereinander: lange Texte brechen sauber um statt Buchstabe für Buchstabe.
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        StatusDot(if (ok) SuccessGreen else WarningAmber, Modifier.padding(top = 6.dp).size(8.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickNoteCard(onSave: (String, Boolean, (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var fromVoice by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }

    val recognizer = remember {
        VoiceNoteRecognizer(
            context,
            onPartial = { text = it },
            onResult = { text = it; fromVoice = true; listening = false },
            onError = { msg -> listening = false; Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
        )
    }
    DisposableEffect(Unit) { onDispose { recognizer.stop() } }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { listening = true; recognizer.start() }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Text("Schnelle Notiz", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; fromVoice = false },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("z. B. „Parkplatz Ebene 3, Platz 42“") },
            maxLines = 4,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = {
                    when {
                        listening -> { recognizer.stop(); listening = false }
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED -> { listening = true; recognizer.start() }
                        else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, "Sprachnotiz")
            }
            Text(
                if (listening) "Höre zu …" else "Sprachnotiz (offline)",
                Modifier.padding(start = 10.dp).weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    onSave(text, fromVoice) { ok ->
                        Toast.makeText(context, if (ok) "Gespeichert" else "Zu kurz oder doppelt", Toast.LENGTH_SHORT).show()
                        if (ok) { text = ""; fromVoice = false }
                    }
                },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Merken")
            }
        }
    }
}

@Composable
private fun RecentItem(s: ContextSnippet) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.appLabel, style = MaterialTheme.typography.labelLarge, color = NeonCyan, modifier = Modifier.weight(1f))
            Text(
                "${s.source.label()} · ${DateUtils.getRelativeTimeSpanString(s.timestamp)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(s.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}
