package com.stevencastro.wornrun

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Places the runner on a route: which segment they're on, how far along the
 * route that is, and how far off it they are (cross-track).
 *
 * Searches a window ahead of the last match so out-and-back or looping routes
 * match the right pass; falls back to a full search when the window is far
 * off (start mid-route, or rejoining after a detour).
 */
class RouteMatcher(val route: Route) {
    data class Match(
        val segment: Int,
        val alongM: Double,
        val crossTrackM: Double,
        val nearestLat: Double,
        val nearestLon: Double,
    )

    private var lastSegment = -1

    fun reset() {
        lastSegment = -1
    }

    fun match(lat: Double, lon: Double): Match {
        val windowed = if (lastSegment >= 0) {
            search(lat, lon, (lastSegment - BACK).coerceAtLeast(0), (lastSegment + AHEAD).coerceAtMost(route.size - 2))
        } else null
        val best = if (windowed == null || windowed.crossTrackM > REACQUIRE_M) {
            val global = search(lat, lon, 0, route.size - 2)
            if (windowed == null || global.crossTrackM < windowed.crossTrackM - 5) global else windowed
        } else windowed
        lastSegment = best.segment
        return best
    }

    private fun search(lat: Double, lon: Double, from: Int, to: Int): Match {
        // Local flat projection around the runner (metres); accurate enough
        // for distances of a few km.
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        var best: Match? = null
        for (i in from..to) {
            val ax = (route.lons[i] - lon) * mPerDegLon
            val ay = (route.lats[i] - lat) * mPerDegLat
            val bx = (route.lons[i + 1] - lon) * mPerDegLon
            val by = (route.lats[i + 1] - lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + dx * t
            val py = ay + dy * t
            val dist = sqrt(px * px + py * py)
            if (best == null || dist < best.crossTrackM) {
                val segLen = route.cumulative[i + 1] - route.cumulative[i]
                val (nLat, nLon) = Geo.lerp(route.lats[i], route.lons[i], route.lats[i + 1], route.lons[i + 1], t)
                best = Match(i, route.cumulative[i] + segLen * t, dist, nLat, nLon)
            }
        }
        return best!!
    }

    companion object {
        private const val BACK = 3
        private const val AHEAD = 40
        private const val REACQUIRE_M = 60.0
    }
}
