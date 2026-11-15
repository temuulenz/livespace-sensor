package com.livespace.sensor

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.livespace.sensor.device.DeviceMonitor
import com.livespace.sensor.model.TelemetryStore
import com.livespace.sensor.network.*
import com.livespace.sensor.storage.SqliteTelemetryOutbox
import java.util.UUID
import java.security.MessageDigest

/** Debug-only physical probe. Seeded telemetry contains no camera/audio data and is sent only to a local mock. */
class QueueProbeActivity:Activity() {
    private var transport:SensorWebSocket?=null
    private var queue:SqliteTelemetryOutbox?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        Thread {
            try {
                queue=SqliteTelemetryOutbox(this)
                val mode=intent.getStringExtra("mode") ?: ""
                if(mode=="cleanup") {
                    val removed=queue!!.discardScope("PHASE6-PROBE","PHASE6-PROBE")
                    Log.i("LiveSpaceQueueProbe","TEST_ROWS_CLEANED removed=$removed dropped=${queue!!.metrics().dropped}")
                    runOnUiThread { finish() };return@Thread
                }
                if(mode=="seed") {
                    val now=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime()
                    val b=DeviceMonitor(this).battery();val s=TelemetryStore();s.updateBattery(b.percent,b.temperatureC,now,elapsed,b.charging)
                    val config=ConnectionConfig("wss://probe.invalid/ws","PHASE6-PROBE","PHASE6-PROBE")
                    val id=UUID.randomUUID().toString();val json=SensorProtocolV1().telemetry(config,s.snapshot(now,elapsed),id,0)
                    check(queue!!.offer(id,json,config.deviceId,config.locationId,now,0))
                    val hash=MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
                    Log.i("LiveSpaceQueueProbe","SEEDED $id payloadSha256=$hash pending=${queue!!.metrics().pending}")
                    runOnUiThread { finish() };return@Thread
                }
                val endpoint=intent.getStringExtra("endpoint") ?: error("Missing endpoint")
                val token=intent.getStringExtra("testToken") ?: error("Missing token")
                val id=intent.getStringExtra("targetId") ?: error("Missing target ID")
                val config=ConnectionConfig(endpoint,"PHASE6-PROBE","PHASE6-PROBE",60000,reconnect=false,allowLanWs=true)
                val before=queue!!.metrics().pending
                Log.i("LiveSpaceQueueProbe","REPLAY_START pending=$before target=$id")
                transport=SensorWebSocket({ TelemetryStore().snapshot(System.currentTimeMillis(),SystemClock.elapsedRealtime()) },{0},changed={ status ->
                    val metrics=queue?.metrics() ?: return@SensorWebSocket
                    if(metrics.pending<before || metrics.delivered>0) Log.i("LiveSpaceQueueProbe","QUEUE_STATE delivered=${metrics.delivered} pending=${metrics.pending}")
                    if(status.acknowledged>0 && metrics.delivered>0 && !queue!!.contains(id)) runOnUiThread { finish() }
                },queue=queue!!)
                transport!!.connect(config,token)
                Thread.sleep(25_000)
                Log.i("LiveSpaceQueueProbe","REPLAY_END delivered=${queue!!.metrics().delivered} pending=${queue!!.metrics().pending}")
                runOnUiThread { finish() }
            } catch(e:Exception) { Log.e("LiveSpaceQueueProbe","FAILED ${e.javaClass.simpleName}") }
        }.start()
    }
    override fun onDestroy() { transport?.close();super.onDestroy() }
}
