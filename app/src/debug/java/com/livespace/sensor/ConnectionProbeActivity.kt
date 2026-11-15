package com.livespace.sensor

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.livespace.sensor.model.TelemetryStore
import com.livespace.sensor.device.DeviceMonitor
import com.livespace.sensor.network.*
import java.security.KeyStore

/** Debug-only, accepts a disposable test credential from ADB; never reads or overwrites the real token. */
class ConnectionProbeActivity: Activity() {
    private var connection: SensorWebSocket?=null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val endpoint=intent.getStringExtra("endpoint") ?: return finish()
        val token=intent.getStringExtra("testToken") ?: return finish()
        Thread {
            try {
                val store=DeviceTokenStore(this,"credential_probe","livespace.credential.probe")
                try { store.save(token);check(store.load()==token);Log.i("LiveSpaceNetworkProbe","KEYSTORE_ROUNDTRIP_OK") }
                finally {
                    store.clear()
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("livespace.credential.probe") }
                }
                val telemetry=TelemetryStore()
                val now=System.currentTimeMillis();val elapsed=android.os.SystemClock.elapsedRealtime()
                val battery=DeviceMonitor(this).battery()
                telemetry.updateBattery(battery.percent,battery.temperatureC,now,elapsed,battery.charging)
                connection=SensorWebSocket({ telemetry.snapshot(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime()) },{elapsed/1000},changed={
                    Log.i("LiveSpaceNetworkProbe","STATE ${it.state}")
                    if(it.acknowledged>0) { Log.i("LiveSpaceNetworkProbe","TELEMETRY_ACK_OK") ;runOnUiThread { finish() } }
                    if(it.state==ConnectionState.AUTH_FAILED) runOnUiThread { finish() }
                })
                connection!!.connect(ConnectionConfig(endpoint,"LS-NOTE4-001","I",reconnect=false,allowLanWs=true),token)
            } catch (_: Exception) { Log.e("LiveSpaceNetworkProbe","PROBE_FAILED (details withheld)");runOnUiThread { finish() } }
        }.start()
    }
    override fun onDestroy() { connection?.close();super.onDestroy() }
}
