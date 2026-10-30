package com.nik.sendtocar

import kotlin.math.pow

/**
 * Car-screen themes. Colours are plain ARGB so the contrast rules are unit-tested on the JVM
 * and the plain-View overlay banner can use them too.
 *
 * Adding a theme: add a [Palette] here, a [ThemeOption] entry that returns it from [Palettes.of],
 * and run PaletteTest (primary text and the main button must reach 7:1, muted text 4.5:1).
 */
data class Palette(
    val name: String,
    val dark: Boolean,
    val background: Long,
    val surface: Long,
    /** Cards and buttons that sit on [surface]. */
    val raised: Long,
    val border: Long,
    val text: Long,
    val muted: Long,
    /** Only the one primary action (Start navigation) uses the accent. */
    val accent: Long,
    val onAccent: Long,
    val good: Long,
    val warn: Long,
    val bad: Long,
) {
    /** Colour for "selected" outlines: the accent, unless it is a dark fill (true black), then its text colour. */
    val highlight: Long get() = if (Palettes.contrast(accent, surface) >= 3.0) accent else onAccent
}

enum class ThemeOption(val label: String, val hint: String) {
    CLEAN("Clean", "Charcoal, one accent. Recommended"),
    TRUE_BLACK("True black", "OLED black, dim amber. For night driving"),
    DAYLIGHT("Daylight", "Light and high contrast for bright sun"),
    AUTO("Auto", "True black with the headlights on, day look otherwise"),
}

enum class Accent(val label: String) { CYAN("Cyan"), ORANGE("Orange") }

object Palettes {
    fun clean(accent: Accent = Accent.CYAN) = Palette(
        name = "Clean", dark = true,
        background = 0xFF121316, surface = 0xFF181A1E, raised = 0xFF202227, border = 0xFF26282E,
        text = 0xFFEDEDED, muted = 0xFFA4A7AE,
        accent = if (accent == Accent.CYAN) 0xFF00D2FF else 0xFFFF6B00,
        onAccent = if (accent == Accent.CYAN) 0xFF00161C else 0xFF000000,
        good = 0xFF4ADE80, warn = 0xFFFFB020, bad = 0xFFFF6B6B,
    )

    /** Low-luminance text and a dim amber button keep night vision intact. */
    val trueBlack = Palette(
        name = "True black", dark = true,
        background = 0xFF000000, surface = 0xFF0A0A0C, raised = 0xFF141417, border = 0xFF1E1E22,
        text = 0xFFA9A9AF, muted = 0xFF8E8E93,
        accent = 0xFF2A1A00, onAccent = 0xFFE8A33D,
        good = 0xFF5E8F6A, warn = 0xFFB07A2A, bad = 0xFFA34848,
    )

    /** Cobalt is #0047B3 rather than #0052CC: white on #0052CC is 6.8:1, just under 7. */
    val daylight = Palette(
        name = "Daylight", dark = false,
        background = 0xFFF4F5F7, surface = 0xFFFFFFFF, raised = 0xFFEBEDF0, border = 0xFFD3D7DE,
        text = 0xFF111113, muted = 0xFF4A4F59,
        accent = 0xFF0047B3, onAccent = 0xFFFFFFFF,
        good = 0xFF0F7A3A, warn = 0xFF8A5A00, bad = 0xFFB3261E,
    )

    /** [night] only matters for [ThemeOption.AUTO]. */
    fun of(option: ThemeOption, accent: Accent, autoDay: ThemeOption, night: Boolean): Palette = when (option) {
        ThemeOption.CLEAN -> clean(accent)
        ThemeOption.TRUE_BLACK -> trueBlack
        ThemeOption.DAYLIGHT -> daylight
        ThemeOption.AUTO -> if (night) trueBlack else if (autoDay == ThemeOption.DAYLIGHT) daylight else clean(accent)
    }

    /** WCAG contrast ratio of two opaque colours. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Long): Double {
        fun ch(v: Long): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
}
