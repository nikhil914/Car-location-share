package com.nik.sendtocar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

/**
 * The car's headlight state from FYT's com.syu.ms service (main module, update 4 = illumination
 * wire), the same hand-written binder CarRadio uses. Drives the Auto theme. Stays null on units
 * without com.syu.ms, and the theme then falls back to the light sensor or the sun.
 */
object CarSignals {
    private const val D_TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private const val D_MODULE = "com.syu.ipc.IRemoteModule"
    private const val D_CALLBACK = "com.syu.ipc.IModuleCallback"
    private const val MODULE_MAIN = 0
    private const val U_LAMPLET = 4

    private val main = Handler(Looper.getMainLooper())
    @Volatile var headlights: Boolean? = null; private set
    @Volatile var state = "not connected"; private set
    private var bound = false

    private val callback = object : Binder() {
        init { attachInterface(null, D_CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(D_CALLBACK); return true }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            try {
                data.enforceInterface(D_CALLBACK)
                val update = data.readInt()
                val ints = data.createIntArray()
                if (update == U_LAMPLET && ints != null && ints.isNotEmpty()) {
                    val on = ints[0] != 0
                    if (headlights != on) { headlights = on; ProbeLog.i("Headlights ${if (on) "ON" else "OFF"}") }
                }
            } catch (e: Exception) { }
            return true
        }
    }

    /** Binds once for the life of the process (the car service calls it). */
    fun start(ctx: Context) = main.post {
        if (bound) return@post
        val app = ctx.applicationContext
        val pkg = listOf("com.syu.ms", "com.syu.ss").firstOrNull { p ->
            try { app.packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }
        }
        if (pkg == null) { state = "no com.syu.ms on this unit"; return@post }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder?) {
                if (service == null) return
                Thread { register(service) }.start()
            }
            override fun onServiceDisconnected(name: ComponentName) { state = "disconnected" }
        }
        bound = try {
            app.bindService(Intent("com.syu.ms.toolkit").setComponent(ComponentName(pkg, "app.ToolkitService")), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { state = "bind refused: $e"; false }
        if (!bound) state = "bind refused by $pkg"
    }

    private fun register(toolkit: IBinder) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(D_TOOLKIT)
            data.writeInt(MODULE_MAIN)
            toolkit.transact(1, data, reply, 0)
            reply.readException()
            val module = reply.readStrongBinder() ?: run { state = "no main module"; return }
            val reg = Parcel.obtain()
            try {
                reg.writeInterfaceToken(D_MODULE)
                reg.writeStrongBinder(callback)
                reg.writeInt(U_LAMPLET)
                reg.writeInt(1)
                module.transact(3, reg, null, IBinder.FLAG_ONEWAY)
            } finally { reg.recycle() }
            state = "connected"
            ProbeLog.i("FYT service connected: listening for the headlights")
        } catch (e: Exception) {
            state = "error: $e"
            ProbeLog.i("FYT headlights: $e")
        } finally { reply.recycle(); data.recycle() }
    }
}
