package com.stevencastro.wornrun

import kotlin.math.abs

/**
 * Finds turns in a plain track (GPX has no turn instructions). Compares the
 * heading ~20 m before and after each point, keeps bends of 35° or more, and
 * merges bends closer than 30 m into the sharpest one. The phone does the same
 * in Swift; this copy serves desk replays and routes sent without turns.
 */
object TurnDetector {
    private const val LOOK_M = 20.0
    private const val MIN_ANGLE = 35.0
    private const val MERGE_M = 30.0

    fun detect(lats: DoubleArray, lons: DoubleArray): List<Turn> {
        val route = Route(lats, lons, emptyList())
        val cum = route.cumulative
        val candidates = ArrayList<Turn>()
        for (i in 1 until route.size - 1) {
            val before = indexAt(cum, cum[i] - LOOK_M, i, -1) ?: continue
            val after = indexAt(cum, cum[i] + LOOK_M, i, +1) ?: continue
            val inB = Geo.bearing(lats[before], lons[before], lats[i], lons[i])
            val outB = Geo.bearing(lats[i], lons[i], lats[after], lons[after])
            val angle = Geo.angleDiff(outB, inB)
            if (abs(angle) >= MIN_ANGLE) candidates.add(Turn(i, Math.round(angle).toInt(), label(angle.toInt())))
        }
        // Merge clusters of neighbouring candidates (one corner spans several points).
        val turns = ArrayList<Turn>()
        for (c in candidates) {
            val last = turns.lastOrNull()
            if (last != null && cum[c.pointIndex] - cum[last.pointIndex] < MERGE_M) {
                if (abs(c.angleDeg) > abs(last.angleDeg)) turns[turns.size - 1] = c
            } else {
                turns.add(c)
            }
        }
        return turns
    }

    fun label(angle: Int): String {
        val side = if (angle > 0) "right" else "left"
        val a = abs(angle)
        return when {
            a >= 165 -> "U-turn"
            a >= 120 -> "Sharp $side"
            a >= 60 -> side.replaceFirstChar { it.uppercase() }
            else -> "Slight $side"
        }
    }

    /** The point about [targetM] along the route, searching from [from] in [dir]. */
    private fun indexAt(cum: DoubleArray, targetM: Double, from: Int, dir: Int): Int? {
        var j = from
        while (j + dir in cum.indices) {
            j += dir
            if (if (dir < 0) cum[j] <= targetM else cum[j] >= targetM) return j
        }
        return null
    }
}
