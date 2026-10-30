package com.nik.sendtocar

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.TimeZone

/** Which palette the car screens use right now, including the Auto theme's day/night decision. */
object CarLook {
    /** Latest ambient light reading while a car screen is open; null without a light sensor. */
    @Volatile var lux: Float? = null
    @Volatile private var wasNight = false
    @Volatile var reason = ""; private set

    fun isNight(ctx: Context): Boolean {
        val systemNight = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cal = Calendar.getInstance()
        val here = CarLocation.last
        val sun = DayNight.sunTimes(
            cal.get(Calendar.DAY_OF_YEAR), here?.latitude ?: DayNight.DEFAULT_LAT, here?.longitude ?: DayNight.DEFAULT_LON,
            TimeZone.getDefault().getOffset(cal.timeInMillis) / 60_000,
        )
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val lights = CarSignals.headlights
        val night = DayNight.isNight(lights, systemNight, lux, wasNight, now, sun)
        wasNight = night
        reason = when {
            lights != null -> "headlights ${if (lights) "on" else "off"}"
            systemNight -> "system night mode"
            lux != null -> "light sensor, ${lux?.toInt()} lux"
            else -> "sunset to sunrise" + (sun?.let { (r, s) -> " (%d:%02d to %d:%02d)".format(s / 60, s % 60, r / 60, r % 60) } ?: "")
        }
        return night
    }

    fun palette(ctx: Context): Palette {
        val p = Prefs(ctx)
        return Palettes.of(p.theme, p.accent, p.autoDay, p.theme == ThemeOption.AUTO && isNight(ctx))
    }
}

val LocalPalette = staticCompositionLocalOf { Palettes.clean() }

fun Long.c() = Color(this)

/**
 * Sizes for a dashboard seen at arm's length: everything is a step or two above phone sizes.
 * Coordinates use [labelMedium], which is monospace.
 */
private val CarType = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp),
    bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontFamily = FontFamily.Monospace),
    labelSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
)

private val CarShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp),
)

/** Touch sizes: 56 dp is the floor, primary actions are bigger to survive a bumpy road. */
object Touch {
    val min = 56.dp
    val secondary = 64.dp
    val primary = 76.dp
    val gap = 16.dp
}

/**
 * Applies a [Palette] as the Material colour scheme. Flat by design: no gradients, no shadows,
 * hierarchy comes from type size and one accent colour.
 */
@Composable
fun CarTheme(palette: Palette, content: @Composable () -> Unit) {
    val scheme = if (palette.dark) darkColorScheme(
        primary = palette.accent.c(), onPrimary = palette.onAccent.c(),
        background = palette.background.c(), onBackground = palette.text.c(),
        surface = palette.surface.c(), onSurface = palette.text.c(),
        surfaceVariant = palette.raised.c(), onSurfaceVariant = palette.muted.c(),
        outline = palette.border.c(), outlineVariant = palette.border.c(),
        secondaryContainer = palette.raised.c(), onSecondaryContainer = palette.text.c(),
        error = palette.bad.c(),
    ) else lightColorScheme(
        primary = palette.accent.c(), onPrimary = palette.onAccent.c(),
        background = palette.background.c(), onBackground = palette.text.c(),
        surface = palette.surface.c(), onSurface = palette.text.c(),
        surfaceVariant = palette.raised.c(), onSurfaceVariant = palette.muted.c(),
        outline = palette.border.c(), outlineVariant = palette.border.c(),
        secondaryContainer = palette.raised.c(), onSecondaryContainer = palette.text.c(),
        error = palette.bad.c(),
    )
    MaterialTheme(colorScheme = scheme, typography = CarType, shapes = CarShapes) {
        CompositionLocalProvider(LocalPalette provides palette, LocalContentColor provides palette.text.c()) { content() }
    }
}
