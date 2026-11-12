package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.ViewVisibilityRouter
import com.takekazex.hypertweak.presentation.icon.PlanSurface
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * Reduces the native mobile row of selected subscriptions to its standalone type label.
 *
 * With stacked signal on, the module draws the bars and the host keeps drawing the 5G text, so
 * neither is rendered twice. OS4 `status_bar_mobile_signal_group_inner` puts the label
 * (`mobile_type_single`) and the signal container side by side in `mobile_group`, next to the
 * HD/roaming/VoLTE/VoWiFi indicators; the MIUI binder writes each child's visibility through
 * `View#setVisibility` (directly or via `MobileSignalAnimatorContainer#setChildVisible`). Every
 * child except the label is held GONE while its row is type-only; the host's latest request is
 * kept and restored when the row is released, so the binder never has to re-emit.
 */
internal class NativeTypeOnlyMask(owner: BaseHooker, private val surfaceOf: (View) -> PlanSurface?) : HookComponent(owner) {
    private companion object {
        const val TAG = "IconTuner"
        const val MOBILE_VIEW = "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView"
        val CHILD_CLASSES = listOf(
            "com.android.systemui.statusbar.views.MobileSignalAnimatorImageView",
            "com.android.systemui.statusbar.views.MobileSignalAnimatorTextView",
            "com.android.systemui.statusbar.views.MobileSignalAnimatorView"
        )
    }

    private val requests = NativeViewVisibilityMask(View.GONE)
    /** Every native `mobile_group` seen so far; weak so recycled rows drop out by themselves. */
    private val groups = WeakHashMap<ViewGroup, Boolean>()
    private var typeOnly: Map<PlanSurface, Set<Int>> = emptyMap()
    private var mobileView: Class<*>? = null
    private var subIdField: Field? = null
    private var groupId = 0
    private var typeId = 0
    private var applying = false

    val installed: Boolean get() = subIdField != null

    /** Once per generation: the visibility routes are owned by the hooker's generation. */
    fun install(): Boolean {
        val result = runCatching {
            val view = MOBILE_VIEW.toClass()
            val field = HostAccess.requireDeclaredField(view, "subId", accessible = true)
            check(field.type == Int::class.javaPrimitiveType) { "subId is not an int" }
            val children = CHILD_CLASSES.map { it.toClass() }
            mobileView = view
            subIdField = field
            children.forEach { type ->
                ViewVisibilityRouter.visibility(owner, type, before = { child, requested -> route(child, requested) })
            }
        }
        result.onFailure {
            mobileView = null
            subIdField = null
            DebugLog.hookSkipped(TAG, "native type-only mobile row", it.message ?: "host row contract changed")
        }
        return result.isSuccess
    }

    /** Main thread only. Re-applies the mask to every known row whose membership changed. */
    fun publish(subIds: Map<PlanSurface, Set<Int>>) {
        if (!installed || subIds == typeOnly) return
        typeOnly = subIds
        groups.keys.toList().forEach(::reconcile)
    }

    /** Living rows after hot reload; a cold start discovers them through the binder's writes. */
    fun adopt(views: Iterable<View>) {
        if (!installed) return
        views.forEach { view ->
            if (mobileView?.isInstance(view) != true) return@forEach
            resolveIds(view)
            if (groupId == 0) return@forEach
            val group = view.findViewById<View>(groupId) as? ViewGroup ?: return@forEach
            groups[group] = true
            reconcile(group)
        }
    }

    fun retire() {
        typeOnly = emptyMap()
        groups.keys.toList().forEach(::reconcile)
        groups.clear()
        mobileView = null
        subIdField = null
    }

    private fun route(child: View, requested: Int): Int? {
        if (applying || Looper.myLooper() != Looper.getMainLooper()) return null
        val group = child.parent as? ViewGroup ?: return null
        val root = group.parent as? View ?: return null
        if (mobileView?.isInstance(root) != true) return null
        resolveIds(root)
        if (groupId == 0 || group.id != groupId) return null
        groups[group] = true
        return requests.hostRequest(child, requested, masks(root, child))
    }

    private fun reconcile(group: ViewGroup) {
        val root = group.parent as? View ?: return
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val target = requests.reconcile(child, child.visibility, masks(root, child))
            if (child.visibility == target) continue
            applying = true
            try {
                child.visibility = target
            } catch (error: Throwable) {
                DebugLog.w(TAG, "type-only row visibility update failed", error)
            } finally {
                applying = false
            }
        }
    }

    private fun masks(root: View, child: View): Boolean {
        if (typeOnly.isEmpty() || typeId == 0 || child.id == typeId) return false
        val subId = runCatching { subIdField?.getInt(root) }.getOrNull() ?: return false
        return subId in typeOnly[surfaceOf(root)].orEmpty()
    }

    private fun resolveIds(view: View) {
        if (groupId != 0 && typeId != 0) return
        groupId = ResourceLookup.identifier(view.resources, "mobile_group", "id", "com.android.systemui")
        typeId = ResourceLookup.identifier(view.resources, "mobile_type_single", "id", "com.android.systemui")
    }
}
