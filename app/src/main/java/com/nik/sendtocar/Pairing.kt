package com.nik.sendtocar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * QR pairing. The car shows `sendtocar://pair?c=CODE&ip=…&bt=MAC&bn=NAME`; the phone scans it
 * (in the app, or with the camera app, which opens the link) and is then paired on every channel:
 * the code names the relay topic, the IPs seed the Wi-Fi search, and the Bluetooth name/address
 * lets the phone reach the car's RFCOMM socket without a system pairing.
 */
object Pairing {
    const val SCHEME = "sendtocar"

    @SuppressLint("MissingPermission", "HardwareIds")
    fun carUri(ctx: Context): String {
        val b = Uri.Builder().scheme(SCHEME).authority("pair").appendQueryParameter("c", Prefs(ctx).carCode)
        val ips = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }
        } catch (e: Exception) { emptyList() }
        if (ips.isNotEmpty()) b.appendQueryParameter("ip", ips.joinToString(","))
        try {
            @Suppress("DEPRECATION") val a = BluetoothAdapter.getDefaultAdapter()
            if (a != null) {
                a.name?.let { b.appendQueryParameter("bn", it) }
                // Android 8+ hides the adapter MAC from apps; some head-unit ROMs still expose it here.
                val mac = listOf(a.address, Settings.Secure.getString(ctx.contentResolver, "bluetooth_address"))
                    .firstOrNull { it != null && BluetoothAdapter.checkBluetoothAddress(it) && it != "02:00:00:00:00:00" }
                mac?.let { b.appendQueryParameter("bt", it) }
            }
        } catch (e: Exception) { }
        return b.build().toString()
    }

    /** Phone side. Returns a short summary, or null if this isn't a pairing link. */
    fun apply(ctx: Context, link: String): String? {
        val u = Uri.parse(link.trim())
        if (u.scheme != SCHEME || u.host != "pair") return null
        val code = Prefs.normalizeCode(u.getQueryParameter("c").orEmpty())
        if (code.length != 8) return null
        val prefs = Prefs(ctx)
        prefs.role = Prefs.PHONE
        prefs.phoneCode = code
        u.getQueryParameter("ip")?.split(',')?.firstOrNull { it.isNotBlank() }?.let { prefs.lastIp = it }
        prefs.carBtName = u.getQueryParameter("bn")
        u.getQueryParameter("bt")?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }?.let { prefs.lastBt = it }
        ProbeLog.i("Paired by QR: code ${Prefs.pretty(code)}, ip ${u.getQueryParameter("ip")}, bt ${u.getQueryParameter("bn")} ${u.getQueryParameter("bt") ?: ""}")
        return "Paired with the car (${Prefs.pretty(code)})"
    }

    /**
     * Finds the car's Bluetooth address when the QR had only its name: first among paired devices,
     * then by a short discovery scan. [done] gets a status line.
     */
    @SuppressLint("MissingPermission")
    fun findCarBluetooth(ctx: Context, done: (String) -> Unit) {
        val prefs = Prefs(ctx)
        @Suppress("DEPRECATION") val a = BluetoothAdapter.getDefaultAdapter() ?: return done("no Bluetooth on this phone")
        if (prefs.lastBt != null) return done("car Bluetooth address known")
        val name = prefs.carBtName ?: return done("the car didn't report a Bluetooth name")
        val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        try {
            a.bondedDevices?.firstOrNull { it.name == name }?.let { prefs.lastBt = it.address; return done("found \"$name\" among paired devices") }
        } catch (e: SecurityException) { }
        if (ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) return done("Bluetooth scan permission not granted")
        if (!a.isEnabled) return done("Bluetooth is off")
        val app = ctx.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        if (d.name == name) {
                            prefs.lastBt = d.address
                            a.cancelDiscovery()
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        try { app.unregisterReceiver(this) } catch (e: Exception) { }
                        done(if (prefs.lastBt != null) "found \"$name\" by scanning" else "\"$name\" not found nearby (is the car's Bluetooth on?)")
                    }
                }
            }
        }
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })
        if (!a.startDiscovery()) {
            try { app.unregisterReceiver(receiver) } catch (e: Exception) { }
            done("Bluetooth scan could not start")
        } else done("scanning for \"$name\"…")
    }
}

/**
 * A notification for every arriving place, next to the direct pop-up. On the FYT unit the pop-up does
 * the work (its shade shows nothing); on Android 10+ an app may not open screens from the background,
 * so there the notification (full-screen where allowed) is how the place reaches you.
 */
object Alerts {
    private const val CHANNEL = "places"
    const val ID = 7

    fun place(ctx: Context, place: Place, via: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Places from your phone", NotificationManager.IMPORTANCE_HIGH))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val card = PendingIntent.getActivity(ctx, 1, Inbox.cardIntent(ctx, place, via), flags)
        val go = PendingIntent.getActivity(ctx, 2, Inbox.cardIntent(ctx, place, via).putExtra(TakeoverActivity.EXTRA_GO_NOW, true), flags)
        val other = PendingIntent.getActivity(ctx, 3, Waze.otherAppIntent(place), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
        val n = b.setSmallIcon(R.drawable.ic_send_car)
            .setContentTitle("Navigate to ${place.label()}")
            .setContentText("From your phone ($via)")
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(card)
            .setFullScreenIntent(card, true)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Navigate (Waze)", go).build())
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Other app", other).build())
            .build()
        try { nm.notify(ID, n) } catch (e: Exception) { ProbeLog.i("Notification refused: $e") }
    }

    fun clear(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) } catch (e: Exception) { }
    }
}
