package com.stevencastro.wornrun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Runs in the background from boot: hosts the BLE link to the phone, reads
 * the glasses' sensors, drives [RunEngine] once a second, and brings the HUD
 * up when a run starts. Everything runs on the main thread.
 *
 * Desk replay (no phone needed):
 *   adb push route.gpx /sdcard/Android/data/com.stevencastro.wornrun/files/route.gpx
 *   adb shell am startservice -n com.stevencastro.wornrun/.RunService \
 *       -a com.stevencastro.wornrun.REPLAY --es gpx /sdcard/Android/data/com.stevencastro.wornrun/files/route.gpx \
 *       --ef speed 3.0 --ef rate 5 --ef detour 400
 * (speed m/s, rate = time multiplier, detour = metres along the route where
 * a 60 m detour starts; omit for none.)
 */
class RunService : Service(), BleLink.Listener, RunController {
    private val main = Handler(Looper.getMainLooper())
    private val engine = RunEngine()
    private val videoRecorder by lazy { GlassesVideoRecorder(this) }
    private val assembler = RouteAssembler()
    private lateinit var ble: BleLink
    private lateinit var sensors: BodySensors
    private var linkProblem: String? = null
    private var phones = 0
    private var routeInfo: String? = null
    private var lux = Float.NaN
    private val track = ArrayList<Fix>()
    private val prefs by lazy { getSharedPreferences("run", Context.MODE_PRIVATE) }
    /** Kept on the glasses so a run started from the touchpad uses it too. */
    private var displaySleepSec = DEFAULT_DISPLAY_SLEEP_SEC
    private var showGps = true
    private var videoRecording = false
    private var cameraPermissionNeeded = false
    private var pendingVideoStart = false
    private var runStartWallMs = 0L

