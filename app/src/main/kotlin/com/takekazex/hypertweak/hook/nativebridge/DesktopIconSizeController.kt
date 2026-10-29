package com.takekazex.hypertweak.hook.nativebridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs

/** The stock provider drives tempIconScale and reconstructs the active immutable grid. */
internal class DesktopIconSizeController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Int>(Channel.CONFLATED)
    @Volatile private var closed = false
    @Volatile private var refreshAfterAttach = true
    private val uri = Uri.parse("content://com.miui.home.launcher.settings")
    private val journal by lazy {
        context.createDeviceProtectedStorageContext().getSharedPreferences("hypertweak_desktop_icon_scale", Context.MODE_PRIVATE)
    }
    init {
        scope.launch {
            for (first in queue) {
                delay(80)
                val percent = queue.tryReceive().getOrNull() ?: first
                try {
                    // The VM-interned monitor also serializes retiring API-102 classloaders.
                    synchronized("com.takekazex.hypertweak.desktop-icon-size".intern()) {
                        if (!closed) apply(percent)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { DebugLog.w("DesktopIconSize", "stock provider update failed", error) }
            }
        }
    }
    fun refresh() { refreshAfterAttach = true }
    fun submit(percent: Int) { if (!closed) queue.trySend(percent) }
    private fun info(): Bundle = requireNotNull(context.contentResolver.call(uri, "getIconScaleInfo", null, null)) {
        "launcher icon scale information unavailable"
    }
    private fun set(value: Float): Boolean = context.contentResolver.call(uri, "setIconScale", null,
        Bundle().apply { putFloat("iconNewScale", value) })?.getBoolean("isIconNewScaleApplied", false) == true
    private fun apply(percent: Int) {
        val active = journal.getBoolean("active", false)
        if (percent == -1 && !active) return
        var before = info()
        val current = before.getFloat("iconCurrentScale", Float.NaN)
        require(current.isFinite() && current > 0)
        val original = if (active) journal.getFloat("original", current) else current
        require(original.isFinite() && original > 0)
        val originalMin = if (active) journal.getFloat("minimum", before.getFloat("iconMinScale")) else before.getFloat("iconMinScale")
        val originalMax = if (active) journal.getFloat("maximum", before.getFloat("iconMaxScale")) else before.getFloat("iconMaxScale")
        require(originalMin.isFinite() && originalMax.isFinite() && originalMin > 0 && originalMin <= originalMax)
        val desired = if (percent == -1) original.coerceIn(originalMin, originalMax) else original * percent / 100f
        check(journal.edit().putBoolean("active", true).putFloat("original", original)
            .putFloat("minimum", originalMin).putFloat("maximum", originalMax).commit())
        // An older module may have updated the native cache while its Dart
        // initializer still clamped the rendered size. Rebuild once on adoption.
        if (refreshAfterAttach && active && percent != -1) {
            val nudge = (if (abs(current-original)>0.015f) original else
                if (originalMax-original>0.015f) original+0.02f else original-0.02f).coerceIn(originalMin,originalMax)
            if (abs(nudge-current)>0.011f) {
                set(nudge)
                for (attempt in 0 until 5) {
                    Thread.sleep(40)
                    before = info()
                    if (abs(before.getFloat("iconCurrentScale")-current)>0.011f) break
                }
            }
            refreshAfterAttach = false
        }
        val accepted = if (abs(before.getFloat("iconCurrentScale")-desired)<0.00001f) true else set(desired)
        if (percent == -1) {
            for (attempt in 0 until 5) {
                val after = info()
                if (abs(after.getFloat("iconCurrentScale") - desired)<0.0001f) {
                    check(journal.edit().putBoolean("active", false).commit()); break
                }
                Thread.sleep(40)
            }
        }
        DebugLog.i("DesktopIconSize", "provider percent=$percent original=$original requested=$desired accepted=$accepted current=$current")
    }
    fun close() { closed = true; queue.close(); scope.cancel() }
}
