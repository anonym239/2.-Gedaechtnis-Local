package de.gedaechtnis.local.ai

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Ergebnis der Analyse einer natürlichsprachigen Frage.
 * @property keywords inhaltstragende Begriffe (ohne Füll- und Zeitwörter)
 * @property from/to  Zeitfenster in Epoch-Millis, das aus Ausdrücken wie "am Dienstag" abgeleitet wurde
 */
data class ParsedQuery(
    val raw: String,
    val keywords: List<String>,
    val from: Long,
    val to: Long,
    val timeLabel: String?,
)

/**
 * Leichtgewichtiger, regelbasierter Parser für deutsche Zeitangaben.
 * Läuft ohne LLM in Mikrosekunden – das LLM wird erst für die Antwort-Formulierung benötigt.
 */
object QueryParser {

    private val WEEKDAYS = mapOf(
        "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY, "mittwoch" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY, "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY,
        "sonnabend" to DayOfWeek.SATURDAY, "sonntag" to DayOfWeek.SUNDAY,
    )

    private val DAY_PARTS = mapOf(
        "morgens" to (5 to 11), "morgen" to (5 to 11), "vormittags" to (8 to 12), "vormittag" to (8 to 12),
        "mittags" to (11 to 14), "mittag" to (11 to 14), "nachmittags" to (12 to 18), "nachmittag" to (12 to 18),
        "abends" to (17 to 24), "abend" to (17 to 24), "nachts" to (0 to 5), "nacht" to (0 to 5),
    )

    private val LAST_WORDS = setOf("letzte", "letzten", "letzter", "letztes", "vergangene", "vergangenen", "vorige", "vorigen")
    private val THIS_WORDS = setOf("diese", "diesen", "dieser", "dieses")

