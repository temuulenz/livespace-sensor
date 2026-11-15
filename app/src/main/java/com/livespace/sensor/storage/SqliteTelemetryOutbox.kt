package com.livespace.sensor.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.livespace.sensor.network.OutboxEntry
import com.livespace.sensor.network.OutboxMetrics
import com.livespace.sensor.network.TelemetryOutbox

/** App-private, JSON-only durable queue. Each row is bound to the identity in its immutable envelope. */
class SqliteTelemetryOutbox(
    context: Context,
    maxMessages: Int = 10_000,
    maxAgeMs: Long = 24L * 60 * 60 * 1000
) : SQLiteOpenHelper(context.applicationContext, "sensor_outbox.db", null, 1), TelemetryOutbox {
    @Volatile private var messageLimit=maxMessages
    @Volatile private var retentionMs=maxAgeMs
    @Volatile private var metricsCache=OutboxMetrics()
    init { configure(maxMessages,maxAgeMs) }
    fun configure(limit:Int,ageMs:Long) { require(limit in 1..10_000 && ageMs in 60_000..24L*60*60*1000);messageLimit=limit;retentionMs=ageMs }
    fun cachedMetrics()=metricsCache
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE telemetry_outbox (message_id TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL, location_id TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, priority INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, sent_at INTEGER, in_flight INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX outbox_order ON telemetry_outbox(priority DESC, created_at DESC)")
        db.execSQL("CREATE TABLE outbox_counters (name TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)")
        listOf("delivered", "retries", "expired", "dropped", "rejected").forEach { db.execSQL("INSERT INTO outbox_counters(name,value) VALUES('$it',0)") }
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }
    private fun count(db: SQLiteDatabase,w: String?=null,args:Array<String>?=null):Int = db.rawQuery("SELECT COUNT(*) FROM telemetry_outbox"+(w?.let { " WHERE $it" } ?: ""),args).use { if(it.moveToFirst()) it.getInt(0) else 0 }
    private fun bump(db:SQLiteDatabase,name:String,by:Long=1) { db.execSQL("UPDATE outbox_counters SET value=value+? WHERE name=?",arrayOf(by,name)) }
    private fun purge(db:SQLiteDatabase,now:Long):Int {
        val n=count(db,"created_at < ?", arrayOf((now-retentionMs).toString()))
        if(n>0) { db.delete("telemetry_outbox","created_at < ?", arrayOf((now-retentionMs).toString()));bump(db,"expired",n.toLong()) }
        return n
    }
    override val size:Int get() = metrics().pending
    override fun offer(id:String,json:String,deviceId:String,locationId:String,createdAt:Long,priority:Int):Boolean {
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,64}")) && deviceId.isNotBlank() && locationId.isNotBlank() && json.toByteArray(Charsets.UTF_8).size<=16384)
        val db=writableDatabase;db.beginTransaction()
        try {
            purge(db,System.currentTimeMillis())
            val existing=db.rawQuery("SELECT payload,device_id,location_id FROM telemetry_outbox WHERE message_id=?",arrayOf(id)).use { c -> if(c.moveToFirst()) Triple(c.getString(0),c.getString(1),c.getString(2)) else null }
            if(existing!=null) { db.setTransactionSuccessful();return existing==Triple(json,deviceId,locationId) }
            if(count(db)>=messageLimit) { bump(db,"dropped");db.setTransactionSuccessful();return false }
            val values=ContentValues().apply { put("message_id",id);put("device_id",deviceId);put("location_id",locationId);put("payload",json);put("created_at",createdAt);put("priority",priority);put("attempts",0);putNull("sent_at");put("in_flight",0) }
            db.insertOrThrow("telemetry_outbox",null,values);db.setTransactionSuccessful();return true
        } finally { db.endTransaction() }
    }
    override fun acknowledge(id:String,deviceId:String,locationId:String):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            val where="message_id=?"+(if(deviceId.isNotEmpty())" AND device_id=?" else "")+(if(locationId.isNotEmpty())" AND location_id=?" else "")
            val proper=ArrayList<String>().apply { add(id);if(deviceId.isNotEmpty())add(deviceId);if(locationId.isNotEmpty())add(locationId) }.toTypedArray()
            val removed=db.delete("telemetry_outbox",where,proper)>0
            if(removed)bump(db,"delivered")
            db.setTransactionSuccessful();return removed
        } finally { db.endTransaction() }
    }
    override fun retry(id:String):Boolean {
        val db=writableDatabase;val changed=ContentValues().apply { put("in_flight",0);putNull("sent_at") }
        val ok=db.update("telemetry_outbox",changed,"message_id=?",arrayOf(id))>0
        if(ok) db.execSQL("UPDATE outbox_counters SET value=value+1 WHERE name='retries'")
        return ok
    }
    override fun reject(id:String):Boolean {
        val db=writableDatabase;db.beginTransaction()
        try { val ok=db.delete("telemetry_outbox","message_id=?",arrayOf(id))>0;if(ok)bump(db,"rejected");db.setTransactionSuccessful();return ok } finally { db.endTransaction() }
    }
    override fun reconnect():Int { val db=writableDatabase;db.beginTransaction();try { val n=count(db,"in_flight=1");db.execSQL("UPDATE telemetry_outbox SET in_flight=0,sent_at=NULL WHERE in_flight=1");if(n>0)bump(db,"retries",n.toLong());db.setTransactionSuccessful();return 0 } finally { db.endTransaction() } }
    override fun expired(now:Long):Int { val db=writableDatabase;db.beginTransaction();try { val n=count(db,"in_flight=1 AND sent_at IS NOT NULL AND sent_at<=?",arrayOf((now-15_000).toString()));if(n>0){db.execSQL("UPDATE telemetry_outbox SET in_flight=0,sent_at=NULL WHERE in_flight=1 AND sent_at<=?",arrayOf(now-15_000));bump(db,"retries",n.toLong())};purge(db,System.currentTimeMillis());db.setTransactionSuccessful();return 0 } finally { db.endTransaction() } }
    override fun ready(deviceId:String,locationId:String,limit:Int):List<OutboxEntry> {
        require(limit in 1..4)
        val db=writableDatabase;db.beginTransaction()
        try {
            purge(db,System.currentTimeMillis())
            val active=count(db,"in_flight=1")
            val slots=minOf((4-active).coerceAtLeast(0),limit);if(slots==0){db.setTransactionSuccessful();return emptyList()}
            val result=ArrayList<OutboxEntry>()
            db.rawQuery("SELECT message_id,payload,device_id,location_id,attempts,created_at,priority FROM telemetry_outbox WHERE device_id=? AND location_id=? AND in_flight=0 ORDER BY priority DESC,created_at DESC LIMIT ?",arrayOf(deviceId,locationId,slots.toString())).use { c ->
                while(c.moveToNext()) {
                    val id=c.getString(0)
                    val values=ContentValues().apply { put("in_flight",1);put("sent_at",System.currentTimeMillis());put("attempts",c.getInt(4)+1) }
                    if(db.update("telemetry_outbox",values,"message_id=? AND in_flight=0",arrayOf(id))>0) result.add(OutboxEntry(id,c.getString(1),c.getString(2),c.getString(3),c.getInt(4)+1,System.currentTimeMillis(),c.getLong(5),c.getInt(6)))
                }
            }
            db.setTransactionSuccessful();return result
        } finally { db.endTransaction() }
    }
    override fun sent(entry:OutboxEntry,now:Long) { val v=ContentValues().apply { put("in_flight",1);put("sent_at",System.currentTimeMillis()) };writableDatabase.update("telemetry_outbox",v,"message_id=? AND device_id=? AND location_id=?",arrayOf(entry.id,entry.deviceId,entry.locationId)) }
    override fun clear():Int { val db=writableDatabase;db.beginTransaction();try { val n=count(db);db.delete("telemetry_outbox",null,null);if(n>0)bump(db,"dropped",n.toLong());db.setTransactionSuccessful();return n } finally { db.endTransaction() } }
    override fun prioritizeBacklog() { writableDatabase.execSQL("UPDATE telemetry_outbox SET priority=0 WHERE in_flight=0") }
    override fun contains(id:String):Boolean = writableDatabase.rawQuery("SELECT 1 FROM telemetry_outbox WHERE message_id=?",arrayOf(id)).use { it.moveToFirst() }
    override fun discardScope(deviceId:String,locationId:String):Int {
        val db=writableDatabase;db.beginTransaction()
        try { val n=count(db,"device_id=? AND location_id=?",arrayOf(deviceId,locationId));if(n>0){db.delete("telemetry_outbox","device_id=? AND location_id=?",arrayOf(deviceId,locationId));bump(db,"dropped",n.toLong())};db.setTransactionSuccessful();return n } finally { db.endTransaction() }
    }
    override fun metrics(now:Long):OutboxMetrics {
        val db=writableDatabase;db.beginTransaction()
        try {
            purge(db,now)
            val pending=count(db)
            val oldest=db.rawQuery("SELECT MIN(created_at) FROM telemetry_outbox",null).use { if(it.moveToFirst()&&!it.isNull(0)) (now-it.getLong(0)).coerceAtLeast(0) else null }
            val counters=mutableMapOf<String,Long>();db.rawQuery("SELECT name,value FROM outbox_counters",null).use { while(it.moveToNext())counters[it.getString(0)]=it.getLong(1) }
            db.setTransactionSuccessful();return OutboxMetrics(pending,oldest,counters["delivered"]?:0,counters["retries"]?:0,counters["expired"]?:0,counters["dropped"]?:0,counters["rejected"]?:0).also { metricsCache=it }
        } finally { db.endTransaction() }
    }
    override fun close() { super<SQLiteOpenHelper>.close() }
}
