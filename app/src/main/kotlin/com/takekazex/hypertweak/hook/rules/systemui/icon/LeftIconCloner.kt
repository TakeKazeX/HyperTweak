package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 图标左置 — shows selected status-bar slots (勿扰 zen, 静音/振动 volume+mute, 热点 hotspot,
 * 闹钟 alarm_clock, 定位 location+gps, 蓝牙 bluetooth, NFC, VPN, 飞行模式 airplane,
 * 耳机 headset+wireless_headset) in a container right after the home-screen clock, hidden from
 * the right cluster. Ported from Hyper Helper's `LeftContainer` (OS4_ADAPTATION_PLAN.md T2,
 * `icon_tuner_left_container`) and rebuilt for the OS4 home status bar.
 *
 * OS4 reality (all targets verified on OS4.0.0.15.XPMCNXM): upstream's OS3 `LeftContainer`
 * registered a SECOND icon group via `StatusBarIconControllerImpl.addIconGroup` and hid the slots
 * from the right cluster through `RIGHT_BLOCK_LIST`. A second `DarkIconManager` cannot be built on
 * OS4 without the Dagger factory and would double-bind the Kairos cellular/WiFi pipeline into the
 * left container, so this port keeps the single home manager as source of truth and does NOT move
 * any view or touch the index scheme at all:
 *
 * - **Right-cluster hiding**: the home `DarkIconManager.setBlockList(List)` is hooked to append
 *   the currently selected slots to the system block list (which the OS4
 *   `HomeStatusBarIconBlockListBinder`/`HomeStatusBarIconBlockListInteractor` machinery applies).
 *   Blocked holders are still added by `addHolder` (z flag) but are not measured
 *   (`MiuiStatusIconContainer.onMeasure` skips `isIconBlocked()` children) and not visible —
 *   exactly the system's own icon_blacklist behavior. The hook remembers the pristine system list
 *   on every emission and re-applies system ∪ selected; the 1.5 s main-thread ticker re-applies it
 *   when the selection changes, so toggling the master or a single slot takes effect live in both
 *   directions (the right cluster shows the icon again within ~1.5 s when switched off).
 * - **Left rendering**: for each selected slot a fresh `StatusBarIconView` clone is created in a
 *   LinearLayout inserted right after the clock (`R.id.phone_status_bar_left_container`,
 *   status_bar.xml: clock → [left icons] → chips → notification area). Its `layoutParams` are
 *   copied from the system's own right-cluster view of the same slot on every sync, so the box
 *   and the glyph (`set(mIcon)` plus the native `adjustViewBounds` behavior) match the right
 *   cluster exactly — this avoids the sizing drift the earlier view-relocation version had. The clones are small boxes
 *   (classic slots: WRAP_CONTENT × `status_bar_icon_height` 20dp) that the right cluster's
 *   `MiuiStatusIconContainer.onLayout` centers vertically itself; in our container the clones
 *   are centered by the container's own `gravity = CENTER_VERTICAL` (plus a per-clone
 *   `layoutParams.gravity` override) — without it LinearLayout's default TOP gravity parks the
 *   boxes against the top of the full-height container and the icons render raised (被抬高).
 *   Dark tint comes from the manager's `DarkIconDispatcher` (the clones are registered as dark
 *   receivers).
 * - **Sync**: `onIconAdded` / `onSetIcon` / `onRemoveIcon` after-hooks re-run the idempotent
 *   clone sync (slot scan, no indices) whenever the bar changes; the ticker reconciles anything
 *   missed and drives on/off transitions.
 *
 * **Threading / hot reload**: `onPrepareHotReload` runs on the LSPosed binder thread and must not
 * mutate views directly. State capture and cleanup finish on the main thread before the old
 * generation retires; framework view references and panel progress rebind the replacement.
 */

