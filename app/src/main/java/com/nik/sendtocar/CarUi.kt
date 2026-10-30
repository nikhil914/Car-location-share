package com.nik.sendtocar

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

enum class CarSurface { DASHBOARD, HISTORY, SETTINGS }

/** Things only the activity can do (dialogs, permission screens, the role switch). */
class CarActions(
    val testPopup: () -> Unit,
    val readBluetoothFile: () -> String,
    val showReportQr: () -> Unit,
    val sendReport: ((String) -> Unit) -> Unit,
    val openOverlaySettings: () -> Unit,
    val switchRole: () -> Unit,
)

/**
 * The car app: one activity, three surfaces. The dashboard is what you see while driving;
 * History and Settings are one tap away and Back returns to the dashboard.
 */
@Composable
fun CarApp(model: CarModel, actions: CarActions) {
    var surface by rememberSaveable { mutableStateOf(CarSurface.DASHBOARD) }
    var settingsTab by rememberSaveable { mutableStateOf(SettingsTab.DISPLAY) }
    LaunchedEffect(Unit) { while (true) { model.refresh(); delay(1500) } }
    BackHandler(surface != CarSurface.DASHBOARD) { surface = CarSurface.DASHBOARD }
    val view = LocalView.current
    SideEffect {
        view.keepScreenOn = model.state.keepAwake
        // The unit's status bar matches the theme instead of the stock grey.
        (view.context as? Activity)?.window?.statusBarColor = model.palette.background.toInt()
    }

    CarTheme(model.palette) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (surface) {
                CarSurface.DASHBOARD -> DashboardScreen(
                    model,
                    onHistory = { surface = CarSurface.HISTORY },
                    onSettings = { tab -> settingsTab = tab; surface = CarSurface.SETTINGS },
                )
                CarSurface.HISTORY -> HistoryScreen(model) { surface = CarSurface.DASHBOARD }
                CarSurface.SETTINGS -> SettingsScreen(model, actions, settingsTab, { settingsTab = it }) { surface = CarSurface.DASHBOARD }
            }
        }
    }
}

// ---- Surface A: the dashboard

@Composable
fun DashboardScreen(model: CarModel, onHistory: () -> Unit, onSettings: (SettingsTab) -> Unit) {
    val s = model.state
    var debug by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
        // Ultra-wide strips (> 2:1) get the recents as a side rail; 16:9 and 4:3 get a bottom bar.
        val wide = maxWidth / maxHeight > 2f
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(s.connectionStatus, model.now, debug, Modifier.weight(1f)) { debug = !debug }
                Spacer(Modifier.width(Touch.gap))
                SquareIconButton(CarIcons.History, "History", onHistory)
                Spacer(Modifier.width(Touch.gap))
                SquareIconButton(CarIcons.Tune, "Settings") { onSettings(SettingsTab.DISPLAY) }
            }
            AnimatedVisibility(debug) { DebugStrip(s.connectionStatus) }
            Spacer(Modifier.height(Touch.gap))
            if (wide) Row(Modifier.weight(1f)) {
                LocationCard(model, Modifier.weight(1f).fillMaxHeight()) { onSettings(SettingsTab.PAIRING) }
                if (s.recentLocations.isNotEmpty()) {
                    Spacer(Modifier.width(Touch.gap))
                    RecentsRail(s.recentLocations, vertical = true, Modifier.width(320.dp).fillMaxHeight()) { model.navigate(it.place) }
                }
            } else {
                LocationCard(model, Modifier.weight(1f).fillMaxWidth()) { onSettings(SettingsTab.PAIRING) }
                if (s.recentLocations.isNotEmpty()) {
                    Spacer(Modifier.height(Touch.gap))
                    RecentsRail(s.recentLocations, vertical = false, Modifier.fillMaxWidth()) { model.navigate(it.place) }
                }
            }
        }
    }
}

