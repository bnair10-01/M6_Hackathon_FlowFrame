package com.stevencastro.wornrun

import kotlin.math.max

/**
 * Settings sent from the phone; most can change mid-run. [autoPause] off keeps the clock and distance running
 * whatever the speed or the off-body sensor says.
 */
data class RunSettings(
    val metric: Boolean = false,
    val autoPause: Boolean = true,
)

enum class RunState { IDLE, RUNNING, PAUSED, FINISHED }

/** Everything the HUD shows, computed once per tick. NaN = unknown. */
data class Snapshot(
    val state: RunState,
    val autoPaused: Boolean,
    val elapsedMs: Long,
    val movingMs: Long,
    val distanceM: Double,
    val speedMps: Double,
    val avgSpeedMps: Double,
    val cadenceSpm: Double,
    val elevGainM: Double,
    val elevLossM: Double,
    val airTempC: Double,
    val skinTempC: Double,
    /** From the phone (a strap or watch, or the demo); NaN when none recently. */
    val heartRateBpm: Double,
    val gpsOk: Boolean,
    /** Latest fix from the phone (any accuracy), for the GPS debug line. */
    val lastFix: Fix?,
    /** Age of [lastFix], or -1 when there is none. */
    val fixAgeMs: Long,
    val offBody: Boolean,
    val route: RouteStatus?,
    val split: SplitEvent?,
    val metric: Boolean,
)

data class RouteStatus(
    val remainingM: Double,
    /** Estimated time to finish at the recent pace, or -1 when unknown. */
    val remainingMs: Long,
    val nextTurn: NextTurn?,
    /** Non-null while off route. */
    val offRoute: OffRoute?,
    val finished: Boolean,
)

data class NextTurn(val angleDeg: Int, val distanceM: Double, val label: String)

/** [relativeBearingDeg]: direction back to the route relative to heading (+ right), NaN if heading unknown. */
data class OffRoute(val distanceM: Double, val relativeBearingDeg: Double)

data class SplitEvent(val number: Int, val durationMs: Long, val untilMs: Long)

/**
 * The run's brain: turns fixes and sensor readings into the numbers on the
 * HUD. Pure Kotlin with no Android types, so it's tested on the JVM. Every
 * input carries its own timestamp; nothing reads the clock.
 */
class RunEngine {
    var settings = RunSettings()
        private set

    private var state = RunState.IDLE
    private var startMs = 0L
    private var pausedTotalMs = 0L
    private var pausedAtMs = 0L
    private var movingMs = 0L
    private var lastTickMs = 0L

    // Distance and speed.
    private var anchor: Fix? = null
    private var lastFix: Fix? = null
    private var lastRunningFix: Fix? = null
    private var distanceM = 0.0
    /** Distance moved whether or not auto-paused; only feeds the speed windows. */
    private var rawM = 0.0
    private val speedWindow = ArrayDeque<Pair<Long, Double>>() // time, GPS speed
    private val distWindow = ArrayDeque<Pair<Long, Double>>() // time, cumulative raw distance
    private var headingDeg = Double.NaN

    // Auto-pause.
    private var slowSinceMs = -1L
    private var autoPaused = false
    private var offBody = false

    // Splits.
    private var nextSplitM = 0.0
    private var splitNumber = 0
    private var lastSplitMovingMs = 0L
    private var split: SplitEvent? = null

    // Route.
    private var matcher: RouteMatcher? = null
    private var match: RouteMatcher.Match? = null
    private var offRouteSinceMs = -1L
    private var offRoute = false

    // Sensors.
    private val stepWindow = ArrayDeque<Pair<Long, Long>>()
    private var elevRef = Double.NaN
    private var elevGain = 0.0
    private var elevLoss = 0.0
    private var airTempC = Double.NaN
    private var skinTempC = Double.NaN
    private var heartRateBpm = Double.NaN
    private var heartRateAtMs = 0L

    fun configure(settings: RunSettings) {
        this.settings = settings
        if (!settings.autoPause) {
            autoPaused = false
            slowSinceMs = -1
        }
        if (state == RunState.RUNNING || state == RunState.PAUSED) {
            nextSplitM = splitLength() * (splitNumber + 1)
        }
    }

    fun setRoute(route: Route?) {
        matcher = route?.let { RouteMatcher(it) }
        match = null
        offRoute = false
        offRouteSinceMs = -1
    }

