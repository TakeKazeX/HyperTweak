package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
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
import java.lang.ref.WeakReference
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
 *
 * The ids and native roles must be unique inside the mobile view. The group may be wrapped and
 * the label may move within that view: mask sibling branches off the path to the label, keeping
 * its ancestors visible. Only missing/ambiguous native roles reject the contract; other modules
 * are neither detected nor accommodated.
 */
internal class NativeTypeOnlyMask(
    owner: BaseHooker,
    private val onContractBroken: () -> Unit = {},
    private val surfaceOf: (View) -> PlanSurface?
) : HookComponent(owner) {
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
    private val roots = WeakHashMap<View, Boolean>()
    // Values must not retain a child (and through its parents the weak key).
    private val targets = WeakHashMap<View, WeakReference<View>>()
    private var typeOnly: Map<PlanSurface, Set<Int>> = emptyMap()
    private var mobileView: Class<*>? = null
    private var subIdField: Field? = null
    private var groupId = 0
    private var typeId = 0
    private var signalId = 0
    private var childClasses: List<Class<*>> = emptyList()
    private var applying = false

    val installed: Boolean get() = subIdField != null
    /** A living row broke the layout contract; the mask stays released for this generation. */
    var contractBroken = false
        private set
    val usable: Boolean get() = installed && !contractBroken

    /** Once per generation: the visibility routes are owned by the hooker's generation. */
    fun install(): Boolean {
        val result = runCatching {
            val view = MOBILE_VIEW.toClass()
            val field = HostAccess.requireDeclaredField(view, "subId", accessible = true)
            check(field.type == Int::class.javaPrimitiveType) { "subId is not an int" }
            val children = CHILD_CLASSES.map { it.toClass() }
            mobileView = view
            subIdField = field
            childClasses = children
            // Wrappers may be ordinary ViewGroups, so visibility ownership follows the resolved
            // branch, not a hard-coded child class or parent depth.
            ViewVisibilityRouter.visibility(owner, before = { child, requested -> route(child, requested) })
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
        roots.keys.toList().forEach(::reconcile)
    }

    /** Living rows after hot reload; a cold start discovers them through the binder's writes. */
    fun adopt(views: Iterable<View>) {
        if (!installed) return
        views.forEach { view ->
            if (mobileView?.isInstance(view) != true) return@forEach
            roots[view] = true
            reconcile(view)
        }
    }

    fun retire() {
        typeOnly = emptyMap()
        roots.clear()
        targets.keys.toList().forEach { apply(it, false) }
        targets.clear()
        childClasses = emptyList()
        mobileView = null
        subIdField = null
        contractBroken = false
    }

    private fun route(child: View, requested: Int): Int? {
        if (applying || Looper.myLooper() != Looper.getMainLooper()) return null
        val knownRoot = targets[child]?.get()
        if (knownRoot != null) return requests.hostRequest(child, requested, active(knownRoot))
        if (child !is ViewGroup && childClasses.none { it.isInstance(child) }) return null
        val root = mobileAncestor(child) ?: return null
        if (roots[root] == true) return null // Preserved ancestor/label; layout adoption updates topology.
        // Discovery during inflation can precede the final sibling. Layout adoption validates the
        // completed row; do not reject an unfinished XML subtree from an early visibility write.
        val branches = resolveBranches(root) ?: return null
        roots[root] = true
        updateTargets(root, branches)
        return if (child in branches) requests.hostRequest(child, requested, active(root)) else null
    }

    /** Resolves known resource roles; hierarchy changes alone do not disable stacked rendering. */
    private fun resolveBranches(root: View): Set<View>? {
        resolveIds(root)
        if (groupId == 0 || typeId == 0 || signalId == 0) return null
        val views = ArrayList<View>()
        fun collect(view: View) {
            views += view
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(root)
        val group = views.singleOrNull { it.id == groupId } as? ViewGroup ?: return null
        val label = views.singleOrNull { it.id == typeId } as? android.widget.TextView ?: return null
        val signal = views.singleOrNull { it.id == signalId } as? ViewGroup ?: return null
        if (!isWithin(signal, group)) return null
        var boundary: View = group
        while (!isWithin(label, boundary)) boundary = boundary.parent as? View ?: return null
        if (!isWithin(boundary, root)) return null
        return TypeOnlyBranchPolicy.hiddenBranches(boundary, label, { it.parent as? View }) { view ->
            if (view is ViewGroup) (0 until view.childCount).map(view::getChildAt) else emptyList()
        }
    }

    private fun isWithin(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun mobileAncestor(view: View): View? {
        var current: View? = view
        while (current != null) {
            if (mobileView?.isInstance(current) == true) return current
            current = current.parent as? View
        }
        return null
    }

    private fun markBroken(reason: String) {
        if (contractBroken) return
        contractBroken = true
        DebugLog.w(TAG, "native type-only row contract unavailable: $reason; rejecting unverified host structure")
        targets.keys.toList().forEach { apply(it, false) }
        // Usually detected inside a host setVisibility call; re-plan outside that call.
        Handler(Looper.getMainLooper()).post {
            if (!contractBroken) return@post
            runCatching(onContractBroken).onFailure { DebugLog.w(TAG, "type-only contract rejection failed", it) }
        }
    }

    private fun reconcile(root: View) {
        val branches = resolveBranches(root)
        if (branches == null) {
            roots[root] = false
            markBroken("missing or ambiguous type/signal roles")
            updateTargets(root, emptySet())
        } else {
            roots[root] = true
            updateTargets(root, branches)
        }
    }

    private fun updateTargets(root: View, branches: Set<View>) {
        targets.entries.toList().filter { it.value.get() === root && it.key !in branches }.forEach {
            apply(it.key, false)
            targets.remove(it.key)
        }
        branches.forEach { child ->
            targets[child] = WeakReference(root)
            apply(child, active(root))
        }
    }

    private fun apply(child: View, masked: Boolean) {
        val target = requests.reconcile(child, child.visibility, masked)
        if (child.visibility == target) return
        applying = true
        try {
            child.visibility = target
        } catch (error: Throwable) {
            DebugLog.w(TAG, "type-only row visibility update failed", error)
        } finally {
            applying = false
        }
    }

    private fun active(root: View): Boolean {
        if (contractBroken || typeOnly.isEmpty() || roots[root] != true) return false
        val subId = runCatching { subIdField?.getInt(root) }.getOrNull() ?: return false
        return subId in typeOnly[surfaceOf(root)].orEmpty()
    }

    private fun resolveIds(view: View) {
        if (groupId != 0 && typeId != 0 && signalId != 0) return
        groupId = ResourceLookup.identifier(view.resources, "mobile_group", "id", "com.android.systemui")
        typeId = ResourceLookup.identifier(view.resources, "mobile_type_single", "id", "com.android.systemui")
        signalId = ResourceLookup.identifier(view.resources, "mobile_signal_container", "id", "com.android.systemui")
    }
}
