package com.takekazex.hypertweak.dock

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri

/** Only system_server may create a host. IPC always enters our main Looper, never WMS's lock. */
class DockSurfaceProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    private data class Entry(val host: SurfaceControlViewHost, val view: DockMaterialView, val width: Int, val height: Int,
        val owner: IBinder, val death: IBinder.DeathRecipient, val lease: Long,
        var parcel: SurfaceControlViewHost.SurfacePackage? = null)
    private val entries = mutableMapOf<String, Entry>() // Main thread only.
    private var lastStatus = "idle"
    private var motionReady = false
    private val leases = DockLeaseLedger()
    private fun status(value: String) {
        if (lastStatus == value) return
        lastStatus = value
        context?.contentResolver?.notifyChange("content://com.takekazex.hypertweak.dock".toUri(), null)
    }
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        check(uid == Process.SYSTEM_UID || (method == "diagnostics" && uid == Process.myUid())) { "Untrusted Dock caller" }
        val task = FutureTask { execute(method, arg, extras ?: Bundle.EMPTY) }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
        return try { task.get(3, TimeUnit.SECONDS) } catch (error: Exception) {
            // The posted task may already own a host. Queue cleanup after it before returning.
            if (method != "settings" && arg != null) main.post { release(arg) }
            DebugLog.w("DockHost", "$method failed", error)
            if (method != "settings") main.post { status("unavailable") }
            Bundle().apply { putString("error", error.cause?.message ?: error.message ?: "Host unavailable") }
        }
    }

    private fun execute(method: String, id: String?, args: Bundle): Bundle {
        if (method == "diagnostics") return Bundle().apply { putString("status", lastStatus); putInt("hosts", entries.size); putBoolean("motion", motionReady) }
        if (method == "settings") return Bundle().apply {
            val encoded = com.takekazex.hypertweak.hook.Preferences.getString(DockConfig.KEY, DockConfig().encode())
            putString("config", requireNotNull(DockConfig.decode(encoded)) { "Invalid saved Dock configuration" }.encode())
        }
        require(id != null && id.length in 1..100)
        val lease = args.getLong("lease")
        // During APK replacement an older WMS classloader can still call this provider.
        // Reject its obsolete create/update protocol without poisoning the current host.
        if (lease <= 0) return Bundle().apply { putString("error", "Obsolete Dock client; reload Java Hook") }
        if (method == "retire") {
            leases.retire(lease)
            entries.filterValues { it.lease <= lease }.keys.toList().forEach(::release)
            return Bundle.EMPTY
        }
        if (!leases.admit(lease)) return Bundle().apply { putString("error", "Retired Dock generation") }
        entries.filterValues { it.lease < leases.active }.keys.toList().forEach(::release)
        if (method == "release") { release(id); return Bundle.EMPTY }
        if (method == "motion") {
            val ready = args.getBoolean("ready")
            if (motionReady != ready) { motionReady = ready; context?.contentResolver?.notifyChange("content://com.takekazex.hypertweak.dock".toUri(), null) }
            return Bundle.EMPTY
        }
        if (method == "update") {
            val entry = entries[id] ?: return Bundle().apply { putString("error", "Renderer retired") }
            val config = DockConfig.decode(args.getString("config") ?: "") ?: error("Invalid Dock configuration")
            val radius = args.getFloat("radius")
            require(radius.isFinite() && radius in 0f..minOf(entry.width, entry.height) / 2f)
            entry.view.apply(config.style, args.getBoolean("dark"), radius, crossWindow = true)
            return Bundle().apply { putBoolean("updated", true) }
        }
        if (method == "probe") {
            val entry = entries[id] ?: return Bundle().apply { putString("error", "Renderer retired") }
            if (entry.view.frameCommitted && entry.view.textureTimestamp() > 0) status("ready")
            return Bundle().apply {
                putBoolean("committed", entry.view.frameCommitted)
                putLong("texture", entry.view.textureTimestamp())
            }
        }
        require(method == "create")
        motionReady = args.getBoolean("motionReady")
        val config = DockConfig.decode(args.getString("config") ?: "") ?: error("Invalid Dock configuration")
        check(config.enabled)
        val width = args.getInt("width")
        val height = args.getInt("height")
        val radius = args.getFloat("radius")
        require(width in 1..4096 && height in 1..4096 && radius.isFinite() && radius in 0f..minOf(width, height) / 2f)
        val owner = requireNotNull(args.getBinder("owner"))
        check(owner.isBinderAlive)
        release(id)
        check(entries.size < 2) { "Too many Dock surfaces" }
        DockReflection.allowOwnViewApis()
        val context = requireNotNull(context)
        val display = requireNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        val displayContext = context.createDisplayContext(display)
        val view = DockMaterialView(displayContext)
        view.sourceRotation = args.getInt("rotation", -1).takeIf { it in 0..3 }
        val host = SurfaceControlViewHost(displayContext, display, Binder())
        val death = IBinder.DeathRecipient { main.post { release(id) } }
        val entry = Entry(host, view, width, height, owner, death, lease)
        entries[id] = entry
        view.onMaterialState = { ready -> if (!ready && entries[id] === entry) {
            release(id)
            status("unavailable")
        } }
        try {
            owner.linkToDeath(death, 0)
            val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply {
                title = "HyperTweak Dock material"
            }
            DockReflection.call(host, "setView", view, params)
            view.post {
                if (entries[id] !== entry) return@post
                runCatching {
                    view.apply(config.style, args.getBoolean("dark"), radius, crossWindow = true)
                    view.invalidate()
                    status("loading")
                }.onFailure { error ->
                    DebugLog.w("DockHost", "native material unavailable", error)
                    release(id)
                    status("unavailable")
                }
            }
            return Bundle().apply {
                val parcel = requireNotNull(host.surfacePackage)
                entry.parcel = parcel
                putParcelable("surface", parcel)
                putBinder("renderer", Binder())
            }
        } catch (error: Exception) { release(id); throw error }
    }

    private fun release(id: String) {
        val entry = entries.remove(id) ?: return
        runCatching { entry.owner.unlinkToDeath(entry.death, 0) }
        entry.view.stop()
        entry.parcel?.release()
        runCatching { entry.host.release() }.onFailure { DebugLog.w("DockHost", "surface release failed", it) }
        if (entries.isEmpty() && lastStatus != "unavailable") status("idle")
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