    val hasRoute: Boolean get() = matcher != null
    val runState: RunState get() = state

    fun start(nowMs: Long) {
        state = RunState.RUNNING
        startMs = nowMs
        lastTickMs = nowMs
        pausedTotalMs = 0
        movingMs = 0
        distanceM = 0.0
        rawM = 0.0
        anchor = null
        lastRunningFix = null
        speedWindow.clear()
        distWindow.clear()
        autoPaused = false
        slowSinceMs = -1
        splitNumber = 0
        nextSplitM = splitLength()
        lastSplitMovingMs = 0
        split = null
        elevRef = Double.NaN
        elevGain = 0.0
        elevLoss = 0.0
        matcher?.reset()
    }

    fun pause(nowMs: Long) {
        if (state != RunState.RUNNING) return
        advance(nowMs)
        state = RunState.PAUSED
        pausedAtMs = nowMs
    }

    fun resume(nowMs: Long) {
        if (state != RunState.PAUSED) return
        pausedTotalMs += nowMs - pausedAtMs
        state = RunState.RUNNING
        lastTickMs = nowMs
        anchor = null // don't count distance covered while paused
        lastRunningFix = null
    }

    fun togglePause(nowMs: Long) = if (state == RunState.RUNNING) pause(nowMs) else resume(nowMs)

    fun stop(nowMs: Long) {
        if (state == RunState.RUNNING) advance(nowMs)
        if (state == RunState.PAUSED) pausedTotalMs += nowMs - pausedAtMs
        if (state == RunState.RUNNING || state == RunState.PAUSED) {
            state = RunState.FINISHED
            pausedAtMs = nowMs
        }
    }

    fun onFix(fix: Fix) {
        lastFix = fix
        if (!fix.speedMps.isNaN()) {
            speedWindow.addLast(fix.timeMs to fix.speedMps)
            trim(speedWindow, fix.timeMs - SPEED_WINDOW_MS)
        }
        if (!fix.courseDeg.isNaN() && (fix.speedMps.isNaN() || fix.speedMps > 1.0)) headingDeg = fix.courseDeg

        matcher?.let { m ->
            if (fix.hAccM <= MAX_ROUTE_ACC_M) match = m.match(fix.lat, fix.lon)
        }

        if (state != RunState.RUNNING || fix.hAccM > MAX_ACC_M) return
        advance(fix.timeMs)

        // Distance: integrate GPS (Doppler) speed when the phone reports it.
        // Summing point-to-point gaps instead inflates distance with position
        // jitter (~10% at 2 m noise), so that's only the fallback.
        val prev = lastRunningFix
        lastRunningFix = fix
        if (prev != null && !fix.speedMps.isNaN()) {
            val dt = ((fix.timeMs - prev.timeMs) / 1000.0).coerceIn(0.0, MAX_FIX_GAP_S)
            if (fix.speedMps >= MIN_MOVING_SPEED_MPS) {
                rawM += fix.speedMps * dt
                if (!autoPaused) distanceM += fix.speedMps * dt
            }
        }
        val a = anchor
        if (a == null) {
            anchor = fix
        } else {
            val d = Geo.distance(a.lat, a.lon, fix.lat, fix.lon)
            // Only count movement clearly larger than the GPS noise.
            if (d >= max(MIN_STEP_M, fix.hAccM * 0.5)) {
                // Movement counts towards speed even while auto-paused, or
                // a run without phone speed could never auto-resume.
                if (fix.speedMps.isNaN()) rawM += d
                if (!autoPaused) {
                    if (fix.speedMps.isNaN()) distanceM += d
                    if (headingDeg.isNaN() || fix.courseDeg.isNaN()) headingDeg = Geo.bearing(a.lat, a.lon, fix.lat, fix.lon)
                }
                anchor = fix
            }
        }
        distWindow.addLast(fix.timeMs to rawM)
        trim(distWindow, fix.timeMs - ETA_WINDOW_MS)
        updateAutoPause(fix.timeMs)
        checkSplit(fix.timeMs)
    }

    /** Total steps since boot (Android step counter). */
    fun onSteps(totalSteps: Long, nowMs: Long) {
        stepWindow.addLast(nowMs to totalSteps)
        trim(stepWindow, nowMs - CADENCE_WINDOW_MS)
    }

