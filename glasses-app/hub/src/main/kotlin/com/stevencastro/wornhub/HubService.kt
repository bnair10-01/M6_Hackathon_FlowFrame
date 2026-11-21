package com.stevencastro.wornhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketException
import java.security.SecureRandom

/**
 * Worn Hub on the glasses: lets the iPhone app see status and battery, list
 * and open apps, join Wi-Fi (over BLE), and watch the camera and browse
 * photos and videos (over Wi-Fi HTTP, token required). Starts at boot.
 *
 * Debug (debuggable builds only):
 *   adb shell am broadcast -a com.stevencastro.wornhub.DEBUG --es cmd '{"cmd":"listApps"}'
 *   adb forward tcp:47900 tcp:47900; curl -H "Authorization: Bearer <token from logcat>" localhost:47900/media
 */
class HubService : LifecycleService(), HubBle.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var ble: HubBle
    private lateinit var status: DeviceStatus
    private lateinit var media: MediaLibrary
    private lateinit var camera: LiveCamera
    private lateinit var http: HttpServer
    private lateinit var accessToken: String
    private var linkProblem: String? = null
    private var nsd: NsdManager? = null
    private val debuggable get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        accessToken = loadToken()
        if (debuggable) Log.i(TAG, "access token: $accessToken")
        status = DeviceStatus(this)
        media = MediaLibrary(contentResolver)
        camera = LiveCamera(this, this)
        ble = HubBle(this, this)
        startBle()
        registerReceiver(bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        http = HttpServer(HubProtocol.HTTP_PORT, { accessToken }, ::handleHttp)
        try {
            http.start()
        } catch (e: Exception) {
            Log.e(TAG, "HTTP server failed", e)
        }
        registerBonjour()
        if (debuggable) registerReceiver(debugReceiver, IntentFilter(ACTION_DEBUG))
        main.post(statusTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(bluetoothState)
        if (debuggable) unregisterReceiver(debugReceiver)
        nsd?.let { runCatching { it.unregisterService(nsdListener) } }
        http.stop()
        ble.stop()
        super.onDestroy()
    }

    // BLE.

    private fun startBle() {
        linkProblem = ble.start()
        linkProblem?.let { Log.w(TAG, "BLE: $it") }
        publishLocal()
    }

    /** Bluetooth restarts (vendor SDK, toggles) drop the GATT server; set it up again. */
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    ble.stop()
                    startBle()
                }
                BluetoothAdapter.STATE_OFF -> {
                    ble.stop()
                    linkProblem = "Bluetooth is off"
                    publishLocal()
                }
            }
        }
    }

    override fun token(): String = accessToken

    override fun onPhonesChanged(count: Int) {
        publishLocal()
        if (count > 0) sendStatus()
    }

    override fun onCommand(device: BluetoothDevice, json: String) {
        val reply = runCommand(json)
        ble.send(device, reply)
        if (JSONObject(reply).optString("type") == "result") sendStatus()
    }

    /** Runs a JSON command and returns the JSON event to send back. */
    private fun runCommand(json: String): String {
        val cmd = try {
            JSONObject(json)
        } catch (e: Exception) {
            return result("?", false, "Bad command")
        }
        val name = cmd.optString("cmd")
        return when (name) {
            "status" -> status.snapshot(ble.phoneCount, camera.viewerCount).toString()
            "listApps" -> appsJson()
            "launch" -> {
                val pkg = cmd.optString("package")
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent == null) {
                    result(name, false, "Not found: $pkg")
                } else {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                    result(name, true, "Opened ${label(pkg)}")
                }
            }
            "home" -> {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                result(name, true, "Home")
            }
            "joinWifi" -> {
                val ssid = cmd.optString("ssid")
                if (ssid.isEmpty()) result(name, false, "No network name")
                else result(name, true, status.joinWifi(ssid, cmd.optString("password")))
            }
            else -> result(name, false, "Unknown command")
        }
    }

    private fun result(cmd: String, ok: Boolean, message: String) =
        JSONObject().put("type", "result").put("cmd", cmd).put("ok", ok).put("message", message).toString()

    private fun appsJson(): String {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
        val arr = JSONArray()
        apps.forEach { (pkg, label) -> arr.put(JSONObject().put("package", pkg).put("label", label)) }
        return JSONObject().put("type", "apps").put("apps", arr).toString()
    }

    private fun label(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private val statusTick = object : Runnable {
        override fun run() {
            if (ble.phoneCount > 0) sendStatus()
            publishLocal()
            main.postDelayed(this, 5000)
        }
    }

    private fun sendStatus() = ble.broadcast(status.snapshot(ble.phoneCount, camera.viewerCount).toString())

    private fun publishLocal() {
        val s = status.snapshot(ble.phoneCount, camera.viewerCount)
        state.value = HubState(
            phones = ble.phoneCount,
            linkProblem = linkProblem,
            ip = s.getJSONObject("wifi").optString("ip"),
            viewers = camera.viewerCount,
        )
    }

    // HTTP (Wi-Fi).

    private fun handleHttp(req: HttpRequest, res: HttpServer.Response) {
        val path = req.path
        when {
            path == "/status" -> res.json(status.snapshot(ble.phoneCount, camera.viewerCount).toString())
            path == "/media" -> res.json(media.listJson())
            path.startsWith("/media/") && path.endsWith("/thumb") -> {
                val jpeg = media.thumbnail(path.removePrefix("/media/").removeSuffix("/thumb"))
                if (jpeg == null) res.text(404, "No thumbnail") else res.bytes(200, "image/jpeg", jpeg)
            }
            path.startsWith("/media/") -> {
                val item = media.find(path.removePrefix("/media/"))
                if (item == null) res.text(404, "Not found") else res.file(File(item.path), item.mime, req.headers["range"])
            }
            path.startsWith("/apps/") && path.endsWith("/icon") -> {
                val png = appIcon(path.removePrefix("/apps/").removeSuffix("/icon"))
                if (png == null) res.text(404, "No icon") else res.bytes(200, "image/png", png)
            }
            path == "/live" -> streamLive(res)
            else -> res.text(404, "Not found")
        }
    }

    /** MJPEG: one JPEG per multipart part, until the viewer disconnects. */
    private fun streamLive(res: HttpServer.Response) {
        camera.acquire()
        main.post(::publishLocal)
        try {
            res.head(200, "multipart/x-mixed-replace; boundary=$BOUNDARY", null)
            var seq = 0L
            while (true) {
                val (next, jpeg) = camera.nextFrame(seq) ?: continue
                seq = next
                res.out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
                res.out.write(jpeg)
                res.out.write("\r\n".toByteArray())
                res.out.flush()
            }
        } catch (e: SocketException) {
            // Viewer left.
        } catch (e: java.io.IOException) {
            // Viewer left.
        } finally {
            camera.release()
            main.post(::publishLocal)
        }
    }

    private fun appIcon(pkg: String): ByteArray? = runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(Canvas(bmp))
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    private fun registerBonjour() {
        val m = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val info = NsdServiceInfo().apply {
            serviceName = "Worn Hub ${android.os.Build.MODEL}"
            serviceType = HubProtocol.BONJOUR_TYPE
            port = HubProtocol.HTTP_PORT
        }
        runCatching { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener) }
    }

    private val nsdListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = Log.i(TAG, "Bonjour: ${info.serviceName}").let { }
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Log.w(TAG, "Bonjour failed: $error").let { }
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
    }

    // Misc.

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "debug result: ${runCommand(intent.getStringExtra("cmd") ?: "{}")}")
        }
    }

    private fun loadToken(): String {
        val prefs = getSharedPreferences("hub", MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", token).apply()
        return token
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Worn Hub", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Worn Hub")
            .setContentText("Ready for your iPhone")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    data class HubState(val phones: Int = 0, val linkProblem: String? = null, val ip: String = "", val viewers: Int = 0)

    companion object {
        private const val TAG = "WornHub"
        private const val CHANNEL = "hub"
        private const val BOUNDARY = "wornhubframe"
        const val ACTION_DEBUG = "com.stevencastro.wornhub.DEBUG"

        /** Shown by [StatusActivity]. */
        val state = MutableStateFlow(HubState())

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HubService::class.java))
        }
    }
}
