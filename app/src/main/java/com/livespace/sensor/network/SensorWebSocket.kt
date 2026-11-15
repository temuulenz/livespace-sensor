package com.livespace.sensor.network

import com.livespace.sensor.model.SensorSnapshot
import okhttp3.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Implement ONLY against the authoritative backend contract. Intentionally absent until it is supplied. */
interface SensorWireProtocol {
    fun telemetry(config: ConnectionConfig,snapshot: SensorSnapshot,messageId: String,uptimeSeconds: Long): String
    fun incoming(text: String): ServerEvent
}
sealed class ServerEvent {
    data class Ready(val deviceId: String,val locationId: String,val dataSource: String,val maxPayloadBytes: Int): ServerEvent()
    data class Acknowledged(val id: String,val status: String="accepted"): ServerEvent()
    data class ProtocolError(val id: String?,val code: String): ServerEvent()
    object Ignore: ServerEvent()
}
enum class ConnectionState { DISCONNECTED,CONNECTING,CONNECTED,RETRY_WAIT,AUTH_FAILED,CLOSED }
data class ConnectionStatus(val state: ConnectionState=ConnectionState.DISCONNECTED,val sent: Long=0,val acknowledged: Long=0,
    val failed: Long=0,val pending: Int=0,val reconnectAttempts: Int=0,val lastConnected: Long?=null,
    val lastTransmitted: Long?=null,val lastAcknowledged: Long?=null,val error: String?=null,val outbox:OutboxMetrics=OutboxMetrics())

