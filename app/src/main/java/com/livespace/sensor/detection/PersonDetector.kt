package com.livespace.sensor.detection

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Confined to the inference worker, including construction and close. Built-in CPU kernels only. */
class PersonDetector(context: Context, threads: Int) : AutoCloseable {
    private val model = context.assets.openFd("detect.tflite").use { asset ->
        FileInputStream(asset.fileDescriptor).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
        }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads)
        .setUseNNAPI(false).setUseXNNPACK(false))
    private val locations = Array(1) { Array(10) { FloatArray(4) } }
    private val classes = Array(1) { FloatArray(10) }
    private val scores = Array(1) { FloatArray(10) }
    private val count = FloatArray(1)
    private val outputs = hashMapOf<Int, Any>(0 to locations, 1 to classes, 2 to scores, 3 to count)
    private val inputs = arrayOf<Any>(ByteBuffer.allocateDirect(0))
    init {
        try {
            interpreter.allocateTensors()
            val input = interpreter.getInputTensor(0)
            require(input.shape().contentEquals(intArrayOf(1, 300, 300, 3)) && input.dataType() == DataType.UINT8)
            require(input.quantizationParams().scale == 0.0078125f && input.quantizationParams().zeroPoint == 128)
            val expected = listOf(intArrayOf(1,10,4), intArrayOf(1,10), intArrayOf(1,10), intArrayOf(1))
            val names = listOf("TFLite_Detection_PostProcess", "TFLite_Detection_PostProcess:1", "TFLite_Detection_PostProcess:2", "TFLite_Detection_PostProcess:3")
            require(interpreter.outputTensorCount == 4)
            for (i in 0..3) {
                val t = interpreter.getOutputTensor(i)
                require(t.shape().contentEquals(expected[i]) && t.dataType() == DataType.FLOAT32 && t.name() == names[i])
            }
            val labels = context.assets.open("labels.txt").bufferedReader().use { it.readLines() }
            require(labels[1] == "person") // Output class 0 maps to label line 1 (background offset).
        } catch (t: Throwable) { interpreter.close(); throw t }
    }
    fun invoke(input: ByteBuffer) {
        input.rewind(); inputs[0] = input
        interpreter.runForMultipleInputsOutputs(inputs, outputs)
    }
    fun people(config: DetectionConfig, crop: Box = Box(0f,0f,1f,1f)): List<Person> {
        check(count[0].isFinite()) { "Invalid detection count" }
        val people = ArrayList<Person>()
        for (i in 0 until count[0].toInt().coerceIn(0,10)) {
            val score = scores[0][i]; val coords = locations[0][i]
            if (classes[0][i] != 0f || !score.isFinite() || score < config.threshold || score > 1f || coords.any { !it.isFinite() }) continue
            val box = FrameGeometry.intoCrop(Box(coords[1].coerceIn(0f,1f), coords[0].coerceIn(0f,1f), coords[3].coerceIn(0f,1f), coords[2].coerceIn(0f,1f)),crop)
            if (box.left < box.right && box.top < box.bottom && config.area.containsCenter(box)) people.add(Person(box,score))
        }
        return people // NMS already runs in TFLite_Detection_PostProcess.
    }
    override fun close() = interpreter.close()
}
