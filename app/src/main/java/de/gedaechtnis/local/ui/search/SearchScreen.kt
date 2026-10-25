package de.gedaechtnis.local.ui.search

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.gedaechtnis.local.data.SearchHit
import de.gedaechtnis.local.ui.components.GlassCard
import de.gedaechtnis.local.ui.components.highlight
import de.gedaechtnis.local.ui.components.label
import de.gedaechtnis.local.ui.theme.NeonCyan
import de.gedaechtnis.local.ui.theme.NeonViolet
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val SUGGESTIONS = listOf(
    "Was war das für eine PDF bezüglich des Budgets am Dienstag?",
    "Was habe ich gestern nachmittags gelesen?",
    "Adresse aus dem Chat letzte Woche",
    "Notizen von heute",
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(contentPadding: PaddingValues, vm: SearchViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val submit: (String) -> Unit = { focus.clearFocus(); vm.search(it) }

    // Treffer nach Kalendertag gruppieren (neueste zuerst) für die Timeline.
    val groups = remember(state.result) {
        state.result?.hits.orEmpty()
            .sortedByDescending { it.snippet.timestamp }
            .groupBy { dayKey(it.snippet.timestamp) }
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
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Frag dein Gedächtnis …") },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = NeonViolet) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = vm::clear) { Icon(Icons.Default.Close, "Leeren") }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(state.query) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonViolet,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }

        if (state.result == null && !state.searching) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { s ->
                        AssistChip(
                            onClick = { submit(s) },
                            label = { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                }
            }
        }

        if (state.searching) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = NeonCyan) }
        }

        state.result?.let { result ->
            item {
                val meta = buildList {
                    add("${result.hits.size} Treffer")
                    result.query.timeLabel?.let { add("Zeitraum: $it") }
                    if (result.query.keywords.isNotEmpty()) add("Begriffe: " + result.query.keywords.joinToString(", "))
                    add(if (result.usedSemanticSearch) "Hybrid-Suche" else "Volltext")
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
                        Text("Nichts gefunden.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Versuche andere Begriffe oder einen größeren Zeitraum.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.answer?.let { answer ->
            item { AnswerCard(answer, state.answerIsGenerated, state.answering) }
        }

        groups.forEach { (day, hits) ->
            stickyHeader(key = "day-$day") { DayHeader(hits.first().snippet.timestamp) }
            items(hits, key = { it.snippet.id }) { hit ->
                TimelineItem(hit, isLast = hit == hits.last())
            }
        }
    }
}

@Composable
private fun AnswerCard(answer: String, generated: Boolean, answering: Boolean) {
    GlassCard(Modifier.fillMaxWidth().animateContentSize(), accent = NeonViolet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = NeonViolet, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (generated) "Antwort (lokales LLM)" else "Antwort",
                style = MaterialTheme.typography.labelLarge,
                color = NeonViolet,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(answer, style = MaterialTheme.typography.bodyMedium)
        if (answering) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = NeonViolet)
            Text(
                "KI formuliert eine Antwort auf dem Gerät …",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
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
            .background(MaterialTheme.colorScheme.background)
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
