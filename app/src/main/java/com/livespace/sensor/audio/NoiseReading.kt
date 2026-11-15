package com.livespace.sensor.audio

data class NoiseReading(
    val rms: Double, val dbfs: Double, val averageDbfs: Double, val peakDbfs: Double,
    val estimatedDbSpl: Double?, val calibrated: Boolean, val floorLimited: Boolean,
    val sampleRate: Int, val windowSamples: Int, val rollingWindows: Int,
    val timestampUtcMs: Long, val elapsedMs: Long
) {
    init {
        require(rms.isFinite() && rms in 0.0..1.0)
        require(listOf(dbfs,averageDbfs,peakDbfs).all { it.isFinite() && it in NoiseMath.FLOOR_DBFS..0.0 })
        require(estimatedDbSpl == null || (calibrated && estimatedDbSpl.isFinite()))
        require(sampleRate > 0 && windowSamples > 0 && rollingWindows in 1..300)
    }
}
data class NoiseConfig(val calibrationOffsetDb: Double? = null) {
    init { require(calibrationOffsetDb == null || (calibrationOffsetDb.isFinite() && calibrationOffsetDb in -200.0..200.0)) }
}
data class MicrophoneStatus(val message: String, val sampleRate: Int? = null,
    val bufferBytes: Int? = null, val source: String? = null)
