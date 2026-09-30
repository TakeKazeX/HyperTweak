package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/** Mirrors the native airplane slot, including host views cloned into the left container. */
object AirplaneIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_ICON_MIRROR_AIRPLANE, false)) return
        val type = "com.android.systemui.statusbar.StatusBarIconView".toClassOrNull() ?: return
        val slot = runCatching { type.getDeclaredField("mSlot").apply { isAccessible = true } }
            .getOrNull() ?: return
        val method = runCatching { type.getDeclaredMethod("onDraw", Canvas::class.java) }.getOrNull()
            ?: return
        val saves = ThreadLocal.withInitial { java.util.ArrayDeque<Int>() }
        method.hook {
            before { param ->
                runCatching {
                    val view = param.thisObject as? View ?: return@runCatching
                    if (slot.get(view) != "airplane") return@runCatching
                    val canvas = param.args.firstOrNull() as? Canvas ?: return@runCatching
                    saves.get().addLast(canvas.save())
                    canvas.scale(-1f, 1f, view.width / 2f, view.height / 2f)
                }.onFailure { DebugLog.w("AirplaneIcon", "native mirror failed", it) }
            }
            after { param ->
                runCatching {
                    val view = param.thisObject as? View ?: return@runCatching
                    if (slot.get(view) != "airplane") return@runCatching
                    val canvas = param.args.firstOrNull() as? Canvas ?: return@runCatching
                    saves.get().pollLast()?.let(canvas::restoreToCount)
                }.onFailure { DebugLog.w("AirplaneIcon", "native mirror restore failed", it) }
            }
        }
        DebugLog.hookRegistered("AirplaneIcon", "StatusBarIconView#onDraw(airplane)")
    }

    /** Resolve by resource name on each host, never by a build-specific integer ID. */
    fun loadGlyph(context: Context): Drawable? {
        val host = runCatching {
            if (context.packageName == "com.android.systemui") context
            else context.createPackageContext("com.android.systemui", 0)
        }.getOrNull()
        val native = host?.let {
            runCatching {
                val id = it.resources.getIdentifier("stat_sys_signal_flightmode", "drawable", "com.android.systemui")
                if (id == 0) null else it.getDrawable(id)?.mutate()
            }.getOrNull()
        }
        if (native != null) return native
        // Settings previews and hosts without this resource use an exact copy of the OS4 vector.
        return runCatching {
            val module = if (context.packageName == HostIconBridge.MODULE_PACKAGE) context
                else context.createPackageContext(HostIconBridge.MODULE_PACKAGE, 0)
            val id = module.resources.getIdentifier("ic_stat_sys_airplane", "drawable", HostIconBridge.MODULE_PACKAGE)
            if (id == 0) null else module.getDrawable(id)?.mutate()
        }.onFailure { DebugLog.w("AirplaneIcon", "native glyph unavailable", it) }.getOrNull()
    }
}
