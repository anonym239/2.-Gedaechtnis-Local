package de.gedaechtnis.local.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Hilfsfunktionen für die lokale Vektorsuche (Brute-Force-Kosinus über normalisierte Vektoren). */
object VectorMath {

    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(v)
        return buf.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** L2-Normalisierung; danach entspricht das Skalarprodukt der Kosinus-Ähnlichkeit. */
    fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm == 0f) return v
        return FloatArray(v.size) { v[it] / norm }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    /** Schnelle 64-Bit-FNV-1a-Prüfsumme zur Duplikaterkennung. */
    fun fnv1a64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (c in text) {
            hash = hash xor c.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }
}
