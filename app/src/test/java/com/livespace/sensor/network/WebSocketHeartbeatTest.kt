package com.livespace.sensor.network

import com.livespace.sensor.model.SensorSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebSocketHeartbeatTest {
    @Test fun missingPongReconnectsAndDurablePayloadReplaysUnchanged() {
        val listener=ServerSocket(0)
        val firstMessage=AtomicReference<String>()
        val replayed=CountDownLatch(1)
        val peerPong=CountDownLatch(1)
        val serverFailure=AtomicReference<Throwable?>()
        val server=Thread {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout=8000
                    val input=handshake(socket)
                    sendText(socket,"""{"type":"sensor.ready","schemaVersion":1,"deviceId":"LS-NOTE4-001","locationId":"I","dataSource":"real","maxPayloadBytes":16384}""")
                    while(true) {
                        val frame=readFrame(input) ?: break
                        if(frame.first==1) firstMessage.compareAndSet(null,String(frame.second,Charsets.UTF_8))
                        // Deliberately ignore opcode 9: this simulates a peer that never returns pong.
                    }
                }
                listener.accept().use { socket ->
                    socket.soTimeout=8000
                    val input=handshake(socket)
                    sendText(socket,"""{"type":"sensor.ready","schemaVersion":1,"deviceId":"LS-NOTE4-001","locationId":"I","dataSource":"real","maxPayloadBytes":16384}""")
                    sendFrame(socket,9,"server-ping".toByteArray(Charsets.UTF_8))
                    while(true) {
                        val frame=readFrame(input) ?: break
                        if(frame.first==9) { sendFrame(socket,10,frame.second);continue }
                        if(frame.first==10 && String(frame.second,Charsets.UTF_8)=="server-ping") { peerPong.countDown();continue }
                        if(frame.first!=1) continue
                        val text=String(frame.second,Charsets.UTF_8)
                        val json=JSONObject(text)
                        if(json.optString("messageId")==JSONObject(firstMessage.get()).optString("messageId")) {
                            assertEquals(firstMessage.get(),text)
                            replayed.countDown()
                        }
                        sendText(socket,"""{"type":"sensor.ack","messageId":"${json.getString("messageId")}","status":"accepted","serverTimestamp":"2026-11-16T00:00:01.000Z"}""")
                    }
                }
            } catch(t:Throwable) { serverFailure.set(t) }
        }.apply { isDaemon=true;start() }

        val connectionStates=java.util.concurrent.CopyOnWriteArrayList<ConnectionState>()
        val transport=SensorWebSocket(
            snapshot={ emptySnapshot() }, uptime={1}, changed={ connectionStates.add(it.state) },
            heartbeatIntervalMs=500,tickIntervalMs=50
        )
        try {
            transport.connect(ConnectionConfig("ws://127.0.0.1:${listener.localPort}/ws/sensors","LS-NOTE4-001","I",60000,true,true),"test-token")
            val didReplay=replayed.await(12,TimeUnit.SECONDS)
            assertTrue("client did not replay the unacknowledged payload; server=${serverFailure.get()}; state=${transport.status}; states=$connectionStates",didReplay)
            assertTrue("OkHttp did not automatically pong the peer ping",peerPong.await(2,TimeUnit.SECONDS))
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(transport.status.reconnectAttempts==0 && System.nanoTime()<until) Thread.sleep(10)
            assertTrue("missing pong did not trigger reconnect",transport.status.reconnectAttempts>0)
            assertTrue(connectionStates.contains(ConnectionState.RETRY_WAIT))
            assertNull("test peer failed",serverFailure.get())
        } finally {
            transport.close()
            listener.close()
            server.join(1000)
        }
    }

    private fun handshake(socket:Socket):BufferedInputStream {
        val input=BufferedInputStream(socket.getInputStream())
        val bytes=ByteArrayOutputStream()
        var matched=0
        val target=byteArrayOf(13,10,13,10)
        while(matched<4) {
            val b=input.read();check(b>=0) { "client closed during handshake" };bytes.write(b)
            matched=if(b==target[matched].toInt()) matched+1 else if(b==13) 1 else 0
        }
        val request=String(bytes.toByteArray(),Charsets.US_ASCII)
        val key=request.lineSequence().first { it.startsWith("Sec-WebSocket-Key:",true) }.substringAfter(':').trim()
        val accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        return input
    }

    private fun sendText(socket:Socket,text:String) = sendFrame(socket,1,text.toByteArray(Charsets.UTF_8))
    private fun sendFrame(socket:Socket,opcode:Int,payload:ByteArray) {
        val out=socket.getOutputStream();out.write(0x80 or opcode)
        when { payload.size<126 -> out.write(payload.size);payload.size<=65535 -> { out.write(126);out.write(payload.size ushr 8);out.write(payload.size and 255) };else -> error("test frame too large") }
        out.write(payload);out.flush()
    }
    private fun readFrame(input:BufferedInputStream):Pair<Int,ByteArray>? {
        val first=input.read();if(first<0)return null
        val second=input.read();if(second<0)return null
        var length=(second and 127).toLong()
        if(length==126L) length=((input.read().toLong() shl 8) or input.read().toLong())
        else if(length==127L) { length=0;repeat(8) { length=(length shl 8) or input.read().toLong() } }
        require(length<=16384) { "unexpected test frame length" }
        val masked=(second and 128)!=0
        val mask=if(masked) ByteArray(4).also { readFully(input,it) } else null
        val payload=ByteArray(length.toInt());readFully(input,payload)
        if(mask!=null) for(i in payload.indices) payload[i]=(payload[i].toInt() xor mask[i%4].toInt()).toByte()
        return (first and 15) to payload
    }
    private fun readFully(input:BufferedInputStream,target:ByteArray) {
        var offset=0
        while(offset<target.size) { val count=input.read(target,offset,target.size-offset);if(count<0)throw java.io.EOFException();offset+=count }
    }
}

private fun emptySnapshot() = SensorSnapshot(
    System.currentTimeMillis(),null,null,null,null,null,null,false,null,null,null,null,null,null,1
)