    // Replay drives a virtual clock so time can run faster than real time.
    private var replay: Replay? = null
    private fun now(): Long = replay?.clockMs ?: System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        displaySleepSec = prefs.getInt(PREF_DISPLAY_SLEEP, DEFAULT_DISPLAY_SLEEP_SEC)
        showGps = prefs.getBoolean(PREF_SHOW_GPS, true)
        // Last settings from the phone, so a touchpad-started run uses the same units.
        engine.configure(RunSettings(
            metric = prefs.getBoolean(PREF_METRIC, false),
            autoPause = prefs.getBoolean(PREF_AUTO_PAUSE, true),
        ))
        startForeground(NOTIFICATION_ID, notification())
        ble = BleLink(this, this)
        startLink()
        // When Bluetooth restarts (the vendor SDK or a user toggle can do
        // this), the GATT server and advertising are gone; set them up again.
        registerReceiver(bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        sensors = BodySensors(
            getSystemService(Context.SENSOR_SERVICE) as SensorManager,
            onAltitude = { if (replay == null) engine.onAltitude(it) },
            onSteps = { if (replay == null) engine.onSteps(it, now()) },
            // On a desk the glasses are off-body; ignore that during replays.
            onOffBody = {
                Log.i(TAG, "off-body sensor: ${if (it) "off" else "on"} body")
                engine.onOffBody(it)
            },
            onTemperature = engine::onSkinTemp,
            onLux = { lux = it },
        )
        sensors.start()
        RunHub.controller = this
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REPLAY) startReplay(intent)
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        replay = null
        if (videoRecording) videoRecorder.stop()
        sensors.stop()
        unregisterReceiver(bluetoothState)
        ble.stop()
        if (RunHub.controller === this) RunHub.controller = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLink() {
        linkProblem = ble.start()
        linkProblem?.let { Log.w(TAG, "BLE unavailable: $it") }
    }

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth back on: restarting the Worn Run link")
                    ble.stop()
                    startLink()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    ble.stop()
                    phones = 0
                    linkProblem = "Bluetooth is off"
                }
            }
            publish()
        }
    }

    // The 1 Hz loop: snapshot → HUD and phone.

    private val tick = object : Runnable {
        override fun run() {
            publish()
            main.postDelayed(this, replay?.let { (1000 / it.rate).toLong() } ?: 1000L)
        }
    }

    private fun publish() {
        val snapshot = engine.snapshot(now())
        RunHub.state.value = HudModel(
            snapshot = snapshot,
            phoneConnected = phones > 0,
            linkProblem = linkProblem,
            routeInfo = routeInfo,
            lux = lux,
            replaying = replay != null,
            displaySleepSec = displaySleepSec,
            showGps = showGps,
            videoRecording = videoRecording,
            cameraPermissionNeeded = cameraPermissionNeeded,
        )
        if (phones > 0) {
            val status = JSONObject()
                .put("state", snapshot.state.name)
                .put("autoPaused", snapshot.autoPaused)
                .put("elapsedMs", snapshot.elapsedMs)
                .put("distanceM", Math.round(snapshot.distanceM * 10) / 10.0)
                .put("videoRecording", videoRecording)
            snapshot.lastFix?.let { fix ->
                status.put("gpsLatitude", fix.lat)
                    .put("gpsLongitude", fix.lon)
                    .put("gpsFixAgeMs", snapshot.fixAgeMs)
            }
            ble.notifyStatus(status.toString().toByteArray())
        }
    }

    // BLE from the phone.

    override fun onFix(bytes: ByteArray) {
        if (replay != null) return
        val fix = RunProtocol.decodeFix(bytes, now()) ?: return
        acceptFix(fix)
    }

    private fun acceptFix(fix: Fix) {
        engine.onFix(fix)
        if (engine.runState == RunState.RUNNING) track.add(fix)
    }

    override fun onRouteChunk(bytes: ByteArray) {
        val body = assembler.accept(bytes) ?: return
        val route = RunProtocol.decodeRoute(body)
        if (route == null) {
            Log.w(TAG, "bad route payload (${body.size} bytes)")
            return
        }
        setRoute(route.takeIf { it.turns.isNotEmpty() } ?: Route(route.lats, route.lons, TurnDetector.detect(route.lats, route.lons)))
    }

    private fun setRoute(route: Route?) {
        engine.setRoute(route)
        routeInfo = route?.let {
            val metric = engine.settings.metric
            "Route ${Units.distance(it.lengthM, metric)} ${Units.distanceUnit(metric)} · ${it.turns.size} turns"
        }
        Log.i(TAG, routeInfo ?: "route cleared")
        publish()
    }

    override fun onControl(json: String) {
        val cmd = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "bad control: $json")
            return
        }
        when (cmd.optString("cmd")) {
            "settings" -> {
                val old = engine.settings
                val settings = RunSettings(
                    metric = cmd.optString("units", if (old.metric) "km" else "mi") == "km",
                    autoPause = cmd.optBoolean("autoPause", old.autoPause),
                )
                engine.configure(settings)
                displaySleepSec = cmd.optInt("displaySleepSec", displaySleepSec).coerceAtLeast(0)
                showGps = cmd.optBoolean("showGps", showGps)
                Log.i(TAG, "settings: $settings displaySleepSec=$displaySleepSec showGps=$showGps")
                prefs.edit()
                    .putInt(PREF_DISPLAY_SLEEP, displaySleepSec)
                    .putBoolean(PREF_SHOW_GPS, showGps)
                    .putBoolean(PREF_METRIC, settings.metric)
                    .putBoolean(PREF_AUTO_PAUSE, settings.autoPause)
                    .apply()
            }
            "video" -> setVideoRecording(cmd.optBoolean("recording"))
            "weather" -> engine.onAirTemp(cmd.optDouble("airTempC", Double.NaN))
            "hr" -> if (replay == null) engine.onHeartRate(cmd.optDouble("bpm", Double.NaN), now())
            "clearRoute" -> setRoute(null)
            "start" -> startRun()
            "pause" -> engine.pause(now())
            "resume" -> engine.resume(now())
            "stop" -> finish()
            else -> Log.w(TAG, "unknown control: $json")
        }
        publish()
    }

    override fun onConnectionChanged(connected: Int) {
        phones = connected
        publish()
    }

    // RunController (from the HUD) and run lifecycle.

    override fun togglePause() {
        engine.togglePause(now())
        publish()
    }

    override fun startFreeRun() = startRun()

    override fun setVideoRecording(recording: Boolean) {
        if (!recording) {
            pendingVideoStart = false
            cameraPermissionNeeded = false
            if (videoRecording) videoRecorder.stop()
            videoRecording = false
            publish()
            return
        }

        if (!hasVideoPermissions()) {
            pendingVideoStart = true
            cameraPermissionNeeded = true
            publish()
            return
        }

        pendingVideoStart = false
        cameraPermissionNeeded = false
        try {
            videoRecorder.start()
            videoRecording = videoRecorder.isRecording
        } catch (error: Exception) {
            videoRecording = false
            Log.e(TAG, "Could not start glasses video", error)
        }
        publish()
    }

    override fun onVideoPermissionResult(granted: Boolean) {
        cameraPermissionNeeded = false
        val shouldStart = pendingVideoStart
        pendingVideoStart = false
        if (granted && shouldStart) setVideoRecording(true) else publish()
    }

    private fun hasVideoPermissions(): Boolean = Build.VERSION.SDK_INT < 23 ||
        (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT > 28 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED))

    override fun finish() {
        if (engine.runState != RunState.RUNNING && engine.runState != RunState.PAUSED) return
        engine.stop(now())
        replay = null
        if (track.size >= 2) {
            try {
                val file = Gpx.write(File(getExternalFilesDir(null), "runs"), track, runStartWallMs)
                Log.i(TAG, "saved ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "couldn't save run", e)
            }
        }
        publish()
    }

    private fun startRun() {
        track.clear()
        runStartWallMs = System.currentTimeMillis()
        engine.start(now())
        showHud()
        publish()
    }

    private fun showHud() {
        startActivity(Intent(this, HudActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    // Desk replay.

    private class Replay(val route: Route, val speedMps: Double, val rate: Float, val detourAtM: Double) {
        var clockMs = System.currentTimeMillis()
        var alongM = 0.0
        var steps = 0L
    }

    private fun startReplay(intent: Intent) {
        val path = intent.getStringExtra("gpx")
        val route = path?.let { p -> runCatching { File(p).inputStream().use(Gpx::read) }.getOrNull() }
        if (route == null) {
            Log.e(TAG, "replay: couldn't read GPX at $path")
            return
        }
        val speed = intent.getFloatExtra("speed", 3.0f).toDouble()
        val rate = intent.getFloatExtra("rate", 5f).coerceIn(0.5f, 50f)
        val detour = intent.getFloatExtra("detour", -1f).toDouble()
        replay = Replay(route, speed, rate, detour)
        engine.onAirTemp(24.0)
        setRoute(route)
        startRun()
        main.removeCallbacks(replayStep)
        main.post(replayStep)
        Log.i(TAG, String.format(Locale.US, "replay: %.0f m at %.1f m/s, %.0fx", route.lengthM, speed, rate))
    }

    private val replayStep = object : Runnable {
        override fun run() {
            val r = replay ?: return
            if (engine.runState == RunState.PAUSED) {
                r.clockMs += 1000
                main.postDelayed(this, (1000 / r.rate).toLong())
                return
            }
            r.clockMs += 1000
            r.alongM += r.speedMps
            r.steps += 3 // 180 spm
            if (r.alongM >= r.route.lengthM) {
                finish()
                return
            }
            val (lat, lon) = pointAlong(r.route, r.alongM)
            // Optional detour: 60 m north for 150 m of the route.
            val north = if (r.detourAtM >= 0 && r.alongM in r.detourAtM..(r.detourAtM + 150)) 60.0 else 0.0
            val noise = { Random.nextDouble(-1.5, 1.5) }
            val fix = Fix(lat + (north + noise()) / 111_320.0, lon + noise() / (111_320.0 * Math.cos(Math.toRadians(lat))),
                30.0, r.speedMps + Random.nextDouble(-0.2, 0.2), Double.NaN, 5.0, r.clockMs)
            acceptFix(fix)
            engine.onSteps(r.steps, r.clockMs)
            engine.onHeartRate(150 + 6 * Math.sin(r.alongM / 400) + Random.nextDouble(-2.0, 2.0), r.clockMs)
            engine.onAltitude(30.0 + 8 * Math.sin(r.alongM / 300))
            main.postDelayed(this, (1000 / r.rate).toLong())
        }
    }

    private fun pointAlong(route: Route, along: Double): Pair<Double, Double> {
        val cum = route.cumulative
        var i = 0
        while (i < route.size - 2 && cum[i + 1] < along) i++
        val seg = cum[i + 1] - cum[i]
        val t = if (seg > 0) ((along - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
        return Geo.lerp(route.lats[i], route.lons[i], route.lats[i + 1], route.lons[i + 1], t)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Worn Run", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Worn Run")
            .setContentText("Ready for your phone")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    companion object {
        private const val TAG = "WornRun"
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 1
        const val ACTION_REPLAY = "com.stevencastro.wornrun.REPLAY"
        const val DEFAULT_DISPLAY_SLEEP_SEC = 3
        private const val PREF_DISPLAY_SLEEP = "displaySleepSec"
        private const val PREF_SHOW_GPS = "showGps"
        private const val PREF_METRIC = "metric"
        private const val PREF_AUTO_PAUSE = "autoPause"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RunService::class.java))
        }
    }
}
