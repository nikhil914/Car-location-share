package com.nik.sendtocar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

/** A received place as the car screens show it. */
data class LocationPayload(val id: String, val place: Place, val sentAt: Long) {
    val msg get() = Msg(id, Msg.PLACE, "", place, sentAt)

    companion object {
        fun of(m: Msg): LocationPayload? = m.place?.let { LocationPayload(m.id, it, m.sentAt) }
    }
}

enum class ChannelKind(val label: String) { WIFI("Wi-Fi"), BLUETOOTH("Bluetooth"), INTERNET("Internet") }

/** One of the car's three listeners, straight from [CarService]'s live state. */
data class ChannelStatus(val kind: ChannelKind, val detail: String, val up: Boolean, val enabled: Boolean)

sealed class ConnectionStatus {
    /** The receiver service isn't running (it restarts itself within 15 min). */
    object Disconnected : ConnectionStatus()
    data class Listening(val channels: List<ChannelStatus>, val lastContactAt: Long, val lastContactVia: String) : ConnectionStatus() {
        val anyUp get() = channels.any { it.up }
    }
}

data class LocationReceiverUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val activePayload: LocationPayload? = null,
    val recentLocations: List<LocationPayload> = emptyList(),
    val favorites: List<LocationPayload> = emptyList(),
    val autoLaunchNavigation: Boolean = true,
    val countdown: Int = 5,
    val currentTheme: ThemeOption = ThemeOption.CLEAN,
    val navEngine: NavEngine = NavEngine.WAZE,
    /** What the main button says it opens, e.g. "Waze". */
    val navLabel: String = "Waze",
    val keepAwake: Boolean = false,
    /** Never heard from a phone and nothing in the inbox: offer pairing on the standby card. */
    val needsPairing: Boolean = false,
)

/** Pure helpers behind the state, unit-tested. */
object CarFormat {
    /** The dashboard hero shows the newest place for this long, unless dismissed. */
    const val ACTIVE_MS = 2 * 60 * 60_000L

    fun pickActive(inbox: List<Msg>, dismissedId: String?, now: Long): Msg? =
        inbox.firstOrNull()?.takeIf { it.id != dismissedId && it.place != null && now - it.sentAt in -ACTIVE_MS..ACTIVE_MS }

