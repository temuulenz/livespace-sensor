package com.livespace.sensor.network

import com.livespace.sensor.model.TelemetryStore
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkFoundationTest {
    @Test fun productionAndFutureWorkersEndpointsAreAccepted() {
        ConnectionConfig("wss://livecafe.space/ws/sensors","LS-NOTE4-001","I")
        val production=ConnectionConfig("wss://livespace.temuulenbayarjargal.workers.dev/ws/sensors","LS-NOTE4-001","I")
        assertEquals(15000L,production.intervalMs)
        assertEquals(30000L,SensorWebSocket.HEARTBEAT_INTERVAL_MS)
    }
    @Test fun cleartextRequiresExplicitPrivateLanAndRejectsSecretsInUrl() {
        for(url in listOf("http://192.168.1.240/ws","ws://8.8.8.8/ws","wss://user:token@example.com/ws","wss://example.com/ws?token=x","wss://example.com/ws#x")) {
            assertThrows(IllegalArgumentException::class.java) { ConnectionConfig(url,"D","I",allowLanWs=true) }
        }
        assertThrows(IllegalArgumentException::class.java) { ConnectionConfig("ws://192.168.1.240/ws","D","I") }
        ConnectionConfig("ws://192.168.1.240:8080/ws/sensors","D","I",allowLanWs=true)
    }
    @Test fun tokenValidationAndErrorsNeverRevealCredentials() {
        assertFalse(CredentialSafety.valid("token\r\nInjected: yes"))
        assertFalse(CredentialSafety.valid(""))
        assertFalse(CredentialSafety.error(IOException("Bearer test-only-token")).contains("test-only-token"))
        assertEquals("Token stored securely",CredentialSafety.displayStored(true))
    }
    @Test fun pendingBoundAndInFlightBoundAreIndependent() {
        val q=PendingMessages(2,1)
        assertTrue(q.offer("a","{}"));assertTrue(q.offer("b","{}"));assertFalse(q.offer("c","{}"))
        q.sent(q.ready().first(),100)
        assertTrue(q.ready().isEmpty());assertEquals(2,q.size)
        assertTrue(q.acknowledge("a"));assertEquals("b",q.ready().single().id)
    }
    @Test fun outboxKeepsIdentityScopeAndPrioritizesFreshTelemetry() {
        val q=PendingMessages(3,2)
        assertTrue(q.offer("old","{\"v\":1}","D","L",100,0))
        assertTrue(q.offer("live","{\"v\":2}","D","L",200,1))
        val selected=q.ready("D","L")
        assertEquals("live",selected.first().id)
        assertFalse(q.acknowledge("live","other","L"))
        q.prioritizeBacklog()
        assertEquals(2,q.ready("D","L").size)
        assertTrue(q.acknowledge("live","D","L"))
        assertFalse(q.acknowledge("live","D","L"))
    }
    @Test fun pacedOutboxClaimsOnlyTheMessageThatWillBeSent() {
        val q=PendingMessages(8,4)
        repeat(4) { q.offer("m$it","{}","D","L",it.toLong(),1) }
        assertEquals(1,q.ready("D","L",1).size)
        assertEquals(4,q.ready("D","L").size)
    }
    @Test fun offlineSamplingKeepsImmutableMessageUntilMatchingReadyAndAck() {
        val server=MockWebServer();server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){}));server.start()
        val q=PendingMessages()
        val transport=SensorWebSocket({ TelemetryStore().snapshot(System.currentTimeMillis(),0) },{0},queue=q)
        try {
            val url=server.url("/ws").toString().replace("http:","ws:")
            transport.connect(ConnectionConfig(url,"DEVICE","LOCATION",1000,allowLanWs=true),"mock-token")
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
            while(q.size==0 && System.nanoTime()<until) Thread.sleep(40)
            assertTrue("sample should persist while ready is absent",q.size>0)
            val original=q.ready().first().json
            assertTrue(original.contains("\"deviceId\":\"DEVICE\""))
            assertTrue(original.contains("\"messageId\":"))
            transport.disconnect()
            val wait=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(transport.status.state!=ConnectionState.DISCONNECTED && System.nanoTime()<wait) Thread.sleep(20)
            assertEquals(original,q.ready().first().json)
        } finally { transport.close();server.shutdown() }
    }
    @Test fun retriesKeepSameMessageAndDuplicateAcksDoNotCount() {
        val q=PendingMessages(timeoutMs=100)
        q.offer("stable-id","{\"sample\":1}")
        val first=q.ready().single();q.sent(first,0);q.expired(101)
        val second=q.ready().single();assertSame(first,second);assertEquals("stable-id",second.id)
        assertEquals("{\"sample\":1}",second.json)
        q.sent(second,102);q.reconnect();assertSame(second,q.ready().single())
        assertTrue(q.acknowledge("stable-id"));assertFalse(q.acknowledge("stable-id"))
    }
    @Test fun ackTimeoutEventuallyFailsAndReleasesBoundedSlot() {
        val q=PendingMessages(timeoutMs=10,maxAttempts=2)
        q.offer("a","{}");q.sent(q.ready().single(),0);assertEquals(0,q.expired(10))
        q.sent(q.ready().single(),10);assertEquals(1,q.expired(20));assertEquals(0,q.size)
    }
    @Test fun backoffIsExponentialJitteredAndBounded() {
        assertEquals(1000,SensorWebSocket.backoffMillis(0,0.5))
        assertEquals(4000,SensorWebSocket.backoffMillis(2,0.5))
        assertTrue(SensorWebSocket.backoffMillis(99,1.0)<=30000)
        assertTrue(SensorWebSocket.backoffMillis(1,0.0)<SensorWebSocket.backoffMillis(1,1.0))
    }
    @Test fun exhaustedRetriesCannotPermanentlyOccupyQueueAfterReconnect() {
        val q=PendingMessages(maxAttempts=1);q.offer("a","{}");q.sent(q.ready().single(),1)
        assertEquals(1,q.reconnect());assertEquals(0,q.size)
    }
    @Test fun unauthorizedHandshakeStopsAutomaticReconnectWithoutExposingResponse() {
        val server=MockWebServer();server.enqueue(MockResponse().setResponseCode(401).setBody("test-only-token"));server.start()
        val authFailed=CountDownLatch(1)
        val transport=SensorWebSocket({ TelemetryStore().snapshot(0,0) },{0},changed={ if(it.state==ConnectionState.AUTH_FAILED) authFailed.countDown() })
        try {
            transport.connect(ConnectionConfig(server.url("/ws/sensors").toString().replace("http:","ws:"),"D","I",allowLanWs=true),"test-only-token")
            assertTrue(authFailed.await(5,TimeUnit.SECONDS));assertEquals(0,transport.status.reconnectAttempts)
            assertFalse(transport.status.error!!.contains("test-only-token"))
        } finally { transport.close();server.shutdown() }
    }
    @Test fun temporaryHandshakeFailureReconnectsAndReturnsToConnected() {
        val server=MockWebServer();server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {}));server.start()
        val connected=CountDownLatch(1);val states=java.util.concurrent.CopyOnWriteArrayList<ConnectionState>()
        val transport=SensorWebSocket({ TelemetryStore().snapshot(0,0) },{0},changed={ states.add(it.state);if(it.state==ConnectionState.CONNECTED) connected.countDown() })
        try {
            transport.connect(ConnectionConfig(server.url("/ws/sensors").toString().replace("http:","ws:"),"D","I",allowLanWs=true),"test-only-token")
            assertTrue(connected.await(6,TimeUnit.SECONDS));assertTrue(states.contains(ConnectionState.RETRY_WAIT))
            assertEquals(1,transport.status.reconnectAttempts)
        } finally { transport.close();server.shutdown() }
    }
    @Test fun mockAuthenticatedUpgradeAndDisconnectDoNotInventTelemetry() {
        val server=MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object: WebSocketListener() {}));server.start()
        val connected=CountDownLatch(1);val disconnected=CountDownLatch(1)
        val transport=SensorWebSocket({ TelemetryStore().snapshot(0,0) },{0},changed={
            if(it.state==ConnectionState.CONNECTED) connected.countDown()
            if(it.state==ConnectionState.DISCONNECTED) disconnected.countDown()
        })
        try {
            val endpoint=server.url("/ws/sensors").toString().replace("http:","ws:")
            transport.connect(ConnectionConfig(endpoint,"D","I",allowLanWs=true),"test-only-token")
            assertTrue(connected.await(5,TimeUnit.SECONDS))
            assertEquals("Bearer test-only-token",server.takeRequest(5,TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals(0L,transport.status.sent)
            assertEquals("Awaiting sensor.ready",transport.status.error)
            transport.disconnect()
            val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(transport.status.state!=ConnectionState.DISCONNECTED && System.nanoTime()<limit) Thread.sleep(10)
            assertEquals(ConnectionState.DISCONNECTED,transport.status.state)
        } finally { transport.close();server.shutdown() }
    }
}
