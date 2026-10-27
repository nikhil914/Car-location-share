package com.nik.sendtocar

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Second way to reach the screen from the background. Holding "Display over other apps" exempts the
 * app from Android 10+'s background-start block, and if a ROM still swallows the card, a floating
 * banner drawn straight onto the screen asks "Navigate to X?" instead.
 */
object Overlay {
    private val main = Handler(Looper.getMainLooper())
    private var banner: View? = null

    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun settingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Called a moment after asking for the card: if it never appeared, draw the banner. */
    fun fallbackIfCardMissing(ctx: Context, place: Place, via: String, requestedAt: Long) {
        main.postDelayed({
            if (Inbox.lastShownAt >= requestedAt) return@postDelayed
            if (!allowed(ctx)) { ProbeLog.i("Card blocked and \"Display over other apps\" is off: only the notification is left"); return@postDelayed }
            ProbeLog.i("Card didn't appear ($via): showing the floating banner")
            show(ctx.applicationContext, place)
        }, 2000)
    }

    fun show(ctx: Context, place: Place) = main.post {
        dismiss(ctx)
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6101418"))
            setPadding(32, 24, 32, 24)
            addView(TextView(ctx).apply {
                text = "Navigate to ${place.label()}?"; textSize = 22f; setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(ctx).apply { text = "Go"; textSize = 20f; setOnClickListener { dismiss(ctx); Alerts.clear(ctx); Waze.navigate(ctx, place) } })
            addView(Button(ctx).apply { text = "✕"; textSize = 20f; setOnClickListener { dismiss(ctx) } })
        }
        try { wm.addView(view, lp); banner = view } catch (e: Exception) { ProbeLog.i("Floating banner refused: $e") }
        main.postDelayed({ dismiss(ctx) }, 120_000)
    }

    fun dismiss(ctx: Context) {
        val v = banner ?: return
        banner = null
        try { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) { }
    }
}
