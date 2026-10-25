package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import android.graphics.Canvas
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/** Mirrors the native airplane slot, including host views cloned into the left container. */
object AirplaneIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onHook() {
        if (!Preferences.getBoolean(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false)) return
        val type = "com.android.systemui.statusbar.StatusBarIconView".toClassOrNull() ?: return
        val slot = runCatching { type.getDeclaredField("mSlot").apply { isAccessible = true } }
            .getOrNull() ?: return
        val method = runCatching { type.getDeclaredMethod("onDraw", Canvas::class.java) }.getOrNull()
            ?: return
        // ThreadLocal.get() is @RecentlyNullable on the platform; withInitial always supplies one.
        val saves = ThreadLocal.withInitial { java.util.ArrayDeque<Int>() }
        fun saves(): java.util.ArrayDeque<Int> = requireNotNull(saves.get())
        method.hook {
            before { param ->
                runCatching {
                    val view = param.thisObject as? View ?: return@runCatching
                    if (slot.get(view) != "airplane") return@runCatching
                    val canvas = param.args.firstOrNull() as? Canvas ?: return@runCatching
                    saves().addLast(canvas.save())
                    canvas.scale(-1f, 1f, view.width / 2f, view.height / 2f)
                }.onFailure { DebugLog.w("AirplaneIcon", "native mirror failed", it) }
            }
            after { param ->
                runCatching {
                    val view = param.thisObject as? View ?: return@runCatching
                    if (slot.get(view) != "airplane") return@runCatching
                    val canvas = param.args.firstOrNull() as? Canvas ?: return@runCatching
                    saves().pollLast()?.let(canvas::restoreToCount)
                }.onFailure { DebugLog.w("AirplaneIcon", "native mirror restore failed", it) }
            }
        }
        DebugLog.hookRegistered("AirplaneIcon", "StatusBarIconView#onDraw(airplane)")
    }

}
