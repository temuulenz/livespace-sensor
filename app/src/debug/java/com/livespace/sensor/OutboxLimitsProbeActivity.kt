package com.livespace.sensor

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.livespace.sensor.storage.SqliteTelemetryOutbox
import java.util.UUID

/** Isolated debug-only exercise of SQLite queue expiry, overflow, persistence and ACK accounting. */
class OutboxLimitsProbeActivity : Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val database="outbox_limits_probe_${UUID.randomUUID().toString().replace("-","")}.db"
        var success=false
        try {
            val now=System.currentTimeMillis()
            val first=SqliteTelemetryOutbox(this,2,60_000,database)
            check(first.offer("expired","{}","probe-device","probe-location",now-60_001,0))
            check(first.metrics().expired==1L && first.metrics().pending==0)
            check(first.offer("pending-a","{\"n\":1}","probe-device","probe-location",now,0))
            check(first.offer("pending-b","{\"n\":2}","probe-device","probe-location",now+1,0))
            check(!first.offer("overflow","{\"n\":3}","probe-device","probe-location",now+2,0))
            check(first.metrics().pending==2 && first.metrics().dropped==1L)
            first.close()

            val reopened=SqliteTelemetryOutbox(this,2,60_000,database)
            check(reopened.metrics().pending==2)
            val replay=reopened.ready("probe-device","probe-location",2)
            check(replay.map { it.id }.toSet()==setOf("pending-a","pending-b"))
            check(replay.map { it.json }.toSet()==setOf("{\"n\":1}","{\"n\":2}"))
            check(reopened.acknowledge("pending-a","probe-device","probe-location"))
            check(!reopened.acknowledge("pending-a","probe-device","probe-location"))
            val metrics=reopened.metrics()
            check(metrics.pending==1 && metrics.delivered==1L && metrics.expired==1L && metrics.dropped==1L)
            reopened.close()
            success=true
        } catch(e:Exception) {
            Log.e(TAG,"SQLite limits probe failed (${e.javaClass.simpleName})")
        } finally {
            deleteDatabase(database)
            Log.i(TAG,if(success) "PASS limit=2 retentionMs=60000 expiry=1 dropped=1 restart=preserved ack=1 isolatedDbDeleted=true" else "FAIL isolatedDbDeleted=true")
            finish()
        }
    }
    companion object { private const val TAG="LiveSpaceOutboxLimitsProbe" }
}
