package de.gedaechtnis.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AnswerComposerTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2025, 10, 26, 22, 40, 0, 0, zone)

    private fun at(h: Int, m: Int, daysAgo: Long = 0) = now.minusDays(daysAgo).withHour(h).withMinute(m).toInstant().toEpochMilli()

    @Test
    fun summaryQuestionIsDetected() {
        val q = QueryParser.parse("Was habe ich heute alles so gemacht?", now)
        assertTrue(q.isSummary)
        assertTrue(q.keywords.isEmpty())
        assertEquals("Heute", q.timeLabel)
    }

    @Test
    fun summaryBuildsSentences() {
        val q = QueryParser.parse("Was habe ich heute gemacht?", now)
        val sources = listOf(
            AnswerSource(at(21, 5), "Claude", null, "Wir bauen eine Android-App mit lokaler KI. Das Release enthält die APK."),
            AnswerSource(at(21, 40), "Claude", null, "Das Release v1.0.0 ist online. Die APK liegt auf GitHub."),
            AnswerSource(at(22, 31), "Claude", null, "Gemma Modelle laufen offline auf dem Gerät."),
            AnswerSource(at(20, 10), "Chrome", "GitHub", "GitHub Actions Workflow für das Release der APK."),
        )
        val a = AnswerComposer.compose(q, sources, zone, now)!!
        assertTrue(a.headline, a.headline.startsWith("Heute warst du vor allem in Claude (3×) und Chrome (1×) unterwegs"))
        assertTrue(a.headline, a.headline.contains("Hauptthemen:"))
        assertTrue(a.headline, a.headline.contains("Release"))
        assertEquals(listOf("Claude", "Chrome"), a.points.map { it.appLabel })
        assertEquals("21:05–22:31 Uhr", a.points.first().timeLabel)
    }

    @Test
    fun concreteAnswerQuotesMatchingSentence() {
        val q = QueryParser.parse("Budget PDF am Dienstag", now)
        val ts = ZonedDateTime.of(2025, 10, 21, 14, 5, 0, 0, zone).toInstant().toEpochMilli()
        val sources = listOf(
            AnswerSource(ts, "Adobe Acrobat", "Budget_Q3.pdf", "Seite 1 von 4. Das Budget für Q3 beträgt 120.000 Euro. Freigabe durch die Leitung."),
        )
        val a = AnswerComposer.compose(q, sources, zone, now)!!
        assertEquals("Am Dienstag um 14:05 hast du zu „budget pdf“ in Adobe Acrobat („Budget_Q3.pdf“) Folgendes gesehen:", a.headline)
        assertEquals("Das Budget für Q3 beträgt 120.000 Euro.", a.points.first().text)
    }

    @Test
    fun whenPhrases() {
        assertEquals("heute um 09:15", AnswerComposer.whenPhrase(at(9, 15), zone, now))
        assertEquals("gestern um 18:00", AnswerComposer.whenPhrase(at(18, 0, 1), zone, now))
    }
}

class HashingEmbedderTest {
    @Test
    fun compoundWordsAreSimilar() {
        val q = HashingEmbedder.embed("Budget")
        val related = VectorMath.dot(q, HashingEmbedder.embed("Die Budgetplanung für das dritte Quartal"))
        val unrelated = VectorMath.dot(q, HashingEmbedder.embed("Wetter morgen sonnig und warm"))
        assertTrue("related=$related unrelated=$unrelated", related > unrelated + 0.1f)
    }
}
