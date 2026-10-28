package com.nik.sendtocar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Plain-View helpers: the probe needs no AndroidX, so it stays tiny and installs on any unit. */
private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun LinearLayout.label(s: String, size: Float = 16f, bold: Boolean = false): TextView =
    TextView(context).apply {
        text = s; textSize = size; setPadding(0, 8, 0, 8)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }.also { addView(it) }

private fun LinearLayout.button(s: String, onClick: (Button) -> Unit): Button =
    Button(context).apply { text = s; isAllCaps = false; setOnClickListener { onClick(this) } }.also { addView(it) }

const val MAPS_PACKAGE = "com.google.android.apps.maps"

private fun bg(f: () -> Unit) = Thread { try { f() } catch (e: Exception) { ProbeLog.i("Error: $e") } }.start()

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private var statusView: TextView? = null
    private var logView: TextView? = null
    private var sendView: TextView? = null
    private var qrView: ImageView? = null
    private var qrShown = ""
    private var overlayButton: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.init(this)
        prefs = Prefs(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        setContentView(ScrollView(this).apply { addView(root) })
        if (!handlePairLink(intent)) build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairLink(intent)
    }

    /** sendtocar://pair links, from the phone's camera app or our own scanner. */
    private fun handlePairLink(i: Intent?): Boolean {
        val link = i?.dataString ?: return false
        val summary = Pairing.apply(this, link) ?: return false
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
        build()
        afterPairing()
        return true
    }

    private fun afterPairing() {
        sendView?.text = "Paired. Looking for the car on Bluetooth and testing every channel…"
        Pairing.findCarBluetooth(this) { status ->
            ProbeLog.i("Car Bluetooth: $status")
            if (!status.startsWith("scanning")) runOnUiThread { send(Msg.ping(prefs.phoneCode)) }
        }
    }

    private fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        try {
            GmsBarcodeScanning.getClient(this, opts).startScan()
                .addOnSuccessListener { code ->
                    val raw = code.rawValue.orEmpty()
                    if (Pairing.apply(this, raw) == null) Toast.makeText(this, "That isn't a Send to Car QR", Toast.LENGTH_LONG).show()
                    else { build(); afterPairing() }
                }
                .addOnFailureListener { e ->
                    ProbeLog.i("QR scanner unavailable: $e")
                    Toast.makeText(this, "Scanner unavailable. Scan the QR with the camera app instead.", Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) { ProbeLog.i("QR scanner failed: $e") }
    }

    override fun onResume() {
        super.onResume()
        ProbeLog.listener = { refreshLog() }
        handler.post(refresher)
    }

    override fun onPause() {
        ProbeLog.listener = null
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun build() {
        root.removeAllViews()
        statusView = null; logView = null; sendView = null; qrView = null; qrShown = ""; overlayButton = null
        when (prefs.role) {
            Prefs.CAR -> buildCar()
            Prefs.PHONE -> buildPhone()
            else -> buildChooser()
        }
    }

    private fun buildChooser() {
        root.label("Send to Car ${BuildConfig.VERSION_NAME}", 24f, true)
        root.label("Install this same app on the car unit and on your phone, then pick what this device is.")
        root.button("This is the CAR (head unit)") { prefs.role = Prefs.CAR; CarService.start(this); build() }
        root.button("This is my PHONE") { prefs.role = Prefs.PHONE; build() }
    }

    private fun buildCar() {
        CarService.start(this)
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT in 23..32) wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.takeIf { it.isNotEmpty() }
            ?.let { requestPermissions(it.toTypedArray(), 1) }
        root.label("Send to Car: car mode (probe ${BuildConfig.VERSION_NAME})", 22f, true)
        root.label("Scan this with the Send to Car app on your phone", 18f, true)
        qrView = ImageView(this).also { root.addView(it) }
        root.label("Pairing code: ${Prefs.pretty(prefs.carCode)} (or type it in on the phone)", 18f)
        statusView = root.label("", 14f)

        root.label("Tests", 20f, true)
        root.button("1. Pop-up test: opens the card in 15 s. Switch to Waze right after tapping") {
            CarService.start(this, CarService.ACTION_TEST_POPUP)
        }
        root.button("2. Open Waze now to a test place") {
            Waze.navigate(this, Place(24.4615, 54.3172, "Emirates Palace"))
        }
        root.button("3. Read the newest file received by Bluetooth") { b ->
            b.text = "3. " + Inbox.checkBluetoothFiles(this)
        }
        root.button("4. Show report as QR codes") { showQr() }
        root.button("5. Send report to the phone (internet)") { b ->
            b.text = "5. Sending…"
            bg { val r = Report.sendToPhone(this); runOnUiThread { b.text = "5. Report: $r" } }
        }

        root.label("Settings", 20f, true)
        root.button(overlayLabel()) { startActivity(Overlay.settingsIntent(this)) }.also { overlayButton = it }
        root.button(autoLabel()) { b -> prefs.autoGo = !prefs.autoGo; b.text = autoLabel() }
        root.button(countdownLabel()) { b ->
            val opts = listOf(0, 3, 5, 10)
            prefs.countdown = opts[(opts.indexOf(prefs.countdown) + 1) % opts.size]
            b.text = countdownLabel()
        }

        val inbox = prefs.inbox
        if (inbox.isNotEmpty()) {
            root.label("Recent places", 20f, true)
            inbox.take(6).forEach { m -> m.place?.let { p -> root.button("Navigate: ${p.label()}") { Waze.navigate(this, p) } } }
        }
        addFooter()
    }

    private fun overlayLabel() = "Open from background (Display over other apps): " +
        if (Overlay.allowed(this)) "ALLOWED" else "OFF, tap to allow"

    private fun autoLabel() = "When a place arrives: " + if (prefs.autoGo) "navigate automatically after the countdown" else "wait for me to tap Go"
    private fun countdownLabel() = "Countdown: ${prefs.countdown} s"

    private fun buildPhone() {
        val wanted = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.takeIf { it.isNotEmpty() }
            ?.let { requestPermissions(it.toTypedArray(), 2) }
        root.label("Send to Car: phone mode (probe ${BuildConfig.VERSION_NAME})", 22f, true)
        root.label(if (prefs.phoneCode.length == 8) "Paired with car ${Prefs.pretty(prefs.phoneCode)}" else "Not paired yet", 18f, true)
        root.button("Scan the car's QR code to pair") { scanQr() }
        root.label("Or type the car's pairing code:")
        val codeIn = EditText(this).apply {
            setText(Prefs.pretty(prefs.phoneCode)); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        root.addView(codeIn)
        root.button("Save code") { prefs.phoneCode = codeIn.text.toString(); codeIn.setText(Prefs.pretty(prefs.phoneCode)); ProbeLog.i("Code saved") }
        root.label("To send a place: in Google Maps, WhatsApp or Waze tap Share → Send to car.", 14f)
        root.button("Send the copied link or address") {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) Toast.makeText(this, "Nothing copied. Long-press a link and copy it first.", Toast.LENGTH_LONG).show()
            else startActivity(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
        }
        root.label("Tapped Google Maps links open in Maps. To send them here instead:", 14f)
        root.button("1. Google Maps → Open by default → turn OFF \"Open supported links\"") {
            openAppLinkSettings(MAPS_PACKAGE)
        }
        root.button("2. Send to Car → Open by default → Add links (tick them all)") {
            openAppLinkSettings(packageName)
        }

        root.label("Tests", 20f, true)
        root.button("1. Ping the car on all channels") { send(Msg.ping(prefs.phoneCode)) }
        root.button("2. Send a test place (Emirates Palace)") {
            send(Msg.place(prefs.phoneCode, Place(24.4615, 54.3172, "Emirates Palace")))
        }
        sendView = root.label("", 15f)
        root.button("3. Get the car's report (internet)") { b ->
            b.text = "3. Fetching…"
            bg {
                val r = Report.fetchFromCar(prefs.phoneCode)
                runOnUiThread {
                    b.text = "3. Get the car's report (internet)"
                    if (r == null) ProbeLog.i("No car report on the relay in the last hour") else shareText("Car report", r)
                }
            }
        }
        root.button("4. Share this phone's report") { shareText("Phone report", Report.build(this)) }
        statusView = root.label("", 14f)
        addFooter()
    }

    private fun send(m: Msg) {
        if (prefs.phoneCode.length != 8) { sendView?.text = "Enter the 8-character code from the car first."; return }
        val status = linkedMapOf(Sender.WIFI to "…", Sender.BT to "…", Sender.NET to "…")
        fun render() { sendView?.text = status.entries.joinToString("\n") { "${it.key}: ${it.value}" } }
        render()
        Sender(this).send(m) { ch, s -> runOnUiThread { status[ch] = s; render() } }
    }

    private fun addFooter() {
        root.label("Log", 20f, true)
        logView = root.label("", 12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.button("Switch role (car/phone)") { prefs.role = null; build() }
        refresh()
    }

    private fun refresh() {
        statusView?.text = Report.status(this).joinToString("\n")
        overlayButton?.text = overlayLabel()
        qrView?.let { v ->
            val link = Pairing.carUri(this)
            if (link != qrShown) {
                qrShown = link
                val size = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.45).toInt()
                v.setImageBitmap(Report.qr(link, size))
            }
        }
        refreshLog()
    }

    private fun refreshLog() {
        logView?.text = ProbeLog.snapshot().takeLast(60).reversed().joinToString("\n")
    }

    private fun showQr() {
        val pages = Report.qrPages(this)
        var i = 0
        val img = ImageView(this)
        val size = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.8).toInt()
        val d = AlertDialog.Builder(this).setView(img).setPositiveButton("Next", null).setNegativeButton("Close", null).create()
        fun page() { img.setImageBitmap(Report.qr(pages[i], size)); d.setTitle("Report page ${i + 1} of ${pages.size}") }
        d.setOnShowListener {
            page()
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { i = (i + 1) % pages.size; page() }
        }
        d.show()
    }

    private fun openAppLinkSettings(pkg: String) {
        val uri = android.net.Uri.parse("package:$pkg")
        val i = if (Build.VERSION.SDK_INT >= 31) Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri)
        else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        try { startActivity(i) } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        }
    }

    private fun shareText(title: String, text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), title))
    }
}

