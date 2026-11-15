package com.livespace.sensor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.*
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.*
import com.livespace.sensor.audio.*
import com.livespace.sensor.network.*
import com.livespace.sensor.ui.NoiseHistoryView
import com.livespace.sensor.device.DeviceMonitor
import com.livespace.sensor.detection.*
import com.livespace.sensor.ui.DetectionOverlayView
import com.livespace.sensor.service.SensorService
import com.livespace.sensor.service.SensorServiceState
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var preview: TextureView
    private lateinit var overlay: DetectionOverlayView
    private lateinit var monitor: DeviceMonitor
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("sensor", MODE_PRIVATE) }
    private var sensorService:SensorService?=null
    private var serviceBound=false
    private var pendingServiceStart=false
    private var pendingServiceConnect=false
    private var serviceState=SensorServiceState()
    private var displayedNoiseTimestamp = Long.MIN_VALUE
    private lateinit var noiseHistory: NoiseHistoryView
    private val tokenStore by lazy { DeviceTokenStore(applicationContext) }
    private var resumed = false
    private var config = DetectionConfig()
    private var benchmarkSamples = 0
    private var autoStart = false
    private val serviceObserver=object:SensorService.Observer {
        override fun onSensorServiceChanged(state:SensorServiceState) {
            if(Looper.myLooper()!=Looper.getMainLooper()) { runOnUiThread { onSensorServiceChanged(state) };return }
            serviceState=state
            if(resumed) refresh()
        }
    }
    private val serviceConnection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:android.os.IBinder?) {
            sensorService=(binder as? SensorService.LocalBinder)?.service;serviceBound=sensorService!=null
            sensorService?.setObserver(serviceObserver)
            val texture=preview.surfaceTexture
            if(resumed && preview.isAvailable && texture!=null) attachCurrentPreview(texture)
            if(pendingServiceStart) { pendingServiceStart=false;start() }
            if(pendingServiceConnect) { pendingServiceConnect=false;connectTransport() }
            refresh()
        }
        override fun onServiceDisconnected(name:ComponentName?) { sensorService=null;serviceBound=false;serviceState=SensorServiceState();refresh() }
    }
    private val update = object : Runnable {
        override fun run() { transform(); refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        noiseHistory = findViewById(R.id.noiseHistory)
        preview.surfaceTextureListener = this
        monitor = DeviceMonitor(this)
        if (!prefs.contains("device") || prefs.getString("device",null)=="LS-001") prefs.edit().putString("device","LS-NOTE4-001").apply()
        if (prefs.getString("location","").isNullOrBlank()) prefs.edit().putString("location","I").apply()
        findViewById<View>(R.id.connect).setOnClickListener { connectTransport() }
        findViewById<View>(R.id.disconnect).setOnClickListener { sensorService?.disconnectByUser();refresh() }
        findViewById<View>(R.id.networkSettings).setOnClickListener { networkSettings() }
        findViewById<View>(R.id.start).setOnClickListener { requestStart() }
        findViewById<View>(R.id.stop).setOnClickListener { stop() }
        findViewById<View>(R.id.settings).setOnClickListener { settings() }
        findViewById<View>(R.id.diagnostics).setOnClickListener { diagnostics() }
        // ADB benchmark overrides exist only in debuggable builds and never change saved settings.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            benchmarkSamples = intent.getIntExtra("benchmarkSamples", 0).coerceIn(0,256)
            autoStart = benchmarkSamples > 0
            if (autoStart) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        refresh()
    }
    override fun onResume() {
        super.onResume(); resumed = true; handler.post(update)
        val texture=preview.surfaceTexture
        if(preview.isAvailable && texture!=null) sensorService?.let { attachCurrentPreview(texture) }
        if (pendingServiceStart) { handler.post { start() } }
        else if (autoStart) { autoStart = false; handler.post { requestStart() } }
    }
    override fun onPause() {
        resumed = false
        handler.removeCallbacks(update)
        super.onPause()
    }
    override fun onStart() {
        super.onStart()
        bindService(Intent(this,SensorService::class.java),serviceConnection,Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        sensorService?.setObserver(null)
        if(serviceBound) { try { unbindService(serviceConnection) } catch(_:Exception) { };serviceBound=false }
        sensorService=null
        super.onStop()
    }
    private fun microphoneEnabled(): Boolean = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.hasExtra("microphoneEnabled"))
        intent.getBooleanExtra("microphoneEnabled",true) else prefs.getBoolean("microphone",true)
    private fun requestStart() {
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.CAMERA)
        if (microphoneEnabled() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),1) else start()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        Log.i("LiveSpacePermissions","Camera=$cameraGranted microphone=$audioGranted")
        // Microphone denial does not block camera inference, and never automatically re-prompts.
        if (cameraGranted) {
            if (resumed) start() else pendingServiceStart = true
            if(!notificationGranted) Toast.makeText(this,"Sensor can run, but enable notifications to see its persistent status",Toast.LENGTH_LONG).show()
        } else findViewById<TextView>(R.id.status).text = "⚠ Camera permission denied. Allow Camera in Android Settings or retry Start."
    }
    private fun readConfig(): DetectionConfig {
        val area = prefs.getString("area", "0,0,1,1")!!.split(',').map { it.trim().toFloat() }
        require(area.size == 4)
        return DetectionConfig(prefs.getFloat("threshold",0.5f),prefs.getLong("inferenceInterval",3000),
            prefs.getInt("threads",2),Box(area[0],area[1],area[2],area[3]))
    }
    private fun start() {
        if(!resumed) { pendingServiceStart=true;return }
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return
        try { config=readConfig() } catch(e:Exception) { findViewById<TextView>(R.id.status).text="Invalid sensor settings";return }
        preview.alpha=if(prefs.getBoolean("preview",true))1f else 0f
        overlay.visibility=if(prefs.getBoolean("preview",true)&&prefs.getBoolean("boxes",true))View.VISIBLE else View.INVISIBLE
        if(!serviceBound || sensorService==null) { pendingServiceStart=true;return }
        sensorService?.startByUser()
        findViewById<TextView>(R.id.status).text="◌ Starting foreground sensor service…"
    }
    private fun stop() { pendingServiceStart=false;sensorService?.stopByUser();noiseHistory.clear();overlay.people=emptyList();overlay.invalidate();refresh() }
    private fun currentAngle():Int {
        @Suppress("DEPRECATION") val displayAngle=windowManager.defaultDisplay.rotation*90
        sensorService?.updateDisplayRotation(displayAngle)
        return (serviceState.sensorOrientation-displayAngle+360)%360
    }
    private fun fresh():Boolean=serviceState.running && serviceState.detection?.let {
        SystemClock.elapsedRealtime()-it.capturedElapsedMs<=maxOf(config.intervalMs*2,5000) && it.rotation==currentAngle()
    }==true
    private fun noiseSummary():String {
        val n=serviceState.noise?.takeIf { serviceState.running && SystemClock.elapsedRealtime()-it.elapsedMs in 0..3000 }
        val configText=if(prefs.getBoolean("calibrationEnabled",false)) "Manual SPL calibration enabled" else "Uncalibrated • dBFS only"
        if(n==null) return "Microphone: ${serviceState.microphoneStatus.message}\nNoise: Unavailable\nCalibration: $configText"
        return "Microphone: ${serviceState.microphoneStatus.message} • ${n.sampleRate} Hz mono PCM16\nRMS: ${"%.6f".format(n.rms)} • ${"%.1f".format(n.dbfs)} dBFS${if(n.floorLimited) " (digital floor)" else ""}\nAverage (${n.rollingWindows} s energy): ${"%.1f".format(n.averageDbfs)} dBFS\nPeak (${n.rollingWindows} s sample peak): ${"%.1f".format(n.peakDbfs)} dBFS\nCalibration: $configText\nEstimated SPL: ${n.estimatedDbSpl?.let { "%.1f dB SPL (estimate)".format(it) } ?: "Unavailable"}"
    }
    private fun refresh() {
        if (!::monitor.isInitialized) return
        val state=serviceState
        val r=state.detection
        val valid = fresh()
        state.noise?.takeIf { state.running && it.elapsedMs != displayedNoiseTimestamp && SystemClock.elapsedRealtime()-it.elapsedMs in 0..3000 }?.let {
            displayedNoiseTimestamp = it.elapsedMs; noiseHistory.add(it.dbfs)
        }
        val battery=state.battery ?: monitor.battery()
        findViewById<TextView>(R.id.identity).text = "${prefs.getString("device","LS-NOTE4-001")} • ${android.os.Build.MODEL} • Android ${android.os.Build.VERSION.RELEASE}"
        val uptime=state.uptimeSeconds
        val count = if (valid) "${r!!.observed} (raw ${r.people.size})" else "Unavailable / stopped or stale"
        val confidence = if (valid && r?.confidence != null) "${"%.0f".format(r.confidence*100)}% mean person score" else "Unavailable"
        val status=findViewById<TextView>(R.id.status)
        status.text=if(state.running) "● Foreground service running • ${state.cameraStatus}" else "○ Sensor stopped"
        val n=state.network
        findViewById<TextView>(R.id.metrics).text = "Observed people: $count\nConfidence: $confidence\nModel: ${state.modelStatus}\nInference: ${r?.let { "%.1f ms (last)".format(it.inferenceMs) } ?: "Unavailable"}\nInterval: ${config.intervalMs} ms • CPU ${config.threads} threads\nActual frequency: ${state.inferenceFrequency?.let { "%.2f Hz".format(it) } ?: "Unavailable"}\n\n${noiseSummary()}\n\n${monitor.readings(battery)}\n\nSensor uptime: $uptime s\nFrames acquired: ${state.cameraFrames} • Inferences: ${state.successfulInferences} • Failed: ${state.failedInferences}"
        fun time(ms: Long?)=ms?.let { SimpleDateFormat("HH:mm:ss 'UTC'",Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC") }.format(Date(it)) } ?: "Never"
        findViewById<TextView>(R.id.networkStatus).text="Server: ${n.state}\n${prefs.getString("endpoint","wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors")}\nConnected: ${time(n.lastConnected)}\nTransmitted: ${time(n.lastTransmitted)} • ACK: ${time(n.lastAcknowledged)}\nSent: ${n.sent} • Acknowledged: ${n.acknowledged} • Failed: ${n.failed}\nPending: ${n.pending} • oldest ${n.outbox.oldestAgeMs?.let { "${it/1000}s" } ?: "—"} • reconnect attempts: ${n.reconnectAttempts}\nDelivered: ${n.outbox.delivered} • retries: ${n.outbox.retries} • expired: ${n.outbox.expired} • dropped: ${n.outbox.dropped} • rejected: ${n.outbox.rejected}\n${n.error ?: "Waiting for telemetry"}"
        overlay.people = if (valid) r!!.people else emptyList(); overlay.area = config.area; overlay.invalidate()
    }
    private fun diagnostics() {
        val state=serviceState;val r=state.detection
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC") }
        val text = monitor.hardware()+"\n\n"+(sensorService?.cameraCapabilities()?:"Camera service unavailable")+
            "\n\nForeground service: ${state.running}\nCamera: ${state.cameraStatus} • ${state.cameraWidth}×${state.cameraHeight}\nFrames acquired: ${state.cameraFrames}\nRaw / smoothed: ${r?.people?.size ?: "Unavailable"} / ${r?.observed ?: "Unavailable"}\nLast successful inference UTC: ${r?.let { utc.format(Date(it.timestampUtcMs)) } ?: "Unavailable"}\nModel: ${state.modelStatus}\nInitialization: ${state.initMs ?: "Unavailable"} ms\nPreprocess / inference / postprocess / total (ms): ${r?.let { "%.1f / %.1f / %.1f / %.1f".format(it.preprocessMs,it.inferenceMs,it.postprocessMs,it.totalMs) } ?: "Unavailable"}\nSuccessful: ${state.successfulInferences} • Failed: ${state.failedInferences}\nConfidence threshold: ${config.threshold}\nInference interval: ${config.intervalMs} ms • CPU threads: ${config.threads}\nProcess PSS: ${Debug.getPss()} KiB\nMicrophone: ${state.microphoneStatus.message}\nAudio buffer bytes: ${state.microphoneStatus.bufferBytes ?: "Unavailable"}\nAudio source: ${state.microphoneStatus.source ?: "Unavailable"}\n${noiseSummary()}\nQueue: ${state.network.pending} pending • oldest ${state.network.outbox.oldestAgeMs?.let { "${it/1000}s" } ?: "—"}\nQueue totals: delivered ${state.network.outbox.delivered}, retried ${state.network.outbox.retries}, expired ${state.network.outbox.expired}, dropped ${state.network.outbox.dropped}, rejected ${state.network.outbox.rejected}\nSensor error: ${state.lastError ?: "None"}\nNetwork state: ${state.network.state} • ${state.network.error ?: "No current error"}"
        val content = TextView(this).apply { setPadding(24,16,24,16); this.text=text; setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("Diagnostics").setView(ScrollView(this).apply { addView(content) }).setPositiveButton("Close",null).show()
    }
    private fun settings() {
        stop()
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,8,24,8) }
        fun field(label: String,value: String): EditText {
            box.addView(TextView(this).apply { text=label })
            return EditText(this).apply { hint=label; setText(value); isSingleLine=true; box.addView(this) }
        }
        val device = field("Device ID",prefs.getString("device","LS-NOTE4-001")!!)
        val location = field("Location ID",prefs.getString("location","")!!)
        val interval = field("Inference interval (500–60000 ms)",prefs.getLong("inferenceInterval",3000).toString()).apply { inputType=2 }
        val threshold = field("Confidence threshold (0.05–0.99)",prefs.getFloat("threshold",0.5f).toString()).apply { inputType=8194 }
        val threads = field("CPU threads (1, 2 or 4)",prefs.getInt("threads",2).toString()).apply { inputType=2 }
        val area = field("Monitoring area (left,top,right,bottom; 0–1)",prefs.getString("area","0,0,1,1")!!)
        val visible = CheckBox(this).apply { text="Enable local camera preview"; isChecked=prefs.getBoolean("preview",true); box.addView(this) }
        val boxes = CheckBox(this).apply { text="Show bounding boxes"; isChecked=prefs.getBoolean("boxes",true); box.addView(this) }
        val microphone = CheckBox(this).apply { text="Enable microphone monitoring"; isChecked=prefs.getBoolean("microphone",true); box.addView(this) }
        val calibration = CheckBox(this).apply { text="Use manual SPL calibration from a reference meter"; isChecked=prefs.getBoolean("calibrationEnabled",false); box.addView(this) }
        val offset = field("Calibration offset dB = reference SPL − measured dBFS",prefs.getFloat("calibrationOffset",100f).toString()).apply { inputType=12290 }
        box.addView(TextView(this).apply { text="Uncalibrated readings are dBFS, not physical dB SPL. Calibration estimates require a reference meter and stable microphone gain/placement; no A-weighting. Counts refer only to visible people." })
        val dialog = AlertDialog.Builder(this).setTitle("Sensor settings").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val id=device.text.toString().trim(); val loc=location.text.toString().trim()
            if(!id.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { device.error="Use 1–64 letters, numbers, _, ., : or -"; return@setOnClickListener }
            if(!loc.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { location.error="Enter a valid location ID (1–64 letters, numbers, _, ., : or -)"; return@setOnClickListener }
            val ms=interval.text.toString().toLongOrNull(); val confidence=threshold.text.toString().toFloatOrNull(); val cpu=threads.text.toString().toIntOrNull()
            if(ms==null || ms !in 500..60000) { interval.error="Enter 500–60000"; return@setOnClickListener }
            if(confidence==null || !confidence.isFinite() || confidence !in 0.05f..0.99f) { threshold.error="Enter 0.05–0.99"; return@setOnClickListener }
            if(cpu !in listOf(1,2,4)) { threads.error="Use 1, 2 or 4"; return@setOnClickListener }
            try {
                val a=area.text.toString().split(',').map { it.trim().toFloat() }; require(a.size==4)
                config=DetectionConfig(confidence,ms,cpu!!,Box(a[0],a[1],a[2],a[3]))
            } catch(_: Exception) { area.error="Use left < right, top < bottom, all between 0 and 1"; return@setOnClickListener }
            val offsetDb = offset.text.toString().toFloatOrNull()
            if (calibration.isChecked && (offsetDb == null || !offsetDb.isFinite() || offsetDb !in -200f..200f)) { offset.error="Enter a finite offset from −200 to 200 dB"; return@setOnClickListener }
            prefs.edit().putBoolean("microphone",microphone.isChecked).putBoolean("calibrationEnabled",calibration.isChecked)
                .putFloat("calibrationOffset",offsetDb?.takeIf { it.isFinite() && it in -200f..200f } ?: 100f).apply()
            prefs.edit().putString("device",id).putString("location",loc).putLong("inferenceInterval",ms).putFloat("threshold",confidence).putInt("threads",cpu!!).putString("area",area.text.toString()).putBoolean("preview",visible.isChecked).putBoolean("boxes",boxes.isChecked).apply()
            benchmarkSamples=0; refresh(); dialog.dismiss()
        } }
        dialog.show()
    }
    private fun connectTransport() {
        if(!serviceBound || sensorService==null) { pendingServiceConnect=true;return }
        if(!serviceState.running) { Toast.makeText(this,"Start the sensor before connecting",Toast.LENGTH_LONG).show();return }
        sensorService?.connectByUser()
    }
    private fun networkSettings() {
        stop()
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8) }
        fun field(label: String,value: String)=EditText(this).apply {
            box.addView(TextView(this@MainActivity).apply { text=label });hint=label;isSingleLine=true;setText(value);box.addView(this)
        }
        val endpoint=field("WebSocket endpoint",prefs.getString("endpoint","wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors")!!)
        val device=field("Device ID",prefs.getString("device","LS-NOTE4-001")!!)
        val location=field("Location ID",prefs.getString("location","I")!!)
        val interval=field("Telemetry interval ms (demo 5000; continuous default 15000)",prefs.getLong("telemetryInterval",15000).toString()).apply { inputType=2 }
        val queueLimit=field("Maximum pending messages (1–10000)",prefs.getInt("queueLimit",10000).toString()).apply { inputType=2 }
        val retentionHours=field("Maximum pending age in hours (1–24)",prefs.getLong("queueMaxAgeHours",24).toString()).apply { inputType=2 }
        box.addView(TextView(this).apply { text=CredentialSafety.displayStored(tokenStore.present())+" • blank keeps stored token" })
        val token=field("New device token (never shown again)","").apply { inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;setSaveEnabled(false) }
        val remove=CheckBox(this).apply { text="Remove stored token";box.addView(this) }
        val lan=CheckBox(this).apply { text="Allow private-IP LAN ws development (unencrypted Bearer token)";isChecked=prefs.getBoolean("allowLan",false);box.addView(this) }
        val reconnect=CheckBox(this).apply { text="Automatic reconnect";isChecked=prefs.getBoolean("reconnect",true);box.addView(this) }
        box.addView(TextView(this).apply { text="Private LAN development may use ws:// only when explicitly enabled. Production requires wss:// with normal certificate validation. Device registration must match the server assignment; connection waits for sensor.ready before sending." })
        val dialog=AlertDialog.Builder(this).setTitle("Server and credentials").setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val c=try { ConnectionConfig(endpoint.text.toString().trim(),device.text.toString().trim(),location.text.toString().trim(),interval.text.toString().toLong(),reconnect.isChecked,lan.isChecked) }
                catch (_: Exception) { endpoint.error="Invalid endpoint/IDs/interval; ws needs private-IP LAN mode";return@setOnClickListener }
            val maxPending=queueLimit.text.toString().toIntOrNull();val maxAge=retentionHours.text.toString().toLongOrNull()
            if(maxPending==null || maxPending !in 1..10000) { queueLimit.error="Enter 1–10000";return@setOnClickListener }
            if(maxAge==null || maxAge !in 1..24) { retentionHours.error="Enter 1–24 hours";return@setOnClickListener }
            val secret=token.text.toString()
            if(secret.isNotEmpty() && !CredentialSafety.valid(secret)) { token.error="Invalid token";return@setOnClickListener }
            val removeToken=remove.isChecked
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            Thread {
                try {
                    if(removeToken) tokenStore.clear() else if(secret.isNotEmpty()) tokenStore.save(secret)
                    prefs.edit().putString("endpoint",c.endpoint).putString("device",c.deviceId).putString("location",c.locationId)
                        .putLong("telemetryInterval",c.intervalMs).putBoolean("allowLan",c.allowLanWs).putBoolean("reconnect",c.reconnect)
                        .putInt("queueLimit",maxPending).putLong("queueMaxAgeHours",maxAge).apply()
                    sensorService?.configureQueue(maxPending,maxAge)
                    runOnUiThread { token.text.clear();dialog.dismiss();refresh() }
                } catch (_: Exception) { runOnUiThread { token.error="Secure credential storage failed";dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true } }
            }.start()
        } }
        dialog.show()
    }
    override fun onDestroy() { super.onDestroy() }
    private fun transform() {
        if(preview.width==0 || preview.height==0 || serviceState.cameraWidth==0) return
        val angle=currentAngle()
        val w=preview.width.toFloat(); val h=preview.height.toFloat()
        val rect=FrameGeometry.fit(w,h,serviceState.cameraWidth,serviceState.cameraHeight,angle)
        val src=floatArrayOf(0f,0f,w,0f,w,h,0f,h)
        // TextureView's consumer has already rotated the raw stream by SENSOR_ORIENTATION.
        // Rotating by sensor-display here applied the sensor orientation twice (portrait tilt).
        @Suppress("DEPRECATION") val displayAngle=windowManager.defaultDisplay.rotation*90
        val upright=FrameGeometry.previewCorners(displayAngle)
        for(i in 0..3) { upright[i*2]=rect.left+upright[i*2]*(rect.right-rect.left); upright[i*2+1]=rect.top+upright[i*2+1]*(rect.bottom-rect.top) }
        preview.setTransform(Matrix().apply { setPolyToPoly(src,0,upright,0,4) })
        overlay.frame=rect; overlay.invalidate()
    }
    private fun attachCurrentPreview(surface:SurfaceTexture) {
        val enabled=prefs.getBoolean("preview",true)
        preview.alpha=if(enabled)1f else 0f
        overlay.visibility=if(enabled&&prefs.getBoolean("boxes",true))View.VISIBLE else View.INVISIBLE
        currentAngle();sensorService?.attachPreview(surface,enabled);transform()
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture,width: Int,height: Int) { transform();if(resumed)attachCurrentPreview(surface) }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture,width: Int,height: Int) { transform() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        sensorService?.attachPreview(null,false) { surface.release() } ?: surface.release()
        return false
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
