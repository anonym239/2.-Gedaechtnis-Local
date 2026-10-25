package de.gedaechtnis.local.ai

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Minimale Sicht auf ein Snippet – hält den Composer frei von Android/Room und damit testbar. */
data class AnswerSource(
    val timestamp: Long,
    val appLabel: String,
    val windowTitle: String?,
    val text: String,
)

/** Ein Stichpunkt der Antwort: wann, wo, was. */
data class AnswerPoint(val timeLabel: String, val appLabel: String, val text: String)

/** Ausformulierte Antwort ohne LLM: ein Kernsatz, Stichpunkte und optional ein Zusatzsatz. */
data class ComposedAnswer(
    val headline: String,
    val points: List<AnswerPoint>,
    val footer: String? = null,
)

/**
 * Bildet aus Suchtreffern eine Antwort in ganzen deutschen Sätzen – komplett regelbasiert, ohne LLM,
 * in wenigen Millisekunden. Zwei Modi:
 *  - **Zusammenfassung** ("Was habe ich heute gemacht?"): Apps, Zeitraum, Hauptthemen, je App ein
 *    repräsentativer Satz.
 *  - **Konkrete Frage** ("Budget-PDF am Dienstag"): wann/wo es zu sehen war plus die passendsten Sätze.
 */
object AnswerComposer {

    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+|\\n+")
    private val WORD = Regex("[\\p{L}][\\p{L}\\p{N}-]+")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
    private val DATE = DateTimeFormatter.ofPattern("d.M.", Locale.GERMANY)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.GERMANY)

    fun compose(
        query: ParsedQuery,
        sources: List<AnswerSource>,
        zone: ZoneId = ZoneId.systemDefault(),
        now: ZonedDateTime = ZonedDateTime.now(zone),
    ): ComposedAnswer? {
        if (sources.isEmpty()) return null
        return if (query.isSummary) summarize(query, sources, zone, now) else answer(query, sources, zone, now)
    }

    // ------------------------------------------------------------------------------------------
    // Zusammenfassung
    // ------------------------------------------------------------------------------------------

    private fun summarize(q: ParsedQuery, sources: List<AnswerSource>, zone: ZoneId, now: ZonedDateTime): ComposedAnswer {
        val sorted = sources.sortedBy { it.timestamp }
        val byApp = sorted.groupBy { it.appLabel }.entries.sortedByDescending { it.value.size }
        val topics = topTopics(sorted, exclude = q.keywords.toSet() + byApp.map { it.key.lowercase() }.toSet())

        val period = periodPhrase(q.timeLabel)
        val apps = byApp.take(3).map { (app, list) -> "$app (${list.size}×)" }
        val span = timeSpan(sorted.first().timestamp, sorted.last().timestamp, zone, now)

        val headline = buildString {
            append(period).append(" warst du ")
            append(if (byApp.size == 1) "in ${apps.first()}" else "vor allem in ${joinGerman(apps)}")
            append(" unterwegs")
            if (span != null) append(" ($span)")
            append('.')
            if (topics.isNotEmpty()) append(" Hauptthemen: ").append(joinGerman(topics.take(5))).append('.')
        }

        val weights = topics.withIndex().associate { (i, t) -> t.lowercase() to (topics.size - i).toDouble() }
        val points = byApp.take(4).map { (app, list) ->
            val best = list.flatMap { sentences(it.text) }.maxByOrNull { scoreByWeights(it, weights) } ?: list.first().text
            AnswerPoint(
                timeLabel = timeSpan(list.first().timestamp, list.last().timestamp, zone, now) ?: whenPhrase(list.first().timestamp, zone, now),
                appLabel = app,
                text = clip(best),
            )
        }
        val footer = if (byApp.size > 4) "Außerdem kurz in ${joinGerman(byApp.drop(4).take(4).map { it.key })}." else null
        return ComposedAnswer(headline, points, footer)
    }

    // ------------------------------------------------------------------------------------------
    // Konkrete Frage
    // ------------------------------------------------------------------------------------------

    private fun answer(q: ParsedQuery, sources: List<AnswerSource>, zone: ZoneId, now: ZonedDateTime): ComposedAnswer {
        val best = sources.first()
        val bestSentences = sentences(best.text)
            .map { it to keywordScore(it, q.keywords) }
            .sortedByDescending { it.second }
            .let { ranked -> if (ranked.firstOrNull()?.second ?: 0 > 0) ranked.filter { it.second > 0 } else ranked }
            .take(2)
            .map { clip(it.first) }

        val where = buildString {
            append(best.appLabel)
            best.windowTitle?.takeIf { it.isNotBlank() && !it.equals(best.appLabel, true) }?.let { append(" („").append(clip(it, 60)).append("“)") }
        }
        val topic = if (q.keywords.isNotEmpty()) " zu „${q.keywords.joinToString(" ")}“" else ""
        val headline = "${whenPhrase(best.timestamp, zone, now).replaceFirstChar { it.uppercase() }} hast du$topic in $where Folgendes gesehen:"

        val points = bestSentences.map { AnswerPoint(TIME.format(time(best.timestamp, zone)), best.appLabel, it) }

        // Weitere Fundstellen in anderen Apps/zu anderen Zeiten als zusammenfassender Satz.
        val others = sources.drop(1)
            .distinctBy { it.appLabel to ChronoUnit.HOURS.between(time(it.timestamp, zone), time(best.timestamp, zone)) }
            .take(3)
            .map { "${it.appLabel} (${whenPhrase(it.timestamp, zone, now)})" }
        val footer = if (others.isEmpty()) null else "Ebenfalls aufgetaucht in ${joinGerman(others)}."
        return ComposedAnswer(headline, points, footer)
    }

    // ------------------------------------------------------------------------------------------
    // Text-Helfer
    // ------------------------------------------------------------------------------------------

    /** Häufigste inhaltstragende Wörter über mehrere Snippets (Substantive bevorzugt). */
    fun topTopics(sources: List<AnswerSource>, exclude: Set<String> = emptySet(), limit: Int = 6): List<String> {
        val docFreq = HashMap<String, Int>()
        val display = HashMap<String, String>()
        for (s in sources) {
            val seen = HashSet<String>()
            for (m in WORD.findAll(s.text)) {
                val w = m.value
                val key = w.lowercase()
                if (key.length < 4 || key in QueryParser.STOPWORDS_PUBLIC || key in exclude || key in GENERIC) continue
                if (seen.add(key)) docFreq.merge(key, 1, Int::plus)
                // Großgeschriebene Form (deutsche Substantive) als Anzeigeform bevorzugen.
                if (w[0].isUpperCase() || key !in display) display[key] = w
            }
        }
        val minDocs = if (sources.size >= 4) 2 else 1
        return docFreq.entries
            .filter { it.value >= minDocs }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value * (if (display[it.key]!![0].isUpperCase()) 1.5 else 1.0) }.thenBy { it.key })
            .take(limit)
            .map { display.getValue(it.key) }
    }

    private fun sentences(text: String): List<String> =
        text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.length in 12..400 && it.count(Char::isLetter) > it.length / 2 }
            .ifEmpty { listOf(text.trim()) }

    private fun keywordScore(sentence: String, keywords: List<String>): Int {
        val lower = sentence.lowercase()
        return keywords.count { lower.contains(it) }
    }

    private fun scoreByWeights(sentence: String, weights: Map<String, Double>): Double {
        val words = WORD.findAll(sentence.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return 0.0
        return words.sumOf { weights[it] ?: 0.0 } / (1 + words.size / 25.0)
    }

    private fun clip(s: String, max: Int = 180): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        val cut = flat.take(max)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: max).trimEnd(',', ';', ':') + " …"
    }

    private fun time(ts: Long, zone: ZoneId) = Instant.ofEpochMilli(ts).atZone(zone)

    /** "heute um 14:05", "gestern um 9:10", "am Dienstag um 8:00", "am 3.10. um 17:45". */
    fun whenPhrase(ts: Long, zone: ZoneId, now: ZonedDateTime): String {
        val t = time(ts, zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        val day = when {
            days == 0L -> "heute"
            days == 1L -> "gestern"
            days in 2..6 -> "am " + WEEKDAY.format(t)
            else -> "am " + DATE.format(t)
        }
        return "$day um ${TIME.format(t)}"
    }

    private fun timeSpan(from: Long, to: Long, zone: ZoneId, now: ZonedDateTime): String? {
        val a = time(from, zone)
        val b = time(to, zone)
        if (a.toLocalDate() != b.toLocalDate()) return null
        return if (ChronoUnit.MINUTES.between(a, b) < 2) "um ${TIME.format(a)} Uhr" else "${TIME.format(a)}–${TIME.format(b)} Uhr"
    }

    private fun periodPhrase(label: String?): String = when {
        label == null -> "Insgesamt"
        label.substringBefore(' ') in WEEKDAYS -> "Am $label"
        else -> label
    }

    private fun joinGerman(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " und " + items.last()
    }

    private val WEEKDAYS = setOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonnabend", "Sonntag")

    /** UI-Rauschen, das in fast jeder App vorkommt und kein Thema ist. */
    private val GENERIC = setOf(
        "suche", "suchen", "menü", "einstellungen", "zurück", "weiter", "schließen", "öffnen", "teilen",
        "mehr", "home", "start", "nachricht", "nachrichten", "senden", "antworten", "kopieren", "button",
        "navigation", "symbol", "bild", "foto", "datei", "https", "http", "www", "uhr", "heute", "gestern",
    )
}
