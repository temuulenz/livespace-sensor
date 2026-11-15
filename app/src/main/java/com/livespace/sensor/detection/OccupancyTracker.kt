package com.livespace.sensor.detection

/** Rolling median of the latest five counts, reset for each capture run. Never accumulates people. */
class OccupancyTracker {
    private val counts = ArrayDeque<Int>()
    fun update(raw: Int): Int {
        require(raw >= 0)
        counts.addLast(raw)
        if (counts.size > 5) counts.removeFirst()
        val sorted = counts.sorted()
        return sorted[sorted.size / 2]
    }
}
