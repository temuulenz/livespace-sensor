package com.livespace.sensor.detection

import kotlin.math.ceil

/** UI-thread bounded performance window (no camera pixels). */
class PerformanceStats {
    private val samples = ArrayDeque<DetectionResult>()
    fun add(result: DetectionResult) { samples.addLast(result); if (samples.size > 256) samples.removeFirst() }
    fun clear() = samples.clear()
    fun frequency(): Double? = if (samples.size < 2) null else {
        val seconds = (samples.last().capturedElapsedMs - samples.first().capturedElapsedMs) / 1000.0
        if (seconds > 0) (samples.size - 1) / seconds else null
    }
    fun summary(): String {
        if (samples.isEmpty()) return "No successful inference yet"
        val inference = samples.map { it.inferenceMs }; val total = samples.map { it.totalMs }
        return "Recent ${samples.size} samples\nInference median/p95: ${"%.1f".format(percentile(inference,0.5))} / ${"%.1f".format(percentile(inference,0.95))} ms\nTotal median/p95: ${"%.1f".format(percentile(total,0.5))} / ${"%.1f".format(percentile(total,0.95))} ms"
    }
    companion object {
        fun percentile(values: List<Double>, fraction: Double): Double {
            require(values.isNotEmpty() && fraction in 0.0..1.0)
            val sorted = values.sorted()
            if (fraction == 0.5) return (sorted[(sorted.size-1)/2]+sorted[sorted.size/2])/2
            return sorted[(ceil(sorted.size*fraction).toInt()-1).coerceIn(0, sorted.lastIndex)]
        }
    }
}
