package com.takekazex.hypertweak.hook.rules.systemui.island.spectrum

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.takekazex.hypertweak.util.DebugLog
import java.util.LinkedHashMap

/**
 * Keeps a side music spectrum visible when another island module injects lyric text into the
 * same native template. Only the tagged lyric wrapper and its text container are hidden; native
 * album/waveform icons are restored inside the real side template and all changed visibility is
 * put back on release.
 */
internal class IslandLyricGuard(
    val roots: List<View>,
    private val active: () -> Boolean,
) {
    private class Snapshot(var visibility: Int, var alpha: Float)

    private val changed = LinkedHashMap<View, Snapshot>()
    private val registrations = ArrayList<Registration>()
    private val iconCache = HashMap<Int, Boolean>()
    private var released = false
    private var reported = false
    private var failureLogged = false
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            runCatching { register(view) }.onFailure { DebugLog.w(TAG, "lyric guard reattach failed", it) }
        }
        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    private class Registration(
        val root: View,
        var observer: ViewTreeObserver,
        var listener: ViewTreeObserver.OnPreDrawListener,
    )

    init {
        roots.distinct().forEach { root ->
            root.addOnAttachStateChangeListener(attachListener)
            register(root)
        }
    }

    fun release() {
        if (released) return
        released = true
        roots.forEach { it.removeOnAttachStateChangeListener(attachListener) }
        registrations.forEach { registration ->
            runCatching { registration.observer.removeOnPreDrawListener(registration.listener) }
        }
        registrations.clear()
        runCatching { restore() }.onFailure { DebugLog.w(TAG, "lyric guard restore failed", it) }
    }

    private fun register(root: View) {
        if (released) return
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!released) runCatching { apply() }
                .onFailure {
                    if (!failureLogged) {
                        failureLogged = true
                        DebugLog.w(TAG, "lyric guard failed", it)
                    }
                }
            true
        }
        val existing = registrations.firstOrNull { it.root === root }
        if (existing != null) {
            runCatching { existing.observer.removeOnPreDrawListener(existing.listener) }
            existing.observer = observer
            existing.listener = listener
        } else registrations += Registration(root, observer, listener)
        observer.addOnPreDrawListener(listener)
    }

    private fun apply() {
        if (!active()) {
            if (changed.isNotEmpty()) restore()
            return
        }
        var foundLyric = false
        roots.forEach { root ->
            if (root.isAttachedToWindow && walk(root)) foundLyric = true
        }
        if (foundLyric) {
            // The lyric module may hide only one side's native icon container. Restore every
            // spectrum icon inside this real side template, never outside it or in fake views.
            roots.forEach { root -> if (root.isAttachedToWindow) showNativeIcons(root) }
        }
    }

    /** Returns whether this subtree contains an injected lyric view. */
    private fun walk(node: View): Boolean {
        if (IslandLyricConflict.injectedTag(node.tag)) {
            if (!reported) {
                reported = true
                DebugLog.i(TAG, "small spectrum hid third-party lyric overlay")
            }
            hide(node)
            hideTextContainer(node)
            return true
        }
        if (node !is ViewGroup) return false
        var found = false
        for (index in 0 until node.childCount) {
            if (walk(node.getChildAt(index))) found = true
        }
        return found
    }

    /**
     * Only the direct lyric parent is considered; its visible native siblings (if any) remain
     * untouched and the ancestor chain is never hidden. This keeps app-return/middle-island
     * icons outside the side spectrum template unaffected.
     */
    private fun hideTextContainer(lyric: View) {
        val parent = lyric.parent as? ViewGroup ?: return
        var visibleNative = false
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (!IslandLyricConflict.injectedTag(child.tag) && child.visibility == View.VISIBLE) {
                visibleNative = true
                break
            }
        }
        if (!visibleNative) hide(parent)
    }

    private fun showNativeIcons(node: View) {
        if (IslandLyricConflict.injectedTag(node.tag)) return
        if (isNativeIcon(node)) show(node)
        if (node is ViewGroup) {
            for (index in 0 until node.childCount) showNativeIcons(node.getChildAt(index))
        }
    }

    private fun isNativeIcon(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        return iconCache.getOrPut(id) {
            val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
            IslandLyricConflict.nativeIconName(name)
        }
    }

    private fun show(view: View) {
        if (view.visibility == View.VISIBLE) return
        snapshot(view)
        view.visibility = View.VISIBLE
    }

    private fun hide(view: View) {
        if (view.visibility == View.GONE) return
        // keepVisible views stay VISIBLE after setVisibility(GONE); alpha already suppressed them.
        if (changed[view] != null && view.alpha == 0f) return
        snapshot(view)
        view.visibility = View.GONE
        // MaxWidthFrameLayout keepVisible overrides setVisibility(GONE); alpha still hides it.
        if (view.visibility != View.GONE) view.alpha = 0f
    }

    private fun snapshot(view: View) {
        changed.getOrPut(view) { Snapshot(view.visibility, view.alpha) }
    }

    private fun restore() {
        changed.entries.toList().asReversed().forEach { (view, snapshot) ->
            if (view.visibility != snapshot.visibility) view.visibility = snapshot.visibility
            if (view.alpha != snapshot.alpha) view.alpha = snapshot.alpha
        }
        changed.clear()
        reported = false
    }

    private companion object { const val TAG = "ThreeIslands" }
}

/** Tags published by HyperLyrics Enhanced for its injected island lyric slots. */
internal object IslandLyricConflict {
    private const val TAG_PREFIX = "HYPERLYRIC_"
    private val iconNames = setOf(
        "island_container_module_icon",
        "island_icon_1",
        "island_icon_2",
        "island_fix_icon",
    )

    fun injectedTag(tag: Any?): Boolean = tag is String && tag.startsWith(TAG_PREFIX)

    fun nativeIconName(name: String?): Boolean = name != null && name in iconNames
}
