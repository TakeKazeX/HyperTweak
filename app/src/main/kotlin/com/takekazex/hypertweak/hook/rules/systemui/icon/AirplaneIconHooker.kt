package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import android.graphics.Canvas
import android.view.View
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog

/** Mirrors the native airplane slot, including host views cloned into the left container. */
object AirplaneIconHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconMirrorAirplane by hookSetting(false) { Preferences.getBoolean(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false) }

    override fun onHook() {
        if (!settingIconMirrorAirplane) return
        val type = "com.android.systemui.statusbar.StatusBarIconView".toClassOrNull() ?: return
        val slot = runCatching { HostAccess.requireDeclaredField(type, "mSlot", accessible = true) }
            .getOrNull() ?: return
        val method = runCatching { HostAccess.requireDeclaredMethod(type, "onDraw", Canvas::class.java, accessible = false) }.getOrNull()
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
