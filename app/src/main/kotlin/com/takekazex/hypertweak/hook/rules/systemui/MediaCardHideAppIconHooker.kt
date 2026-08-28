package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field

/**
 * Hides the source-app icon overlay on the media cards (封面隐藏来源应用图标), OS4 SystemUI.
 *
 * The notification-shade media card (`miui_media_session.xml`) stacks a 24dp `CachingIconView`
 * (`@id/icon`, the playing app's launcher icon) on the top-left corner of the album cover, and
 * the island media card (`miui_media_session_island`) mirrors the same layout. The icon is
 * written by the flip-animation callback (`MiuiMediaViewControllerImpl$startFlipAnimation$1` /
 * `MiuiIslandMediaViewBinderImpl$startFlipAnimation$1` via `holder.appIcon.setImageDrawable`),
 * and no code ever restores the view to VISIBLE, so hiding it once per attach is permanent for
 * that holder instance.
 *
 * Two independent render chains, each with its own holder and binder:
 * - shade card: `MiuiMediaViewControllerImpl.attach(MiuiMediaViewHolder)`; hide
 *   `holder.appIcon`;
 * - island: `MiuiIslandMediaViewBinderImpl.attach(MiuiIslandMediaViewHolder,
 *   MiuiIslandMediaViewHolder)` (real + dummy holder); hide both `appIcon` views.
 *
 * The master switch gates hook installation and needs a SystemUI restart.
 */
object MediaCardHideAppIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "MediaCardHideAppIcon"
    private const val SHADE_VC =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl"
    private const val SHADE_HOLDER =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder"
    private const val ISLAND_BINDER =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl"
    private const val ISLAND_HOLDER =
        "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder"

    @Volatile
    private var shadeAppIconField: Field? = null

    @Volatile
    private var islandAppIconField: Field? = null

    override fun onHook() {
        shadeAppIconField = null
        islandAppIconField = null
        if (!Preferences.getBoolean(Preferences.KEY_MEDIA_CARD_HIDE_APP_ICON, false)) {
            DebugLog.hookSkipped(TAG, "media card app icon", "disabled")
            return
        }
        installShadeHook()
        installIslandHook()
    }

    override fun onPrepareHotReload() {
        shadeAppIconField = null
        islandAppIconField = null
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
        val attach = vc.declaredMethods.firstOrNull {
            it.name == "attach" && it.parameterTypes.size == 1
        } ?: run {
            DebugLog.hookSkipped(TAG, "$SHADE_VC#attach", "method not found")
            return
        }
        shadeAppIconField = findAppIconField(holderCls)
        if (shadeAppIconField == null) {
            DebugLog.hookSkipped(TAG, "$SHADE_HOLDER#appIcon", "field not found")
            return
        }
        attach.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "shade attach", Unit) {
                    val holder = param.args.getOrNull(0) ?: return@open
                    hideAppIcon(holder, shadeAppIconField)
                }
            }
        }
        DebugLog.d(TAG, "shade media card app icon hidden on attach")
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
        val attach = binder.declaredMethods.firstOrNull {
            it.name == "attach" && it.parameterTypes.size == 2
        } ?: run {
            DebugLog.hookSkipped(TAG, "$ISLAND_BINDER#attach", "method not found")
            return
        }
        islandAppIconField = findAppIconField(holderCls)
        if (islandAppIconField == null) {
            DebugLog.hookSkipped(TAG, "$ISLAND_HOLDER#appIcon", "field not found")
            return
        }
        attach.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "island attach", Unit) {
                    // Real holder + dummy holder (the second may be null).
                    hideAppIcon(param.args.getOrNull(0), islandAppIconField)
                    hideAppIcon(param.args.getOrNull(1), islandAppIconField)
                }
            }
        }
        DebugLog.d(TAG, "island media card app icon hidden on attach")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun hideAppIcon(holder: Any?, field: Field?) {
        val icon = field?.get(holder) as? View ?: return
        if (icon.visibility != View.GONE) icon.visibility = View.GONE
    }

    /** Resolves the public `appIcon` ImageView field, walking the class hierarchy. */
    private fun findAppIconField(cls: Class<*>): Field? {
        var type: Class<*>? = cls
        while (type != null && type != Any::class.java) {
            try {
                return type.getDeclaredField("appIcon").apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        return null
    }
}
