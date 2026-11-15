package com.livespace.sensor.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class NoiseMathTest {
    @Test fun digitalSilenceHasZeroRmsFiniteFloorAndNoSpl() {
        assertEquals(0.0,NoiseMath.rms(ShortArray(16000)),0.0)
        assertEquals(-120.0,NoiseMath.dbfs(0.0),0.0)
        assertNull(NoiseMath.estimatedSpl(0.0,100.0))
    }
    @Test fun signedFullScaleAndHalfScaleAreNormalizedCorrectly() {
        assertEquals(1.0,NoiseMath.rms(shortArrayOf(Short.MIN_VALUE)),0.0)
        assertEquals(0.0,NoiseMath.dbfs(1.0),0.0)
        assertEquals(0.5,NoiseMath.rms(shortArrayOf(16384,-16384)),0.0)
        assertEquals(-6.020599913,NoiseMath.dbfs(0.5),1e-8)
        assertEquals(32767.0/32768,NoiseMath.rms(shortArrayOf(32767)),0.0)
    }
    @Test fun sineWaveRmsMatchesAmplitudeOverSqrtTwo() {
        val tone=ShortArray(16000) { (16384*sin(2*PI*1000*it/16000)).toInt().toShort() }
        assertEquals(0.5/sqrt(2.0),NoiseMath.rms(tone),0.0001)
        assertEquals(-9.0309,NoiseMath.dbfs(NoiseMath.rms(tone)),0.002)
    }
    @Test fun sumsDoNotOverflowAndOnlyActualReadLengthIsUsed() {
        assertEquals(1.0,NoiseMath.rms(ShortArray(48000) { Short.MIN_VALUE }),0.0)
        assertEquals(0.0,NoiseMath.rms(shortArrayOf(0,Short.MIN_VALUE),1),0.0)
    }
    @Test fun invalidInputsAreRejectedAndTinyLevelsStayFinite() {
        assertThrows(IllegalArgumentException::class.java) { NoiseMath.rms(shortArrayOf()) }
        for (v in listOf(Double.NaN,Double.POSITIVE_INFINITY,-0.1,1.1)) {
            assertThrows(IllegalArgumentException::class.java) { NoiseMath.dbfs(v) }
        }
        assertEquals(-120.0,NoiseMath.dbfs(1e-100),0.0)
    }
    @Test fun calibrationIsOptionalValidatedAndOnlyAnEstimate() {
        assertNull(NoiseMath.estimatedSpl(0.5,null))
        assertEquals(93.9794,NoiseMath.estimatedSpl(0.5,100.0)!!,0.0001)
        for (v in listOf(Double.NaN,Double.POSITIVE_INFINITY,201.0)) {
            assertThrows(IllegalArgumentException::class.java) { NoiseConfig(v) }
        }
    }
    @Test fun rollingAverageUsesEnergyAndPeakEvictsAtWindowBoundary() {
        val processor=NoiseProcessor(4,NoiseConfig(),2)
        val readings=mutableListOf<NoiseReading>()
        processor.accept(ShortArray(4) { Short.MIN_VALUE },4,1000,1000,readings::add)
        processor.accept(ShortArray(4),4,2000,2000,readings::add)
        assertEquals(-3.0103,readings.last().averageDbfs,0.0001)
        assertEquals(0.0,readings.last().peakDbfs,0.0)
        processor.accept(ShortArray(4),4,3000,3000,readings::add)
        assertEquals(-120.0,readings.last().averageDbfs,0.0)
        assertEquals(-120.0,readings.last().peakDbfs,0.0)
        assertEquals(2,readings.last().rollingWindows)
    }
    @Test fun partialAndMultipleReadChunksProduceExactOneSecondWindows() {
        val processor=NoiseProcessor(16000,NoiseConfig())
        val readings=mutableListOf<NoiseReading>()
        repeat(9) { processor.accept(ShortArray(1600) { 16384 },1600,it.toLong(),it.toLong(),readings::add) }
        assertEquals(0,readings.size)
        processor.accept(ShortArray(3200) { 16384 },3200,1000,1000,readings::add)
        assertEquals(1,readings.size); assertEquals(16000,readings[0].windowSamples)
        assertEquals(0.5,readings[0].rms,0.0)
        repeat(9) { processor.accept(ShortArray(1600),1600,2000,2000,readings::add) }
        assertEquals(2,readings.size)
    }
    @Test fun readingsRejectNonfiniteOrUncalibratedSpl() {
        assertThrows(IllegalArgumentException::class.java) { NoiseReading(Double.NaN,-30.0,-30.0,-20.0,null,false,false,16000,16000,1,0,0) }
        assertThrows(IllegalArgumentException::class.java) { NoiseReading(0.1,-20.0,-20.0,-10.0,80.0,false,false,16000,16000,1,0,0) }
    }
}
