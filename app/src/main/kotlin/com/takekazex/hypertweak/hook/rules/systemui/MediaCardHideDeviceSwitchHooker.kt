package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hides the device-switch button on the media cards (隐藏设备切换按钮), OS4 SystemUI.
 *
 * The button has three independent render chains with their own holder classes:
 * - shade card (`miui_media_session.xml`): `@id/media_seamless` (a 34dp `LinearLayout` pinned to
 *   the top-right corner). Its visibility is set by `MiuiMediaViewControllerImpl.setSeamless(
 *   MediaData)` — `GONE` while casting video, `VISIBLE` otherwise — and that method is the only
 *   entry every bind walks (`attach` :467 and `onMediaDataChanged` :818), so forcing the holder's
 *   `seamless` view GONE right after it returns sticks for the lifetime of that holder.
 * - island (`miui_media_session_island`): the mirror chain
 *   `MiuiIslandMediaViewBinderImpl.setSeamless(MediaData, MiuiIslandMediaViewHolder)` :887, called
 *   with both the real and the dummy holder from `onMediaDataChanged` :743-744; the second
 *   parameter is the holder whose `seamless` view must be hidden.
 * - notification shade/keyguard AOSP cards (`MediaControlPanel`): `bindPlayer()` makes the
 *   `MediaViewHolder.seamless` output switch visible and installs its click listener on every
 *   media refresh, so it needs its own final after-hook.
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
    private const val TRANSFER_MANAGER =
        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaTransferManagerImpl"
    private const val AOSP_MEDIA_PANEL =
        "com.android.systemui.media.controls.ui.controller.MediaControlPanel"
    private const val AOSP_MEDIA_HOLDER =
        "com.android.systemui.media.controls.ui.view.MediaViewHolder"
    private const val SYSTEMUI_IDS = "com.android.systemui.R\$id"
    private const val FALLBACK_MEDIA_SEAMLESS_ID = 0x7f0b071c
    private const val FALLBACK_MEDIA_SEAMLESS_BUTTON_ID = 0x7f0b071d
    private const val FALLBACK_MEDIA_SEAMLESS_IMAGE_ID = 0x7f0b071e

    private val mediaSwitchResourceNames = setOf(
        "media_seamless",
        "media_seamless_button",
        "media_seamless_image"
    )

    @Volatile
    private var shadeHolderField: Field? = null

    @Volatile
    private var shadeSeamlessField: Field? = null

    @Volatile
    private var islandSeamlessField: Field? = null

    @Volatile
    private var islandSeamlessButtonField: Field? = null

    @Volatile
    private var islandSeamlessIconField: Field? = null

    @Volatile
    private var shadeSeamlessButtonField: Field? = null

    @Volatile
    private var shadeSeamlessIconField: Field? = null

    @Volatile
    private var aospHolderField: Field? = null

    @Volatile
    private var aospSeamlessField: Field? = null

    @Volatile
    private var aospSeamlessButtonField: Field? = null

    @Volatile
    private var aospSeamlessIconField: Field? = null

    @Volatile
    private var mediaSwitchVisibilityIds: IntArray = intArrayOf()

    private val visibilityGuardLogged = AtomicBoolean(false)

    override fun onHook() {
        shadeHolderField = null
        shadeSeamlessField = null
        islandSeamlessField = null
        shadeSeamlessButtonField = null
        shadeSeamlessIconField = null
        islandSeamlessButtonField = null
        islandSeamlessIconField = null
        aospHolderField = null
        aospSeamlessField = null
        aospSeamlessButtonField = null
        aospSeamlessIconField = null
        mediaSwitchVisibilityIds = intArrayOf()
        visibilityGuardLogged.set(false)
        if (!Preferences.getBoolean(Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)) {
            DebugLog.hookSkipped(TAG, "media card device switch", "disabled")
            return
        }
        installVisibilityGuard()
        installShadeHook()
        installIslandHook()
        installTransferManagerHook()
        installAospMediaPanelHook()
    }

    override fun onPrepareHotReload() {
        shadeHolderField = null
        shadeSeamlessField = null
        islandSeamlessField = null
        shadeSeamlessButtonField = null
        shadeSeamlessIconField = null
        islandSeamlessButtonField = null
        islandSeamlessIconField = null
        aospHolderField = null
        aospSeamlessField = null
        aospSeamlessButtonField = null
        aospSeamlessIconField = null
        mediaSwitchVisibilityIds = intArrayOf()
        visibilityGuardLogged.set(false)
    }

    /**
     * Final guard for both MIUI and AOSP media layouts. Their bind methods can run in different
     * orders (and other SystemUI components can refresh the transfer chip later), so guarding the
     * three exact SystemUI resource IDs is the only reliable last-write boundary. The ID check is
     * intentionally done before entering the failure wrapper because View#setVisibility is hot.
     */
    private fun installVisibilityGuard() {
        val idClass = SYSTEMUI_IDS.toClassOrNull()
        val reflectedIds = idClass?.let {
            listOf("media_seamless", "media_seamless_button", "media_seamless_image")
                .mapNotNull { name ->
                    runCatching {
                        it.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
                    }.getOrNull()
                }
                .toIntArray()
        }
        val ids = reflectedIds?.takeIf { it.isNotEmpty() }
            ?: intArrayOf(
                FALLBACK_MEDIA_SEAMLESS_ID,
                FALLBACK_MEDIA_SEAMLESS_BUTTON_ID,
                FALLBACK_MEDIA_SEAMLESS_IMAGE_ID
            ).also {
                DebugLog.w(TAG, "$SYSTEMUI_IDS unavailable; using verified device resource IDs")
            }
        mediaSwitchVisibilityIds = ids

        val viewClass = "android.view.View".toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "android.view.View", "class not found")
            return
        }
        val setVisibility = viewClass.declaredMethods.firstOrNull {
            it.name == "setVisibility" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.returnType == Void.TYPE
        } ?: run {
            DebugLog.hookSkipped(TAG, "android.view.View#setVisibility", "method not found")
            return
        }
        deoptimize(setVisibility)
        setVisibility.hook {
            before { param ->
                val view = param.thisObject as? View
                if (view != null && isMediaSwitchView(view)) {
                    HookFailurePolicy.open(TAG, "media switch View#setVisibility", Unit) {
                        val requested = param.args.getOrNull(0) as? Int
                        if (requested != null && requested != View.GONE) {
                            param.args[0] = View.GONE
                            if (visibilityGuardLogged.compareAndSet(false, true)) {
                                DebugLog.i(TAG, "final visibility guard engaged for media output switch")
                            }
                        }
                    }
                }
            }
        }
        DebugLog.d(TAG, "final media output switch visibility guard installed ids=${ids.joinToString()}")
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
        shadeSeamlessButtonField = findField(holderCls, "seamlessButton")
        shadeSeamlessIconField = findField(holderCls, "seamlessIcon")
        val setSeamless = vc.declaredMethods.firstOrNull {
            it.name == "setSeamless" && it.parameterTypes.size == 1
        } ?: run {
            DebugLog.hookSkipped(TAG, "$SHADE_VC#setSeamless", "method not found")
            return
        }
        deoptimize(setSeamless)
        setSeamless.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "shade setSeamless", Unit) {
                    val holder = shadeHolderField?.get(param.thisObject) ?: return@open
                    hideMediaSwitchViews(
                        holder,
                        shadeSeamlessField,
                        shadeSeamlessButtonField,
                        shadeSeamlessIconField
                    )
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
        islandSeamlessButtonField = findField(holderCls, "seamlessButton")
        islandSeamlessIconField = findField(holderCls, "seamlessIcon")
        val setSeamless = binder.declaredMethods.firstOrNull {
            it.name == "setSeamless" && it.parameterTypes.size == 2
        } ?: run {
            DebugLog.hookSkipped(TAG, "$ISLAND_BINDER#setSeamless", "method not found")
            return
        }
        deoptimize(setSeamless)
        setSeamless.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "island setSeamless", Unit) {
                    // Real holder + dummy holder, both fed through the same method.
                    val holder = param.args.getOrNull(1)
                    hideMediaSwitchViews(
                        holder,
                        islandSeamlessField,
                        islandSeamlessButtonField,
                        islandSeamlessIconField
                    )
                }
            }
        }
        DebugLog.d(TAG, "island media card device switch hidden after setSeamless")
    }

    // ─── Media transfer refresh ──────────────────────────────────────────────

    /**
     * `setSeamless()` delegates to this manager on the non-debug path. The manager can update the
     * transfer chip later from a posted callback, so hiding only immediately after setSeamless()
     * is not sufficient on OS4.
     */
    private fun installTransferManagerHook() {
        val manager = TRANSFER_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, TRANSFER_MANAGER, "class not found")
            return
        }
        val applyMediaTransferView = manager.declaredMethods.firstOrNull {
            it.name == "applyMediaTransferView" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == ViewGroup::class.java
        }
        if (applyMediaTransferView == null) {
            DebugLog.hookSkipped(TAG, "$TRANSFER_MANAGER#applyMediaTransferView", "method not found")
        } else {
            deoptimize(applyMediaTransferView)
            applyMediaTransferView.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "transfer applyMediaTransferView", Unit) {
                        hideSeamless(param.args.getOrNull(0) as? ViewGroup)
                    }
                }
            }
            DebugLog.d(TAG, "media transfer view hidden after applyMediaTransferView")
        }

        val updateChip = manager.declaredMethods.firstOrNull {
            it.name == "updateChip" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == android.widget.ImageView::class.java
        }
        if (updateChip == null) {
            DebugLog.hookSkipped(TAG, "$TRANSFER_MANAGER#updateChip", "method not found")
        } else {
            deoptimize(updateChip)
            updateChip.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "transfer updateChip", Unit) {
                        hideSeamlessContainer(param.args.getOrNull(0) as? View)
                    }
                }
            }
            DebugLog.d(TAG, "media transfer view hidden after updateChip")
        }
    }

    // ─── AOSP media panel ─────────────────────────────────────────────────────

    /**
     * Notification shade and keyguard media cards on this build use the AOSP
     * `MediaControlPanel`, not the MIUI media controller above. `bindPlayer()` makes the output
     * switch visible and installs its click listener on every app/media refresh, which is why an
     * app switch can make the button reappear after the MIUI hook already ran.
     */
    private fun installAospMediaPanelHook() {
        val panel = AOSP_MEDIA_PANEL.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, AOSP_MEDIA_PANEL, "class not found")
            return
        }
        val holderCls = AOSP_MEDIA_HOLDER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, AOSP_MEDIA_HOLDER, "class not found")
            return
        }
        aospHolderField = findField(panel, "mMediaViewHolder")
        aospSeamlessField = findField(holderCls, "seamless")
        aospSeamlessButtonField = findField(holderCls, "seamlessButton")
        aospSeamlessIconField = findField(holderCls, "seamlessIcon")
        if (aospHolderField == null || aospSeamlessField == null) {
            DebugLog.hookSkipped(TAG, "$AOSP_MEDIA_PANEL#bindPlayer", "holder fields not found")
            return
        }
        val bindPlayer = panel.declaredMethods.firstOrNull {
            it.name == "bindPlayer" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0].name == "com.android.systemui.media.controls.shared.model.MediaData" &&
                it.parameterTypes[1] == String::class.java
        } ?: run {
            DebugLog.hookSkipped(TAG, "$AOSP_MEDIA_PANEL#bindPlayer", "method not found")
            return
        }
        deoptimize(bindPlayer)
        bindPlayer.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "AOSP media bindPlayer", Unit) {
                    val holder = aospHolderField?.get(param.thisObject) ?: return@open
                    hideMediaSwitchViews(
                        holder,
                        aospSeamlessField,
                        aospSeamlessButtonField,
                        aospSeamlessIconField
                    )
                }
            }
        }
        DebugLog.i(TAG, "HOOK_OK AOSP notification/keyguard media output switch disabled")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun hideSeamless(holder: Any?, field: Field?) {
        val seamless = field?.get(holder) as? View ?: return
        hideSeamless(seamless)
    }

    private fun hideMediaSwitchViews(
        holder: Any?,
        seamlessField: Field?,
        buttonField: Field?,
        iconField: Field?
    ) {
        hideSeamless(holder, seamlessField)
        hideViewField(holder, buttonField, disableInteraction = true)
        hideViewField(holder, iconField, disableInteraction = true)
    }

    private fun hideViewField(holder: Any?, field: Field?, disableInteraction: Boolean) {
        val view = runCatching { field?.get(holder) as? View }.getOrNull() ?: return
        hideSeamless(view)
        if (disableInteraction) {
            view.isClickable = false
            view.isEnabled = false
            view.setOnClickListener(null)
        }
    }

    private fun hideSeamless(view: View?) {
        if (view != null && view.visibility != View.GONE) view.visibility = View.GONE
    }

    private fun isMediaSwitchView(view: View): Boolean {
        if (!mediaSwitchVisibilityIds.contains(view.id)) return false
        return runCatching {
            view.resources.getResourceEntryName(view.id) in mediaSwitchResourceNames
        }.getOrDefault(false)
    }

    private fun hideSeamlessContainer(view: View?) {
        var current = view
        repeat(5) {
            if (current == null) return
            if (isMediaSeamlessView(current)) {
                hideSeamless(current as? ViewGroup)
                return
            }
            current = current.parent as? View
        }
        hideSeamless((view?.parent as? ViewGroup))
    }

    private fun isMediaSeamlessView(view: View): Boolean {
        return runCatching {
            view.id != View.NO_ID && view.resources.getResourceEntryName(view.id) == "media_seamless"
        }.getOrDefault(false)
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
