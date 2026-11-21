package com.stevencastro.wornrun

import kotlin.math.cos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunEngineTest {
    private val lat0 = 37.7749
    private val lon0 = -122.4194
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))

    /** Point [east] / [north] metres from the origin. */
    private fun at(east: Double, north: Double) = Pair(lat0 + north / mPerDegLat, lon0 + east / mPerDegLon)

    private fun fix(east: Double, north: Double, t: Long, speed: Double, course: Double = 90.0, acc: Double = 5.0): Fix {
        val (lat, lon) = at(east, north)
        return Fix(lat, lon, 10.0, speed, course, acc, t)
    }

    /** Runs east along y=[north] from [fromM] to [toM] at [speed] m/s, one fix per second. */
    private fun RunEngine.runEast(fromM: Double, toM: Double, startMs: Long, speed: Double = 3.0,
                                  north: Double = 0.0, noise: Double = 0.0, rnd: Random = Random(1)): Long {
        var t = startMs
        var x = fromM
        while (x <= toM) {
            val nx = if (noise > 0) rnd.nextDouble(-noise, noise) else 0.0
            val ny = if (noise > 0) rnd.nextDouble(-noise, noise) else 0.0
            onFix(fix(x + nx, north + ny, t, speed))
            x += speed
            t += 1000
        }
        return t
    }

    private fun straightRoute(lengthM: Double, stepM: Double = 50.0): Route {
        val n = (lengthM / stepM).toInt() + 1
        val pts = (0 until n).map { at(it * stepM, 0.0) }
        return Route(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), emptyList())
    }

    @Test
    fun distanceIsAccurateWithNoisyFixes() {
        val e = RunEngine()
        e.start(0)
        val end = e.runEast(0.0, 1500.0, 0, noise = 2.0)
        val d = e.snapshot(end).distanceM
        assertTrue(d in 1470.0..1530.0, "distance $d")
    }

    @Test
    fun speedAndAveragePace() {
        val e = RunEngine()
        e.start(0)
        val end = e.runEast(0.0, 600.0, 0, speed = 3.0)
        val s = e.snapshot(end)
        assertEquals(3.0, s.speedMps, 0.01)
        assertEquals(3.0, s.avgSpeedMps, 0.15)
        assertTrue(s.gpsOk)
    }

    @Test
    fun splitsFireEachKilometre() {
        val e = RunEngine()
        e.configure(RunSettings(metric = true))
        e.start(0)
        val end = e.runEast(0.0, 1005.0, 0, speed = 3.0)
        val split = assertNotNull(e.snapshot(end).split)
        assertEquals(1, split.number)
        assertEquals(333_000.0, split.durationMs.toDouble(), 3_000.0)
        assertNull(e.snapshot(end + 10_000).split, "split flash should expire")
    }

    @Test
    fun milesSplitAtOneMile() {
        val e = RunEngine()
        e.start(0)
        val beforeMile = e.runEast(0.0, 1600.0, 0)
        assertNull(e.snapshot(beforeMile).split)
        val after = e.runEast(1603.0, 1615.0, beforeMile)
        assertEquals(1, e.snapshot(after).split?.number)
    }

    @Test
    fun autoPausesWhenStoppedAndResumes() {
        val e = RunEngine()
        e.start(0)
        var t = e.runEast(0.0, 300.0, 0)
        // Stand still for 20 s.
        repeat(20) {
            e.onFix(fix(300.0, 0.0, t, 0.0))
            t += 1000
        }
        val paused = e.snapshot(t)
        assertTrue(paused.autoPaused)
        val moving = paused.movingMs
        // ~100 s running + ~3 s for speed to drop + 5 s confirmation.
        assertTrue(moving in 100_000L..109_000L, "moving $moving")
        // Still paused a bit later: moving time doesn't grow.
        e.onFix(fix(300.0, 0.0, t + 5000, 0.0))
        assertEquals(moving, e.snapshot(t + 5000).movingMs)
        // Run again.
        t = e.runEast(303.0, 400.0, t + 6000)
        assertFalse(e.snapshot(t).autoPaused)
    }

    @Test
    fun offBodySensorDoesNotPause() {
        // The wrist off-body sensor reads "off" on a head; a run must go on.
        val e = RunEngine()
        e.start(0)
        e.onOffBody(true)
        val t = e.runEast(0.0, 100.0, 0)
        val s = e.snapshot(t)
        assertFalse(s.autoPaused)
        assertTrue(s.offBody)
        assertTrue(s.distanceM > 90, "distance ${s.distanceM}")
    }

    @Test
    fun autoPauseOffKeepsCounting() {
        val e = RunEngine()
        e.configure(RunSettings(autoPause = false))
        e.start(0)
        var t = e.runEast(0.0, 100.0, 0)
        repeat(20) {
            e.onFix(fix(100.0, 0.0, t, 0.0))
            t += 1000
        }
        val s = e.snapshot(t)
        assertFalse(s.autoPaused)
        assertTrue(s.movingMs >= t - 1000, "moving ${s.movingMs} of $t")
    }

    @Test
    fun resumesWithoutPhoneSpeed() {
        // No Doppler speed: speed comes from positions, which must still be
        // tracked while auto-paused or the run never resumes.
        val e = RunEngine()
        e.start(0)
        var t = 0L
        repeat(15) {
            e.onFix(fix(0.0, 0.0, t, Double.NaN, Double.NaN))
            t += 1000
        }
        assertTrue(e.snapshot(t).autoPaused)
        var east = 0.0
        repeat(15) {
            east += 3.0
            e.onFix(fix(east, 0.0, t, Double.NaN, Double.NaN))
            t += 1000
        }
        assertFalse(e.snapshot(t).autoPaused)
    }

    @Test
    fun manualPauseFreezesElapsed() {
        val e = RunEngine()
        e.start(0)
        e.pause(10_000)
        assertEquals(10_000, e.snapshot(60_000).elapsedMs)
        e.resume(60_000)
        assertEquals(15_000, e.snapshot(65_000).elapsedMs)
        e.stop(70_000)
        assertEquals(RunState.FINISHED, e.snapshot(90_000).state)
        assertEquals(20_000, e.snapshot(90_000).elapsedMs)
    }

    @Test
    fun routeRemainingAndEta() {
        val e = RunEngine()
        e.setRoute(straightRoute(2000.0))
        e.start(0)
        val t = e.runEast(0.0, 500.0, 0, speed = 3.0)
        val r = assertNotNull(e.snapshot(t).route)
        assertEquals(1500.0, r.remainingM, 10.0)
        assertEquals(500_000.0, r.remainingMs.toDouble(), 25_000.0)
        assertNull(r.offRoute)
        assertFalse(r.finished)
    }

    @Test
    fun offRouteWarnsAfterDelayAndClears() {
        val e = RunEngine()
        e.setRoute(straightRoute(2000.0))
        e.start(0)
        var t = e.runEast(0.0, 300.0, 0)
        // Detour 60 m north for 5 s: too short to warn.
        t = e.runEast(303.0, 315.0, t, north = 60.0)
        assertNull(e.snapshot(t).route?.offRoute)
        // Stay off for 15 s more: warns, pointing back to the right (south while heading east).
        t = e.runEast(318.0, 360.0, t, north = 60.0)
        val off = assertNotNull(e.snapshot(t).route?.offRoute)
        assertEquals(60.0, off.distanceM, 3.0)
        assertTrue(off.relativeBearingDeg in 45.0..135.0, "bearing ${off.relativeBearingDeg}")
        // Back on the route.
        t = e.runEast(363.0, 400.0, t)
        assertNull(e.snapshot(t).route?.offRoute)
    }

    @Test
    fun nextTurnOnLShapedRoute() {
        // East 500 m, then north 500 m: a left turn at the corner.
        val pts = (0..10).map { at(it * 50.0, 0.0) } + (1..10).map { at(500.0, it * 50.0) }
        val route = Route(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(),
            listOf(Turn(10, -90, "Left")))
        val e = RunEngine()
        e.setRoute(route)
        e.start(0)
        val t = e.runEast(0.0, 400.0, 0)
        val turn = assertNotNull(e.snapshot(t).route?.nextTurn)
        assertEquals(-90, turn.angleDeg)
        assertEquals(100.0, turn.distanceM, 5.0)
        assertEquals(1000.0, e.snapshot(t).route!!.remainingM + 400.0, 10.0)
    }

    @Test
    fun loopRouteMatchesTheRightPass() {
        // Out 400 m east and back on the same road: halfway back must not match the way out.
        val out = (0..8).map { at(it * 50.0, 0.0) }
        val back = (7 downTo 0).map { at(it * 50.0, 0.0) }
        val pts = out + back
        val route = Route(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), emptyList())
        val e = RunEngine()
        e.setRoute(route)
        e.start(0)
        var t = e.runEast(0.0, 399.0, 0)
        // Run back west to x = 200.
        var x = 399.0
        while (x >= 200.0) {
            e.onFix(fix(x, 0.0, t, 3.0, course = 270.0))
            x -= 3.0
            t += 1000
        }
        assertEquals(200.0, e.snapshot(t).route!!.remainingM, 15.0)
    }

    @Test
    fun elevationHysteresis() {
        val e = RunEngine()
        e.start(0)
        listOf(100.0, 100.8, 101.5, 103.0, 102.0, 105.0, 104.5, 101.0).forEach(e::onAltitude)
        val s = e.snapshot(0)
        assertEquals(5.0, s.elevGainM, 0.01)
        assertEquals(4.0, s.elevLossM, 0.01)
    }

    @Test
    fun cadenceFromStepCounter() {
        val e = RunEngine()
        e.start(0)
        var steps = 5000L
        for (i in 0..15) e.onSteps(steps + i * 3, i * 1000L) // 3 steps/s = 180 spm
        assertEquals(180.0, e.snapshot(15_000).cadenceSpm, 1.0)
    }

    @Test
    fun heartRateShowsUntilStale() {
        val e = RunEngine()
        e.start(0)
        assertTrue(e.snapshot(0).heartRateBpm.isNaN())
        e.onHeartRate(152.0, 1_000)
        assertEquals(152.0, e.snapshot(5_000).heartRateBpm)
        assertTrue(e.snapshot(12_000).heartRateBpm.isNaN()) // no update for 11 s
        e.onHeartRate(400.0, 12_000) // implausible: ignored
        assertTrue(e.snapshot(12_000).heartRateBpm.isNaN())
    }
}
