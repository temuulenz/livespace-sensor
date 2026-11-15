package com.livespace.sensor.network

import java.net.URI

data class ConnectionConfig(val endpoint: String,val deviceId: String,val locationId: String,
    val intervalMs: Long=15000,val reconnect: Boolean=true,val allowLanWs: Boolean=false) {
    init {
        require(intervalMs in 1000..60000) { "Telemetry interval must be 1000–60000 ms" }
        require(deviceId.matches(Regex("[A-Za-z0-9_.:-]{1,64}")) && locationId.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { "Invalid device/location ID" }
        require(endpoint.length <= 2048) { "Endpoint too long" }
        val u=try { URI(endpoint) } catch (_: Exception) { throw IllegalArgumentException("Invalid endpoint") }
        require(u.scheme in listOf("ws","wss") && !u.host.isNullOrEmpty() && u.rawUserInfo==null && u.rawQuery==null && u.rawFragment==null) { "Use ws/wss with no credentials, query or fragment" }
        require(u.port == -1 || u.port in 1..65535) { "Invalid endpoint port" }
        if (u.scheme=="ws") require(allowLanWs && privateHost(u.host)) { "Plain ws requires explicit private-IP LAN development mode" }
    }
    companion object {
        fun privateHost(host: String): Boolean {
            if(host=="localhost") return true
            val p=host.split('.').map { it.toIntOrNull() }
            if(p.size!=4 || p.any { it==null || it !in 0..255 }) return false
            return p[0]==10 || p[0]==127 || (p[0]==192 && p[1]==168) || (p[0]==172 && p[1]!! in 16..31)
        }
    }
}
object CredentialSafety {
    fun valid(token: String)=token.length in 1..2048 && token.all { it.code in 33..126 }
    fun displayStored(present: Boolean)=if(present) "Token stored securely" else "Token missing"
    fun error(t: Throwable)="Connection failed (${t.javaClass.simpleName})" // Never echo URLs, response bodies, reasons or tokens.
}
