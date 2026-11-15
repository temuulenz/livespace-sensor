package com.livespace.sensor.detection

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DetectionMathTest {
    @Test fun previewCompensatesDisplayOnlyAndNeverRotatesSensorTwice() {
        assertArrayEquals(floatArrayOf(0f,0f,1f,0f,1f,1f,0f,1f),FrameGeometry.previewCorners(0),0f)
        assertArrayEquals(floatArrayOf(0f,1f,0f,0f,1f,0f,1f,1f),FrameGeometry.previewCorners(90),0f)
        assertArrayEquals(floatArrayOf(1f,1f,0f,1f,0f,0f,1f,0f),FrameGeometry.previewCorners(180),0f)
        assertArrayEquals(floatArrayOf(1f,0f,1f,1f,0f,1f,0f,0f),FrameGeometry.previewCorners(270),0f)
    }

    @Test fun previewConsumerAndModelCoordinatesAgreeForEveryQuarterTurn() {
        fun rotate(x: Float,y: Float,degrees: Int)=when(degrees) {
            0 -> x to y;90 -> 1f-y to x;180 -> 1f-x to 1f-y;else -> y to 1f-x
        }
        for(sensor in listOf(0,90,180,270)) for(display in listOf(0,90,180,270)) {
            val (u,v)=rotate(0.2f,0.7f,sensor)
            val c=FrameGeometry.previewCorners(display)
            val x=c[0]+u*(c[2]-c[0])+v*(c[6]-c[0])
            val y=c[1]+u*(c[3]-c[1])+v*(c[7]-c[1])
            val expected=rotate(0.2f,0.7f,(sensor-display+360)%360)
            assertEquals(expected.first,x,0.00001f);assertEquals(expected.second,y,0.00001f)
        }
    }
    @Test fun paddedInterleavedPlanesRespectStridesPositionsCropAndRotation() {
        // Synthetic grayscale pattern, not a model accuracy test or real observation.
        val y = ByteBuffer.allocate(4+8*4).apply { position(4) }
        for (row in 0..3) for (col in 0..3) y.put(4+row*8+col,(16+row*40+col*10).toByte())
        val u = ByteBuffer.allocate(2+8*2).apply { position(2) }
        val v = ByteBuffer.allocate(3+8*2).apply { position(3) }
        for (row in 0..1) for (col in 0..1) { u.put(2+row*8+col*2,128.toByte()); v.put(3+row*8+col*2,128.toByte()) }
        val planes = arrayOf(ImagePreprocessor.Plane(y,8,1),ImagePreprocessor.Plane(u,8,2),ImagePreprocessor.Plane(v,8,2))
        val p = ImagePreprocessor()
        val expectedY = mapOf(0 to 66,90 to 106,180 to 116,270 to 76)
        for ((angle,yy) in expectedY) {
            p.prepare(planes,1,1,2,2,angle)
            val expected = (1192*(yy-16) shr 10).coerceIn(0,255)
            assertEquals(270000,p.input.remaining())
            for (channel in 0..2) assertEquals("angle=$angle",expected,p.input.get(channel).toInt() and 255)
        }
    }
    @Test fun blackWhiteAndClampingAreUnsignedRgbBytes() {
        val y=ByteBuffer.wrap(byteArrayOf(16,235.toByte()))
        val chroma=ImagePreprocessor.Plane(ByteBuffer.wrap(byteArrayOf(128.toByte())),1,1)
        val p=ImagePreprocessor()
        p.prepare(arrayOf(ImagePreprocessor.Plane(y,2,1),chroma,chroma),0,0,2,1,0)
        assertEquals(0,p.input.get(0).toInt() and 255)
        assertEquals(254,p.input.get(299*3).toInt() and 255)
    }
    @Test fun temporalCountsNeverAccumulateAndMedianResistsOneOutlier() {
        val tracker=OccupancyTracker()
        assertEquals(2,tracker.update(2)); assertEquals(2,tracker.update(2)); assertEquals(2,tracker.update(9))
        assertEquals(2,tracker.update(2)); assertEquals(2,tracker.update(2))
        repeat(5) { tracker.update(0) }; assertEquals(0,tracker.update(0))
    }
    @Test fun fitGeometryLetterboxesAndSupportsAllRotations() {
        assertEquals(Box(40f,0f,280f,320f),FrameGeometry.fit(320f,320f,640,480,90))
        assertEquals(Box(0f,40f,320f,280f),FrameGeometry.fit(320f,320f,640,480,0))
        for(angle in listOf(0,90,180,270)) {
            val x=FrameGeometry.sensorX(0.2f,0.7f,angle); val y=FrameGeometry.sensorY(0.2f,0.7f,angle)
            val upright = when(angle) { 0 -> x to y; 90 -> 1f-y to x; 180 -> 1f-x to 1f-y; else -> y to 1f-x }
            assertEquals(0.2f,upright.first,0.00001f); assertEquals(0.7f,upright.second,0.00001f)
        }
    }
    @Test fun croppedImageBoxesMapBackToTheFullUprightPreview() {
        val crop=FrameGeometry.uprightCrop(Box(0.25f,0f,0.75f,0.5f),90)
        assertEquals(Box(0.5f,0.25f,1f,0.75f),crop)
        assertEquals(crop,FrameGeometry.intoCrop(Box(0f,0f,1f,1f),crop))
        assertEquals(Box(0.625f,0.375f,0.875f,0.625f),FrameGeometry.intoCrop(Box(0.25f,0.25f,0.75f,0.75f),crop))
    }
    @Test fun invalidConfigurationIsRejected() {
        for(f in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f)) {
            assertThrows(IllegalArgumentException::class.java) { DetectionConfig(threshold=f) }
        }
        assertThrows(IllegalArgumentException::class.java) { DetectionConfig(threads=3) }
        assertThrows(IllegalArgumentException::class.java) { DetectionConfig(area=Box(1f,0f,0f,1f)) }
    }
    @Test fun monitoringAreaUsesCentersWithoutTrackingIdentity() {
        val area=Box(0.2f,0.2f,0.8f,0.8f)
        assertTrue(area.containsCenter(Box(0f,0f,1f,1f)))
        assertFalse(area.containsCenter(Box(0f,0f,0.1f,0.1f)))
    }
    @Test fun medianAveragesMiddleAndP95UsesNearestRank() {
        val samples=(1..30).map { it.toDouble() }
        assertEquals(15.5,PerformanceStats.percentile(samples,0.5),0.0)
        assertEquals(29.0,PerformanceStats.percentile(samples,0.95),0.0)
    }
}
