package com.stevencastro.wornhub

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/** Battery, storage, Wi-Fi and foreground-app readings, as JSON for the phone. */
class DeviceStatus(private val context: Context) {
    private val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val designMah: Double? by lazy { designCapacityMah() }

    fun snapshot(phones: Int, viewers: Int): JSONObject {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && scale > 0) l * 100 / scale else -1
        } ?: -1
        val counter = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val full = BatteryHealth.fullCapacityMah(counter, level)
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val bat = JSONObject()
            .put("level", level)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("full", status == BatteryManager.BATTERY_STATUS_FULL)
            .put("source", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "Charger"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Battery"
            })
            .put("health", BatteryHealth.healthName(sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, 1) ?: 1))
            .put("tempC", (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            .put("voltageMv", sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0)
            .put("currentMa", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000)
            .put("technology", sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "")
        full?.let { bat.put("fullMah", Math.round(it)) }
        // Only report the design figure when it's believable (see BatteryHealth).
        BatteryHealth.percentOfDesign(full, designMah)?.let {
            bat.put("capacityPercent", it)
            bat.put("designMah", Math.round(designMah!!))
        }

        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val storage = JSONObject()
            .put("freeBytes", stat.availableBytes)
            .put("totalBytes", stat.totalBytes)

        val info = wifi.connectionInfo
        val ip = info?.ipAddress ?: 0
        val net = JSONObject()
            .put("enabled", wifi.isWifiEnabled)
            .put("connected", ip != 0)
            .put("ssid", info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "")
            .put("ip", if (ip == 0) "" else formatIp(ip))
            .put("port", HubProtocol.HTTP_PORT)

        return JSONObject()
            .put("type", "status")
            .put("model", android.os.Build.MODEL)
            .put("android", android.os.Build.VERSION.RELEASE)
            .put("battery", bat)
            .put("storage", storage)
            .put("wifi", net)
            .put("foreground", foregroundApp() ?: "")
            .put("phones", phones)
            .put("liveViewers", viewers)
    }

    /** Joins a Wi-Fi network (Android 9 still lets apps add networks). */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun joinWifi(ssid: String, password: String): String {
        if (!wifi.isWifiEnabled) wifi.isWifiEnabled = true
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (password.isEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$password\""
            }
        }
        val id = wifi.addNetwork(config)
        if (id < 0) return "Couldn't add network $ssid"
        wifi.disconnect()
        wifi.enableNetwork(id, true)
        wifi.reconnect()
        return "Joining $ssid…"
    }

    /** Package in front, from usage events (needs GET_USAGE_STATS granted via adb appops). */
    private fun foregroundApp(): String? = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 6 * 60 * 60 * 1000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        last?.let { pkg ->
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
    } catch (e: Exception) {
        null
    }

    /** Design capacity from the hidden PowerProfile (readable on Android 9). */
    private fun designCapacityMah(): Double? = try {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val profile = cls.getConstructor(Context::class.java).newInstance(context)
        (cls.getMethod("getBatteryCapacity").invoke(profile) as Double).takeIf { it > 10 }
    } catch (e: Exception) {
        Log.w("WornHub", "design capacity unavailable: $e")
        null
    }

    private fun formatIp(ip: Int) = String.format(Locale.US, "%d.%d.%d.%d",
        ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
}
