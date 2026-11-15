package com.livespace.sensor.audio

import kotlin.math.log10
import kotlin.math.sqrt

object NoiseMath {
    const val FLOOR_DBFS = -120.0
    const val FLOOR_RMS = 0.000001
    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        require(count in 1..samples.size)
        var sum = 0.0
        for (i in 0 until count) { val v = samples[i].toDouble()/32768.0; sum += v*v }
        return sqrt(sum/count)
    }
    fun dbfs(rms: Double): Double {
        require(rms.isFinite() && rms in 0.0..1.0)
        return if (rms <= FLOOR_RMS) FLOOR_DBFS else 20.0*log10(rms)
    }
    fun estimatedSpl(rms: Double, offset: Double?): Double? {
        NoiseConfig(offset)
        // The digital silence floor is a numeric convention, not a physical sound level.
        return if (offset != null && rms > FLOOR_RMS) dbfs(rms)+offset else null
    }
}

/** Exact one-second sample windows; bounded rolling energy average, never averages dB arithmetically. */
class NoiseProcessor(private val sampleRate: Int, private val config: NoiseConfig, private val rollingSize: Int = 30) {
    init { require(sampleRate > 0 && rollingSize in 1..300) }
    private val energies = DoubleArray(rollingSize)
    private val peaks = DoubleArray(rollingSize)
    private var index = 0
    private var windows = 0
    private var sum = 0.0
    private var peak = 0.0
    private var count = 0
    fun accept(samples: ShortArray, size: Int, utcMs: Long, elapsedMs: Long, publish: (NoiseReading) -> Unit) {
        require(size in 0..samples.size)
        for (i in 0 until size) {
            val value = samples[i].toDouble()/32768.0
            sum += value*value
            peak = maxOf(peak, kotlin.math.abs(value))
            count++
            if (count == sampleRate) {
                val meanSquare = sum/count
                energies[index] = meanSquare; peaks[index] = peak
                index = (index+1)%rollingSize; windows = minOf(windows+1,rollingSize)
                val rms = sqrt(meanSquare)
                var energy = 0.0; var rollingPeak = 0.0
                for (j in 0 until windows) { energy += energies[j]; rollingPeak = maxOf(rollingPeak,peaks[j]) }
                publish(NoiseReading(rms,NoiseMath.dbfs(rms),NoiseMath.dbfs(sqrt(energy/windows)),
                    NoiseMath.dbfs(rollingPeak),NoiseMath.estimatedSpl(rms,config.calibrationOffsetDb),
                    config.calibrationOffsetDb != null,rms <= NoiseMath.FLOOR_RMS,sampleRate,count,windows,utcMs,elapsedMs))
                sum = 0.0; peak = 0.0; count = 0
            }
        }
    }
}