/** Glanceable receiver state: one dot and one sentence, channel details on tap. */
@Composable
fun StatusPill(status: ConnectionStatus, now: Long, expanded: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val pal = LocalPalette.current
    val (dot, headline) = when (status) {
        ConnectionStatus.Disconnected -> pal.bad to "Receiver stopped, restarting…"
        is ConnectionStatus.Listening -> if (status.anyUp) pal.good to "Ready for your phone" else pal.warn to "Waiting for a network"
    }
    Surface(
        onClick = onToggle, modifier = modifier.heightIn(min = Touch.secondary), shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CarIcons.PhoneLink, null, Modifier.size(28.dp), tint = dot.c())
            Spacer(Modifier.width(12.dp))
            Text(headline, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status is ConnectionStatus.Listening) {
                for (ch in status.channels) if (ch.enabled) {
                    Spacer(Modifier.width(18.dp))
                    ChannelBadge(ch)
                }
                Spacer(Modifier.weight(1f))
                if (status.lastContactAt > 0) Text(
                    "Phone ${CarFormat.ago(status.lastContactAt, now)}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 12.dp),
                )
            } else Spacer(Modifier.weight(1f))
            Icon(if (expanded) CarIcons.ExpandLess else CarIcons.ExpandMore, if (expanded) "Hide details" else "Show details",
                Modifier.padding(start = 8.dp).size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun ChannelKind.icon(): ImageVector = when (this) {
    ChannelKind.WIFI -> CarIcons.Wifi
    ChannelKind.BLUETOOTH -> CarIcons.Bluetooth
    ChannelKind.INTERNET -> CarIcons.Cloud
}

@Composable
private fun ChannelBadge(ch: ChannelStatus) {
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(ch.kind.icon(), ch.kind.label, Modifier.size(22.dp), tint = (if (ch.up) pal.good else pal.muted).c())
        // Only Wi-Fi's detail (the IP) fits the pill; the rest show in the debug strip.
        if (ch.kind == ChannelKind.WIFI && ch.up) Text(
            ch.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun DebugStrip(status: ConnectionStatus) {
    Surface(
        Modifier.fillMaxWidth().padding(top = 12.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (status !is ConnectionStatus.Listening) Text("The receiver service is not running. It restarts within 15 minutes, or open Settings.")
            else {
                for (ch in status.channels) Text(
                    "${ch.kind.label}: ${ch.detail}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Port ${Wire.PORT}" + if (status.lastContactVia.isNotBlank()) ", last message via ${status.lastContactVia}" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The hero card: standby radar, or the place that just arrived with one big button. */
@Composable
fun LocationCard(model: CarModel, modifier: Modifier = Modifier, onPair: () -> Unit) {
    val s = model.state
    Surface(
        modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        val p = s.activePayload
        if (p == null) IdleCard(s.needsPairing, onPair)
        else Column(Modifier.fillMaxSize().padding(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CarIcons.Place, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    "FROM YOUR PHONE · ${CarFormat.ago(p.sentAt, model.now).uppercase()}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(p.place.label(), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                if (p.place.hasCoords) CarFormat.coords(p.place) else "Address search: ${p.place.query ?: p.place.name}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            DistanceLine(p.place, model.here)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Start navigation", "in ${s.navLabel}", CarIcons.NearMe, Modifier.fillMaxWidth()) { model.navigate(p.place) }
            Spacer(Modifier.height(Touch.gap))
            QuickActionsRow(model, p)
        }
    }
}

@Composable
private fun DistanceLine(place: Place, here: android.location.Location?) {
    if (here == null || !place.hasCoords) return
    val r = FloatArray(2)
    android.location.Location.distanceBetween(here.latitude, here.longitude, place.lat!!, place.lon!!, r)
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CarIcons.Navigation, null, Modifier.size(24.dp).rotate(r[1]), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            "${CarFormat.distance(r[0])} ${CarFormat.compass(r[1])} as the crow flies",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Copy coordinates, save to favourites, dismiss. */
@Composable
fun QuickActionsRow(model: CarModel, p: LocationPayload) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(p.id) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val fav = model.isFavorite(p)
    Row(horizontalArrangement = Arrangement.spacedBy(Touch.gap)) {
        SecondaryButton(
            if (copied) "Copied" else if (p.place.hasCoords) "Copy coords" else "Copy address",
            if (copied) CarIcons.Check else CarIcons.Copy, Modifier.weight(1f),
        ) { clipboard.setText(AnnotatedString(CarFormat.coords(p.place))); copied = true }
        SecondaryButton(if (fav) "Saved" else "Save", if (fav) CarIcons.Star else CarIcons.StarOutline, Modifier.weight(1f)) {
            model.toggleFavorite(p)
        }
        SecondaryButton("Dismiss", CarIcons.Close, Modifier.weight(1f)) { model.dismiss(p) }
    }
}

@Composable
private fun IdleCard(needsPairing: Boolean, onPair: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        RadarPulse(Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(CarIcons.Place, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("Ready for a destination from your phone", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "On the phone: Share a place → Send to car", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            if (needsPairing) {
                Spacer(Modifier.height(24.dp))
                SecondaryButton("Pair a phone", CarIcons.Qr, Modifier.widthIn(min = 260.dp), onClick = onPair)
            }
        }
    }
}

/** Slow concentric rings: the only motion on the dashboard, quiet enough to ignore while driving. */
@Composable
private fun RadarPulse(modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.outline
    val t by rememberInfiniteTransition(label = "radar").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "radar",
    )
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = minOf(size.width, size.height) * 0.48f
        for (i in 0 until 3) {
            val f = (t + i / 3f) % 1f
            drawCircle(ring.copy(alpha = (1f - f) * 0.9f), radius = maxR * (0.25f + 0.75f * f), center = c, style = Stroke(2.dp.toPx()))
        }
    }
}

/** Last places, one tap to drive there again. Never scrolls: it shows what fits. */
@Composable
fun RecentsRail(items: List<LocationPayload>, vertical: Boolean, modifier: Modifier = Modifier, onPick: (LocationPayload) -> Unit) {
    if (vertical) Column(modifier, verticalArrangement = Arrangement.spacedBy(Touch.gap)) {
        Text("RECENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (p in items) RecentChip(p, Modifier.fillMaxWidth()) { onPick(p) }
    } else Row(modifier, horizontalArrangement = Arrangement.spacedBy(Touch.gap)) {
        for (p in items.take(4)) RecentChip(p, Modifier.weight(1f)) { onPick(p) }
    }
}

@Composable
private fun RecentChip(p: LocationPayload, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.height(Touch.secondary), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CarIcons.NearMe, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(p.place.label(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Shared controls (all at least 56 dp; see Touch)

@Composable
fun PrimaryButton(text: String, sub: String?, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick, modifier.height(Touch.primary), shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        contentPadding = PaddingValues(horizontal = 28.dp),
    ) {
        Icon(icon, null, Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
        if (sub != null) Text("  $sub", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, height: Dp = Touch.secondary, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.height(height), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(26.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SquareIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = Modifier.size(Touch.secondary), shape = CircleShape,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(30.dp)) }
    }
}
