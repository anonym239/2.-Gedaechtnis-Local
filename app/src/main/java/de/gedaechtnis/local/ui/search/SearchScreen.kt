package de.gedaechtnis.local.ui.search

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.gedaechtnis.local.ai.AnswerPoint
import de.gedaechtnis.local.ai.ComposedAnswer
import de.gedaechtnis.local.capture.VoiceNoteRecognizer
import de.gedaechtnis.local.data.SearchHit
import de.gedaechtnis.local.ui.components.GlassCard
import de.gedaechtnis.local.ui.components.highlight
import de.gedaechtnis.local.ui.components.label
import de.gedaechtnis.local.ui.theme.InputBarColor
import de.gedaechtnis.local.ui.theme.NeonCyan
import de.gedaechtnis.local.ui.theme.NeonViolet
import de.gedaechtnis.local.ui.theme.SendButtonColor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val SUGGESTIONS = listOf(
    "Was habe ich heute gemacht?",
    "Was habe ich gestern nachmittags gelesen?",
    "Budget-PDF am Dienstag",
    "Adresse aus dem Chat letzte Woche",
    "Notizen von heute",
)

/** Verlauf Violett → Cyan → Blau wie das Funkel-Symbol. */
private val SparkleBrush = Brush.linearGradient(listOf(Color(0xFFB388FF), Color(0xFF22D3EE), Color(0xFF4F7CFF)))

