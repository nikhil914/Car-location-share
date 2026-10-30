package com.nik.sendtocar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The full-screen "Navigate to X?" card that opens over Waze when a place arrives. Navigates after
 * a countdown, or waits for a tap (a setting). Own task, so Not now drops you back where you were.
 */
class TakeoverActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var place by mutableStateOf(Place(null, null, ""))
    private var left by mutableIntStateOf(0)
    private var total by mutableIntStateOf(0)
    private var counting by mutableStateOf(false)
    private var done = false

    private val tick = object : Runnable {
        override fun run() {
            if (done) return
            if (left <= 0) { go(); return }
            left--
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!setup(intent)) return
        val palette = CarLook.palette(this)
        setContent { CarTheme(palette) { Card() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handler.removeCallbacks(tick)
        done = false
        setup(intent)
    }

    /** False when the place went straight to navigation (the notification's Navigate button). */
    private fun setup(i: Intent): Boolean {
        val lat = i.getDoubleExtra("lat", Double.NaN)
        val lon = i.getDoubleExtra("lon", Double.NaN)
        place = Place(lat.takeUnless { it.isNaN() }, lon.takeUnless { it.isNaN() }, i.getStringExtra("name").orEmpty(), i.getStringExtra("q"))
        val via = i.getStringExtra("via").orEmpty()
        Inbox.lastShown = "shown ${java.text.DateFormat.getTimeInstance().format(java.util.Date())} ($via)"
        Inbox.lastShownAt = System.currentTimeMillis()
        Overlay.dismiss(this)
        ProbeLog.i("Pop-up SHOWN on screen ($via)")
        Alerts.clear(this)
        if (i.getBooleanExtra(EXTRA_GO_NOW, false)) { go(); return false }

        val prefs = Prefs(this)
        counting = prefs.autoGo
        if (counting) { total = prefs.countdown; left = prefs.countdown; handler.post(tick) }
        return true
    }

    @androidx.compose.runtime.Composable
    private fun Card() {
        val nav = Nav.label(this)
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("NAVIGATE TO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(place.label(), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(
                if (place.hasCoords) CarFormat.coords(place) else "Address search", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                if (counting) {
                    Text(
                        if (left > 0) "Starting $nav in $left s" else "Starting $nav…", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (total == 0) 1f else 1f - left.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = LocalPalette.current.highlight.c(), trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else Text(
                    "Tap Start navigation to go", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(24.dp))
                PrimaryButton(if (counting) "Start now" else "Start navigation", "in $nav", CarIcons.NearMe, Modifier.fillMaxWidth()) { go() }
                Spacer(Modifier.height(Touch.gap))
                Row(horizontalArrangement = Arrangement.spacedBy(Touch.gap)) {
                    SecondaryButton("Other app", CarIcons.Map, Modifier.weight(1f)) {
                        stop()
                        try { startActivity(Waze.otherAppIntent(place)) } catch (e: Exception) { ProbeLog.i("No other map app: $e") }
                        finish()
                    }
                    SecondaryButton("Not now", CarIcons.Close, Modifier.weight(1f)) { stop(); ProbeLog.i("Pop-up cancelled"); finish() }
                }
            }
        }
    }

    private fun stop() { done = true; handler.removeCallbacks(tick) }

    private fun go() {
        if (done) return
        stop()
        Nav.navigate(this, place)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GO_NOW = "goNow"
    }
}
