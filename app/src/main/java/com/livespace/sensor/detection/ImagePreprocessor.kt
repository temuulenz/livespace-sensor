package com.livespace.sensor.detection

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Samples only the required 300×300 RGB pixels, no Bitmaps or full-resolution copies. */
class ImagePreprocessor {
    data class Plane(val bytes: ByteBuffer, val rowStride: Int, val pixelStride: Int, val base: Int = bytes.position())
    val input: ByteBuffer = ByteBuffer.allocateDirect(300 * 300 * 3).order(ByteOrder.nativeOrder())
    private val xMap = IntArray(300 * 300)
    private val yMap = IntArray(300 * 300)
    private var mapping = ""

    fun prepare(planes: Array<Plane>, left: Int, top: Int, width: Int, height: Int, rotation: Int) {
        val key = "$left,$top,$width,$height,$rotation"
        if (mapping != key) {
            for (y in 0 until 300) for (x in 0 until 300) {
                val u = (x + 0.5f) / 300; val v = (y + 0.5f) / 300; val i = y * 300 + x
                xMap[i] = left + (FrameGeometry.sensorX(u, v, rotation) * width).toInt().coerceIn(0, width - 1)
                yMap[i] = top + (FrameGeometry.sensorY(u, v, rotation) * height).toInt().coerceIn(0, height - 1)
            }
            mapping = key
        }
        input.clear()
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        for (i in xMap.indices) {
            val x = xMap[i]; val y = yMap[i]
            val yy = (yPlane.bytes.get(yPlane.base + y * yPlane.rowStride + x * yPlane.pixelStride).toInt() and 255) - 16
            val uu = (uPlane.bytes.get(uPlane.base + (y / 2) * uPlane.rowStride + (x / 2) * uPlane.pixelStride).toInt() and 255) - 128
            val vv = (vPlane.bytes.get(vPlane.base + (y / 2) * vPlane.rowStride + (x / 2) * vPlane.pixelStride).toInt() and 255) - 128
            val lum = 1192 * yy.coerceAtLeast(0)
            // BT.601 limited-range YUV, clamped RGB; quantization maps RGB bytes directly.
            input.put(((lum + 1634 * vv) shr 10).coerceIn(0, 255).toByte())
            input.put(((lum - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255).toByte())
            input.put(((lum + 2066 * uu) shr 10).coerceIn(0, 255).toByte())
        }
        input.rewind()
    }
}
