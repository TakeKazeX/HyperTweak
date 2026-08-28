package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Hides the device-switch icon on the control-center main media card (隐藏设备切换按钮, plugin
 * half), OS4 miui.systemui.plugin.
 *
 * The plugin's main media card (`media_player_view.xml` / `media_player_wide_flip_view.xml`) has
 * no `media_seamless` — its device-switch equivalent is `@id/device_icon`, whose visibility is
 * written by exactly one place:
 * `MediaPlayerController$MediaPlayerViewHolder.setDetailAvailable(boolean)` — `VISIBLE` (0) when
 * detail is available, `GONE` (8) otherwise. Hooking it after the original body and re-hiding the
 * icon keeps `detailAvailable` true, so tapping the card still opens the MiPlay detail; only the
 * icon disappears. The view is re-hidden on every bind (`updateIcon()` → `setDetailAvailable()`
 * runs on `onBindViewHolder` / `onCreate` / `onSuperPowerModeChanged` / `onUserSwitched`), so the
 * one after-hook covers every path that could restore it.
 *
 * Attached ONLY from `SystemUIPluginHooker.attachPluginHooker` with the plugin PathClassLoader —
 * the class lives in the miui.systemui.plugin APK, not in SystemUI. The master switch gates
 * attachment at plugin load; each callback re-reads it live so disabling applies without a
 * SystemUI restart.
 */
class MediaPlayerDeviceIconHooker : DynamicHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

    private companion object {
        const val TAG = "MediaPlayerDeviceIcon"
        const val HOLDER_CLASS =
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder"
    }

    @Volatile
    private var bindingField: Field? = null

    @Volatile
    private var getDeviceIcon: Method? = null

    override fun onHook() {
        bindingField = null
        getDeviceIcon = null
        val holder = HOLDER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, HOLDER_CLASS, "class not found")
            return
        }
        val bindingType = holder.declaredFields.firstOrNull { it.name == "binding" } ?: run {
            DebugLog.hookSkipped(TAG, "$HOLDER_CLASS#binding", "field not found")
            return
        }
        bindingField = bindingType.apply { isAccessible = true }
        val iconGetter = bindingType.type.methods.firstOrNull { it.name == "getDeviceIcon" } ?: run {
            DebugLog.hookSkipped(TAG, "${bindingType.type.name}#getDeviceIcon", "method not found")
            return
        }
        getDeviceIcon = iconGetter
        val setDetail = holder.declaredMethods.firstOrNull {
            it.name == "setDetailAvailable" && it.parameterTypes.size == 1
        } ?: run {
            DebugLog.hookSkipped(TAG, "$HOLDER_CLASS#setDetailAvailable", "method not found")
            return
        }
        setDetail.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "plugin setDetailAvailable", Unit) {
                    if (!Preferences.getBoolean(Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)) {
                        return@open
                    }
                    val binding = bindingField?.get(param.thisObject) ?: return@open
                    val icon = getDeviceIcon?.invoke(binding) as? View ?: return@open
                    if (icon.visibility != View.GONE) icon.visibility = View.GONE
                }
            }
        }
        DebugLog.d(TAG, "plugin media card device icon hidden after setDetailAvailable")
    }

    override fun onPrepareHotReload() {
        bindingField = null
        getDeviceIcon = null
    }
}