    private val STOPWORDS = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
        "und", "oder", "aber", "mit", "von", "vom", "zu", "zum", "zur", "im", "in", "an", "am", "auf", "aus",
        "bei", "beim", "für", "fuer", "über", "ueber", "um", "nach", "vor", "bis", "seit", "wegen", "bezüglich",
        "bezueglich", "betreffend", "zwecks", "ist", "war", "waren", "wurde", "wurden", "hat", "hatte", "habe",
        "hab", "ich", "du", "er", "sie", "es", "wir", "ihr", "mir", "mich", "mein", "meine", "meinen", "dir",
        "was", "wer", "wie", "wo", "wann", "welche", "welcher", "welches", "warum", "so", "da", "dort", "hier",
        "nicht", "noch", "schon", "nur", "auch", "mal", "etwa", "etwas", "gesehen", "gelesen", "gesagt",
        "nochmal", "eigentlich", "denn", "dass", "daß", "ob", "sich", "sein", "seine", "kann", "könnte",
        "zeig", "zeige", "finde", "such", "suche", "the", "a", "of", "what", "was", "for", "about",
        "uhr", "tag", "tage", "tagen", "woche", "monat", "stunde", "stunden", "einer", "heute", "gestern",
        "vorgestern",
    ) + LAST_WORDS + THIS_WORDS

    private val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")

    fun parse(query: String, now: ZonedDateTime = ZonedDateTime.now()): ParsedQuery {
        val tokens = TOKEN_REGEX.findAll(query.lowercase()).map { it.value }.toList()
        val consumed = BooleanArray(tokens.size)

        var from: ZonedDateTime? = null
        var to: ZonedDateTime? = null
        var label: String? = null
        val startOfToday = now.truncatedTo(ChronoUnit.DAYS)

        fun day(d: ZonedDateTime, l: String) {
            from = d; to = d.plusDays(1); label = l
        }

        for (i in tokens.indices) {
            val t = tokens[i]
            val prev = tokens.getOrNull(i - 1)
            val next = tokens.getOrNull(i + 1)
            when {
                t == "heute" -> { day(startOfToday, "Heute"); consumed[i] = true }
                t == "gestern" -> { day(startOfToday.minusDays(1), "Gestern"); consumed[i] = true }
                t == "vorgestern" -> { day(startOfToday.minusDays(2), "Vorgestern"); consumed[i] = true }

                // "am Dienstag", "letzten Freitag", "dienstags"
                WEEKDAYS.containsKey(t.removeSuffix("s")) -> {
                    val dow = WEEKDAYS.getValue(t.removeSuffix("s"))
                    var d = startOfToday.with(TemporalAdjusters.previousOrSame(dow))
                    if (prev in LAST_WORDS && d == startOfToday) d = d.minusWeeks(1)
                    day(d, t.removeSuffix("s").replaceFirstChar { it.uppercase() })
                    consumed[i] = true
                }

                t == "woche" && (prev in LAST_WORDS || prev in THIS_WORDS) -> {
                    val monday = startOfToday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    if (prev in LAST_WORDS) {
                        from = monday.minusWeeks(1); to = monday; label = "Letzte Woche"
                    } else {
                        from = monday; to = now; label = "Diese Woche"
                    }
                    consumed[i] = true
                }

                t == "monat" && (prev in LAST_WORDS || prev in THIS_WORDS) -> {
                    val first = startOfToday.withDayOfMonth(1)
                    if (prev in LAST_WORDS) {
                        from = first.minusMonths(1); to = first; label = "Letzter Monat"
                    } else {
                        from = first; to = now; label = "Dieser Monat"
                    }
                    consumed[i] = true
                }

                // "vor 3 Tagen", "vor 2 Stunden", "vor einer Stunde"
                t == "vor" && next != null -> {
                    val unit = tokens.getOrNull(i + 2)
                    val n = next.toIntOrNull() ?: if (next in setOf("einer", "einem", "ein")) 1 else null
                    if (n != null && unit != null) {
                        when {
                            unit.startsWith("tag") -> { day(startOfToday.minusDays(n.toLong()), "Vor $n Tag(en)") }
                            unit.startsWith("stunde") -> { from = now.minusHours(n.toLong() + 1); to = now; label = "Letzte ${n + 1} Std." }
                            unit.startsWith("woche") -> { from = startOfToday.minusWeeks(n.toLong()).minusDays(3); to = from!!.plusDays(7); label = "Vor $n Woche(n)" }
                        }
                        consumed[i] = true; consumed[i + 1] = true; consumed[i + 2] = true
                    }
                }
            }
        }

        // Tageszeit nur anwenden, wenn ein einzelner Tag gemeint ist ("Dienstag nachmittags").
        val singleDay = from != null && to != null && from!!.plusDays(1) == to
        for (i in tokens.indices) {
            val part = DAY_PARTS[tokens[i]] ?: continue
            // "morgen" alleine bedeutet meist "der nächste Tag" – nur mit Tagesbezug als Tageszeit deuten.
            if (tokens[i] == "morgen" && !singleDay) continue
            val base = if (singleDay) from!! else startOfToday
            from = base.with(LocalTime.of(part.first, 0))
            to = if (part.second == 24) base.plusDays(1) else base.with(LocalTime.of(part.second, 0))
            label = (label?.let { "$it, " } ?: "") + tokens[i].replaceFirstChar { it.uppercase() }
            consumed[i] = true
        }

        val keywords = tokens.filterIndexed { i, t ->
            !consumed[i] && t !in STOPWORDS && t !in DAY_PARTS && (t.length >= 2 || t.all { it.isDigit() })
        }.distinct()

        return ParsedQuery(
            raw = query.trim(),
            keywords = keywords,
            from = from?.toInstant()?.toEpochMilli() ?: 0L,
            to = to?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE,
            timeLabel = label,
        )
    }

    /**
     * Baut einen sicheren FTS4-MATCH-Ausdruck. Präfixsuche ("budget*") fängt deutsche Komposita
     * und Flexionen ab ("Budgetplanung", "Budgets"). Sonderzeichen werden entfernt (keine Injection).
     */
    fun toFtsMatch(keywords: List<String>): String? {
        val terms = keywords.map { kw -> kw.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { "$it*" }
    }
}
