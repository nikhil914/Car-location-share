package com.nik.sendtocar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File
import java.net.URLEncoder

/** Car side: checks, de-duplicates and stores every message, then opens the takeover card. */
object Inbox {
    /** Places older than this go to the inbox only, so a replay after a long sleep never hijacks the route. */
    private const val FRESH_MS = 15 * 60_000L
    private val seen = LinkedHashSet<String>()

    @Volatile var lastShown = "never"
    @Volatile var lastShownAt = 0L
    /** Last message from the paired phone (a ping or a place), for the dashboard's status pill. */
    @Volatile var lastContactAt = 0L
    @Volatile var lastContactVia = ""

    @Synchronized
    fun handle(ctx: Context, body: String, via: String): String {
        val prefs = Prefs(ctx)
        val m = Msg.fromJson(body.trim()) ?: run { ProbeLog.i("Unreadable message via $via"); return "bad" }
        if (m.code != prefs.carCode) { ProbeLog.i("Wrong pairing code via $via"); return "denied" }
        lastContactAt = System.currentTimeMillis()
        lastContactVia = via.substringBefore(" from")
        if (m.type == Msg.PING) { ProbeLog.i("Ping via $via"); return "pong" }
        if (m.id in seen || prefs.inbox.any { it.id == m.id }) { ProbeLog.i("Duplicate ignored via $via"); return "dup" }
        seen.add(m.id)
        if (seen.size > 200) seen.remove(seen.first())
        val place = m.place ?: return "bad"
        prefs.inbox = listOf(m) + prefs.inbox
        val age = System.currentTimeMillis() - m.sentAt
        ProbeLog.i("Place \"${place.label()}\" via $via, ${age / 1000} s after sending")
        if (age > FRESH_MS) ProbeLog.i("Older than 15 min: kept in the inbox, no pop-up")
        else show(ctx, place, via)
        return "ok"
    }

    fun cardIntent(ctx: Context, place: Place, via: String): Intent = Intent(ctx, TakeoverActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("lat", place.lat ?: Double.NaN).putExtra("lon", place.lon ?: Double.NaN)
        .putExtra("name", place.name).putExtra("q", place.query).putExtra("via", via)

    /**
     * Three ways to the screen: the notification, the direct card (works on the 8.1 unit, and on
     * Android 10+ once "Display over other apps" is allowed), and a floating banner if the card
     * still didn't appear.
     */
    fun show(ctx: Context, place: Place, via: String) {
        Alerts.place(ctx, place, via)
        val at = System.currentTimeMillis()
        try { ctx.startActivity(cardIntent(ctx, place, via)); ProbeLog.i("Pop-up requested ($via)") }
        catch (e: Exception) { ProbeLog.i("Pop-up refused ($via): $e") }
        Overlay.fallbackIfCardMissing(ctx, place, via, at)
    }

    /** Fallback channel: a place sent as a file over Bluetooth file transfer. Reads the newest one. */
    fun checkBluetoothFiles(ctx: Context): String {
        val root = Environment.getExternalStorageDirectory()
        val dirs = listOf(File(root, "bluetooth"), File(root, "Bluetooth"), File(root, "Download/bluetooth"),
            File(root, "Download/Bluetooth"), File(root, "Download"))
        val files = dirs.filter { it.isDirectory }.flatMap { d -> d.listFiles()?.filter { it.isFile }.orEmpty() }
            .distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
        if (files.isEmpty()) return "No files found in ${dirs.filter { it.isDirectory }.joinToString { it.path }.ifBlank { "any Bluetooth folder" }} (storage permission granted?)"
        val report = files.take(5).joinToString("\n") { "  ${it.path} (${(System.currentTimeMillis() - it.lastModified()) / 60000} min old)" }
        ProbeLog.i("Bluetooth folder, newest files:\n$report")
        val newest = files.firstOrNull { it.extension.lowercase() in setOf("txt", "html", "htm", "vcf", "url") && it.length() < 32_000 }
            ?: return "No text file to read. Newest:\n$report"
        val text = newest.readText()
        Thread {
            val place = Http.placeFrom(text)
            if (place == null) ProbeLog.i("No place found in ${newest.name}")
            else {
                ProbeLog.i("Place from Bluetooth file ${newest.name}: ${place.label()}")
                show(ctx, place, "Bluetooth file")
            }
        }.start()
        return "Reading ${newest.name}…"
    }
}

object Waze {
    fun installed(ctx: Context): String = try {
        "installed, version " + ctx.packageManager.getPackageInfo("com.waze", 0).versionName
    } catch (e: Exception) { "NOT installed" }

    fun geoUri(p: Place): Uri = Uri.parse(
        if (p.hasCoords) "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(p.name)})"
        else "geo:0,0?q=${Uri.encode(p.query ?: p.name)}"
    )

    /** "Other app": lets you pick any installed map app (Google Maps, a car launcher's nav…). */
    fun otherAppIntent(p: Place): Intent =
        Intent.createChooser(Intent(Intent.ACTION_VIEW, geoUri(p)), "Navigate with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens Waze navigating to the place; falls back to any map app through geo:. */
    fun navigate(ctx: Context, p: Place): String {
        val waze = if (p.hasCoords) "waze://?ll=${p.lat},${p.lon}&navigate=yes"
        else "waze://?q=${URLEncoder.encode(p.query ?: p.name, "UTF-8")}&navigate=yes"
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(waze)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ProbeLog.i("Opened Waze: $waze")
            return "Waze"
        } catch (e: ActivityNotFoundException) { ProbeLog.i("Waze link not handled, trying geo:") }
        val geo = geoUri(p)
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ProbeLog.i("Opened map app: $geo"); "map app"
        } catch (e: ActivityNotFoundException) { ProbeLog.i("No app opens geo: links"); "nothing" }
    }
}
