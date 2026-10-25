package de.gedaechtnis.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QueryParserTest {

    private val zone = ZoneId.of("Europe/Berlin")
    // Donnerstag, 23.10.2025, 15:30
    private val now = ZonedDateTime.of(2025, 10, 23, 15, 30, 0, 0, zone)

    private fun millis(y: Int, m: Int, d: Int, h: Int = 0) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun weekdayAndKeywords() {
        val q = QueryParser.parse("Was war das für eine PDF bezüglich des Budgets am Dienstag?", now)
        assertEquals(listOf("pdf", "budgets"), q.keywords)
        assertEquals(millis(2025, 10, 21), q.from)
        assertEquals(millis(2025, 10, 22), q.to)
        assertEquals("Dienstag", q.timeLabel)
    }

    @Test
    fun yesterdayAfternoon() {
        val q = QueryParser.parse("Was habe ich gestern nachmittags gelesen?", now)
        assertTrue(q.keywords.isEmpty())
        assertEquals(millis(2025, 10, 22, 12), q.from)
        assertEquals(millis(2025, 10, 22, 18), q.to)
    }

    @Test
    fun lastWeek() {
        val q = QueryParser.parse("Adresse aus dem Chat letzte Woche", now)
        assertEquals(listOf("adresse", "chat"), q.keywords)
        assertEquals(millis(2025, 10, 13), q.from)
        assertEquals(millis(2025, 10, 20), q.to)
    }

    @Test
    fun sameWeekdayWithLastMeansPreviousWeek() {
        val q = QueryParser.parse("letzten Donnerstag Meeting", now)
        assertEquals(millis(2025, 10, 16), q.from)
    }

    @Test
    fun hoursAgo() {
        val q = QueryParser.parse("Rezept vor 2 Stunden", now)
        assertEquals(listOf("rezept"), q.keywords)
        assertEquals(now.minusHours(3).toInstant().toEpochMilli(), q.from)
    }

    @Test
    fun noTime() {
        val q = QueryParser.parse("Flugnummer Lufthansa", now)
        assertNull(q.timeLabel)
        assertEquals(0L, q.from)
        assertEquals(Long.MAX_VALUE, q.to)
    }

    @Test
    fun ftsMatchIsSanitized() {
        assertEquals("budget* OR pdf*", QueryParser.toFtsMatch(listOf("budget\"", "pdf")))
        assertNull(QueryParser.toFtsMatch(listOf("\"*")))
    }

    @Test
    fun vectorRoundTrip() {
        val v = VectorMath.normalize(floatArrayOf(3f, 4f))
        val back = VectorMath.fromBytes(VectorMath.toBytes(v))
        assertEquals(1f, VectorMath.dot(back, back), 1e-5f)
    }
}
