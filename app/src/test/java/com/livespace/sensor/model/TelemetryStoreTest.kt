package com.livespace.sensor.model

import com.livespace.sensor.audio.NoiseReading
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class TelemetryStoreTest {
    private fun noise()=NoiseReading(0.1,-20.0,-20.0,-10.0,null,false,false,16000,16000,1,1000,100)
    @Test fun unavailableAndStoppedMeasurementsStayNull() {
        val store=TelemetryStore()
        assertNull(store.snapshot(1000,100).observedPeople)
        store.updateNoise(noise());store.updateDetection(0,null,145.0,1000,100,6000)
        assertEquals(0,store.snapshot(1001,101).observedPeople)
        assertEquals(0.1,store.snapshot(1001,101).noiseRms!!,0.0)
        store.clearSensors()
        val stopped=store.snapshot(1002,102)
        assertNull(stopped.noiseDbfs);assertNull(stopped.observedPeople);assertFalse(stopped.calibrated)
    }
    @Test fun staleSensorsAndBatteryExpireIndependentlyUsingMonotonicTime() {
        val store=TelemetryStore();store.updateNoise(noise());store.updateDetection(2,0.8f,145.0,1000,100,6000)
        store.updateBattery(27,34.4,1000,100)
        assertNull(store.snapshot(5000,3101).noiseRms)
        assertEquals(2,store.snapshot(5000,3101).observedPeople)
        assertNull(store.snapshot(8000,6101).observedPeople)
        assertEquals(27,store.snapshot(8000,6101).batteryPercent)
        assertNull(store.snapshot(40000,30101).batteryPercent)
        assertNull(store.snapshot(0,99).observedPeople)
    }
    @Test fun microphoneFailureDoesNotInvalidateOccupancyAndCalibrationIsNullable() {
        val store=TelemetryStore();store.updateDetection(1,0.75f,140.0,1000,100,6000)
        store.updateNoise(noise().copy(calibrated=true,estimatedDbSpl=80.0))
        assertEquals(80.0,store.snapshot(1001,101).estimatedDbSpl!!,0.0)
        store.clearNoise();assertEquals(1,store.snapshot(1002,102).observedPeople)
        assertNull(store.snapshot(1002,102).noiseRms)
    }
    @Test fun concurrentReadersNeverObserveTornDetectionFields() {
        val store=TelemetryStore();val pool=Executors.newFixedThreadPool(2)
        try {
            val writer=pool.submit { repeat(2000) { store.updateDetection(it,0.5f,it.toDouble(),1000,100,6000) } }
            val reader=pool.submit { repeat(2000) { val s=store.snapshot(1000,100); if(s.observedPeople!=null) assertEquals(s.observedPeople!!.toDouble(),s.inferenceMs!!,0.0) } }
            writer.get();reader.get()
        } finally { pool.shutdownNow() }
    }
}
