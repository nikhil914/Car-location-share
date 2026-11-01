package com.nik.sendtocar

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

const val MAPS_PACKAGE = "com.google.android.apps.maps"

private fun bg(f: () -> Unit) = Thread { try { f() } catch (e: Exception) { ProbeLog.i("Error: $e") } }.start()

/**
 * One activity for both ends. The car gets the dashboard (CarUi.kt); the phone gets its home
 * screen (PhoneUi.kt); a fresh install asks which one this device is.
 */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private var carModel: CarModel? = null
    private val phone = PhoneState()
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { phone.tick = System.currentTimeMillis(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.init(this)
        prefs = Prefs(this)
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
        phone.sendTitle = "Paired. Looking for the car on Bluetooth and testing every channel…"
        Pairing.findCarBluetooth(this) { status ->
            ProbeLog.i("Car Bluetooth: $status")
            if (!status.startsWith("scanning")) runOnUiThread { send(Msg.ping(prefs.phoneCode), "Testing every channel") }
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
        ProbeLog.listener = { phone.tick = System.currentTimeMillis() }
        handler.post(refresher)
        carModel?.resume()
    }

    override fun onPause() {
        ProbeLog.listener = null
        handler.removeCallbacks(refresher)
        carModel?.pause()
        super.onPause()
    }

    private fun build() {
        actionBar?.hide()
        if (prefs.role == Prefs.CAR) { buildCar(); return }
        carModel?.pause(); carModel = null
        if (prefs.role == Prefs.PHONE) buildPhone()
        else setContent {
            RoleChooser(
                onCar = { prefs.role = Prefs.CAR; CarService.start(this); build() },
                onPhone = { prefs.role = Prefs.PHONE; build() },
            )
        }
    }

    private fun buildCar() {
        CarService.start(this)
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT in 23..32) wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        // Distance and bearing to the place on the dashboard, and the sunset time for the Auto theme.
        if (Build.VERSION.SDK_INT <= 30) wanted += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.takeIf { it.isNotEmpty() }
            ?.let { requestPermissions(it.toTypedArray(), 1) }
        val model = carModel ?: CarModel(this).also { carModel = it }
        val actions = CarActions(
            testPopup = { CarService.start(this, CarService.ACTION_TEST_POPUP) },
            readBluetoothFile = { Inbox.checkBluetoothFiles(this) },
            showReportQr = { showQr() },
            sendReport = { done -> bg { val r = Report.sendToPhone(this); runOnUiThread { done(r) } } },
            openOverlaySettings = { startActivity(Overlay.settingsIntent(this)) },
            switchRole = { prefs.role = null; build() },
        )
        setContent { CarApp(model, actions) }
        model.resume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        carModel?.let { it.pause(); it.resume() }
        phone.tick = System.currentTimeMillis()
    }

    private fun buildPhone() {
        val wanted = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.takeIf { it.isNotEmpty() }
            ?.let { requestPermissions(it.toTypedArray(), 2) }
        val actions = PhoneActions(
            scanQr = { scanQr() },
            saveCode = { code -> prefs.phoneCode = code; ProbeLog.i("Code saved"); phone.tick = System.currentTimeMillis() },
            sendCopied = { sendCopied() },
            pickBluetooth = { pickCarBluetooth { phone.tick = System.currentTimeMillis() } },
            testBluetooth = { testBluetooth() },
            mapsLinks = { openAppLinkSettings(MAPS_PACKAGE) },
            ourLinks = { openAppLinkSettings(packageName) },
            ping = { send(Msg.ping(prefs.phoneCode), "Ping on every channel") },
            testPlace = { send(Msg.place(prefs.phoneCode, Place(24.4615, 54.3172, "Emirates Palace")), "Test place: Emirates Palace") },
            carReport = { done ->
                bg {
                    val r = Report.fetchFromCar(prefs.phoneCode)
                    runOnUiThread {
                        done()
                        if (r == null) ProbeLog.i("No car report on the relay in the last hour") else shareText("Car report", r)
                    }
                }
            },
            phoneReport = { shareText("Phone report", Report.build(this)) },
            switchRole = { prefs.role = null; build() },
        )
        setContent { PhoneApp(phone, actions) }
    }

    private fun sendCopied() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) Toast.makeText(this, "Nothing copied. Long-press a link and copy it first.", Toast.LENGTH_LONG).show()
        else startActivity(Intent(this, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    }

    private fun testBluetooth() {
        if (prefs.phoneCode.length != 8) { phone.sendTitle = "Pair with the car first."; return }
        phone.sendTitle = "Bluetooth test"
        phone.status = linkedMapOf(Sender.BT to "…")
        Sender(this).sendBluetooth(Msg.ping(prefs.phoneCode)) { s -> runOnUiThread { phone.status = linkedMapOf(Sender.BT to s) } }
    }

    /** Lists the phone's paired devices; the one picked is the only one Bluetooth sends to. */
    @SuppressLint("MissingPermission")
    private fun pickCarBluetooth(done: () -> Unit) {
        @Suppress("DEPRECATION") val a = BluetoothAdapter.getDefaultAdapter()
        if (a == null) { Toast.makeText(this, "No Bluetooth on this phone", Toast.LENGTH_LONG).show(); return }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 2); return
        }
        val devices = try { a.bondedDevices.orEmpty().sortedBy { it.name ?: it.address } } catch (e: Exception) { emptyList() }
        val carName = prefs.carBtName
        val labels = devices.map { d -> (d.name ?: d.address) + if (carName != null && d.name == carName) "  ← name from the car's QR" else "" } +
            "Not listed: open Bluetooth settings to pair the car"
        AlertDialog.Builder(this).setTitle("Which device is the car?")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == devices.size) startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                else {
                    prefs.lastBt = devices[i].address
                    ProbeLog.i("Car's Bluetooth set to ${devices[i].name} ${devices[i].address}")
                }
                done()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun send(m: Msg, title: String) {
        if (prefs.phoneCode.length != 8) { phone.sendTitle = "Pair with the car first."; phone.status = emptyMap(); return }
        phone.sendTitle = title
        val status = linkedMapOf(Sender.WIFI to "…", Sender.BT to "…", Sender.NET to "…")
        phone.status = LinkedHashMap(status)
        Sender(this).send(m) { ch, s -> runOnUiThread { status[ch] = s; phone.status = LinkedHashMap(status) } }
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
class ShareActivity : ComponentActivity() {
    private var heading by mutableStateOf("Reading the place…")
    private var status by mutableStateOf<Map<String, String>>(emptyMap())
    private var notPaired by mutableStateOf(false)
    private var deliveredAt by mutableLongStateOf(0L)
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.init(this)
        val prefs = Prefs(this)
        // A tapped map link landed here: keep a way to look at it in Google Maps as before.
        val tapped = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW && it.scheme?.startsWith("http") == true }
        val openInMaps: (() -> Unit)? = tapped?.let { uri ->
            {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(MAPS_PACKAGE))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage("com.android.chrome"))
                }
                finish()
            }
        }
        setContent {
            SendSheet(
                heading, status, notPaired, deliveredAt > 0,
                onClose = { finish() },
                onOpenApp = { startActivity(Intent(this, MainActivity::class.java)); finish() },
                onOpenInMaps = openInMaps,
            )
        }

        if (prefs.phoneCode.length != 8) {
            heading = "Pair with the car first"
            notPaired = true
            return
        }
        val shared = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT), intent.dataString
        ).joinToString("\n")
        ProbeLog.i("Shared: ${shared.take(300).replace('\n', ' ')}")
        bg {
            val place = Http.placeFrom(shared)
            runOnUiThread {
                if (place == null) { heading = "No place found in what was shared"; return@runOnUiThread }
                heading = place.label()
                val st = linkedMapOf(Sender.WIFI to "…", Sender.BT to "…", Sender.NET to "…")
                status = LinkedHashMap(st)
                Sender(this).send(Msg.place(prefs.phoneCode, place)) { ch, s ->
                    runOnUiThread {
                        st[ch] = s; status = LinkedHashMap(st)
                        // Delivered once is enough: close by itself shortly after, the other channels finish quietly.
                        if (deliveredAt == 0L && s.startsWith("DELIVERED")) {
                            deliveredAt = System.currentTimeMillis()
                            handler.postDelayed({ if (!isFinishing) finish() }, 3500)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
