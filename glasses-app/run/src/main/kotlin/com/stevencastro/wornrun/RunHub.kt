package com.stevencastro.wornrun

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the HUD draws: the engine's numbers plus link and environment state. */
data class HudModel(
    val snapshot: Snapshot? = null,
    val phoneConnected: Boolean = false,
    /** Short status when BLE isn't usable, e.g. "Bluetooth is off". */
    val linkProblem: String? = null,
    val routeInfo: String? = null,
    val lux: Float = Float.NaN,
    val replaying: Boolean = false,
    /** Seconds the HUD stays lit after a touch before it sleeps; 0 = always on. */
    val displaySleepSec: Int = RunService.DEFAULT_DISPLAY_SLEEP_SEC,
    /** Temporary: draw the latest coordinates so GPS can be checked mid-run. */
    val showGps: Boolean = true,
    val videoRecording: Boolean = false,
    val cameraPermissionNeeded: Boolean = false,
)

/** Commands the HUD sends to the running service. */
interface RunController {
    fun togglePause()
    fun startFreeRun()
    fun finish()
    fun setVideoRecording(recording: Boolean)
    fun onVideoPermissionResult(granted: Boolean)
}

/** In-process link between [RunService] and [HudActivity]. */
object RunHub {
    val state = MutableStateFlow(HudModel())
    val model: StateFlow<HudModel> get() = state

    @Volatile
    var controller: RunController? = null
}
