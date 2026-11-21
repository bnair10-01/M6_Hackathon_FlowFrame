package com.stevencastro.wornrun

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One location fix from the phone. [speedMps] / [courseDeg] are NaN when unknown. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val speedMps: Double,
    val courseDeg: Double,
    val hAccM: Double,
    val timeMs: Long,
)

/** A turn at a route point. [angleDeg]: + right, - left, magnitude 0..180. */
data class Turn(val pointIndex: Int, val angleDeg: Int, val label: String)

/** A planned route: a polyline plus the turns along it. */
class Route(val lats: DoubleArray, val lons: DoubleArray, val turns: List<Turn>) {
    val size: Int get() = lats.size

    /** Distance from the start to each point, metres. */
    val cumulative: DoubleArray = DoubleArray(size).also { cum ->
        for (i in 1 until size) cum[i] = cum[i - 1] + Geo.distance(lats[i - 1], lons[i - 1], lats[i], lons[i])
    }

    val lengthM: Double get() = cumulative.last()
}

object Geo {
    private const val EARTH_R = 6_371_000.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees 0..360 (0 = north). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Signed smallest difference a - b in degrees, -180..180. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /** Point at [fraction] along the segment (simple interpolation; fine for short segments). */
    fun lerp(lat1: Double, lon1: Double, lat2: Double, lon2: Double, fraction: Double): Pair<Double, Double> =
        Pair(lat1 + (lat2 - lat1) * fraction, lon1 + (lon2 - lon1) * fraction)
}
