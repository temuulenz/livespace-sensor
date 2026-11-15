package com.livespace.sensor.detection

import android.content.Context
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class InferenceScheduler(context: Context, val config: DetectionConfig,
    private val status: (String) -> Unit, private val result: (DetectionResult) -> Unit) {
    private val thread = HandlerThread("LiveSpaceInference").apply { start() }
    private val handler = Handler(thread.looper)
    private val busy = AtomicBoolean(false)
    private val enabled = AtomicBoolean(true)
    private val preprocessor = ImagePreprocessor()
    private val tracker = OccupancyTracker()
    private var detector: PersonDetector? = null
    private var lastStarted = Long.MIN_VALUE
    @Volatile private var ready = false
    @Volatile var successful = 0; private set
    @Volatile var failed = 0; private set
    @Volatile var dropped = 0; private set
    @Volatile var initMs: Double? = null; private set
    init {
        handler.post {
            try {
                val start = SystemClock.elapsedRealtimeNanos()
                if (enabled.get()) {
                    detector = PersonDetector(context.applicationContext, config.threads)
                    initMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    ready = true
                    status("Ready • CPU ${config.threads} threads • init ${"%.1f".format(initMs)} ms")
                }
            } catch (t: Throwable) { failed++; status("Model error: ${t.javaClass.simpleName}: ${t.message}"); Log.e("LiveSpaceDetection", "Model initialization failed", t) }
        }
    }
    /** Called only by camera worker; caller retains ownership and closes Image on return. */
    fun offer(image: Image, rotation: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!enabled.get() || !ready) return
        if (lastStarted != Long.MIN_VALUE && now - lastStarted < config.intervalMs) return
        if (!busy.compareAndSet(false, true)) { dropped++; return }
        lastStarted = now
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            val crop = image.cropRect
            val uprightCrop = FrameGeometry.uprightCrop(Box(crop.left.toFloat()/image.width,
                crop.top.toFloat()/image.height,crop.right.toFloat()/image.width,crop.bottom.toFloat()/image.height),rotation)
            val planes = image.planes.map { ImagePreprocessor.Plane(it.buffer, it.rowStride, it.pixelStride) }.toTypedArray()
            preprocessor.prepare(planes, crop.left, crop.top, crop.width(), crop.height(), rotation)
            val preMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            if (!handler.post {
                try {
                    if (enabled.get()) {
                        val inferStart = SystemClock.elapsedRealtimeNanos()
                        detector!!.invoke(preprocessor.input)
                        val inferMs = (SystemClock.elapsedRealtimeNanos() - inferStart) / 1e6
                        val postStart = SystemClock.elapsedRealtimeNanos()
                        val people = detector!!.people(config,uprightCrop)
                        val observed = tracker.update(people.size)
                        val confidence = if (people.isEmpty()) null else people.map { it.confidence }.average().toFloat()
                        val postMs = (SystemClock.elapsedRealtimeNanos() - postStart) / 1e6
                        successful++
                        result(DetectionResult(people, observed, confidence, System.currentTimeMillis(), now, rotation,
                            preMs, inferMs, postMs, (SystemClock.elapsedRealtimeNanos() - start) / 1e6))
                    }
                } catch (t: Throwable) { failed++; status("Inference error: ${t.message}"); Log.e("LiveSpaceDetection", "Inference failed", t) }
                finally { busy.set(false) }
            }) busy.set(false)
        } catch (t: Throwable) { busy.set(false); failed++; status("Preprocessing error: ${t.message}"); Log.e("LiveSpaceDetection", "Preprocessing failed", t) }
    }
    fun close(afterClose:(()->Unit)?=null) {
        if (!enabled.getAndSet(false)) { afterClose?.invoke();return }
        ready = false
        // Queued behind any invocation. Never close an interpreter from the UI or camera thread.
        if(!handler.post { detector?.close(); detector = null; Log.i("LiveSpaceDetection", "Interpreter closed"); thread.quitSafely();afterClose?.invoke() }) afterClose?.invoke()
    }
}
