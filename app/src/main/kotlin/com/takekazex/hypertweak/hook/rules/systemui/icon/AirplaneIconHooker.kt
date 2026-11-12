package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Canvas
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.util.ArrayDeque

/** Mirrors the native airplane slot, including host views cloned into the left container. */
internal object AirplaneIconHooker : IconRenderer() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingIconMirrorAirplane by hookSetting(false) { Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE) }

    init {
        // The mirror only applies at draw time: redraw the living airplane views after the hook
        // is installed or removed, so a toggle shows without the icon groups being rebuilt.
        hostRecovery(HostRecoveryPhase.ICONS, icons = true) { scope ->
            val type = "com.android.systemui.statusbar.StatusBarIconView".toClassOrNull() ?: return@hostRecovery
            val slot = HostAccess.field(type, "mSlot") ?: return@hostRecovery
            scope.views.filter { type.isInstance(it) && slot.get(it) == "airplane" }.forEach(View::invalidate)
        }
    }

    override fun onHook() {
        if (!settingIconMirrorAirplane) return
        val type = "com.android.systemui.statusbar.StatusBarIconView".toClassOrNull() ?: run { DebugLog.hookSkipped("AirplaneIconHooker", "com.android.systemui.statusbar.StatusBarIconView", "not found on host"); return }
        val slot = runCatching { HostAccess.requireDeclaredField(type, "mSlot", accessible = true) }
            .getOrNull() ?: return
        val method = runCatching { HostAccess.requireDeclaredMethod(type, "onDraw", Canvas::class.java, accessible = false) }.getOrNull()
            ?: return
        // ThreadLocal.get() is @RecentlyNullable on the platform; withInitial always supplies one.
        val saves = ThreadLocal.withInitial { ArrayDeque<Int>() }
        fun saves(): ArrayDeque<Int> = requireNotNull(saves.get())
        method.observe {
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
