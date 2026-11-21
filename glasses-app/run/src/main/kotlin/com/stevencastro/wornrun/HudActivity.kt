package com.stevencastro.wornrun

import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Full-screen running HUD. The phone stays in your pocket; the temple
 * touchpad does everything:
 *   tap          wake the display (or put it to sleep if it's awake)
 *   long-press   pause / resume (or start a free run when idle)
 *   swipe back   toggle glasses video recording
 *   swipe up     finish (while paused)
 *
 * The display sleeps a few seconds after the last touch (set on the phone;
 * 0 = always on). Asleep, only alerts and turns within 50 m are drawn.
 */
class HudActivity : Activity() {
    private lateinit var hud: HudView
    private val scope: CoroutineScope = MainScope()
    private var collector: Job? = null
    private var downX = 0f
    private var downY = 0f
    private var videoPermissionRequestInFlight = false
    private val main = Handler(Looper.getMainLooper())
    private val sleep = Runnable { hud.layout = HudView.Layout.OFF }
    private var lastState: RunState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hud = HudView(this)
        setContentView(hud)
        RunService.start(this)
    }

    override fun onResume() {
        super.onResume()
        collector = scope.launch { RunHub.model.collect(::onModel) }
        wake()
    }

    override fun onPause() {
        collector?.cancel()
        main.removeCallbacks(sleep)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(sleep)
        scope.cancel()
        super.onDestroy()
    }

    private val running get() = hud.model.snapshot?.state.let { it == RunState.RUNNING || it == RunState.PAUSED }

    private fun onModel(model: HudModel) {
        val old = hud.model
        hud.model = model
        if (model.cameraPermissionNeeded && !videoPermissionRequestInFlight) {
            videoPermissionRequestInFlight = true
            val permissions = if (android.os.Build.VERSION.SDK_INT <= 28) {
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                arrayOf(Manifest.permission.CAMERA)
            }
            requestPermissions(permissions, VIDEO_PERMISSION_REQUEST)
        }
        val state = model.snapshot?.state
        // Light up when the run starts, pauses, resumes or finishes, or when
        // the sleep setting changes (so "always on" takes effect at once).
        if (state != lastState || model.displaySleepSec != old.displaySleepSec) wake()
        lastState = state
    }

    /** Shows the full HUD, then sleeps after the phone's setting (unless always on). */
    private fun wake() {
        hud.layout = HudView.Layout.FULL
        main.removeCallbacks(sleep)
        val sec = hud.model.displaySleepSec
        if (sec > 0) main.postDelayed(sleep, sec * 1000L)
    }

    private fun tap() {
        if (hud.layout == HudView.Layout.OFF || hud.model.displaySleepSec == 0) wake() else sleep.run()
    }

    private fun longPress() {
        val c = RunHub.controller ?: return
        if (running) c.togglePause() else c.startFreeRun()
        wake()
    }

    private fun swipeUp() {
        if (hud.model.snapshot?.state == RunState.PAUSED) RunHub.controller?.finish()
        wake()
    }

    private fun swipeBack() {
        RunHub.controller?.setVideoRecording(!hud.model.videoRecording)
        wake()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val held = event.eventTime - event.downTime
                when {
                    dx < -SWIPE_PX && abs(dx) > abs(dy) -> swipeBack()
                    dy < -SWIPE_PX && abs(dy) > abs(dx) -> swipeUp()
                    abs(dx) < SWIPE_PX && abs(dy) < SWIPE_PX -> if (held >= LONG_PRESS_MS) longPress() else tap()
                }
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            event.startTracking()
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> { swipeBack(); true }
        KeyEvent.KEYCODE_DPAD_UP -> { swipeUp(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != VIDEO_PERMISSION_REQUEST) return
        videoPermissionRequestInFlight = false
        val granted = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        RunHub.controller?.onVideoPermissionResult(granted)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longPress()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0 && !event.isCanceled) tap()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun abs(v: Float) = kotlin.math.abs(v)

    companion object {
        private const val VIDEO_PERMISSION_REQUEST = 42
        private const val SWIPE_PX = 60f
        private const val LONG_PRESS_MS = 600L
    }
}

/** Starts the run service at boot so the phone can connect any time. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RunService.start(context)
    }
}
