package com.stevencastro.wornrun

import java.util.Locale
import kotlin.math.roundToLong

/** Formatting for the HUD in miles/°F or km/°C. */
object Units {
    private const val FT_PER_M = 3.28084

    /** "8:14" per mile/km, or "--:--" when not moving. */
    fun pace(speedMps: Double, metric: Boolean): String {
        if (speedMps.isNaN() || speedMps < 0.5) return "--:--"
        val perUnit = (if (metric) 1000.0 else RunEngine.METERS_PER_MILE) / speedMps
        if (perUnit >= 60 * 60) return "--:--"
        return clock(perUnit.roundToLong() * 1000)
    }

    fun paceUnit(metric: Boolean) = if (metric) "/km" else "/mi"

    fun distance(m: Double, metric: Boolean): String =
        String.format(Locale.US, "%.2f", if (metric) m / 1000 else m / RunEngine.METERS_PER_MILE)

    fun distanceUnit(metric: Boolean) = if (metric) "km" else "mi"

    /** Short distances for turns: "250 ft" / "80 m", switching to "0.3 mi" / "1.2 km" when far. */
    fun shortDistance(m: Double, metric: Boolean): String = if (metric) {
        if (m < 1000) "${(m / 10).roundToLong() * 10} m" else String.format(Locale.US, "%.1f km", m / 1000)
    } else {
        val ft = m * FT_PER_M
        if (ft < 1000) "${(ft / 10).roundToLong() * 10} ft"
        else String.format(Locale.US, "%.1f mi", m / RunEngine.METERS_PER_MILE)
    }

    /** GPS accuracy, unrounded to tens: "16 ft" / "5 m". */
    fun accuracy(m: Double, metric: Boolean): String =
        if (metric) "${m.roundToLong()} m" else "${(m * FT_PER_M).roundToLong()} ft"

    fun speed(mps: Double, metric: Boolean): String =
        String.format(Locale.US, "%.1f %s", if (metric) mps * 3.6 else mps * 2.23694, if (metric) "km/h" else "mph")

    fun temp(c: Double, metric: Boolean): String =
        if (c.isNaN()) "--°" else "${(if (metric) c else c * 9 / 5 + 32).roundToLong()}°"

    fun elevation(m: Double, metric: Boolean): String =
        if (metric) "${m.roundToLong()} m" else "${(m * FT_PER_M).roundToLong()} ft"

    fun volume(ml: Double, metric: Boolean): String =
        if (metric) "${(ml / 10).roundToLong() * 10} ml" else "${(ml / 29.5735).roundToLong()} oz"

    /** "1:02:03" or "32:15". Negative shows as 0:00. */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
