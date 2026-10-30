package com.nik.sendtocar

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure day/night decision for the Auto theme, unit-tested. The best signal wins:
 * the car's headlights (FYT illumination wire) → the system night mode → the light sensor →
 * sunset to sunrise.
 */
object DayNight {
    /** Abu Dhabi: where the sun is computed until the unit has a GPS fix. */
    const val DEFAULT_LAT = 24.4539
    const val DEFAULT_LON = 54.3773
    /** Light-sensor hysteresis in lux, so dusk doesn't flicker the screen. */
    const val DARK_LUX = 15f
    const val BRIGHT_LUX = 40f

    fun isNight(headlights: Boolean?, systemNight: Boolean, lux: Float?, wasNight: Boolean, nowMin: Int, sun: Pair<Int, Int>?): Boolean {
        headlights?.let { return it }
        if (systemNight) return true
        lux?.let { return if (wasNight) it < BRIGHT_LUX else it < DARK_LUX }
        val (rise, set) = sun ?: (6 * 60 + 30 to 18 * 60 + 30)
        return inWindow(nowMin, set, rise)
    }

    /** Window may wrap midnight (19:00 → 06:30). */
    fun inWindow(now: Int, start: Int, end: Int): Boolean =
        if (start <= end) now in start until end else now >= start || now < end

    /**
     * Sunrise and sunset (NOAA solar position, ±2 min at these latitudes) as local minutes after
     * midnight, for [dayOfYear] 1–366 and [utcOffsetMin] (+240 in the UAE). null in polar day/night.
     */
    fun sunTimes(dayOfYear: Int, lat: Double, lon: Double, utcOffsetMin: Int): Pair<Int, Int>? {
        val g = 2 * Math.PI / 365.0 * (dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val cosH = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosH < -1 || cosH > 1) return null
        val ha = Math.toDegrees(acos(cosH))
        val noon = 720 - 4 * lon - eqTime
        fun norm(m: Double) = Math.floorMod(Math.round(m).toInt(), 1440)
        return norm(noon - 4 * ha + utcOffsetMin) to norm(noon + 4 * ha + utcOffsetMin)
    }
}
