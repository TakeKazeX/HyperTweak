package com.takekazex.hypertweak.hook.rules.ime

import android.content.Context
import android.content.res.Resources
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Restores AOSP's full-screen IME navigation bar inside a selected keyboard's own process.
 *
 * HyperOS suppresses the AOSP IME nav bar for keyboards it does not recognise as MIUI-customised,
 * so `InputMethodService.hideImeRenderGesturalNavButtons` returns true and the caption bar collapses
 * to zero height. Forcing `IS_INTERNATIONAL_BUILD` for that one call takes the AOSP branch; the rest
 * restores the bar's height, layout, rounded-corner padding, and dead zone.
 *
 * Ported from Howard20181's Mi_AOSP_IME (GPL-3.0).
 */

/** Owns input-view padding and caption injection, including upgrade restoration. */
internal class ImeRaiseController(
    owner: BaseHooker,
    private val aospRaiseActive: (Context?) -> Boolean,
    private val captionBarHeightPx: (Resources) -> Int
) : HookComponent(owner) {
    private companion object {
        const val TAG = "AospIme"
        const val INPUT_METHOD_SERVICE = "android.inputmethodservice.InputMethodService"
        const val RAISE_RECHECK_ATTEMPTS = 4
        const val RAISE_RECHECK_DELAY_MS = 250L
        const val RAISE_CLEAR_TOLERANCE_DP = 2
    }
    private var imeRootField: Field? = null
    private var inputViewField: Field? = null
    private val paddedViews = WeakHashMap<View, Boolean>()
    private val pendingRaiseChecks = WeakHashMap<View, Boolean>()
    private val raiseWatchers = WeakHashMap<View, Boolean>()
    private val lastRaiseEval = WeakHashMap<View, String>()
    private val captionInjectionState = WeakHashMap<View, Boolean>()
    private val legacySweptRoots = WeakHashMap<View, Boolean>()
    /**
     * One-shot cleanup of padding written by pre-input-view builds onto the `parentPanel` root.
     * Bounded: each root is considered only once, so a keyboard that legitimately pads the root to
     * exactly the bar height is not fought on every layout pass.
     */
    private fun sweepLegacyRootPadding(root: View, target: Int): Boolean {
        if (legacySweptRoots.put(root, true) == true) return false
        if (root.paddingBottom != target) return false
        runCatching { root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0) }
        paddedViews.remove(root)
        DebugLog.i(TAG, "legacy root raise withdrawn")
        return true
    }

    fun prepareReload() {
        imeRootField = null
        inputViewField = null
        pendingRaiseChecks.clear()
        raiseWatchers.clear()
        lastRaiseEval.clear()
        captionInjectionState.clear()
        legacySweptRoots.clear()
    }
    fun saveOwnedViews(): List<View> = paddedViews.keys.filter { paddedViews[it] == true }
    fun restoreOwnedViews(state: Any?) {
        (state as? List<*>)?.filterIsInstance<View>()?.forEach { paddedViews[it] = true }
    }
    private fun dpToPx(dp: Int, resources: Resources): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics)
            .roundToInt()
    fun onWindowShownForRaise(service: Context) {
        val root = findInputMethodRoot(service) ?: return
        installRaiseLayoutWatcher(service, root)
        evaluateKeyboardRaise(service, root)
        // Settle checks: the keyboard may apply or reset its own clearance well after the show.
        for (attempt in 1..RAISE_RECHECK_ATTEMPTS) {
            root.postDelayed({ evaluateKeyboardRaise(service, root) }, RAISE_RECHECK_DELAY_MS * attempt)
        }
    }

    /**
     * Re-runs the raise decision whenever the keyboard hierarchy relayouts while shown. This is
     * what actually catches a keyboard that pads itself late (or resets its padding on re-show);
     * timed checks alone miss any change that lands after the schedule ends. The evaluation is
     * idempotent, so repeated invocations are cheap no-ops once settled. Coalesced through
     * [pendingRaiseChecks] so a layout burst triggers at most one posted evaluation.
     */
    private fun installRaiseLayoutWatcher(service: Context, root: View) {
        if (raiseWatchers.put(root, true) == true) return
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (pendingRaiseChecks.put(view, true) == true) return@addOnLayoutChangeListener
            view.post {
                pendingRaiseChecks.remove(view)
                evaluateKeyboardRaise(service, view)
            }
        }
    }

    private fun findInputMethodRoot(service: Context): View? {
        val rootField = imeRootField ?: runCatching {
            INPUT_METHOD_SERVICE.toClassOrNull()
                ?.getDeclaredField("mRootView")
                ?.apply { isAccessible = true }
        }.getOrNull()?.also { imeRootField = it }
        // The service owns the current inflated input_method layout. Resource integers are
        // build-specific (the previous root ID is overlay_display_window_title on OS4 stable).
        val root = runCatching { rootField?.get(service) as? View }.getOrNull()
        if (root == null) DebugLog.d(TAG, "raise: InputMethodService root not ready")
        return root
    }

    /**
     * The view whose bottom padding raises the keyboard: `InputMethodService.mInputView`. Padding
     * must live THERE and not on the `input_method` root — the root has no background (Theme.Panel
     * window background is transparent, the ROM layout paints nothing, and the bar frame is
     * background-less in gesture mode), so a root-padded strip rendered as a transparent band with
     * the app showing through. The input view's own keyboard background spans its padding, which
     * keeps the raised strip opaque. Falls back to the first child of the `inputArea` frame
     * (`mInputFrame`, id 16908318) and finally to the root.
     */
    private fun raiseTargetView(service: Context, root: View): View? {
        val ims = service as? InputMethodService
        if (ims != null) {
            val field = inputViewField ?: runCatching {
                INPUT_METHOD_SERVICE.toClassOrNull()
                    ?.getDeclaredField("mInputView")
                    ?.apply { isAccessible = true }
            }.getOrNull()?.also { inputViewField = it }
            val view = field?.let { runCatching { it.get(ims) as? View }.getOrNull() }
            if (view != null && view !== root) return view
        }
        @Suppress("ResourceType")
        val frame = root.findViewById(16908318) as? ViewGroup
        return frame?.getChildAt(0)?.takeIf { it.isVisible } ?: root
    }

    /**
     * Decides how far the keyboard content must be raised so it ends exactly at the caption-bar
     * top, and applies that as the target view's bottom padding. Idempotent: our own current
     * padding is counted back into the measured content bottom, so evaluating any number of times
     * — from the show hook, the settle schedule, or the layout watcher — always lands on the same
     * stable value. Style-aware: under 小米样式 (or when the AOSP branch is not active) nothing is
     * raised and our padding is withdrawn instead.
     */
    private fun evaluateKeyboardRaise(service: Context, root: View) {
        if (!root.isAttachedToWindow || !root.isLaidOut || root.height <= 0) return
        val target = captionBarHeightPx(service.resources)

        // 小米样式 / inactive branch: restore native behavior. Also push a matching caption-inset
        // height so self-carving keyboards flatten immediately instead of waiting for the
        // framework to re-call the setter on its own.
        if (!aospRaiseActive(service)) {
            withdrawRaise(service, root, target, "native style")
            syncCaptionInjection(root, false, service)
            return
        }
        syncCaptionInjection(root, true, service)

        // Fullscreen/extract mode lays out its own themed area; a raise fights it. And once the
        // input view hides (including teardown after hide), stale padding would linger into the
        // next show — withdraw instead of measuring garbage.
        val ims = service as? InputMethodService
        if (ims != null && (ims.isFullscreenMode || ims.isExtractViewShown || !ims.isInputViewShown)) {
            withdrawRaise(service, root, target, "not raisable now")
            return
        }

        val view = raiseTargetView(service, root)
        if (view == null || !view.isLaidOut || view.height <= 0) return

        // One-time migration: builds before the input-view change padded parentPanel itself.
        if (root !== view) sweepLegacyRootPadding(root, target)

        val currentPb = view.paddingBottom
        if (currentPb != 0 && paddedViews[view] != true) {
            DebugLog.d(TAG, "raise: foreign paddingBottom=$currentPb, left alone")
            return
        }

        // Count our own current padding back in: the measured content bottom already reflects it.
        val baseContentBottom = deepestVisibleBottom(view) + currentPb
        val barTop = view.height - target
        val tolerancePx = dpToPx(RAISE_CLEAR_TOLERANCE_DP, service.resources)
        val shortfall = baseContentBottom - barTop
        val desiredPb = when {
            shortfall <= tolerancePx -> 0
            else -> minOf(shortfall, target)
        }
        // Log only evaluations whose numbers actually changed; the layout watcher re-runs this on
        // every relayout, so unconditional logging would flood the log while typing.
        val snapshot = "h=${view.height} content=$baseContentBottom barTop=$barTop pb=$currentPb"
        if (lastRaiseEval[view] != snapshot) {
            lastRaiseEval[view] = snapshot
            DebugLog.i(TAG, "raise eval: $snapshot desired=$desiredPb")
        }
        if (desiredPb == currentPb) {
            if (desiredPb > 0) paddedViews[view] = true else paddedViews.remove(view)
            return
        }
        runCatching {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, desiredPb)
            if (desiredPb > 0) paddedViews[view] = true else paddedViews.remove(view)
            DebugLog.i(TAG, "keyboard raise $currentPb -> $desiredPb")
        }.onFailure { DebugLog.e(TAG, "evaluateKeyboardRaise failed: $it") }
    }

    private fun withdrawRaise(service: Context, root: View, target: Int, reason: String) {
        var withdrew = false
        for (entry in paddedViews.entries.toList()) {
            val view = entry.key
            if (entry.value && view.paddingBottom != 0) {
                runCatching { view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0) }
                withdrew = true
            }
            paddedViews.remove(view)
        }
        // Migration sweep for padding written by pre-input-view builds onto the root.
        if (root !== raiseTargetView(service, root)) {
            withdrew = sweepLegacyRootPadding(root, target) || withdrew
        }
        if (withdrew) DebugLog.i(TAG, "keyboard raise withdrawn ($reason)")
    }

    /**
     * Pushes the caption-insets height directly onto the window's InsetsController so style
     * switches take effect on the next layout instead of waiting for the framework to call the
     * setter again. Under 小米样式 this is what actually kills a source injected during an earlier
     * AOSP-style session: `InsetsController` re-applies its stored non-zero height on every frame
     * change, so without an explicit 0 the phantom carve survives indefinitely.
     */
    private fun syncCaptionInjection(root: View, active: Boolean, service: Context) {
        if (captionInjectionState[root] == active) return
        val controller = root.windowInsetsController ?: return
        val height = if (active) captionBarHeightPx(service.resources) else 0
        // Record intent BEFORE invoking: the setter notifies insets synchronously, which can
        // relayout into the watcher and re-enter this function mid-invocation.
        captionInjectionState[root] = active
        runCatching {
            controller.javaClass
                .getMethod("setImeCaptionBarInsetsHeight", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(controller, height)
            DebugLog.i(TAG, "caption injection synced: $height")
        }.onFailure {
            captionInjectionState.remove(root)
            DebugLog.w(TAG, "caption injection sync failed", it)
        }
    }

    /** Deepest visible descendant bottom edge inside [root], in root coordinates. */
    private fun deepestVisibleBottom(root: View): Int {
        var best = 0
        val stack = ArrayDeque<Pair<View, Int>>()
        for (index in 0 until childCount(root)) {
            stack.addLast(childAt(root, index) to 0)
        }
        while (true) {
            val (view, offset) = stack.removeLastOrNull() ?: break
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) continue
            val top = offset + view.top
            val bottom = top + view.height
            if (bottom > best) best = bottom
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    stack.addLast(view.getChildAt(index) to top)
                }
            }
        }
        return best
    }

    private fun childCount(view: View): Int = (view as? ViewGroup)?.childCount ?: 0

    private fun childAt(view: View, index: Int): View =
        (view as? ViewGroup)?.getChildAt(index) ?: view

}
