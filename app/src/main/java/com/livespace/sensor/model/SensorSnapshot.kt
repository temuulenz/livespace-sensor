package com.livespace.sensor.model

import com.livespace.sensor.audio.NoiseReading

/** Immutable, finite structured data for Phase 4; there is deliberately no serializer or networking yet. */
data class SensorSnapshot(
    val timestampUtcMs: Long, val observedPeople: Int?, val detectionConfidence: Float?, val inferenceMs: Double?,
    val noiseRms: Double?, val noiseDbfs: Double?, val estimatedDbSpl: Double?, val calibrated: Boolean,
    val batteryPercent: Int?, val batteryTemperatureC: Double?,
    val detectionTimestampUtcMs: Long?, val noiseTimestampUtcMs: Long?, val batteryTimestampUtcMs: Long?,
    val charging: Boolean? = null,val deviceUptimeSeconds: Long? = null
)

/** All updates/reads share a lock. A reader gets a coherent immutable copy; stale producers become null. */
class TelemetryStore {
    private data class Observation(val people: Int,val confidence: Float?,val inferenceMs: Double,
        val utc: Long,val elapsed: Long,val maxAge: Long)
    private data class Battery(val percent: Int?,val temperature: Double?,val utc: Long,val elapsed: Long,val charging: Boolean?)
    private var observation: Observation? = null
    private var noise: NoiseReading? = null
    private var battery: Battery? = null
    @Synchronized fun updateDetection(people: Int,confidence: Float?,inferenceMs: Double,utc: Long,elapsed: Long,maxAge: Long) {
        require(people >= 0 && (confidence == null || (confidence.isFinite() && confidence in 0f..1f)))
        require(inferenceMs.isFinite() && inferenceMs >= 0 && maxAge > 0)
        observation = Observation(people,confidence,inferenceMs,utc,elapsed,maxAge)
    }
    @Synchronized fun updateNoise(reading: NoiseReading) { noise = reading }
    @Synchronized fun clearDetection() { observation = null }
    @Synchronized fun clearNoise() { noise = null }
    @Synchronized fun clearSensors() { observation = null; noise = null }
    @Synchronized fun updateBattery(percent: Int?,temperature: Double?,utc: Long,elapsed: Long,charging: Boolean? = null) {
        require(percent == null || percent in 0..100)
        require(temperature == null || temperature.isFinite())
        battery = Battery(percent,temperature,utc,elapsed,charging)
    }
    @Synchronized fun snapshot(utcMs: Long,elapsedMs: Long): SensorSnapshot {
        val d = observation?.takeIf { elapsedMs-it.elapsed in 0..it.maxAge }
        val n = noise?.takeIf { elapsedMs-it.elapsedMs in 0..3000 }
        val b = battery?.takeIf { elapsedMs-it.elapsed in 0..30000 }
        return SensorSnapshot(utcMs,d?.people,d?.confidence,d?.inferenceMs,n?.rms,n?.dbfs,n?.estimatedDbSpl,
            n?.calibrated ?: false,b?.percent,b?.temperature,d?.utc,n?.timestampUtcMs,b?.utc,b?.charging,elapsedMs.takeIf { it>=0 }?.div(1000))
    }
}
object TelemetryRepository { val store = TelemetryStore() }