/**
 * Suche im Chat-Stil: Ergebnisse oben, schwebende Eingabeleiste unten (wie bei modernen KI-Assistenten).
 * Leerer Zustand zeigt nur das Funkel-Symbol und Vorschläge – keine Begrüßung.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onOpenSettings: () -> Unit = {},
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val submit: (String) -> Unit = { if (it.isNotBlank()) { focus.clearFocus(); vm.search(it.trim()) } }
    val listState = rememberLazyListState()

    // Spracheingabe direkt in die Suchleiste (nutzt das Offline-Sprachpaket, falls installiert).
    var listening by remember { mutableStateOf(false) }
    val recognizer = remember {
        VoiceNoteRecognizer(
            context,
            onPartial = { vm.onQueryChange(it) },
            onResult = { listening = false; submit(it) },
            onError = { msg -> listening = false; Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() },
        )
    }
    DisposableEffect(Unit) { onDispose { recognizer.stop() } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { listening = true; recognizer.start() }
    }
    val toggleVoice: () -> Unit = {
        when {
            listening -> { recognizer.stop(); listening = false }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                listening = true; recognizer.start()
            }
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Treffer nach Kalendertag gruppieren (neueste zuerst) für die Timeline.
    val groups = remember(state.result) {
        state.result?.hits.orEmpty()
            .sortedByDescending { it.snippet.timestamp }
            .groupBy { dayKey(it.snippet.timestamp) }
    }
    // Neue Ergebnisse immer von oben zeigen.
    LaunchedEffect(state.result) { if (state.result != null) listState.scrollToItem(0) }

    val empty = state.result == null && !state.searching

    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .imePadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (empty) {
                EmptyState(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { QuestionBubble(state.query, onClear = vm::clear) }

                    if (state.searching) {
                        item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = NeonCyan) }
                    }

                    state.result?.let { result ->
                        item {
                            val meta = buildList {
                                add("${result.hits.size} Treffer")
                                result.query.timeLabel?.let { add("Zeitraum: $it") }
                                if (result.query.keywords.isNotEmpty()) add("Begriffe: " + result.query.keywords.joinToString(", "))
                                add(
                                    when {
                                        result.query.keywords.isEmpty() -> "Überblick"
                                        result.usedSemanticSearch -> "Hybrid-Suche"
                                        else -> "Volltext"
                                    },
                                )
                            }
                            Text(
                                meta.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        if (result.hits.isEmpty()) {
                            item {
                                GlassCard(Modifier.fillMaxWidth()) {
                                    Text("Dazu habe ich nichts gefunden.", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Versuche andere Begriffe oder einen größeren Zeitraum.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    state.result?.composed?.let { composed ->
                        item {
                            AnswerCard(
                                composed = composed,
                                generated = state.generated,
                                answering = state.answering,
                                llmInstalled = state.llmInstalled,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                    }

                    groups.forEach { (day, hits) ->
                        stickyHeader(key = "day-$day") { DayHeader(hits.first().snippet.timestamp) }
                        items(hits, key = { it.snippet.id }) { hit ->
                            TimelineItem(hit, isLast = hit == hits.last())
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = empty, enter = fadeIn(), exit = fadeOut()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                items(SUGGESTIONS) { s -> SuggestionChip(s) { submit(s) } }
            }
        }

        InputBar(
            value = state.query,
            onValueChange = vm::onQueryChange,
            onSubmit = { submit(state.query) },
            onPlus = { vm.clear(); focus.clearFocus() },
            listening = listening,
            onVoice = toggleVoice,
        )
    }
}

/** Großes Funkel-Symbol mit Farbverlauf – der leere Zustand ohne Begrüßungstext. */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Icon(
        Icons.Default.AutoAwesome,
        contentDescription = null,
        modifier = modifier
            .size(56.dp)
            .graphicsLayer(alpha = 0.99f)
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(SparkleBrush, blendMode = BlendMode.SrcAtop)
                }
            },
    )
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        maxLines = 1,
        modifier = Modifier
            .background(InputBarColor, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Die gestellte Frage als Sprechblase rechts oben, wie in einem Chat. */
@Composable
private fun QuestionBubble(query: String, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, "Neue Frage", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            query,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f, fill = false)
                .background(InputBarColor, RoundedCornerShape(topStart = 22.dp, topEnd = 6.dp, bottomStart = 22.dp, bottomEnd = 22.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * Schwebende Eingabeleiste: "+" (neue Frage), Textfeld, Mikrofon und runder Aktionsknopf.
 * Der Aktionsknopf startet bei leerem Feld die Spracheingabe, sonst die Suche.
 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPlus: () -> Unit,
    listening: Boolean,
    onVoice: () -> Unit,
) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(InputBarColor, RoundedCornerShape(32.dp))
            .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPlus) {
            Icon(Icons.Default.Add, "Neue Frage", tint = MaterialTheme.colorScheme.onSurface)
        }
        Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    if (listening) "Ich höre zu …" else "Frag dein Gedächtnis",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp),
                cursorBrush = SolidColor(NeonViolet),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onVoice) {
            Icon(
                if (listening) Icons.Default.Stop else Icons.Default.Mic,
                "Spracheingabe",
                tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(48.dp)
                .background(SendButtonColor, CircleShape)
                .clickable(onClick = if (value.isBlank()) onVoice else onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (value.isBlank()) Icons.Default.GraphicEq else Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = if (value.isBlank()) "Spracheingabe" else "Fragen",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun AnswerCard(
    composed: ComposedAnswer,
    generated: String?,
    answering: Boolean,
    llmInstalled: Boolean,
    onOpenSettings: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth().animateContentSize(), accent = NeonViolet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = NeonViolet, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (generated != null) "KI-Antwort (lokal)" else "Antwort",
                style = MaterialTheme.typography.labelLarge,
                color = NeonViolet,
            )
        }
        Spacer(Modifier.height(10.dp))

        // Kernaussage: LLM-Text, sonst der regelbasiert formulierte Satz.
        Text(
            generated ?: composed.headline,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
        )

        if (composed.points.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            if (generated != null) {
                Text("Grundlage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            composed.points.forEach { p -> AnswerPointRow(p) }
        }
        composed.footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (answering) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = NeonViolet)
            Text(
                "Die KI formuliert eine ausführlichere Antwort auf dem Gerät …",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else if (!llmInstalled) {
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 4.dp)) {
                Text("Noch bessere Antworten: Sprachmodell installieren →", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AnswerPointRow(p: AnswerPoint) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(NeonCyan, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${p.appLabel} · ${p.timeLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = NeonCyan,
            )
            Text(p.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DayHeader(timestamp: Long) {
    val label = when {
        DateUtils.isToday(timestamp) -> "Heute"
        DateUtils.isToday(timestamp + DateUtils.DAY_IN_MILLIS) -> "Gestern"
        else -> SimpleDateFormat("EEEE, d. MMMM yyyy", Locale.GERMANY).format(Date(timestamp))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = NeonCyan)
    }
}

@Composable
private fun TimelineItem(hit: SearchHit, isLast: Boolean) {
    val s = hit.snippet
    val context = LocalContext.current
    var expanded by rememberSaveable(s.id) { mutableStateOf(false) }
    val time = remember(s.timestamp) { SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(s.timestamp)) }

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Zeitachse
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(10.dp).background(NeonViolet, CircleShape))
            if (!isLast) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
        }
        GlassCard(
            Modifier
                .weight(1f)
                .padding(bottom = 4.dp)
                .clickable { expanded = !expanded }
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.appLabel, style = MaterialTheme.typography.labelLarge, color = NeonCyan)
                Text(
                    "  · ${s.source.label()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (expanded) {
                    IconButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("Snippet", s.text))
                        Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, "Kopieren", Modifier.size(16.dp))
                    }
                }
            }
            s.windowTitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                highlight(s.text, hit.matchedTerms, NeonViolet),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun dayKey(ts: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}
