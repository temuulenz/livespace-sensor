package com.livespace.sensor.network

import com.livespace.sensor.model.SensorSnapshot
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SensorProtocolV1Test {
    private fun keys(json: JSONObject)=json.keys().asSequence().toSet()
    private val config=ConnectionConfig("wss://example.workers.dev/ws/sensors","LS-NOTE4-001","I")
    private val populated=SensorSnapshot(1794787200123L,8,0.87f,1200.0,0.014,-37.1,null,false,85,36.5,1794787200000L,1794787200000L,1794787200000L,true,3600)
    private val protocol=SensorProtocolV1()

    @Test fun telemetryHasExactV1EnvelopeNumericVersionUtcAndNullableSpl() {
        val json=JSONObject(protocol.telemetry(config,populated,"92e00580-79d6-4208-899f-cba34284ecdf",3600))
        assertEquals(setOf("schemaVersion","type","messageId","deviceId","locationId","timestamp","occupancy","noise","device"),keys(json))
        assertEquals(1,json.getInt("schemaVersion"));assertEquals("sensor.telemetry",json.getString("type"))
        assertEquals("LS-NOTE4-001",json.getString("deviceId"));assertEquals("I",json.getString("locationId"))
        assertTrue(json.getString("timestamp").endsWith("Z"));assertEquals("2026-11-16T00:00:00.123Z",json.getString("timestamp"))
        val occ=json.getJSONObject("occupancy");assertEquals(setOf("observedPeople","confidence"),keys(occ));assertEquals(8,occ.getInt("observedPeople"));assertEquals(0.87,occ.getDouble("confidence"),0.0001)
        val noise=json.getJSONObject("noise");assertEquals(setOf("rms","dbfs","estimatedDbSpl","calibrated"),keys(noise))
        assertTrue(noise.isNull("estimatedDbSpl"));assertFalse(noise.getBoolean("calibrated"))
        val device=json.getJSONObject("device");assertEquals(setOf("batteryPercent","batteryTemperatureC","charging","inferenceMs","uptimeSeconds"),keys(device))
        assertEquals(85,device.getInt("batteryPercent"));assertTrue(device.getBoolean("charging"));assertEquals(3600,device.getLong("uptimeSeconds"))
    }

    @Test fun missingAndInvalidMeasurementsAreNullNeverInvented() {
        val unavailable=populated.copy(observedPeople=null,detectionConfidence=null,noiseRms=null,noiseDbfs=-120.0,estimatedDbSpl=null,
            batteryPercent=null,batteryTemperatureC=null,charging=null,inferenceMs=null,deviceUptimeSeconds=null,calibrated=true)
        val root=JSONObject(protocol.telemetry(config,unavailable,"id",-1))
        val occupancy=root.getJSONObject("occupancy");assertTrue(occupancy.isNull("observedPeople"));assertTrue(occupancy.isNull("confidence"))
        val noise=root.getJSONObject("noise");assertTrue(noise.isNull("rms"));assertTrue(noise.isNull("estimatedDbSpl"));assertTrue(noise.getBoolean("calibrated"))
        val device=root.getJSONObject("device");assertTrue(device.isNull("charging"));assertTrue(device.isNull("batteryPercent"));assertTrue(device.isNull("uptimeSeconds"))
    }

    @Test fun identifiersAndMessageIdsAreUniqueAndMatchContract() {
        val first=MessageIds.next();val second=MessageIds.next()
        assertNotEquals(first,second);assertTrue(first.matches(Regex("[A-Za-z0-9_.:-]{1,64}")))
        assertTrue(JSONObject(protocol.telemetry(config,populated,first,3600)).getString("messageId")==first)
        assertThrows(IllegalArgumentException::class.java) { ConnectionConfig("wss://example.workers.dev/ws","bad id","I") }
        ConnectionConfig("wss://example.workers.dev/ws","LS.NOTE4:001","I")
    }

    @Test fun readyAckDuplicateAndProtocolErrorFramesAreParsed() {
        val ready=protocol.incoming("""{"type":"sensor.ready","schemaVersion":1,"deviceId":"LS-NOTE4-001","locationId":"I","dataSource":"real","maxPayloadBytes":16384}""") as ServerEvent.Ready
        assertEquals(config.deviceId,ready.deviceId);assertEquals(16384,ready.maxPayloadBytes)
        for(status in listOf("accepted","duplicate")) assertEquals(ServerEvent.Acknowledged("m1"),protocol.incoming("""{"type":"sensor.ack","messageId":"m1","status":"$status","serverTimestamp":"2026-11-16T00:00:01.000Z"}"""))
        assertEquals(ServerEvent.Ignore,protocol.incoming("""{"type":"sensor.ack","messageId":"m1","status":"rejected"}"""))
        assertEquals(ServerEvent.ProtocolError("m2","storage_failed"),protocol.incoming("""{"type":"sensor.error","messageId":"m2","error":{"code":"storage_failed","message":"Unavailable","details":[]}}"""))
        assertEquals(ServerEvent.ProtocolError(null,"invalid_device_token"),protocol.incoming("""{"type":"sensor.error","error":{"code":"invalid_device_token","message":"Denied","details":[]}}"""))
        assertThrows(Exception::class.java) { protocol.incoming("not json") }
    }

    @Test fun serverReadyGatesTelemetryAndMatchingAckCountsOnce() {
        val server=MockWebServer();val got=CountDownLatch(1);val envelope=AtomicReference<JSONObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket,response: okhttp3.Response) {
                webSocket.send("""{"type":"sensor.ready","schemaVersion":1,"deviceId":"LS-NOTE4-001","locationId":"I","dataSource":"real","maxPayloadBytes":16384}""")
            }
            override fun onMessage(webSocket: WebSocket,text: String) {
                val message=JSONObject(text);envelope.set(message);got.countDown()
                val ack="""{"type":"sensor.ack","messageId":"${message.getString("messageId")}","status":"accepted","serverTimestamp":"2026-11-16T00:00:01.000Z"}"""
                webSocket.send(ack);webSocket.send(ack)
            }
        }));server.start()
        val acknowledged=CountDownLatch(1)
        val transport=SensorWebSocket({ populated },{3600},protocol,changed={ if(it.acknowledged==1L) acknowledged.countDown() },queue=PendingMessages(capacity=1,maxInFlight=1))
        try {
            val endpoint=server.url("/ws/sensors").toString().replace("http:","ws:")
            transport.connect(ConnectionConfig(endpoint,config.deviceId,config.locationId,60000,allowLanWs=true),"test-only-token")
            assertTrue(got.await(5,TimeUnit.SECONDS));assertTrue(acknowledged.await(5,TimeUnit.SECONDS))
            assertEquals(1,transport.status.sent);assertEquals(1,transport.status.acknowledged);assertEquals(0,transport.status.pending)
            assertEquals("sensor.telemetry",envelope.get().getString("type"))
            assertEquals("Bearer test-only-token",server.takeRequest(5,TimeUnit.SECONDS)!!.getHeader("Authorization"))
        } finally { transport.close();server.shutdown() }
    }

    @Test fun transientServerErrorRetriesUnchangedMessageIdAndBody() {
        val server=MockWebServer();val attempts=CountDownLatch(2);val first=AtomicReference<String>();val second=AtomicReference<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {
            var received=0
            override fun onOpen(webSocket: WebSocket,response: okhttp3.Response) { webSocket.send("""{"type":"sensor.ready","schemaVersion":1,"deviceId":"LS-NOTE4-001","locationId":"I","dataSource":"real","maxPayloadBytes":16384}""") }
            override fun onMessage(webSocket: WebSocket,text: String) {
                received++;val obj=JSONObject(text)
                if(received==1) {
                    first.set(text);webSocket.send("""{"type":"sensor.error","messageId":"${obj.getString("messageId")}","error":{"code":"storage_failed","message":"Unavailable","details":[]}}""")
                } else { second.set(text);attempts.countDown();webSocket.send("""{"type":"sensor.ack","messageId":"${obj.getString("messageId")}","status":"duplicate","serverTimestamp":"2026-11-16T00:00:01.000Z"}""") }
                if(received==1) attempts.countDown()
            }
        }));server.start()
        val transport=SensorWebSocket({ populated },{3600},protocol,queue=PendingMessages(capacity=1,maxInFlight=1))
        try {
            val endpoint=server.url("/ws/sensors").toString().replace("http:","ws:")
            transport.connect(ConnectionConfig(endpoint,config.deviceId,config.locationId,60000,allowLanWs=true),"test-only-token")
            assertTrue(attempts.await(7,TimeUnit.SECONDS));assertEquals(first.get(),second.get())
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(transport.status.acknowledged==0L && System.nanoTime()<deadline) Thread.sleep(10)
            assertEquals(1L,transport.status.acknowledged);assertEquals(0,transport.status.pending)
        } finally { transport.close();server.shutdown() }
    }
}