    /** Barometric altitude, metres (relative is fine). */
    fun onAltitude(altM: Double) {
        if (state != RunState.RUNNING || altM.isNaN()) return
        if (elevRef.isNaN()) {
            elevRef = altM
            return
        }
        val d = altM - elevRef
        if (d >= ELEV_HYSTERESIS_M) {
            elevGain += d
            elevRef = altM
        } else if (d <= -ELEV_HYSTERESIS_M) {
            elevLoss -= d
            elevRef = altM
        }
    }

    fun onAirTemp(c: Double) {
        airTempC = c
    }

    fun onSkinTemp(c: Double) {
        skinTempC = c
    }

    /** Heart rate in beats per minute; ignored outside a plausible range. */
    fun onHeartRate(bpm: Double, nowMs: Long) {
        if (bpm.isNaN() || bpm !in 25.0..250.0) return
        heartRateBpm = bpm
        heartRateAtMs = nowMs
    }

    fun onOffBody(off: Boolean) {
        offBody = off
    }

    fun snapshot(nowMs: Long): Snapshot {
        if (state == RunState.RUNNING) {
            advance(nowMs)
            updateAutoPause(nowMs)
        }
        val elapsed = when (state) {
            RunState.IDLE -> 0L
            RunState.RUNNING -> nowMs - startMs - pausedTotalMs
            RunState.PAUSED, RunState.FINISHED -> pausedAtMs - startMs - pausedTotalMs
        }
        val fix = lastFix
        val gpsOk = fix != null && nowMs - fix.timeMs < GPS_STALE_MS && fix.hAccM <= MAX_ACC_M
        val speed = if (!gpsOk || autoPaused || state != RunState.RUNNING) 0.0 else currentSpeed()
        return Snapshot(
            state = state,
            autoPaused = autoPaused,
            elapsedMs = elapsed,
            movingMs = movingMs,
            distanceM = distanceM,
            speedMps = speed,
            avgSpeedMps = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0,
            cadenceSpm = cadence(nowMs),
            elevGainM = elevGain,
            elevLossM = elevLoss,
            airTempC = airTempC,
            skinTempC = skinTempC,
            heartRateBpm = if (nowMs - heartRateAtMs < HEART_RATE_STALE_MS) heartRateBpm else Double.NaN,
            gpsOk = gpsOk,
            lastFix = fix,
            fixAgeMs = if (fix == null) -1 else nowMs - fix.timeMs,
            offBody = offBody,
            route = routeStatus(nowMs),
            split = split?.takeIf { nowMs < it.untilMs },
            metric = settings.metric,
        )
    }

    // Internals.

    /** Accumulates moving time up to [nowMs]. */
    private fun advance(nowMs: Long) {
        val dt = nowMs - lastTickMs
        lastTickMs = nowMs
        if (dt <= 0 || state != RunState.RUNNING || autoPaused) return
        movingMs += dt
    }

    private fun updateAutoPause(nowMs: Long) {
        // React to the last few seconds, not the 10 s display smoothing.
        if (!settings.autoPause) {
            autoPaused = false
            slowSinceMs = -1
            return
        }
        // The off-body sensor is the watch chipset's wrist detector and
        // reads "off" on a head, so it's reported but doesn't pause.
        val speed = currentSpeed(PAUSE_WINDOW_MS)
        if (autoPaused) {
            if (speed >= RESUME_SPEED_MPS) {
                autoPaused = false
                slowSinceMs = -1
            }
            return
        }
        if (speed < PAUSE_SPEED_MPS && distWindow.isNotEmpty()) {
            if (slowSinceMs < 0) slowSinceMs = nowMs
            if (nowMs - slowSinceMs >= AUTO_PAUSE_MS) autoPaused = true
        } else {
            slowSinceMs = -1
        }
    }

    private fun currentSpeed(windowMs: Long = SPEED_WINDOW_MS): Double {
        if (speedWindow.isNotEmpty()) {
            val since = speedWindow.last().first - windowMs
            val recent = speedWindow.filter { it.first > since }
            return recent.sumOf { it.second } / recent.size
        }
        if (distWindow.size < 2) return 0.0
        val recent = distWindow.filter { it.first >= distWindow.last().first - windowMs }
        val dt = (recent.last().first - recent.first().first) / 1000.0
        return if (dt > 0) (recent.last().second - recent.first().second) / dt else 0.0
    }

