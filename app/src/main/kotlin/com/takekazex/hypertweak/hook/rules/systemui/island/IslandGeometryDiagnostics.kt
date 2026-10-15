package com.takekazex.hypertweak.hook.rules.systemui.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.util.DebugLog

/** Explicit, DUMP-permission protected, read-only geometry sampling; never records text. */
internal class IslandGeometryDiagnostics(context: Context, main: Handler, dump: () -> Unit) {
    private val context = context.applicationContext ?: context
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching { dump() }.onFailure { DebugLog.e("IslandGeometry", "dump failed", it) }
        }
    }
    init {
        this.context.registerReceiver(receiver, IntentFilter(ACTION), android.Manifest.permission.DUMP,
            main, Context.RECEIVER_EXPORTED)
    }
    fun retire() { runCatching { context.unregisterReceiver(receiver) } }

    companion object {
        const val ACTION = "com.takekazex.hypertweak.action.DUMP_ISLAND_GEOMETRY"
        fun tree(role: String, view: View?, depth: Int = 0) {
            if (view == null || depth > 5) return
            val params = view.layoutParams
            val location = IntArray(2).also(view::getLocationOnScreen)
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("none")
            val gravity = when (params) {
                is FrameLayout.LayoutParams -> params.gravity
                is LinearLayout.LayoutParams -> params.gravity
                else -> 0
            }
            val textWidth = if (view is TextView) " textWidth=${view.paint.measureText(view.text.toString())}" else ""
            DebugLog.i("IslandGeometry", "$role/$name type=${view.javaClass.simpleName} id=${System.identityHashCode(view)} " +
                "screen=${location.joinToString()} box=${view.left},${view.top},${view.width},${view.height} " +
                "measured=${view.measuredWidth} lp=${params?.width},${params?.height} gravity=$gravity " +
                "weight=${(params as? LinearLayout.LayoutParams)?.weight} " +
                "translation=${view.translationX},${view.translationY} alpha=${view.alpha} visibility=${view.visibility} " +
                "clip=${view.clipBounds}$textWidth")
            if (view is ViewGroup) for (i in 0 until view.childCount) tree("$role/$i", view.getChildAt(i), depth + 1)
        }
    }
}
