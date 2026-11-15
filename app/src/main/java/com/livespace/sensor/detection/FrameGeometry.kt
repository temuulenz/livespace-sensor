package com.livespace.sensor.detection

object FrameGeometry {
    /** Inverse rotation from upright normalized model coordinates to raw sensor coordinates. */
    fun sensorX(u: Float, v: Float, rotation: Int) = when (rotation) {
        0 -> u; 90 -> v; 180 -> 1f - u; 270 -> 1f - v; else -> error("Invalid rotation")
    }
    fun sensorY(u: Float, v: Float, rotation: Int) = when (rotation) {
        0 -> v; 90 -> 1f - u; 180 -> 1f - v; 270 -> u; else -> error("Invalid rotation")
    }
    fun uprightCrop(raw: Box, rotation: Int): Box = when (rotation) {
        0 -> raw
        90 -> Box(1f-raw.bottom,raw.left,1f-raw.top,raw.right)
        180 -> Box(1f-raw.right,1f-raw.bottom,1f-raw.left,1f-raw.top)
        270 -> Box(raw.top,1f-raw.right,raw.bottom,1f-raw.left)
        else -> error("Invalid rotation")
    }
    fun intoCrop(box: Box, crop: Box) = Box(
        crop.left+box.left*(crop.right-crop.left),crop.top+box.top*(crop.bottom-crop.top),
        crop.left+box.right*(crop.right-crop.left),crop.top+box.bottom*(crop.bottom-crop.top))
    /** TextureView already applies sensor orientation; compensate only for display rotation. */
    fun previewCorners(displayDegrees: Int): FloatArray = when ((360-displayDegrees)%360) {
        0 -> floatArrayOf(0f,0f,1f,0f,1f,1f,0f,1f)
        90 -> floatArrayOf(1f,0f,1f,1f,0f,1f,0f,0f)
        180 -> floatArrayOf(1f,1f,0f,1f,0f,0f,1f,0f)
        270 -> floatArrayOf(0f,1f,0f,0f,1f,0f,1f,1f)
        else -> error("Display rotation must be a quarter turn")
    }
    fun fit(viewWidth: Float, viewHeight: Float, rawWidth: Int, rawHeight: Int, rotation: Int): Box {
        val width = if (rotation % 180 == 0) rawWidth else rawHeight
        val height = if (rotation % 180 == 0) rawHeight else rawWidth
        val scale = minOf(viewWidth / width, viewHeight / height)
        val x = (viewWidth - width * scale) / 2
        val y = (viewHeight - height * scale) / 2
        return Box(x, y, viewWidth - x, viewHeight - y)
    }
}
