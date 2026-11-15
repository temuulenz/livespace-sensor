package com.livespace.sensor.detection

/** Normalized coordinates in the upright, full-camera field of view. No identities. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun containsCenter(other: Box) = (other.left + other.right) / 2 in left..right &&
        (other.top + other.bottom) / 2 in top..bottom
}
data class Person(val box: Box, val confidence: Float)
data class DetectionConfig(
    val threshold: Float = 0.5f, val intervalMs: Long = 3000, val threads: Int = 2,
    val area: Box = Box(0f, 0f, 1f, 1f)
) {
    init {
        require(threshold.isFinite() && threshold in 0.05f..0.99f)
        require(intervalMs in 500..60000 && threads in listOf(1, 2, 4))
        require(listOf(area.left, area.top, area.right, area.bottom).all { it.isFinite() && it in 0f..1f })
        require(area.left < area.right && area.top < area.bottom)
    }
}
data class DetectionResult(
    val people: List<Person>, val observed: Int, val confidence: Float?,
    val timestampUtcMs: Long, val capturedElapsedMs: Long, val rotation: Int,
    val preprocessMs: Double, val inferenceMs: Double, val postprocessMs: Double, val totalMs: Double
)