/** Phone side: Share → Send to car. Parses what was shared, sends it on every channel, shows each result. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.init(this)
        val prefs = Prefs(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        setContentView(col)
        val what = col.label("Reading the place…", 18f, true)
        val status = col.label("", 15f)
        col.button("Close") { finish() }
        // A tapped map link landed here: keep a way to look at it in Google Maps as before.
        val tapped = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW && it.scheme?.startsWith("http") == true }
        if (tapped != null) col.button("Open in Google Maps instead") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, tapped).setPackage(MAPS_PACKAGE))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, tapped).addCategory(Intent.CATEGORY_BROWSABLE).setPackage("com.android.chrome"))
            }
            finish()
        }

        if (prefs.phoneCode.length != 8) {
            what.text = "Open Send to Car and enter the car's pairing code first."
            col.button("Open Send to Car") { startActivity(Intent(this, MainActivity::class.java)); finish() }
            return
        }
        val shared = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT), intent.dataString
        ).joinToString("\n")
        ProbeLog.i("Shared: ${shared.take(300).replace('\n', ' ')}")
        bg {
            val place = Http.placeFrom(shared)
            runOnUiThread {
                if (place == null) { what.text = "No place found in what was shared."; status.visibility = View.GONE; return@runOnUiThread }
                what.text = "Sending: ${place.label()}"
                val st = linkedMapOf(Sender.WIFI to "…", Sender.BT to "…", Sender.NET to "…")
                fun render() { status.text = st.entries.joinToString("\n") { "${it.key}: ${it.value}" } }
                render()
                Sender(this).send(Msg.place(prefs.phoneCode, place)) { ch, s -> runOnUiThread { st[ch] = s; render() } }
            }
        }
    }
}
