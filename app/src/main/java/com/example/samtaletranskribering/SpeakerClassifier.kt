package com.example.samtaletranskribering

import kotlin.math.abs

/** Én replikk i transkripsjonen. speaker: 0 = Meg, 1 = Anroper. */
data class Entry(
    val time: String,
    val text: String,
    val level: Float,
    var speaker: Int = 0,
    var manual: Boolean = false
)

/**
 * Skiller talere ut fra lydstyrke. Med høyttaler er egen stemme normalt nærmere
 * mikrofonen (sterkere) enn anroperens stemme fra høyttaleren.
 *
 * - Uten rettelser: replikkene deles i en sterk og en svak gruppe (sterk = Meg).
 * - Med rettelser fra brukeren: appen lærer hvilket lydnivå som hører til hvem,
 *   og merker resten på nytt ut fra det.
 */
object SpeakerClassifier {
    private const val MIN_SPREAD = 1.0

    fun relabel(entries: List<Entry>) {
        val m0 = entries.filter { it.manual && it.speaker == 0 }.map { it.level.toDouble() }
        val m1 = entries.filter { it.manual && it.speaker == 1 }.map { it.level.toDouble() }

        // Midtpunkt (lydnivå) for taler 0 og taler 1
        val centers: Pair<Double, Double>? =
            if (m0.isNotEmpty() && m1.isNotEmpty()) {
                m0.average() to m1.average()
            } else {
                val k = twoMeans(entries.map { it.level.toDouble() })
                if (k == null) {
                    null
                } else {
                    val (low, high) = k
                    when {
                        m0.isNotEmpty() -> {
                            val m = m0.average()
                            if (abs(m - high) <= abs(m - low)) high to low else low to high
                        }
                        m1.isNotEmpty() -> {
                            val m = m1.average()
                            if (abs(m - high) <= abs(m - low)) low to high else high to low
                        }
                        else -> high to low // standard: sterkeste stemme = Meg
                    }
                }
            }

        for (e in entries) {
            if (e.manual) continue
            e.speaker = if (centers == null) {
                0
            } else {
                val l = e.level.toDouble()
                if (abs(l - centers.first) <= abs(l - centers.second)) 0 else 1
            }
        }
    }

    /** Deler lydnivåene i to grupper. Returnerer (svak, sterk) eller null. */
    private fun twoMeans(values: List<Double>): Pair<Double, Double>? {
        if (values.size < 2) return null
        var low = values.min()
        var high = values.max()
        if (high - low < MIN_SPREAD) return null
        repeat(20) {
            val mid = (low + high) / 2
            val a = values.filter { it < mid }
            val b = values.filter { it >= mid }
            if (a.isEmpty() || b.isEmpty()) return low to high
            low = a.average()
            high = b.average()
        }
        return low to high
    }
}
