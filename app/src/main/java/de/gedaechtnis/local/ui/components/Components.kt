package de.gedaechtnis.local.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.gedaechtnis.local.data.db.SnippetSource
import java.util.Locale
import de.gedaechtnis.local.ui.theme.NeonCyan
import de.gedaechtnis.local.ui.theme.NeonViolet

/** Dunkle Karte mit feinem Rand – Grundbaustein aller Screens. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        // Leicht transparent, damit der Hintergrundverlauf durchscheint.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f)),
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.45f) ?: MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

/** Verlauf Violett → Cyan für Akzent-Elemente. */
val NeonGradient = Brush.linearGradient(listOf(NeonViolet, NeonCyan))

fun SnippetSource.label(): String = when (this) {
    SnippetSource.SCREEN_TEXT -> "Bildschirm"
    SnippetSource.SCREEN_OCR -> "OCR"
    SnippetSource.NOTE -> "Notiz"
    SnippetSource.VOICE -> "Sprache"
}

/** Hebt Suchbegriffe im Text farbig hervor. */
fun highlight(text: String, rawTerms: List<String>, color: Color) = buildAnnotatedString {
    val terms = rawTerms.map { it.lowercase() }.filter { it.isNotEmpty() }
    if (terms.isEmpty()) { append(text); return@buildAnnotatedString }
    val lower = text.lowercase()
    var i = 0
    while (i < text.length) {
        val match = terms.mapNotNull { t -> lower.indexOf(t, i).takeIf { it >= 0 }?.let { it to t.length } }
            .minByOrNull { it.first }
        if (match == null) { append(text.substring(i)); break }
        // lowercase() kann in seltenen Fällen die Länge ändern – Grenzen daher absichern.
        val start = minOf(match.first, text.length)
        val end = minOf(start + match.second, text.length)
        append(text.substring(i, start))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold, background = color.copy(alpha = 0.12f))) {
            append(text.substring(start, end))
        }
        i = maxOf(end, i + 1)
    }
}

/**
 * Kompakte Größenangabe mit Einheit: "500 B", "12,3 KB", "104 MB", "1,2 GB".
 * Ab drei Stellen ohne Nachkommastelle, damit die Angabe auch auf schmale Kacheln passt.
 */
fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.coerceAtLeast(0).toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    val number = when {
        i == 0 || value >= 100 -> "%.0f".format(Locale.GERMANY, value)
        else -> "%.1f".format(Locale.GERMANY, value).removeSuffix(",0")
    }
    return "$number ${units[i]}"
}