/** All mutable connection/queue state belongs to a single executor, never sensor or UI threads. */
class SensorWebSocket(private val snapshot: ()->SensorSnapshot,private val uptime: ()->Long,
    private val protocol: SensorWireProtocol=SensorProtocolV1(),private val changed: (ConnectionStatus)->Unit={},
    private val queue:TelemetryOutbox=PendingMessages(), private val heartbeatIntervalMs:Long=HEARTBEAT_INTERVAL_MS,
    private val tickIntervalMs:Long=1000L,
    private val endpointClient:(String,OkHttpClient)->OkHttpClient={ _,base -> base },
    private val diagnostic:(String)->Unit={}) : AutoCloseable {
    private val worker=Executors.newSingleThreadScheduledExecutor()
    // RFC 6455 control pings are transport-level only. OkHttp handles received pings/pongs and
    // fails the socket if a peer does not answer our ping; onFailure enters normal reconnect flow.
    private val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS)
        .pingInterval(heartbeatIntervalMs,TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    @Volatile var status=ConnectionStatus();private set
    private var socket: WebSocket?=null
    private var config: ConnectionConfig?=null
    private var token: String?=null
    private var desired=false
    private var generation=0
    private var nextRetry=0L
    private var lastSample=Long.MIN_VALUE
    private var failures=0
    private var protocolReady=false
    private var maxPayloadBytes=16384
    private var nextSendAllowed=0L
    private var lastQueueSend=0L
    init {
        require(heartbeatIntervalMs > 0 && tickIntervalMs > 0) { "Heartbeat and telemetry tick intervals must be positive" }
        worker.scheduleAtFixedRate({ tick() },1,tickIntervalMs,TimeUnit.MILLISECONDS)
    }
    private fun publish(next: ConnectionStatus) { val metrics=queue.metrics();status=next.copy(pending=metrics.pending,outbox=metrics);changed(status) }
    fun connect(config: ConnectionConfig,token: String) {
        require(CredentialSafety.valid(token)) { "Invalid device token" }
        worker.execute {
            disconnectInternal();this.config=config;this.token=token;desired=true;failures=0;open()
        }
    }
    private fun open() {
        val c=config ?: return
        val secret=token ?: return
        generation++;val owner=generation
        publish(status.copy(state=ConnectionState.CONNECTING,error=null))
        try {
            socket=endpointClient(c.endpoint,client).newWebSocket(Request.Builder().url(c.endpoint).header("Authorization","Bearer $secret").build(),object: WebSocketListener() {
                override fun onOpen(ws: WebSocket,response: Response) { dispatch {
                    if(owner!=generation || !desired) { ws.cancel();return@dispatch }
                    val exhausted=queue.reconnect();lastSample=Long.MIN_VALUE
                    protocolReady=false
                    publish(status.copy(state=ConnectionState.CONNECTED,failed=status.failed+exhausted,lastConnected=System.currentTimeMillis(),
                        error="Awaiting sensor.ready"))
                    worker.schedule({ if(owner==generation && status.state==ConnectionState.CONNECTED && !protocolReady) lost("Server readiness timeout",false) },10,TimeUnit.SECONDS)
                } }
                override fun onMessage(ws: WebSocket,text: String) { dispatch {
                    if(owner!=generation || text.length>16384) return@dispatch
                    val event=try { protocol.incoming(text) } catch (_: Exception) {
                        publish(status.copy(error="Invalid server message"));return@dispatch
                    }
                    when(event) {
                        is ServerEvent.Ready -> {
                            val c=config
                            if(c==null || event.deviceId!=c.deviceId || event.locationId!=c.locationId || event.dataSource!="real") {
                                desired=false;protocolReady=false;generation++;socket?.cancel()
                                publish(status.copy(state=ConnectionState.AUTH_FAILED,error="Server registration does not match configured device/location"))
                            } else {
                                protocolReady=true;maxPayloadBytes=event.maxPayloadBytes;lastSample=Long.MIN_VALUE;failures=0;queue.prioritizeBacklog()
                                publish(status.copy(error=null))
                                diagnostic("READY_MATCHED device=${c.deviceId} location=${c.locationId} source=real")
                            }
                        }
                        is ServerEvent.Acknowledged -> if(protocolReady && queue.acknowledge(event.id,config?.deviceId.orEmpty(),config?.locationId.orEmpty())) {
                            publish(status.copy(acknowledged=status.acknowledged+1,lastAcknowledged=System.currentTimeMillis(),error=null))
                            diagnostic("ACK_${if(event.status=="duplicate") "DUPLICATE" else "ACCEPTED"} id=${event.id} pending=${status.pending}")
                        }
                        is ServerEvent.ProtocolError -> {
                            val code=event.code
                            val id=event.id
                            when(code) {
                                "invalid_json","json_only","validation_failed" -> if(id!=null && queue.reject(id)) publish(status.copy(failed=status.failed+1,error="Telemetry quarantined by server ($code)"))
                                "invalid_device_token","unauthorized_location","simulation_not_allowed","device_assignment_changed" -> {
                                    if(id!=null && queue.reject(id)) publish(status.copy(failed=status.failed+1))
                                    desired=false;protocolReady=false;generation++;socket?.cancel();token=null
                                    publish(status.copy(state=ConnectionState.AUTH_FAILED,error="Server authorization rejected ($code)"))
                                }
                                "message_id_conflict" -> if(id!=null && queue.reject(id)) publish(status.copy(failed=status.failed+1,error="Server reported message_id_conflict"))
                                "rate_limited" -> {
                                    if(id!=null && !queue.retry(id)) publish(status.copy(failed=status.failed+1))
                                    val wait=backoffMillis(failures++,Random.nextDouble()).coerceAtMost(30000)
                                    nextSendAllowed=System.nanoTime()/1000000+wait
                                    publish(status.copy(error="Server rate limited telemetry"))
                                }
                                "storage_failed" -> {
                                    if(id!=null && !queue.retry(id)) publish(status.copy(failed=status.failed+1))
                                    nextSendAllowed=System.nanoTime()/1000000+1000
                                    publish(status.copy(error="Server storage temporarily unavailable"))
                                }
                                else -> {
                                    val exhausted=id!=null && !queue.retry(id)
                                    publish(status.copy(failed=status.failed+if(exhausted) 1 else 0,error="Server protocol error"))
                                }
                            }
                        }
                        ServerEvent.Ignore -> Unit
                    }
                } }
                override fun onMessage(ws: WebSocket,bytes: okio.ByteString) { dispatch {
                    if(owner==generation) { publish(status.copy(error="Server sent unsupported binary data"));ws.close(1003,"JSON text required") }
                } }
                override fun onClosing(ws: WebSocket,code: Int,reason: String) { ws.close(code,null) }
                override fun onClosed(ws: WebSocket,code: Int,reason: String) { dispatch { if(owner==generation) lost("Server closed ($code)",false) } }
                override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) { dispatch {
                    if(owner==generation) lost(if(response!=null) "Handshake HTTP ${response.code}" else CredentialSafety.error(t),response?.code in listOf(401,403))
                } }
            })
        } catch (e: Exception) { lost(CredentialSafety.error(e),false) }
    }
    private fun dispatch(block: ()->Unit) { try { worker.execute(block) } catch (_: java.util.concurrent.RejectedExecutionException) { /* Closed owner. */ } }
    private fun lost(error: String,authentication: Boolean) {
        generation++;protocolReady=false;socket?.cancel();socket=null
        if(authentication) { desired=false;publish(status.copy(state=ConnectionState.AUTH_FAILED,error=error));return }
        if(desired && config?.reconnect==true) {
            val delay=backoffMillis(failures++,Random.nextDouble())
            nextRetry=System.nanoTime()/1000000+delay
            publish(status.copy(state=ConnectionState.RETRY_WAIT,reconnectAttempts=status.reconnectAttempts+1,error=error))
        } else publish(status.copy(state=ConnectionState.DISCONNECTED,error=error))
    }
    private fun tick() {
        try {
            val now=System.nanoTime()/1000000
            if(desired && status.state==ConnectionState.RETRY_WAIT && now>=nextRetry) open()
            if(!desired) return
            val c=config ?: return
            if(lastSample==Long.MIN_VALUE || now-lastSample>=c.intervalMs) {
                lastSample=now
                val id=MessageIds.next()
                val json=protocol.telemetry(c,snapshot(),id,uptime())
                org.json.JSONObject(json) // JSON objects only, never binary media.
                if(json.toByteArray(Charsets.UTF_8).size>maxPayloadBytes && protocolReady) { publish(status.copy(failed=status.failed+1,error="Telemetry exceeds server payload limit"));return }
                if(!queue.offer(id,json,c.deviceId,c.locationId,System.currentTimeMillis(),1)) publish(status.copy(failed=status.failed+1,error="Telemetry outbox full; new sample dropped")) else publish(status.copy())
            }
            if(status.state!=ConnectionState.CONNECTED || !protocolReady || now<nextSendAllowed || now-lastQueueSend<1000) return
            val expired=queue.expired(System.currentTimeMillis())
            if(expired>0) publish(status.copy(failed=status.failed+expired,error="Acknowledgement timeout"))
            for(entry in queue.ready(c.deviceId,c.locationId,1)) {
                if(entry.json.toByteArray(Charsets.UTF_8).size>maxPayloadBytes) { queue.reject(entry.id);publish(status.copy(failed=status.failed+1,error="Queued telemetry exceeds server payload limit"));continue }
                if(socket?.send(entry.json)!=true) { queue.reconnect();lost("WebSocket send buffer unavailable",false);break }
                queue.sent(entry,System.currentTimeMillis());lastQueueSend=now
                diagnostic("SEND id=${entry.id} sha256=${java.security.MessageDigest.getInstance("SHA-256").digest(entry.json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }}")
                publish(status.copy(sent=status.sent+1,lastTransmitted=System.currentTimeMillis()));break
            }
        } catch (_: Exception) { publish(status.copy(error="Telemetry encoding failed")) }
    }
    fun disconnect() { dispatch { disconnectInternal() } }
    private fun disconnectInternal() {
        desired=false;protocolReady=false;generation++;socket?.close(1000,null);socket?.cancel();socket=null;token=null
        val pending=queue.reconnect();publish(status.copy(state=ConnectionState.DISCONNECTED,failed=status.failed+pending))
    }
    override fun close() { close {} }
    fun close(afterClose:()->Unit) {
        try { worker.execute {
            disconnectInternal();publish(status.copy(state=ConnectionState.CLOSED));client.dispatcher.executorService.shutdown();client.connectionPool.evictAll();worker.shutdown()
            afterClose()
        } } catch(_:java.util.concurrent.RejectedExecutionException) { afterClose() }
    }
    companion object {
        const val HEARTBEAT_INTERVAL_MS=30_000L
        fun backoffMillis(attempt: Int,jitter: Double): Long = ((1000L shl attempt.coerceIn(0,5))* (0.5+jitter.coerceIn(0.0,1.0))).toLong().coerceAtMost(30000)
    }
}
object MessageIds { fun next(): String = UUID.randomUUID().toString() }
