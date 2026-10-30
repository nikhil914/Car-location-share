package com.nik.sendtocar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private val all = Accent.entries.map { Palettes.clean(it) } + Palettes.trueBlack + Palettes.daylight

    @Test fun primaryTextIsAaaOnEveryBackground() = all.forEach { p ->
        for (bg in listOf(p.background, p.surface, p.raised)) {
            val r = Palettes.contrast(p.text, bg)
            assertTrue("${p.name}: text on ${bg.toString(16)} is $r", r >= 7.0)
        }
    }

    @Test fun startNavigationLabelIsAaa() = all.forEach { p ->
        val r = Palettes.contrast(p.onAccent, p.accent)
        assertTrue("${p.name}: button label is $r", r >= 7.0)
    }

    @Test fun mutedTextIsAtLeastAa() = all.forEach { p ->
        for (bg in listOf(p.background, p.surface, p.raised)) {
            val r = Palettes.contrast(p.muted, bg)
            assertTrue("${p.name}: muted on ${bg.toString(16)} is $r", r >= 4.5)
        }
    }

    @Test fun nightThemeIsTrueBlackAndDim() {
        val p = Palettes.trueBlack
        assertEquals(0xFF000000, p.background)
        assertTrue("night text should stay dim", Palettes.contrast(p.text, p.background) < 10.0)
    }

    @Test fun specColours() {
        assertEquals(0xFF121316, Palettes.clean().background)
        assertEquals(0xFF26282E, Palettes.clean().border)
        assertEquals(0xFFEDEDED, Palettes.clean().text)
        assertEquals(0xFF00D2FF, Palettes.clean(Accent.CYAN).accent)
        assertEquals(0xFFFF6B00, Palettes.clean(Accent.ORANGE).accent)
        assertEquals(0xFFF4F5F7, Palettes.daylight.background)
        assertEquals(0xFF111113, Palettes.daylight.text)
    }

    @Test fun autoPicksByNight() {
        assertEquals(Palettes.trueBlack, Palettes.of(ThemeOption.AUTO, Accent.CYAN, ThemeOption.DAYLIGHT, night = true))
        assertEquals(Palettes.daylight, Palettes.of(ThemeOption.AUTO, Accent.CYAN, ThemeOption.DAYLIGHT, night = false))
        assertEquals(Palettes.clean(), Palettes.of(ThemeOption.AUTO, Accent.CYAN, ThemeOption.CLEAN, night = false))
        assertEquals(Palettes.daylight, Palettes.of(ThemeOption.DAYLIGHT, Accent.CYAN, ThemeOption.CLEAN, night = true))
    }

    @Test fun highlightIsVisibleOnEveryTheme() = all.forEach { p ->
        assertTrue(p.name, Palettes.contrast(p.highlight, p.surface) >= 3.0)
    }
}

class DayNightTest {
    private val sun = 6 * 60 + 10 to 18 * 60 + 5

    @Test fun headlightsWin() {
        assertTrue(DayNight.isNight(true, false, 5000f, false, 12 * 60, sun))
        assertFalse(DayNight.isNight(false, true, 1f, true, 23 * 60, sun))
    }

    @Test fun systemNightThenSensor() {
        assertTrue(DayNight.isNight(null, true, 5000f, false, 12 * 60, sun))
        assertTrue(DayNight.isNight(null, false, 3f, false, 12 * 60, sun))
        assertFalse(DayNight.isNight(null, false, 3000f, false, 23 * 60, sun))
    }

    @Test fun sensorHasHysteresis() {
        assertFalse(DayNight.isNight(null, false, 25f, wasNight = false, nowMin = 0, sun = sun))
        assertTrue(DayNight.isNight(null, false, 25f, wasNight = true, nowMin = 0, sun = sun))
    }

    @Test fun sunsetToSunriseWithoutSensors() {
        assertTrue(DayNight.isNight(null, false, null, false, 19 * 60, sun))
        assertTrue(DayNight.isNight(null, false, null, false, 5 * 60, sun))
        assertFalse(DayNight.isNight(null, false, null, false, 12 * 60, sun))
    }

    @Test fun abuDhabiSunTimes() {
        // 30 October (UTC+4): sunrise ~06:20, sunset ~17:40; the formula is good to about ±10 min.
        val (rise, set) = DayNight.sunTimes(303, DayNight.DEFAULT_LAT, DayNight.DEFAULT_LON, 240)!!
        assertTrue("rise $rise", rise in 6 * 60 + 10..6 * 60 + 32)
        assertTrue("set $set", set in 17 * 60 + 28..17 * 60 + 50)
    }
}

class CarFormatTest {
    private fun msg(id: String, at: Long) = Msg(id, Msg.PLACE, "C", Place(1.0, 2.0, id), at)

    @Test fun activeIsNewestUnlessDismissedOrOld() {
        val now = 10_000_000L
        val inbox = listOf(msg("a", now - 60_000), msg("b", now - 120_000))
        assertEquals("a", CarFormat.pickActive(inbox, null, now)?.id)
        assertNull(CarFormat.pickActive(inbox, "a", now))
        assertNull(CarFormat.pickActive(listOf(msg("old", now - CarFormat.ACTIVE_MS - 1)), null, now))
        assertNull(CarFormat.pickActive(emptyList(), null, now))
    }

    @Test fun distances() {
        assertEquals("120 m", CarFormat.distance(118f))
        assertEquals("1.5 km", CarFormat.distance(1500f))
        assertEquals("42 km", CarFormat.distance(41_700f))
    }

    @Test fun compass() {
        assertEquals("N", CarFormat.compass(0f))
        assertEquals("NE", CarFormat.compass(40f))
        assertEquals("S", CarFormat.compass(-180f))
        assertEquals("NW", CarFormat.compass(-30f))
        assertEquals("N", CarFormat.compass(350f))
    }

    @Test fun ago() {
        assertEquals("just now", CarFormat.ago(0, 10_000))
        assertEquals("3 min ago", CarFormat.ago(0, 180_000))
        assertEquals("2 h ago", CarFormat.ago(0, 2 * 3_600_000L + 1))
    }
}
