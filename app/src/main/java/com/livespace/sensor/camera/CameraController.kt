package com.livespace.sensor.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/** Service-owned Camera2 pipeline. The optional TextureView target can attach/detach without stopping YUV analysis. */
class CameraController(context: Context, private val report: (String) -> Unit,
    private val frame: (Image, Int) -> Unit) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    @Volatile private var thread: HandlerThread? = null
    @Volatile private var worker: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewTexture: SurfaceTexture? = null
    private var cameraId: String? = null
    @Volatile private var active = false
    @Volatile var frames = 0L; private set
    @Volatile var sampledFrames = 0L; private set
    @Volatile var lastFrameMs = 0L; private set
    @Volatile var selected = "Unavailable"; private set
    @Volatile var sensorOrientation = 90; private set
    @Volatile var width = 640; private set
    @Volatile var height = 480; private set
    @Volatile var displayRotation = 0
    var intervalMs = 3000L
    private var lastSample = 0L
    private var sessionGeneration = 0

    fun capabilities(): String = try {
        manager.cameraIdList.joinToString("\n\n") { id ->
            val c = manager.getCameraCharacteristics(id)
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.joinToString() ?: "Unavailable"
            "Camera $id: facing=${c.get(CameraCharacteristics.LENS_FACING)} (back=1), hardware=${c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)} (legacy=2), orientation=${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}°\nYUV: $sizes"
        }
    } catch (e: Exception) { "Camera enumeration failed: ${e.message}" }

    @SuppressLint("MissingPermission")
    fun start(texture: SurfaceTexture? = null, previewEnabled: Boolean = false) {
        if (active) { attachPreview(texture, previewEnabled); return }
        if (thread != null) { report("Camera is closing; retry after cleanup"); return }
        active = true
        frames = 0; sampledFrames = 0; lastSample = 0; lastFrameMs = 0
        previewTexture = texture
        val t = HandlerThread("LiveSpaceCamera").also { it.start() }
        thread = t
        worker = Handler(t.looper)
        worker!!.post {
            try {
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: error("No rear camera")
                val characteristics=manager.getCameraCharacteristics(id)
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: error("No stream configurations")
                val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
                val common = yuv.filter { it in previewSizes }
                val size = common.firstOrNull { it.width == 640 && it.height == 480 }
                    ?: common.filter { it.width <= 640 && it.height <= 480 }.maxByOrNull { it.width * it.height }
                    ?: common.minByOrNull { it.width * it.height } ?: yuv.minByOrNull { it.width * it.height } ?: error("No YUV capture size")
                width = size.width; height = size.height; selected = size.toString()
                if(texture!=null && previewEnabled) { texture.setDefaultBufferSize(size.width,size.height);previewSurface=Surface(texture) }
                reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { r ->
                    r.setOnImageAvailableListener({ source ->
                        try {
                            source.acquireLatestImage()?.use { image ->
                                frames++;lastFrameMs=SystemClock.elapsedRealtime()
                                if(lastFrameMs-lastSample>=intervalMs) { lastSample=lastFrameMs;sampledFrames++ }
                                if(active && thread===t) frame(image,(sensorOrientation-displayRotation+360)%360)
                            }
                        } catch(e: Exception) { if(active && thread===t) fail("Frame error: ${e.message}") }
                    },worker)
                }
                cameraId=id
                openDevice(id,t)
            } catch(e: Exception) { fail("Camera unavailable: ${e.message}") }
        }
    }

    /** Surface changes only recreate the Camera2 capture session, not the reader/model/audio owners. */
    fun attachPreview(texture: SurfaceTexture?,enabled: Boolean,afterChange:(()->Unit)?=null) {
        val t=thread ?: run { afterChange?.invoke();return }
        worker?.post {
            if(!active || thread!==t) { afterChange?.invoke();return@post }
            previewTexture=texture
            if(texture!=null && enabled) texture.setDefaultBufferSize(width,height)
            val old=previewSurface
            previewSurface=if(texture!=null && enabled) Surface(texture) else null
            val camera=device
            if(camera!=null) {
                sessionGeneration++
                try { session?.stopRepeating() } catch(_: Exception) { }
                try { session?.close() } catch(_:Exception) { };session=null;device=null
                // Exynos Camera HAL1 cannot reliably reconfigure a live legacy Camera2 session.
                // Fully close the device before changing the output set; retain reader and detector.
                camera.close()
                worker?.postDelayed({
                    try { old?.release() } catch(_:Exception) { }
                    val id=cameraId
                    if(active && thread===t && id!=null) openDevice(id,t,afterChange)
                    else afterChange?.invoke()
                },250)
            } else { old?.release();afterChange?.invoke() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(id:String,t:HandlerThread,afterOpened:(()->Unit)?=null) {
        try {
            manager.openCamera(id,object:CameraDevice.StateCallback() {
                override fun onOpened(camera:CameraDevice) {
                    if(!active || thread!==t) { camera.close();afterOpened?.invoke();return }
                    device=camera
                    try { configureSession(camera,t,afterOpened) } catch(e:Exception) { fail("Session failed: ${e.message}");afterOpened?.invoke() }
                }
                override fun onDisconnected(camera:CameraDevice) { camera.close();if(thread!==t)return;fail("Camera disconnected; sensor continues without camera") }
                override fun onError(camera:CameraDevice,error:Int) { camera.close();if(thread!==t)return;fail("Camera error $error; sensor continues without camera") }
            },worker)
        } catch(e:Exception) { fail("Camera reopen failed: ${e.javaClass.simpleName}");afterOpened?.invoke() }
    }

    private fun configureSession(camera: CameraDevice,t: HandlerThread,afterConfigured:(()->Unit)?=null) {
        val imageSurface=reader?.surface ?: error("ImageReader unavailable")
        val targets=listOfNotNull(previewSurface,imageSurface)
        val current=++sessionGeneration
        camera.createCaptureSession(targets,object: CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if(!active || thread!==t || current!=sessionGeneration || device!==camera) { s.close();afterConfigured?.invoke();return }
                session=s
                try {
                    val request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        targets.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO)
                    }.build()
                    s.setRepeatingRequest(request,null,worker)
                    report("● Camera running • $selected${if(previewSurface==null) " • preview detached" else ""}")
                    Log.i("LiveSpaceCamera","Capture running: $selected preview=${previewSurface!=null}")
                    afterConfigured?.invoke()
                } catch(e: Exception) { fail("Capture failed: ${e.message}") }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { s.close();afterConfigured?.invoke();if(thread===t && current==sessionGeneration)fail("Stream configuration failed") }
        },worker)
    }

    private fun fail(message:String) {
        active=false;sessionGeneration++;release();report(message);Log.e("LiveSpaceCamera",message)
        val t=thread;t?.quitSafely();worker=null;thread=null
    }
    private fun release() {
        try { session?.close() } catch(_: Exception) { };session=null
        try { device?.close() } catch(_: Exception) { };device=null
        try { reader?.close() } catch(_: Exception) { };reader=null
        try { previewSurface?.release() } catch(_: Exception) { };previewSurface=null;previewTexture=null
    }
    fun stop(afterClose:(()->Unit)?=null) {
        active=false;sessionGeneration++
        val t=thread
        if(t==null) { afterClose?.invoke();return }
        val posted=worker?.post {
            if(thread===t) { release();worker=null;thread=null }
            afterClose?.invoke();t.quitSafely()
        } ?: false
        if(!posted) afterClose?.invoke()
    }
}
