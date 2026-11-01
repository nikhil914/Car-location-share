package com.nik.sendtocar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Live state of the phone home screen; the activity writes it, Compose reads it. */
class PhoneState {
    /** Bumped every 2 s and on log lines, so status and log re-read. */
    var tick by mutableLongStateOf(0L)
    var sendTitle by mutableStateOf("")
    /** Channel → status text, in Wi-Fi, Bluetooth, Internet order. */
    var status by mutableStateOf<Map<String, String>>(emptyMap())
}

class PhoneActions(
    val scanQr: () -> Unit,
    val saveCode: (String) -> Unit,
    val sendCopied: () -> Unit,
    val pickBluetooth: () -> Unit,
    val testBluetooth: () -> Unit,
    val mapsLinks: () -> Unit,
    val ourLinks: () -> Unit,
    val ping: () -> Unit,
    val testPlace: () -> Unit,
    val carReport: (done: () -> Unit) -> Unit,
    val phoneReport: () -> Unit,
    val switchRole: () -> Unit,
)

/** The phone follows the system: Clean when it's in dark mode, Daylight otherwise. */
@Composable
private fun PhoneTheme(content: @Composable () -> Unit) =
    CarTheme(if (isSystemInDarkTheme()) Palettes.clean() else Palettes.daylight, phone = true, content = content)

// ---- Home

