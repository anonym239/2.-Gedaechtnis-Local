package de.gedaechtnis.local.ai

/**
 * Ersatz-Embedding ohne Modell: Wörter und Zeichen-Trigramme werden in einen festen Vektor gehasht.
 * Findet so auch Wortteile deutscher Komposita ("Budget" ↔ "Budgetplanung") und Tippfehler.
 * Läuft garantiert auf jedem Gerät; wird genutzt, wenn MediaPipe nicht starten kann.
 */
object HashingEmbedder {
    const val DIM = 256
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    fun embed(text: String): FloatArray {
        val v = FloatArray(DIM)
        for (m in WORD.findAll(text.lowercase())) {
            val w = m.value
            if (w.length < 2 || w in QueryParser.STOPWORDS_PUBLIC) continue
            add(v, w, 1.0f)
            val padded = "#$w#"
            for (i in 0..padded.length - 3) add(v, padded.substring(i, i + 3), 0.5f)
        }
        return VectorMath.normalize(v)
    }

    private fun add(v: FloatArray, token: String, weight: Float) {
        val h = VectorMath.fnv1a64(token)
        val idx = ((h ushr 1) % DIM).toInt()
        // Vorzeichen-Hashing reduziert systematische Kollisionen.
        v[idx] += if ((h and 1L) == 0L) weight else -weight
    }
}
