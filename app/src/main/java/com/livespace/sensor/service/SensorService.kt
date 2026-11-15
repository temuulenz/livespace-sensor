package com.livespace.sensor.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.os.*
import android.util.Log
import com.livespace.sensor.MainActivity
import com.livespace.sensor.R
import com.livespace.sensor.audio.*
import com.livespace.sensor.camera.CameraController
import com.livespace.sensor.detection.*
import com.livespace.sensor.device.BatteryReading
import com.livespace.sensor.device.DeviceMonitor
import com.livespace.sensor.model.SensorSnapshot
import com.livespace.sensor.model.TelemetryRepository
import com.livespace.sensor.network.*
import com.livespace.sensor.storage.SqliteTelemetryOutbox
import java.util.concurrent.atomic.AtomicBoolean

data class SensorServiceState(
    val running:Boolean=false,val uptimeSeconds:Long=0,val cameraStatus:String="Stopped",val modelStatus:String="Not loaded",
    val microphoneStatus:MicrophoneStatus=MicrophoneStatus("Not started"),val detection:DetectionResult?=null,
    val noise:NoiseReading?=null,val battery:BatteryReading?=null,val cameraFrames:Long=0,val successfulInferences:Long=0,
    val failedInferences:Long=0,val initMs:Double?=null,val inferenceFrequency:Double?=null,val lastError:String?=null,
    val network:ConnectionStatus=ConnectionStatus(),val cameraWidth:Int=0,val cameraHeight:Int=0,val sensorOrientation:Int=90
)

/** Explicitly user-started foreground owner for all continuous sensor and socket resources. */
class SensorService: Service() {
    interface Observer { fun onSensorServiceChanged(state:SensorServiceState) }
    inner class LocalBinder:Binder() { val service get()=this@SensorService }
    private val binder=LocalBinder()
    private val main=Handler(Looper.getMainLooper())
    private val telemetry=TelemetryRepository.store
    private val performance=PerformanceStats()
    private lateinit var monitor:DeviceMonitor
    private lateinit var outbox:SqliteTelemetryOutbox
    private val prefs by lazy { getSharedPreferences("sensor",Context.MODE_PRIVATE) }
    private val tokenStore by lazy { DeviceTokenStore(applicationContext) }
    private var camera:CameraController?=null
    private var scheduler:InferenceScheduler?=null
    private var audio:AudioMonitor?=null
    private var transport:SensorWebSocket?=null
    private var observer:Observer?=null
    private var startElapsed=0L
    @Volatile private var cameraEpoch=0
    @Volatile private var audioEpoch=0
    @Volatile private var cameraRunning=false
    @Volatile private var running=false
    private var notificationActive=false
    @Volatile private var transportClosePending=false
    @Volatile private var outboxClosed=false
    private var foregroundMic=false
    private var micStatus=MicrophoneStatus("Not started")
    @Volatile private var latestDetection:DetectionResult?=null
    @Volatile private var latestNoise:NoiseReading?=null
    private var lastError:String?=null
    private var modelStatus="Not loaded"
    private var detectionConfig=DetectionConfig()
    private var previewTexture:SurfaceTexture?=null
    private var previewRequested=false
    @Volatile private var displayRotation=0
    private var wakeLock:PowerManager.WakeLock?=null
    @Volatile private var currentState=SensorServiceState()
    private val batteryTick=object:Runnable { override fun run() { if(!destroyed) { refreshBattery();publish();main.postDelayed(this,5000) } } }
    @Volatile private var destroyed=false

