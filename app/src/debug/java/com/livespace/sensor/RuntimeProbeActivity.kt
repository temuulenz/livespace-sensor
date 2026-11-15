package com.livespace.sensor
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
class RuntimeProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Thread {
            try {
                val bytes = assets.open("detect.tflite").use { it.readBytes() }
                val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
                val began = SystemClock.elapsedRealtimeNanos()
                Interpreter(model, Interpreter.Options().setNumThreads(2).setUseNNAPI(false).setUseXNNPACK(false)).use { interpreter ->
                    interpreter.allocateTensors()
                    Log.i("RuntimeProbe", "INIT_OK ms=${(SystemClock.elapsedRealtimeNanos()-began)/1e6} abi=${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                    for (i in 0 until interpreter.inputTensorCount) logTensor("input", i, interpreter.getInputTensor(i))
                    for (i in 0 until interpreter.outputTensorCount) logTensor("output", i, interpreter.getOutputTensor(i))
                    val input = ByteBuffer.allocateDirect(270000).apply { repeat(270000) { put(128.toByte()) }; rewind() }
                    val outputs = hashMapOf<Int,Any>(0 to Array(1){Array(10){FloatArray(4)}},1 to Array(1){FloatArray(10)},2 to Array(1){FloatArray(10)},3 to FloatArray(1))
                    interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
                    Log.i("RuntimeProbe", "INVOKE_OK synthetic neutral input; not a camera observation")
                }
                Log.i("RuntimeProbe", "CLOSE_OK")
            } catch(t: Throwable) { Log.e("RuntimeProbe", "PROBE_FAILED", t) }
            runOnUiThread { finish() }
        }.start()
    }
    private fun logTensor(kind: String, i: Int, t: org.tensorflow.lite.Tensor) {
        Log.i("RuntimeProbe", "$kind[$i] name=${t.name()} shape=${t.shape().joinToString()} type=${t.dataType()} scale=${t.quantizationParams().scale} zero=${t.quantizationParams().zeroPoint}")
    }
}
