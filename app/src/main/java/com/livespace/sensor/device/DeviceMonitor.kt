package com.livespace.sensor.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build

data class BatteryReading(val percent: Int?,val temperatureC: Double?,val power: String,val charging: Boolean? = null)

class DeviceMonitor(private val context: Context) {
    fun hardware() = "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} • SDK ${Build.VERSION.SDK_INT}\nABI: ${Build.SUPPORTED_ABIS.joinToString()}"
    fun battery(): BatteryReading {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level in 0..scale && scale > 0) (level.toLong()*100/scale).toInt() else null
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val temperature = if (temp == Int.MIN_VALUE) null else temp / 10.0
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "On battery"
            else -> "Unavailable"
        }
        return BatteryReading(percent,temperature,charging,when(status) {
            BatteryManager.BATTERY_STATUS_CHARGING,BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING,BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        })
    }
    fun readings(battery: BatteryReading = battery()): String {
        val percent = battery.percent?.let { "$it%" } ?: "Unavailable"
        val temperature = battery.temperatureC?.let { "$it °C" } ?: "Unavailable"
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        @Suppress("DEPRECATION")
        val network = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
        return "Battery: $percent • $temperature\nPower: ${battery.power}\nAvailable RAM: ${info.availMem / 1048576} MiB\nNetwork: ${if (network?.isConnected == true) network.typeName else "Disconnected"}"
    }
}
