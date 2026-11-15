package com.livespace.sensor

import android.app.Activity
import android.content.*
import android.os.*
import android.util.Log
import com.livespace.sensor.network.DeviceTokenStore
import com.livespace.sensor.network.ProductionTls
import com.livespace.sensor.service.SensorService

/** Explicitly invoked Phase 7 observation of the real service. Logs structured metrics only. */
class ProductionIntegrationProbeActivity: Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private var service:SensorService?=null
    private var bound=false
    private var connected=false
    private var started=SystemClock.elapsedRealtime()
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName?,binder:IBinder?) {
            service=(binder as SensorService.LocalBinder).service
            if(intent.getBooleanExtra("stop",false)) { service?.stopByUser();handler.postDelayed({ finish() },2000);return }
            if(intent.getBooleanExtra("connect",false)) service?.startByUser()
            handler.post(tick)
        }
        override fun onServiceDisconnected(name:ComponentName?) { service=null }
    }
    private val tick=object:Runnable { override fun run() {
        val owner=service ?: return
        val s=owner.state();val n=s.network;val snapshot=owner.telemetrySnapshot()
        val power=getSystemService(POWER_SERVICE) as PowerManager
        val memory=android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        Log.i(TAG,"SAMPLE utc=${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtime()} interactive=${power.isInteractive} pssKiB=${memory.totalPss} running=${s.running} camera=${s.cameraFrames} inference=${s.successfulInferences} failedInference=${s.failedInferences} people=${snapshot.observedPeople} inferenceMs=${snapshot.inferenceMs} rms=${snapshot.noiseRms} dbfs=${snapshot.noiseDbfs} mic=${s.microphoneStatus.message} temperature=${s.battery?.temperatureC} charging=${s.battery?.charging} network=${n.state} sent=${n.sent} ack=${n.acknowledged} pending=${n.pending} delivered=${n.outbox.delivered} retry=${n.outbox.retries} expired=${n.outbox.expired} dropped=${n.outbox.dropped} rejected=${n.outbox.rejected} lastAck=${n.lastAcknowledged} error=${n.error} sensorError=${s.lastError}")
        if((s.battery?.temperatureC ?: 0.0)>=42.0) { Log.e(TAG,"THERMAL_STOP");owner.stopByUser();finish();return }
        if(intent.getBooleanExtra("connect",false) && s.running && !connected) { connected=true;owner.connectByUser() }
        if(SystemClock.elapsedRealtime()-started>=600000) { Log.i(TAG,"OBSERVATION_ENDED");finish();return }
        handler.postDelayed(this,5000)
    } }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val prefs=getSharedPreferences("sensor",MODE_PRIVATE)
        val valid=prefs.getString("endpoint",ProductionTls.ENDPOINT)==ProductionTls.ENDPOINT &&
            prefs.getString("device","LS-NOTE4-001")=="LS-NOTE4-001" && prefs.getString("location","I")=="I" &&
            prefs.getLong("telemetryInterval",15000)==15000L && DeviceTokenStore(this).present()
        Log.i(TAG,"SAVED_PRODUCTION_CONFIG_OK=$valid tokenPresent=${DeviceTokenStore(this).present()} interval=${prefs.getLong("telemetryInterval",15000)}")
        if(!valid) { finish();return }
        if(intent.getBooleanExtra("audit",false)) {
            Thread {
                try {
                    android.database.sqlite.SQLiteDatabase.openDatabase(getDatabasePath("sensor_outbox.db").path,null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT message_id,payload,device_id,location_id,created_at,attempts FROM telemetry_outbox ORDER BY created_at LIMIT 100",null).use { rows ->
                            Log.i(TAG,"QUEUE_AUDIT rows=${rows.count} limit=100")
                            while(rows.moveToNext()) {
                                val sha=java.security.MessageDigest.getInstance("SHA-256").digest(rows.getString(1).toByteArray(Charsets.UTF_8))
                                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                                Log.i(TAG,"QUEUE_ROW id=${rows.getString(0)} sha256=$sha device=${rows.getString(2)} location=${rows.getString(3)} created=${rows.getLong(4)} attempts=${rows.getInt(5)}")
                            }
                        }
                    }
                } catch(t:Exception) { Log.e(TAG,"QUEUE_AUDIT_FAILED ${t.javaClass.simpleName}") }
                finally { runOnUiThread { finish() } }
            }.start()
            return
        }
        bound=bindService(Intent(this,SensorService::class.java),connection,Context.BIND_AUTO_CREATE)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null);if(bound) unbindService(connection);super.onDestroy() }
    companion object { const val TAG="LiveSpaceIntegration" }
}
