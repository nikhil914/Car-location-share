package com.nik.sendtocar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Map apps the car can hand a place to. [packages] are tried in order to tell if one is installed. */
enum class NavEngine(val label: String, val packages: List<String>) {
    WAZE("Waze", listOf("com.waze")),
    GOOGLE_MAPS("Google Maps", listOf(MAPS_PACKAGE)),
    OSMAND("OsmAnd", listOf("net.osmand.plus", "net.osmand")),
    SYGIC("Sygic", listOf("com.sygic.aura")),
    ASK("Ask every time", emptyList()),
}

/** Starts navigation in the preferred map app, then Waze, then any app that opens geo: links. */
object Nav {
    fun installedPackage(ctx: Context, e: NavEngine): String? = e.packages.firstOrNull { p ->
        try { ctx.packageManager.getPackageInfo(p, 0); true } catch (ex: Exception) { false }
    }

    fun isAvailable(ctx: Context, e: NavEngine) = e == NavEngine.ASK || installedPackage(ctx, e) != null

    /** The label for the main button, e.g. "Waze". */
    fun label(ctx: Context): String = Prefs(ctx).navEngine.let { e ->
        if (e == NavEngine.ASK) "map app" else if (isAvailable(ctx, e)) e.label else NavEngine.WAZE.label
    }

    fun navigate(ctx: Context, p: Place): String {
        val e = Prefs(ctx).navEngine
        if (e == NavEngine.ASK) {
            try { ctx.startActivity(Waze.otherAppIntent(p)); ProbeLog.i("Opened the map app chooser"); return "chooser" }
            catch (ex: Exception) { ProbeLog.i("No map app chooser: $ex") }
        }
        if (e != NavEngine.WAZE && e != NavEngine.ASK) {
            val pkg = installedPackage(ctx, e)
            if (pkg != null) for (uri in uris(e, p)) {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ProbeLog.i("Opened ${e.label}: $uri")
                    return e.label
                } catch (ex: ActivityNotFoundException) { }
            }
            ProbeLog.i("${e.label} could not navigate, using Waze")
        }
        return Waze.navigate(ctx, p)
    }

    /** Navigation links for each app, best first; geo: (map at the place) is the last resort. */
    private fun uris(e: NavEngine, p: Place): List<String> {
        val text = Uri.encode(p.query ?: p.name)
        val geo = Waze.geoUri(p).toString()
        return when (e) {
            NavEngine.GOOGLE_MAPS -> listOf(if (p.hasCoords) "google.navigation:q=${p.lat},${p.lon}" else "google.navigation:q=$text", geo)
            NavEngine.OSMAND -> listOfNotNull(
                if (p.hasCoords) "osmand.api://navigate?dest_lat=${p.lat}&dest_lon=${p.lon}&dest_name=${Uri.encode(p.label())}&profile=car&force=true" else null,
                geo,
            )
            NavEngine.SYGIC -> listOf(if (p.hasCoords) "com.sygic.aura://coordinate|${p.lon}|${p.lat}|drive" else "com.sygic.aura://search|$text", geo)
            else -> listOf(geo)
        }
    }
}