    private fun checkSplit(nowMs: Long) {
        if (distanceM < nextSplitM) return
        splitNumber++
        split = SplitEvent(splitNumber, movingMs - lastSplitMovingMs, nowMs + SPLIT_SHOW_MS)
        lastSplitMovingMs = movingMs
        nextSplitM = splitLength() * (splitNumber + 1)
    }

    private fun splitLength() = if (settings.metric) 1000.0 else METERS_PER_MILE

    private fun cadence(nowMs: Long): Double {
        trim(stepWindow, nowMs - CADENCE_WINDOW_MS)
        if (stepWindow.size < 2) return Double.NaN
        val dtMin = (stepWindow.last().first - stepWindow.first().first) / 60_000.0
        if (dtMin < 5.0 / 60) return Double.NaN
        return (stepWindow.last().second - stepWindow.first().second) / dtMin
    }

    private fun routeStatus(nowMs: Long): RouteStatus? {
        val m = matcher ?: return null
        val at = match ?: return RouteStatus(m.route.lengthM, -1, m.route.turns.firstOrNull()?.let {
            NextTurn(it.angleDeg, m.route.cumulative[it.pointIndex], it.label)
        }, null, false)

        // Off-route needs to persist before we warn, and clears with hysteresis.
        if (at.crossTrackM > OFF_ROUTE_M) {
            if (offRouteSinceMs < 0) offRouteSinceMs = nowMs
            if (nowMs - offRouteSinceMs >= OFF_ROUTE_MS) offRoute = true
        } else {
            offRouteSinceMs = -1
            if (at.crossTrackM < BACK_ON_ROUTE_M) offRoute = false
        }

        val remaining = (m.route.lengthM - at.alongM).coerceAtLeast(0.0)
        val finished = remaining < FINISH_M
        val recentSpeed = recentAverageSpeed()
        val remainingMs = if (recentSpeed > 0.5) (remaining / recentSpeed * 1000).toLong() else -1L

        val next = m.route.turns.firstOrNull { m.route.cumulative[it.pointIndex] > at.alongM + 2 }
            ?.let { NextTurn(it.angleDeg, m.route.cumulative[it.pointIndex] - at.alongM, it.label) }

        val off = if (offRoute) {
            val fix = lastFix
            val rel = if (fix == null || headingDeg.isNaN()) Double.NaN
            else Geo.angleDiff(Geo.bearing(fix.lat, fix.lon, at.nearestLat, at.nearestLon), headingDeg)
            OffRoute(at.crossTrackM, rel)
        } else null

        return RouteStatus(remaining, remainingMs, if (finished) null else next, off, finished)
    }

    /** Average speed over the last couple of minutes (for ETA). */
    private fun recentAverageSpeed(): Double {
        if (distWindow.size < 2) return currentSpeed()
        val dt = (distWindow.last().first - distWindow.first().first) / 1000.0
        if (dt < 20) return currentSpeed()
        return (distWindow.last().second - distWindow.first().second) / dt
    }

    private fun <T> trim(window: ArrayDeque<Pair<Long, T>>, before: Long) {
        while (window.isNotEmpty() && window.first().first < before) window.removeFirst()
    }

    companion object {
        const val METERS_PER_MILE = 1609.344
        private const val MAX_ACC_M = 25.0
        private const val MAX_ROUTE_ACC_M = 40.0
        private const val MIN_STEP_M = 3.0
        private const val MIN_MOVING_SPEED_MPS = 0.3
        private const val MAX_FIX_GAP_S = 3.0
        private const val SPEED_WINDOW_MS = 10_000L
        private const val PAUSE_WINDOW_MS = 3_000L
        private const val ETA_WINDOW_MS = 120_000L
        private const val CADENCE_WINDOW_MS = 15_000L
        private const val GPS_STALE_MS = 5_000L
        private const val HEART_RATE_STALE_MS = 10_000L
        private const val PAUSE_SPEED_MPS = 0.5
        private const val RESUME_SPEED_MPS = 1.0
        private const val AUTO_PAUSE_MS = 5_000L
        private const val SPLIT_SHOW_MS = 4_000L
        private const val ELEV_HYSTERESIS_M = 2.0
        private const val OFF_ROUTE_M = 40.0
        private const val BACK_ON_ROUTE_M = 25.0
        private const val OFF_ROUTE_MS = 10_000L
        private const val FINISH_M = 15.0
    }
}
