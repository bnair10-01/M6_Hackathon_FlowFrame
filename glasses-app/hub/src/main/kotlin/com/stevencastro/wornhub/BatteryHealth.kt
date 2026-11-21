package com.stevencastro.wornhub

/**
 * Estimates how much charge the battery holds now compared with when new.
 * Android 9 has no cycle count, but the fuel gauge's charge counter (µAh) at
 * a known level gives the current full capacity; the power profile gives the
 * design capacity.
 */
object BatteryHealth {
    /** Full-charge capacity in mAh, or null when the reading isn't usable. */
    fun fullCapacityMah(chargeCounterUah: Int, levelPercent: Int): Double? {
        // Low levels amplify gauge error; very small counters are bogus.
        if (chargeCounterUah <= 1000 || levelPercent < 20 || levelPercent > 100) return null
        return chargeCounterUah / 1000.0 / (levelPercent / 100.0)
    }

    /**
     * Percent of design capacity (capped at 100), or null. Vendors often ship
     * a generic power profile (the M6 claims 1900 mAh for a ~240 mAh cell), so
     * a design figure more than 2.5× the measured capacity is treated as
     * unknown rather than reported as a dying battery.
     */
    fun percentOfDesign(fullMah: Double?, designMah: Double?): Int? {
        if (fullMah == null || designMah == null || designMah <= 0) return null
        if (designMah > fullMah * 2.5) return null
        return Math.round(fullMah / designMah * 100).toInt().coerceIn(1, 100)
    }

    fun healthName(code: Int): String = when (code) {
        2 -> "Good"
        3 -> "Overheating"
        4 -> "Dead"
        5 -> "Over voltage"
        6 -> "Failure"
        7 -> "Cold"
        else -> "Unknown"
    }
}