    fun distance(meters: Float): String = when {
        meters < 950 -> "${(meters / 10).roundToInt() * 10} m"
        meters < 9_950 -> "%.1f km".format(meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }

    fun compass(bearing: Float): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((bearing % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
    }

    fun ago(then: Long, now: Long): String {
        val s = (now - then) / 1000
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    fun coords(p: Place): String = if (p.hasCoords) "%.5f, %.5f".format(p.lat, p.lon) else p.query ?: p.name
}

/** The car's own position, for distance and bearing, and for the Auto theme's sunset time. */
object CarLocation {
    @Volatile var last: Location? = null
}

/**
 * Holds [LocationReceiverUiState] for the car screens. The receiver runs in [CarService] on other
 * threads, so the screen re-reads its state on a short tick ([refresh]) rather than subscribing.
 * Also owns the GPS and light-sensor listeners while a car screen is visible.
 */
class CarModel(ctx: Context) {
    private val ctx = ctx.applicationContext
    private val prefs = Prefs(ctx)

    var state by mutableStateOf(LocationReceiverUiState()); private set
    var palette by mutableStateOf(CarLook.palette(ctx)); private set
    var here by mutableStateOf(CarLocation.last); private set
    var now by mutableStateOf(System.currentTimeMillis()); private set

    fun refresh() {
        now = System.currentTimeMillis()
        val inbox = prefs.inbox
        val active = CarFormat.pickActive(inbox, prefs.dismissedId, now)
        state = LocationReceiverUiState(
            connectionStatus = connection(),
            activePayload = active?.let(LocationPayload::of),
            recentLocations = inbox.filter { it.id != active?.id }.mapNotNull(LocationPayload::of).distinctBy { it.place.label() }.take(5),
            favorites = prefs.favorites.mapNotNull(LocationPayload::of),
            autoLaunchNavigation = prefs.autoGo,
            countdown = prefs.countdown,
            currentTheme = prefs.theme,
            navEngine = prefs.navEngine,
            navLabel = Nav.label(ctx),
            keepAwake = prefs.keepAwake,
            needsPairing = inbox.isEmpty() && Inbox.lastContactAt == 0L,
        )
        palette = CarLook.palette(ctx)
    }

    private fun connection(): ConnectionStatus {
        if (!CarService.running) return ConnectionStatus.Disconnected
        fun ch(kind: ChannelKind, raw: String, upWhen: (String) -> Boolean, detail: (String) -> String) =
            ChannelStatus(kind, if (raw == CarService.OFF) "off" else detail(raw), raw != CarService.OFF && upWhen(raw), raw != CarService.OFF)
        val ip = localIps().firstOrNull()
        return ConnectionStatus.Listening(
            listOf(
                ch(ChannelKind.WIFI, CarService.wifiState, { it.startsWith("listening") }) { s ->
                    if (s.startsWith("listening")) ip ?: "no network yet" else s
                },
                ch(ChannelKind.BLUETOOTH, CarService.btState, { it.startsWith("listening") }) { s ->
                    if (s.startsWith("listening")) "ready" else s
                },
                ch(ChannelKind.INTERNET, CarService.relayState, { it == "connected" }) { s -> if (s == "connected") "connected" else "offline" },
            ),
            Inbox.lastContactAt, Inbox.lastContactVia,
        )
    }

    // ---- Actions

    fun dismiss(p: LocationPayload) { prefs.dismissedId = p.id; refresh() }

    fun isFavorite(p: LocationPayload) = state.favorites.any { it.id == p.id || it.place.label() == p.place.label() }

    fun toggleFavorite(p: LocationPayload) {
        val favs = prefs.favorites
        prefs.favorites = if (favs.any { it.id == p.id || it.place?.label() == p.place.label() })
            favs.filterNot { it.id == p.id || it.place?.label() == p.place.label() }
        else listOf(p.msg) + favs
        refresh()
    }

    fun clearHistory() { prefs.inbox = emptyList(); ProbeLog.i("History cleared"); refresh() }

    fun navigate(p: Place) { Nav.navigate(ctx, p) }

    fun update(f: Prefs.() -> Unit) { prefs.f(); refresh() }

    /** Receiver channel toggles restart the service so the change applies at once. */
    fun setChannel(kind: ChannelKind, on: Boolean) {
        when (kind) {
            ChannelKind.WIFI -> prefs.wifiOn = on
            ChannelKind.BLUETOOTH -> prefs.btOn = on
            ChannelKind.INTERNET -> prefs.relayOn = on
        }
        CarService.restart(ctx)
        refresh()
    }

    // ---- Sensors while visible

    private val lm get() = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val sm get() = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var locating = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) { CarLocation.last = l; here = l }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { CarLook.lux = e.values.firstOrNull() }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    @SuppressLint("MissingPermission")
    fun resume() {
        refresh()
        sm?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm?.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (locating || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val m = lm ?: return
        try {
            val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { m.isProviderEnabled(it) }.mapNotNull { m.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (best != null) { CarLocation.last = best; here = best }
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.allProviders.contains(p)) m.requestLocationUpdates(p, 5_000L, 25f, locationListener)
            }
            locating = true
        } catch (e: Exception) { ProbeLog.i("Location unavailable: $e") }
    }

    fun pause() {
        try { sm?.unregisterListener(lightListener) } catch (e: Exception) { }
        CarLook.lux = null
        if (locating) try { lm?.removeUpdates(locationListener) } catch (e: Exception) { }
        locating = false
    }

    companion object {
        fun localIps(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }
        } catch (e: Exception) { emptyList() }
    }
}
