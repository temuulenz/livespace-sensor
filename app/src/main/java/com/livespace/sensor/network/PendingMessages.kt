package com.livespace.sensor.network

/** Actor-confined, bounded temporary queue. Same ID and exact JSON bytes survive retries; no persistence. */
interface TelemetryOutbox : AutoCloseable {
    data class Entry(val id: String,val json: String,val deviceId:String="",val locationId:String="",var attempts:Int=0,var sentAt:Long?=null,var createdAt:Long=System.currentTimeMillis(),var priority:Int=0)
    val size:Int
    fun offer(id:String,json:String,deviceId:String="",locationId:String="",createdAt:Long=System.currentTimeMillis(),priority:Int=0):Boolean
    fun acknowledge(id:String,deviceId:String="",locationId:String=""):Boolean
    fun retry(id:String):Boolean
    fun reject(id:String):Boolean
    fun reconnect():Int
    fun expired(now:Long):Int
    fun ready(deviceId:String="",locationId:String="",limit:Int=4):List<Entry>
    fun sent(entry:Entry,now:Long)
    fun clear():Int
    fun prioritizeBacklog()
    fun contains(id:String):Boolean
    fun discardScope(deviceId:String,locationId:String):Int
    fun metrics(now:Long=System.currentTimeMillis()):OutboxMetrics
    override fun close() {}
}
data class OutboxMetrics(val pending:Int=0,val oldestAgeMs:Long?=null,val delivered:Long=0,val retries:Long=0,val expired:Long=0,val dropped:Long=0,val rejected:Long=0)
typealias OutboxEntry = TelemetryOutbox.Entry

class PendingMessages(private val capacity: Int=16,private val maxInFlight: Int=4,private val timeoutMs: Long=15000,private val maxAttempts: Int=3) : TelemetryOutbox {
    private val entries=linkedMapOf<String,OutboxEntry>()
    init { require(capacity>0 && maxInFlight in 1..capacity && timeoutMs>0 && maxAttempts>0) }
    override val size get()=entries.size
    override fun offer(id: String,json: String,deviceId:String,locationId:String,createdAt:Long,priority:Int): Boolean {
        require(id.isNotBlank() && json.toByteArray(Charsets.UTF_8).size<=16384)
        if(entries.containsKey(id)) return entries[id]!!.json==json
        if(entries.size>=capacity) return false // Reject newest; retain already sent messages until ACK/expiry.
        entries[id]=OutboxEntry(id,json,deviceId,locationId,createdAt=createdAt,priority=priority);return true
    }
    override fun acknowledge(id: String,deviceId:String,locationId:String)=entries[id]?.let { (deviceId.isEmpty() || it.deviceId==deviceId) && (locationId.isEmpty() || it.locationId==locationId) && entries.remove(id)!=null } ?: false
    /** Re-arm an unchanged message after a transient application error. */
    override fun retry(id: String): Boolean {
        val entry=entries[id] ?: return false
        if(entry.attempts>=maxAttempts) { entries.remove(id);return false }
        entry.sentAt=null;return true
    }
    override fun reject(id: String): Boolean = entries.remove(id)!=null
    override fun reconnect(): Int {
        val exhausted=entries.values.filter { it.attempts>=maxAttempts }.map { it.id }
        exhausted.forEach { entries.remove(it) }
        entries.values.forEach { it.sentAt=null }
        return exhausted.size
    }
    override fun expired(now: Long): Int {
        var failed=0
        val iterator=entries.values.iterator()
        while(iterator.hasNext()) {
            val e=iterator.next()
            if(e.sentAt?.let { now-it>=timeoutMs }==true) {
                if(e.attempts>=maxAttempts) { iterator.remove();failed++ } else e.sentAt=null
            }
        }
        return failed
    }
    override fun ready(deviceId:String,locationId:String,limit:Int): List<OutboxEntry> {
        require(limit in 1..4)
        val slots=(maxInFlight-entries.values.count { it.sentAt!=null }).coerceAtLeast(0)
        return entries.values.filter { (deviceId.isEmpty()||it.deviceId==deviceId) && (locationId.isEmpty()||it.locationId==locationId) && it.sentAt==null && it.attempts<maxAttempts }
            .sortedWith(compareByDescending<OutboxEntry> { it.priority }.thenByDescending { it.createdAt }).take(minOf(slots,limit))
    }
    override fun sent(entry: TelemetryOutbox.Entry,now: Long) { entry.attempts++;entry.sentAt=now }
    override fun clear(): Int { val count=size;entries.clear();return count }
    override fun prioritizeBacklog() { entries.values.forEach { it.priority=0 } }
    override fun contains(id:String)=entries.containsKey(id)
    override fun discardScope(deviceId:String,locationId:String):Int { val ids=entries.values.filter { it.deviceId==deviceId&&it.locationId==locationId }.map { it.id };ids.forEach { entries.remove(it) };return ids.size }
    override fun metrics(now:Long)=OutboxMetrics(size,entries.values.minByOrNull { it.createdAt }?.let { (now-it.createdAt).coerceAtLeast(0) })
}