/** Owns cloned payloads, dark receiver registration and visibility animations. */
internal class LeftIconCloner(owner: BaseHooker) : HookComponent(owner) {
    private companion object {
        const val TAG = "IconTuner"
        const val STATUS_BAR_ICON_VIEW_CLASS = "com.android.systemui.statusbar.StatusBarIconView"
    }
    private var iconViewConstructor: java.lang.reflect.Constructor<*>? = null
    private var iconViewMIconField: Field? = null
    private var iconPayloadCloneMethod: Method? = null
    private var isIconVisibleMethod: Method? = null
    private var iconPayloadVisibleField: Field? = null
    private var iconViewSetMethod: Method? = null
    private var iconViewSetResolved = false
    private var darkDispatcherField: Field? = null
    fun isAnimating(view: View): Boolean = cloneFades[view]?.animator != null
    fun cancel(view: View) { cloneFades[view]?.animator?.cancel() }
    fun forget(view: View) { cloneFades.remove(view)?.animator?.cancel() }
    fun reset() {
        iconViewConstructor = null
        iconViewMIconField = null
        iconPayloadCloneMethod = null
        isIconVisibleMethod = null
        iconPayloadVisibleField = null
        iconViewSetMethod = null
        iconViewSetResolved = false
        darkDispatcherField = null
    }
    fun prepare(): Boolean {

        val iconViewClass = STATUS_BAR_ICON_VIEW_CLASS.toClassOrNull()
        if (iconViewClass == null) {
            DebugLog.hookSkipped(TAG, STATUS_BAR_ICON_VIEW_CLASS, "class not found")
            return false
        }
        iconViewConstructor = runCatching {
            iconViewClass.getConstructor(
                android.content.Context::class.java,
                String::class.java,
                Class.forName("android.service.notification.StatusBarNotification"),
                Boolean::class.javaPrimitiveType
            )
        }.getOrNull()
        if (iconViewConstructor == null) {
            DebugLog.hookSkipped(TAG, "$STATUS_BAR_ICON_VIEW_CLASS#<init>", "constructor not found")
            return false
        }
        resolveIconViewSetter()
        iconViewMIconField = runCatching {
            iconViewClass.getDeclaredField("mIcon").apply { isAccessible = true }
        }.getOrNull()
        isIconVisibleMethod = runCatching {
            iconViewClass.getMethod("isIconVisible")
        }.getOrNull()

        return true
    }
    fun createClone(context: android.content.Context, slot: String): View? {
        val ctor = iconViewConstructor ?: return null
        if (iconViewSetMethod == null) return null
        val clone = runCatching {
            ctor.newInstance(context, slot, null, false)
        }.getOrNull() as? View ?: return null
        // IconManager enables this on every native StatusBarIconView. Without it, the special
        // 58x56dp airplane drawable measures at its intrinsic width instead of fitting the
        // 20dp status-bar slot, producing an abnormally wide clone.
        (clone as? android.widget.ImageView)?.apply {
            setAdjustViewBounds(true)
            // Native CENTER draws the 58dp airplane outside its measured status-bar box.
            if (slot == "airplane") scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        return clone
    }

    fun updateClone(clone: View, child: View): Boolean {
        // Size: copy the system view's own layout params so the box matches the right cluster
        // exactly (this is what fixes the misplaced height of the earlier view-move version).
        runCatching {
                    val src = child.layoutParams
                    if (src != null) {
                        val cur = clone.layoutParams
                        val marginsDiffer = when {
                            cur is ViewGroup.MarginLayoutParams && src is ViewGroup.MarginLayoutParams ->
                                cur.leftMargin != src.leftMargin || cur.topMargin != src.topMargin ||
                                    cur.rightMargin != src.rightMargin || cur.bottomMargin != src.bottomMargin
                            else -> (cur is ViewGroup.MarginLayoutParams) !=
                                (src is ViewGroup.MarginLayoutParams)
                        }
                        if (cur == null || cur.width != src.width || cur.height != src.height || marginsDiffer) {
                    val copy = when (src) {
                        is ViewGroup.MarginLayoutParams -> LinearLayout.LayoutParams(src)
                        else -> LinearLayout.LayoutParams(src)
                    }
                    // Per-child gravity overrides the container's, so the clone is always
                    // vertically centered even if some slot's source params carry a stray
                    // gravity (the right cluster centers ~20dp boxes inside the full-height
                    // bar; TOP alignment made the clones render raised).
                    copy.gravity = android.view.Gravity.CENTER_VERTICAL
                    clone.layoutParams = copy
                }
            }
        }
        // Icon payload: mirror the child's StatusBarIcon so scale/desc/colors are identical.
        val payloadSet = runCatching {
            val icon = iconViewMIconField?.get(child)
            val clonedIcon = icon?.let { cloneIconPayload(it) } ?: return@runCatching false
            val setter = iconViewSetMethod ?: return@runCatching false
            val visibleField = iconPayloadVisibleField ?: HostAccess.field(clonedIcon.javaClass, "visible")
                ?.also { iconPayloadVisibleField = it } ?: return@runCatching false
            visibleField.setBoolean(clonedIcon, true)
            setter.invoke(clone, clonedIcon)
            true
        }.getOrDefault(false)
        if (!payloadSet) return false
        // Visibility mirrors the system (icon logically active).
        val visible = isIconVisibleMethod?.let { m ->
            runCatching { m.invoke(child) as? Boolean }.getOrNull()
        } ?: false
        animateCloneVisibility(clone, visible)
        return true
    }

    private class CloneFade(var visible: Boolean? = null, var animator: android.animation.ValueAnimator? = null)
    private val cloneFades = WeakHashMap<View, CloneFade>()

    fun animateCloneVisibility(clone: View, visible: Boolean) {
        val image = clone as? android.widget.ImageView ?: run {
            clone.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        val fade = cloneFades.getOrPut(clone) { CloneFade() }
        if (fade.visible == visible) {
            if (!visible && fade.animator == null) clone.visibility = View.GONE
            return
        }
        val initial = fade.visible == null
        fade.animator?.cancel()
        fade.animator = null
        fade.visible = visible
        if (initial) image.imageAlpha = 0
        clone.visibility = View.VISIBLE
        val target = if (visible) 255 else 0
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || (!visible && initial)) {
            image.imageAlpha = target
            clone.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }
        fade.animator = android.animation.ValueAnimator.ofInt(image.imageAlpha, target).apply {
            duration = 200L
            addUpdateListener { image.imageAlpha = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (fade.animator !== animation) return
                    fade.animator = null
                    if (fade.visible == false) clone.visibility = View.GONE
                }
            })
            start()
        }
    }

    /** StatusBarIcon is mutable; use its host clone implementation before StatusBarIconView.set(). */
    private fun cloneIconPayload(icon: Any): Any? {
        val method = iconPayloadCloneMethod ?: HostAccess.method(icon, "clone")?.also { iconPayloadCloneMethod = it } ?: return null
        return runCatching { method.invoke(icon) }.getOrNull()
    }

    private fun managerDarkDispatcher(manager: Any): Any? {
        if (darkDispatcherField == null) {
            var c: Class<*>? = manager.javaClass
            while (c != null) {
                val f = runCatching {
                    c.getDeclaredField("mDarkIconDispatcher").apply { isAccessible = true }
                }.getOrNull()
                if (f != null) {
                    darkDispatcherField = f
                    break
                }
                c = c.superclass
            }
        }
        val f = darkDispatcherField ?: return null
        return runCatching { f.get(manager) }.getOrNull()
    }

    fun registerDarkReceiver(manager: Any, clone: View) {
        val dispatcher = managerDarkDispatcher(manager) ?: return
        runCatching {
            val method = dispatcher.javaClass.methods.firstOrNull {
                it.name == "addDarkReceiver" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(clone.javaClass)
            } ?: dispatcher.javaClass.methods.firstOrNull {
                it.name == "addDarkReceiver" && it.parameterTypes.size == 1
            } ?: return
            method.invoke(dispatcher, clone)
        }
    }

    fun unregisterDarkReceiver(manager: Any, clone: View) {
        val dispatcher = managerDarkDispatcher(manager) ?: return
        runCatching {
            val method = dispatcher.javaClass.methods.firstOrNull {
                it.name == "removeDarkReceiver" && it.parameterTypes.size == 1
            } ?: return
            method.invoke(dispatcher, clone)
        }
    }

    private fun resolveIconViewSetter(): Method? {
        iconViewSetMethod?.let { return it }
        if (iconViewSetResolved) return null
        iconViewSetResolved = true
        val cls = STATUS_BAR_ICON_VIEW_CLASS.toClassOrNull() ?: return null
        val iconCls = runCatching {
            Class.forName("com.android.internal.statusbar.StatusBarIcon")
        }.getOrNull() ?: return null
        var c: Class<*>? = cls
        while (c != null) {
            runCatching {
                c.getDeclaredMethod("set", iconCls).apply { isAccessible = true }
            }.getOrNull()?.let { method ->
                iconViewSetMethod = method
                return method
            }
            c = c.superclass
        }
        return null
    }

}