    override fun onCreate() {
        super.onCreate();monitor=DeviceMonitor(this);outbox=SqliteTelemetryOutbox(this,prefs.getInt("queueLimit",10000),prefs.getLong("queueMaxAgeHours",24)*60*60*1000);createNotificationChannel();refreshBattery();publish();Thread({try{outbox.metrics()}catch(e:Exception){Log.e(TAG,"Outbox diagnostics unavailable (${e.javaClass.simpleName})")};main.post{if(!destroyed)publish()}},"LiveSpaceOutboxWarmup").start();main.postDelayed(batteryTick,5000)
    }
    override fun onBind(intent:Intent?)=binder
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action) {
            ACTION_START -> { enterForeground();startRuntime() }
            ACTION_STOP -> { stopRuntime("Stopped by user");leaveForeground();stopSelfResult(startId) }
            else -> { stopSelfResult(startId);return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }
    fun state()=currentState
    fun configureQueue(limit:Int,maxAgeHours:Long) { outbox.configure(limit,maxAgeHours*60*60*1000);publish() }
    fun setObserver(value:Observer?) { observer=value;publish() }
    fun attachPreview(texture:SurfaceTexture?,enabled:Boolean,after:()->Unit={}) {
        previewTexture=texture;previewRequested=texture!=null && enabled
        val controller=camera
        if(running && controller!=null) controller.attachPreview(texture,enabled,after) else after()
    }
    fun updateDisplayRotation(degrees:Int) { displayRotation=degrees;camera?.displayRotation=degrees }
    fun startByUser() { startAsForeground(ACTION_START) }
    fun stopByUser() { sendAction(ACTION_STOP) }
    private fun startAsForeground(action:String) {
        val i=Intent(this,SensorService::class.java).setAction(action)
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i)
    }
    private fun sendAction(action:String) { startService(Intent(this,SensorService::class.java).setAction(action)) }
    fun connectByUser() {
        if(!running) { lastError="Start sensors before connecting";publish();return }
        val config=try { ConnectionConfig(prefs.getString("endpoint","wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors")!!,
            prefs.getString("device","LS-NOTE4-001")!!.ifBlank { "LS-NOTE4-001" },prefs.getString("location","I")!!.ifBlank { "I" },
            prefs.getLong("telemetryInterval",15000),prefs.getBoolean("reconnect",true),prefs.getBoolean("allowLan",false)) }
            catch(_:Exception) { lastError="Invalid server configuration";publish();return }
        Thread({ try {
            val token=tokenStore.load() ?: error("Missing token")
            main.post {
                if(!running) return@post
                transport?.disconnect();transport?.close()
                transport=SensorWebSocket({ telemetrySnapshot() },{ if(running) (SystemClock.elapsedRealtime()-startElapsed)/1000 else 0 },changed={ status ->
                    main.post { if(!destroyed) { publish(network=status);updateNotification() } }
                },queue=outbox)
                transport?.connect(config,token);lastError=null;publish()
            }
        } catch(_:Exception) { main.post { lastError="Token missing/unavailable; configure credentials";publish() } } },"LiveSpaceCredentials").start()
    }
    fun disconnectByUser() { transport?.disconnect() }
    fun telemetrySnapshot():SensorSnapshot=telemetry.snapshot(System.currentTimeMillis(),SystemClock.elapsedRealtime())
    fun cameraCapabilities()=camera?.capabilities() ?: monitor.hardware()+"\nCamera unavailable while stopped"

    private fun startRuntime() {
        if(running) { publish();return }
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            lastError="Camera permission missing; open the app and grant permission";leaveForeground();publish();stopSelf();return
        }
        running=true;startElapsed=SystemClock.elapsedRealtime();lastError=null;modelStatus="Loading SSD MobileNet V1…"
        synchronized(performance) { performance.clear() }
        latestDetection=null;latestNoise=null;micStatus=MicrophoneStatus("Initializing microphone")
        telemetry.clearSensors()
        val config=try { readDetectionConfig() } catch(e:Exception) { running=false;lastError="Invalid detector settings";leaveForeground();publish();stopSelf();return }
        detectionConfig=config
        acquireWakeLock()
        val epoch=++cameraEpoch
        camera=CameraController(applicationContext,{ msg -> main.post {
            if(destroyed || !running) return@post
            if(msg.startsWith("●")) { cameraRunning=true;publish(cameraStatus=msg) }
            else { cameraRunning=false;cameraEpoch++;latestDetection=null;telemetry.clearDetection();scheduler?.close();scheduler=null
                lastError=msg;publish(cameraStatus="Camera error",modelStatus="Closed after camera error",lastError=msg) }
        } },{image,rotation -> if(epoch==cameraEpoch) scheduler?.offer(image,rotation) }).also { it.intervalMs=config.intervalMs }
        scheduler=InferenceScheduler(applicationContext,config,{ msg -> main.post {
            if(!running || destroyed || epoch!=cameraEpoch) return@post
            modelStatus=msg
            if(msg.startsWith("Model error")) { lastError=msg;scheduler?.close();scheduler=null }
            publish()
        } },{result ->
            if(!running || epoch!=cameraEpoch) return@InferenceScheduler
            telemetry.updateDetection(result.observed,result.confidence,result.inferenceMs,result.timestampUtcMs,result.capturedElapsedMs,maxOf(config.intervalMs*2,5000))
            latestDetection=result;synchronized(performance) { performance.add(result) }
            if(!destroyed) main.post { publish() }
        })
        val allowedMic=prefs.getBoolean("microphone",true) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
        if(allowedMic) startAudio() else micStatus=MicrophoneStatus(if(prefs.getBoolean("microphone",true)) "Microphone permission denied; camera continues" else "Disabled in settings")
        camera?.displayRotation=displayRotation
        camera?.start(previewTexture.takeIf { previewRequested },previewRequested)
        publish(cameraStatus="Opening rear camera…",modelStatus=modelStatus)
    }
    private fun readDetectionConfig():DetectionConfig {
        val area=prefs.getString("area","0,0,1,1")!!.split(',').map { it.trim().toFloat() };require(area.size==4)
        return DetectionConfig(prefs.getFloat("threshold",0.5f),prefs.getLong("inferenceInterval",3000),prefs.getInt("threads",2),Box(area[0],area[1],area[2],area[3]))
    }
    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val epoch=++audioEpoch
        val noiseConfig=NoiseConfig(if(prefs.getBoolean("calibrationEnabled",false)) prefs.getFloat("calibrationOffset",100f).toDouble() else null)
        audio=AudioMonitor(noiseConfig,{ status -> if(epoch!=audioEpoch || !running)return@AudioMonitor;micStatus=status
            if(status.message.startsWith("⚠")) { latestNoise=null;telemetry.clearNoise() }
            if(!destroyed) main.post { publish() }
        },{reading -> if(epoch==audioEpoch && running) { latestNoise=reading;telemetry.updateNoise(reading);if(!destroyed)main.post{publish()} } }).also { it.start() }
    }
    private fun stopRuntime(reason:String) {
        if(!running) {
            val closing=transport;transport=null
            if(closing!=null) { transportClosePending=true;closing.close { transportClosePending=false;if(destroyed) closeOutbox() } }
            return
        }
        running=false;cameraRunning=false;latestDetection=null;latestNoise=null;telemetry.clearSensors()
        micStatus=MicrophoneStatus("Stopped");modelStatus="Closed • press Start to load"
        val shuttingDown=mutableListOf<((()->Unit)->Unit)>()
        audioEpoch++
        audio?.let { owner -> shuttingDown.add { done -> owner.close(done) } };audio=null
        camera?.let { owner -> shuttingDown.add { done -> owner.stop(done) } };camera=null
        scheduler?.let { owner -> shuttingDown.add { done -> owner.close(done) } };scheduler=null
        val closing=transport;transport=null
        if(closing!=null) { transportClosePending=true;closing.close { transportClosePending=false;if(destroyed) closeOutbox() } }
        lastError=if(reason=="Stopped by user") null else reason
        publish(cameraStatus="Stopped",modelStatus=modelStatus,lastError=lastError)
        if(shuttingDown.isEmpty()) releaseWakeLock() else {
            val remaining=java.util.concurrent.atomic.AtomicInteger(shuttingDown.size)
            shuttingDown.forEach { close -> close { if(remaining.decrementAndGet()==0) releaseWakeLock() } }
        }
    }
    private fun refreshBattery() {
        val battery=monitor.battery();telemetry.updateBattery(battery.percent,battery.temperatureC,System.currentTimeMillis(),SystemClock.elapsedRealtime(),battery.charging)
        val s=currentState;currentState=s.copy(battery=battery)
    }
    private fun publish(cameraStatus:String?=null,modelStatus:String?=null,lastError:String?=this.lastError,network:ConnectionStatus?=transport?.status) {
        val elapsed=if(running) ((SystemClock.elapsedRealtime()-startElapsed)/1000) else 0
        val c=camera
        val sc=scheduler
        currentState=SensorServiceState(running,elapsed,cameraStatus?:if(cameraRunning) "Camera running" else "Stopped",
            modelStatus?:this.modelStatus,micStatus,latestDetection,latestNoise,currentState.battery,c?.frames?:0L,(sc?.successful?:0).toLong(),(sc?.failed?:0).toLong(),
            sc?.initMs,synchronized(performance) { performance.frequency() },lastError,network?:transport?.status?:ConnectionStatus(outbox=outbox.cachedMetrics()),c?.width?:0,c?.height?:0,c?.sensorOrientation?:90)
        observer?.onSensorServiceChanged(currentState)
        if(notificationActive) updateNotification()
    }
    private fun enterForeground() {
        if(notificationActive) return
        val notification=buildNotification()
        if(Build.VERSION.SDK_INT>=29) {
            var types=ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if(Build.VERSION.SDK_INT>=30 && prefs.getBoolean("microphone",true) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
                types=types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;foregroundMic=true
            }
            startForeground(NOTIFICATION_ID,notification,types)
        } else startForeground(NOTIFICATION_ID,notification)
        notificationActive=true
    }
    private fun leaveForeground() {
        if(!notificationActive) return
        if(Build.VERSION.SDK_INT>=24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        notificationActive=false;foregroundMic=false
    }
    private fun createNotificationChannel() {
        if(Build.VERSION.SDK_INT>=26) {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,"LiveSpace Sensor",NotificationManager.IMPORTANCE_LOW).apply {
                description="Visible status for the user-started camera and microphone sensor"
                setShowBadge(false)
            })
        }
    }
    private fun buildNotification():Notification {
        val stop=PendingIntent.getService(this,2,Intent(this,SensorService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=23) PendingIntent.FLAG_IMMUTABLE else 0))
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=23) PendingIntent.FLAG_IMMUTABLE else 0))
        val status=if(running) "Sensor running" else "Sensor service active"
        val people=latestDetection?.observed?.let { " • $it observed" }.orEmpty()
        val connection=transport?.status?.state?.name ?: "Disconnected"
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_sensor).setContentTitle("LiveSpace Sensor")
            .setContentText("$status$people • Server $connection").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0,"Stop sensor",stop).build()
    }
    private fun updateNotification() { if(notificationActive) try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID,buildNotification()) } catch(e:Exception) { Log.w(TAG,"Notification update failed (${e.javaClass.simpleName})") } }
    private fun acquireWakeLock() {
        try {
            val pm=getSystemService(POWER_SERVICE) as PowerManager
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LiveSpaceSensor:Capture").apply { setReferenceCounted(false);acquire() }
        } catch(e:Exception) { Log.e(TAG,"Wake lock unavailable (${e.javaClass.simpleName})") }
    }
    private fun releaseWakeLock() { try { wakeLock?.takeIf { it.isHeld }?.release() } catch(_:Exception) { };wakeLock=null }
    @Synchronized private fun closeOutbox() { if(!outboxClosed) { outboxClosed=true;outbox.close() } }
    override fun onDestroy() {
        destroyed=true;main.removeCallbacks(batteryTick);stopRuntime("Service destroyed");leaveForeground();observer=null
        if(!transportClosePending) closeOutbox()
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent:Intent?) { // The service is user-started and remains foreground when its task is dismissed.
        super.onTaskRemoved(rootIntent)
    }
    companion object {
        const val ACTION_START="com.livespace.sensor.action.START"
        const val ACTION_STOP="com.livespace.sensor.action.STOP"
        private const val CHANNEL_ID="livespace_sensor"
        private const val NOTIFICATION_ID=5101
        private const val TAG="LiveSpaceService"
    }
}
