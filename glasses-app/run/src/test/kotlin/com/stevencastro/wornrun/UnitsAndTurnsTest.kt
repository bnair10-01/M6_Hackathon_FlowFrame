package com.stevencastro.wornrun

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitsAndTurnsTest {
    @Test
    fun paceFormatting() {
        // 3.25 m/s = 8:15 /mi = 5:08 /km
        assertEquals("8:15", Units.pace(3.25, metric = false))
        assertEquals("5:08", Units.pace(3.25, metric = true))
        assertEquals("--:--", Units.pace(0.1, metric = false))
    }

    @Test
    fun clockFormatting() {
        assertEquals("0:05", Units.clock(5_000))
        assertEquals("32:15", Units.clock(1_935_000))
        assertEquals("1:02:03", Units.clock(3_723_000))
    }

    @Test
    fun otherUnits() {
        assertEquals("1.00", Units.distance(1609.344, metric = false))
        assertEquals("250 ft", Units.shortDistance(76.2, metric = false))
        assertEquals("80 m", Units.shortDistance(78.0, metric = true))
        assertEquals("0.6 mi", Units.shortDistance(1000.0, metric = false))
        assertEquals("68°", Units.temp(20.0, metric = false))
        assertEquals("20°", Units.temp(20.0, metric = true))
        assertEquals("7.3 mph", Units.speed(3.25, metric = false))
    }

    @Test
    fun detectsCornersOnly() {
        val lat0 = 37.0
        val mLat = 111_320.0
        val mLon = 111_320.0 * cos(Math.toRadians(lat0))
        // East 300 m, north 300 m (left), then east 300 m (right), points every 10 m.
        val pts = (0..30).map { Pair(lat0, -122.0 + it * 10 / mLon) } +
                (1..30).map { Pair(lat0 + it * 10 / mLat, -122.0 + 300 / mLon) } +
                (1..30).map { Pair(lat0 + 300 / mLat, -122.0 + (300 + it * 10) / mLon) }
        val turns = TurnDetector.detect(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray())
        assertEquals(2, turns.size, "$turns")
        assertTrue(turns[0].angleDeg in -100..-80, "${turns[0]}")
        assertEquals("Left", turns[0].label)
        assertTrue(turns[1].angleDeg in 80..100, "${turns[1]}")
        assertTrue(turns[0].pointIndex in 28..32)
    }

    @Test
    fun turnLabels() {
        assertEquals("Slight left", TurnDetector.label(-40))
        assertEquals("Right", TurnDetector.label(90))
        assertEquals("Sharp right", TurnDetector.label(140))
        assertEquals("U-turn", TurnDetector.label(-175))
    }
}
