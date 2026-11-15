package com.livespace.sensor.network

import com.livespace.sensor.model.SensorSnapshot
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Exact JSON contract from docs/SENSOR_PROTOCOL.md. Unknown server messages are ignored safely. */
class SensorProtocolV1 : SensorWireProtocol {
    override fun telemetry(config: ConnectionConfig, snapshot: SensorSnapshot, messageId: String, uptimeSeconds: Long): String {
        require(messageId.matches(Regex("[A-Za-z0-9_.:-]{1,64}")))
        val occupancy = JSONObject()
            .put("observedPeople", snapshot.observedPeople?.takeIf { it in 0..10000 } ?: JSONObject.NULL)
            .put("confidence", snapshot.detectionConfidence?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: JSONObject.NULL)
        val calibrated = snapshot.calibrated
        val noise = JSONObject()
            .put("rms", snapshot.noiseRms?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: JSONObject.NULL)
            .put("dbfs", snapshot.noiseDbfs?.takeIf { it.isFinite() && it in -160.0..0.0 } ?: JSONObject.NULL)
            .put("estimatedDbSpl", snapshot.estimatedDbSpl?.takeIf { calibrated && it.isFinite() && it in 0.0..140.0 } ?: JSONObject.NULL)
            .put("calibrated", calibrated)
        val device = JSONObject()
            .put("batteryPercent", snapshot.batteryPercent?.takeIf { it in 0..100 } ?: JSONObject.NULL)
            .put("batteryTemperatureC", snapshot.batteryTemperatureC?.takeIf { it.isFinite() && it in -40.0..100.0 } ?: JSONObject.NULL)
            .put("charging", snapshot.charging ?: JSONObject.NULL)
            .put("inferenceMs", snapshot.inferenceMs?.takeIf { it.isFinite() && it in 0.0..600000.0 } ?: JSONObject.NULL)
            .put("uptimeSeconds", snapshot.deviceUptimeSeconds?.takeIf { it >= 0 } ?: uptimeSeconds.takeIf { it >= 0 } ?: JSONObject.NULL)
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(snapshot.timestampUtcMs))
        return JSONObject()
            .put("schemaVersion", 1)
            .put("type", "sensor.telemetry")
            .put("messageId", messageId)
            .put("deviceId", config.deviceId)
            .put("locationId", config.locationId)
            .put("timestamp", timestamp)
            .put("occupancy", occupancy)
            .put("noise", noise)
            .put("device", device)
            .toString()
    }

    override fun incoming(text: String): ServerEvent {
        require(text.toByteArray(Charsets.UTF_8).size <= 16384) { "Server frame too large" }
        val root = JSONObject(text)
        return when (root.optString("type")) {
            "sensor.ready" -> {
                val version=root.opt("schemaVersion")
                require(version is Number && version.toDouble()==1.0) { "Unsupported ready schema" }
                val rawLimit=root.opt("maxPayloadBytes")
                require(rawLimit is Number && rawLimit.toDouble()%1.0==0.0) { "Invalid server payload limit" }
                val maxBytes=rawLimit.toInt()
                require(maxBytes in 1..16384) { "Invalid server payload limit" }
                ServerEvent.Ready(root.optString("deviceId"), root.optString("locationId"), root.optString("dataSource"), maxBytes)
            }
            "sensor.ack" -> {
                val id = root.optString("messageId")
                val accepted = root.optString("status") in setOf("accepted", "duplicate")
                if (accepted && id.isNotBlank()) ServerEvent.Acknowledged(id,root.optString("status")) else ServerEvent.Ignore
            }
            "sensor.error" -> {
                val id = root.optString("messageId").takeIf { it.isNotBlank() }
                val code = root.optJSONObject("error")?.optString("code").orEmpty()
                ServerEvent.ProtocolError(id, code)
            }
            else -> ServerEvent.Ignore
        }
    }
}
