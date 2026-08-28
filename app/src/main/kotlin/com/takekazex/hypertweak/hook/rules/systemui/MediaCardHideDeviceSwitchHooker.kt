package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field

/**
 * Hides the device-switch button on the media cards (隐藏设备切换按钮), OS4 SystemUI.
 *
 * The button has two independent render chains with their own holder classes:
 * - shade card (`miui_media_session.xml`): `@id/media_seamless` (a 34dp `LinearLayout` pinned to
 *   the top-right corner). Its visibility is set by `MiuiMediaViewControllerImpl.setSeamless(
 *   MediaData)` — `GONE` while casting video, `VISIBLE` otherwise — and that method is the only
 *   entry every bind walks (`attach` :467 and `onMediaDataChanged` :818), so forcing the holder's
 *   `seamless` view GONE right after it returns sticks for the lifetime of that holder.
 * - island (`miui_media_session_island`): the mirror chain
 *   `MiuiIslandMediaViewBinderImpl.setSeamless(MediaData, MiuiIslandMediaViewHolder)` :887, called
 *   with both the real and the dummy holder from `onMediaDataChanged` :743-744; the second
 *   parameter is the holder whose `seamless` view must be hidden.
 *
 * The `holder` field on `MiuiMediaViewControllerImpl` and the `seamless` field on both holder
 * classes are public, so no reflection is needed beyond a plain field read.
 *
 * The master switch gates hook installation and needs a SystemUI restart.
 */
object MediaCardHideDeviceSwitchHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "MediaCardHideDeviceSwitch"
    private const val SHADE_VC =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl"
    private const val SHADE_HOLDER =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder"
    private const val ISLAND_BINDER =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl"
    private const val ISLAND_HOLDER =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder"

    @Volatile
    private var shadeHolderField: Field? = null

    @Volatile
    private var shadeSeamlessField: Field? = null

    @Volatile
    private var islandSeamlessField: Field? = null

    override fun onHook() {
        shadeHolderField = null
        shadeSeamlessField = null
        islandSeamlessField = null
        if (!Preferences.getBoolean(Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)) {
            DebugLog.hookSkipped(TAG, "media card device switch", "disabled")
            return
        }
        installShadeHook()
        installIslandHook()
    }

    override fun onPrepareHotReload() {
        shadeHolderField = null
        shadeSeamlessField = null
        islandSeamlessField = null
    }

    // ─── Shade card ───────────────────────────────────────────────────────────

    private fun installShadeHook() {
        val vc = SHADE_VC.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, SHADE_VC, "class not found")
            return
        }
        val holderCls = SHADE_HOLDER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, SHADE_HOLDER, "class not found")
            return
        }
        shadeHolderField = findField(vc, "holder")
        if (shadeHolderField == null) {
            DebugLog.hookSkipped(TAG, "$SHADE_VC#holder", "field not found")
            return
        }
        shadeSeamlessField = findField(holderCls, "seamless")
        if (shadeSeamlessField == null) {
            DebugLog.hookSkipped(TAG, "$SHADE_HOLDER#seamless", "field not found")
            return
        }
        val setSeamless = vc.declaredMethods.firstOrNull {
            it.name == "setSeamless" && it.parameterTypes.size == 1
        } ?: run {
            DebugLog.hookSkipped(TAG, "$SHADE_VC#setSeamless", "method not found")
            return
        }
        setSeamless.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "shade setSeamless", Unit) {
                    val holder = shadeHolderField?.get(param.thisObject) ?: return@open
                    hideSeamless(holder, shadeSeamlessField)
                }
            }
        }
        DebugLog.d(TAG, "shade media card device switch hidden after setSeamless")
    }

    // ─── Island ───────────────────────────────────────────────────────────────

    private fun installIslandHook() {
        val binder = ISLAND_BINDER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ISLAND_BINDER, "class not found")
            return
        }
        val holderCls = ISLAND_HOLDER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ISLAND_HOLDER, "class not found")
            return
        }
        islandSeamlessField = findField(holderCls, "seamless")
        if (islandSeamlessField == null) {
            DebugLog.hookSkipped(TAG, "$ISLAND_HOLDER#seamless", "field not found")
            return
        }
        val setSeamless = binder.declaredMethods.firstOrNull {
            it.name == "setSeamless" && it.parameterTypes.size == 2
        } ?: run {
            DebugLog.hookSkipped(TAG, "$ISLAND_BINDER#setSeamless", "method not found")
            return
        }
        setSeamless.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "island setSeamless", Unit) {
                    // Real holder + dummy holder, both fed through the same method.
                    hideSeamless(param.args.getOrNull(1), islandSeamlessField)
                }
            }
        }
        DebugLog.d(TAG, "island media card device switch hidden after setSeamless")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun hideSeamless(holder: Any?, field: Field?) {
        val seamless = field?.get(holder) as? View ?: return
        if (seamless.visibility != View.GONE) seamless.visibility = View.GONE
    }

    /** Resolves a (public) field, walking the class hierarchy. */
    private fun findField(cls: Class<*>, name: String): Field? {
        var type: Class<*>? = cls
        while (type != null && type != Any::class.java) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        return null
    }
}