@Composable
fun PhoneApp(state: PhoneState, actions: PhoneActions) = PhoneTheme {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = state.tick // re-read prefs and the log on every refresh
    val prefs = Prefs(ctx)
    val paired = prefs.phoneCode.length == 8
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Send to Car", style = MaterialTheme.typography.headlineSmall)
                Text("Phone · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PairBadge(paired, prefs.phoneCode)
        }

        if (!paired) PairCard(prefs, actions)
        else {
            SendCard(actions)
            if (state.sendTitle.isNotBlank() || state.status.isNotEmpty()) DeliveryCard(state.sendTitle, state.status)
            BluetoothCard(prefs, actions)
            LinksCard(actions)
            Card {
                CardTitle("Pairing", CarIcons.Qr)
                Hint("Paired with car ${Prefs.pretty(prefs.phoneCode)}. Scan again if the car shows a new code.")
                Spacer(Modifier.height(12.dp))
                GhostButton("Scan the car's QR again", CarIcons.Qr, Modifier.fillMaxWidth(), actions.scanQr)
            }
        }
        DiagnosticsCard(state, actions, paired)
        TextButton(actions.switchRole, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            Text("This is the car, not my phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PairBadge(paired: Boolean, code: String) {
    val pal = LocalPalette.current
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background((if (paired) pal.good else pal.warn).c(), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(if (paired) Prefs.pretty(code) else "Not paired", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PairCard(prefs: Prefs, actions: PhoneActions) = Card {
    CardTitle("Pair with your car", CarIcons.Qr)
    Hint("On the car, open Send to Car → Settings → Pairing & debug, then scan its QR code.")
    Spacer(Modifier.height(16.dp))
    AccentButton("Scan the car's QR code", CarIcons.Qr, Modifier.fillMaxWidth(), actions.scanQr)
    Spacer(Modifier.height(20.dp))
    Hint("Or type the 8-character code shown under the QR:")
    Spacer(Modifier.height(8.dp))
    var code by rememberSaveable { mutableStateOf(Prefs.pretty(prefs.phoneCode)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            code, { code = it.uppercase() }, Modifier.weight(1f), singleLine = true,
            placeholder = { Text("ABCD-EFGH") }, textStyle = MaterialTheme.typography.titleMedium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalPalette.current.highlight.c()),
        )
        Spacer(Modifier.width(12.dp))
        GhostButton("Save", null, Modifier.height(56.dp), onClick = { actions.saveCode(code) })
    }
}

@Composable
private fun SendCard(actions: PhoneActions) = Card {
    CardTitle("Send a place", CarIcons.NearMe)
    Hint("In Google Maps, WhatsApp or Waze tap Share → Send to car. Or copy a link or address and:")
    Spacer(Modifier.height(16.dp))
    AccentButton("Send the copied link", CarIcons.Copy, Modifier.fillMaxWidth(), actions.sendCopied)
}

/** One row per channel: green when the car confirmed, grey while trying, amber for a problem. */
@Composable
fun DeliveryRows(status: Map<String, String>) {
    val pal = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((ch, s) in status) {
            val color = when {
                s.startsWith("DELIVERED") -> pal.good
                s == "…" || s.endsWith("…") -> pal.muted
                else -> pal.warn
            }
            val icon = when (ch) { Sender.WIFI -> CarIcons.Wifi; Sender.BT -> CarIcons.Bluetooth; else -> CarIcons.Cloud }
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, ch, Modifier.size(22.dp), tint = color.c())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (s == "…") "trying…" else s.replace("DELIVERED", "Delivered"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeliveryCard(title: String, status: Map<String, String>) = Card {
    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium)
    if (status.isNotEmpty()) { Spacer(Modifier.height(12.dp)); DeliveryRows(status) }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothCard(prefs: Prefs, actions: PhoneActions) = Card {
    CardTitle("Car's Bluetooth", CarIcons.Bluetooth)
    val mac = prefs.lastBt
    @Suppress("DEPRECATION")
    val name = mac?.let { m -> (try { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(m)?.name } catch (e: Exception) { null }) ?: m }
    Text(name ?: "Not chosen yet", style = MaterialTheme.typography.titleMedium)
    Hint("Bluetooth sends only to this device. Pick the name the car shows under Settings → Receiver.")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GhostButton(if (mac == null) "Choose" else "Change", null, Modifier.weight(1f), actions.pickBluetooth)
        GhostButton("Test", null, Modifier.weight(1f), actions.testBluetooth, enabled = mac != null)
    }
}

@Composable
private fun LinksCard(actions: PhoneActions) = Card {
    CardTitle("Tapped map links", CarIcons.Map)
    Hint("Map links you tap open in Google Maps. To send them to the car instead:")
    Spacer(Modifier.height(12.dp))
    Step(1, "Google Maps → Open by default → turn off \"Open supported links\"", actions.mapsLinks)
    Spacer(Modifier.height(10.dp))
    Step(2, "Send to Car → Open by default → Add links, tick them all", actions.ourLinks)
}

@Composable
private fun DiagnosticsCard(state: PhoneState, actions: PhoneActions, paired: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    Card {
        Surface(onClick = { open = !open }, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CarIcons.Info, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("Tests and diagnostics", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (open) CarIcons.ExpandLess else CarIcons.ExpandMore, if (open) "Hide" else "Show")
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (paired) {
                    GhostButton("Ping the car on every channel", null, Modifier.fillMaxWidth(), actions.ping)
                    GhostButton("Send a test place", null, Modifier.fillMaxWidth(), actions.testPlace)
                    var fetching by remember { mutableStateOf(false) }
                    GhostButton(if (fetching) "Fetching…" else "Get the car's report (internet)", null, Modifier.fillMaxWidth(), onClick = {
                        fetching = true; actions.carReport { fetching = false }
                    })
                }
                GhostButton("Share this phone's report", null, Modifier.fillMaxWidth(), actions.phoneReport)
                @Suppress("UNUSED_VARIABLE") val tick = state.tick
                Text(
                    Report.status(ctx).joinToString("\n"), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Log", style = MaterialTheme.typography.titleSmall)
                Text(
                    ProbeLog.snapshot().takeLast(40).reversed().joinToString("\n"), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing, fontFamily = MaterialTheme.typography.labelMedium.fontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- First run

@Composable
fun RoleChooser(onCar: () -> Unit, onPhone: () -> Unit) = PhoneTheme {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(CarIcons.PhoneLink, null, Modifier.size(56.dp), tint = LocalPalette.current.highlight.c())
        Spacer(Modifier.height(16.dp))
        Text("Send to Car", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Install this same app on your phone and on the car's head unit. What is this device?",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentButton("My phone", CarIcons.PhoneLink, Modifier.fillMaxWidth(), onPhone)
            GhostButton("The car (head unit)", CarIcons.NearMe, Modifier.fillMaxWidth().height(56.dp), onCar)
        }
    }
}

// ---- Share → Send to car

@Composable
fun SendSheet(
    title: String, status: Map<String, String>, notPaired: Boolean, delivered: Boolean,
    onClose: () -> Unit, onOpenApp: () -> Unit, onOpenInMaps: (() -> Unit)?,
) = PhoneTheme {
    Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(24.dp).widthIn(min = 280.dp)) {
        Text(
            if (delivered) "SENT TO YOUR CAR" else if (notPaired) "SEND TO CAR" else "SENDING TO YOUR CAR",
            style = MaterialTheme.typography.labelSmall,
            color = if (delivered) LocalPalette.current.good.c() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (status.isNotEmpty()) { Spacer(Modifier.height(16.dp)); DeliveryRows(status) }
        Spacer(Modifier.height(20.dp))
        if (notPaired) AccentButton("Open Send to Car", CarIcons.Qr, Modifier.fillMaxWidth(), onOpenApp)
        if (onOpenInMaps != null) {
            GhostButton("Open in Google Maps instead", CarIcons.Map, Modifier.fillMaxWidth(), onOpenInMaps)
            Spacer(Modifier.height(10.dp))
        }
        GhostButton(if (delivered) "Done" else "Close", null, Modifier.fillMaxWidth(), onClose)
    }
}

// ---- Building blocks

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Column(Modifier.padding(20.dp), content = content) }
}

@Composable
private fun CardTitle(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Step(n: Int, text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(LocalPalette.current.highlight.c(), CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.surface)
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AccentButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick, modifier.height(56.dp), shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun GhostButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}
