package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.util.SparseIntArray
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * True stacked mobile signal, rebuilt on Flux Decor 2.0.3's view-level model — NOT the native
 * Compose stacked slot and NOT a custom `getIcon()` renderer (both former approaches are gone).
 * See docs/FLUX_DECOR_STACKED_SIGNAL_PLAN.md for the full analysis and port notes.
 *
 * How it works (all targets verified on OS4.0.0.15.XPMCNXM):
 *
 * 1. **Rendering** — every level change funnels through `ImageView.setImageResource(int)` on the
 *    `mobile_signal` view (OS4 inlines `MiuiStatusBarIconViewHelper.transformResId` into the binder;
 *    the `access$setImageResWithTintLight` helpers Flux Decor hooks do not exist on this baseline).
 *    The before-hook parses the level out of the `stat_sys_signal_N` resource name, remembers it per
 *    sub id, and for the **data SIM** replaces the image with a module drawable
 *    (`statusbar_signal_1_{level}`) plus a synthetic second ImageView
 *    (`statusbar_signal_2_{otherLevel}`, id [StackedSignalResources.SUB_MOBILE_ID]) added next to
 *    the real one — the two vectors draw the data SIM's bars on the upper half and the other SIM's
 *    bars on the lower half, composing the stacked icon. The non-data SIM's own image is skipped and
 *    its group force-hidden.
 * 2. **Drawable loading** — module vectors are loaded **directly** from the module package's own
 *    `Resources` (by drawable name) and installed with `ImageView.setImageDrawable`. Fake ids
 *    (`0x7E000000`-based) are only used as stable tags for the composite bookkeeping and are NEVER
 *    passed to `setImageResource` — on this build the framework resolves them below the hooked
 *    `Resources` API and throws `Resources.NotFoundException`, which would kill the binder's
 *    collector coroutines (that is also why the views' `tag` fields are left alone: the binder's
 *    theme-change collector re-applies the drawable id it reads back from `View.getTag()`, so a
 *    fake id stored there gets re-fed into `setImageResource` and crashes the flow collection —
 *    which breaks every other cellular feature, e.g. hiding the data-activity arrows).
 * 3. **State** — the data sub id is read from `MobileIconsViewModel.activeMobileDataSubscriptionId`
 *    (`MobileUiAdapter.start` after-hook) and refreshed through the adapter's
 *    `javaAdapter.alwaysCollectFlow`. Per-SIM levels come from the res-id parse of the binder's own
 *    `setImageResource` calls (flow-driven, so they fire even while the group is gone); no VM-level
 *    flow collection is needed. The dark/light/tint state is tracked from the args of
 *    `MiuiStatusBarIconViewHelper.transformResId(int,boolean,boolean)` before-hooks.
 * 4. **Hiding the other SIM** — `ModernStatusBarView.isIconVisible()` is hooked only when the
 *    receiver is exactly `ModernStatusBarMobileView` and returns false for the non-data subId, so
 *    `StatusIconContainer` stops measuring that view entirely (it keys slots on `isIconVisible()`,
 *    not on `View.getVisibility()` — a plain GONE leaves an empty slot). The
 *    `ModernStatusBarMobileView` group is also force-hidden (tag [StackedSignalResources.TAG_FORCE_GONE]
 *    + GONE), and a `View.setVisibility`/`MobileSignalAnimatorContainer.setChildVisible` guard keeps
 *    the binder's own visibility collector from flipping it back visible.
 *
 * Requires a SystemUI restart (the page offers one). The master switch and every option are read at
 * `onHook()`.
 */
object StackedSignalHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"

    private const val ADAPTER_CLASS =
        "com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter"
    private const val BINDER_CLASS =
        "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder"
    private const val ICONS_VM_CLASS =
        "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel"
    private const val ICON_VIEW_HELPER = "com.android.systemui.statusbar.MiuiStatusBarIconViewHelper"
    private const val ALPHA_IMAGE_VIEW = "com.android.systemui.statusbar.AlphaOptimizedImageView"
    private const val MOBILE_ANIM_CONTAINER =
        "com.android.systemui.statusbar.views.MobileSignalAnimatorContainer"
    private const val MODERN_STATUS_BAR_MOBILE_VIEW =
        "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView"

    /** Drawable-name prefix shared with the module vectors and [StackedSignalResources]. */
    private val signalLevelName = Pattern.compile("stat_sys_signal_(\\d)")

    // ─── Settings (read at onHook) ────────────────────────────────────────────
    @Volatile
    private var enabled = false
    @Volatile
    private var scale = 1f

    // ─── Runtime state ────────────────────────────────────────────────────────
    private val installed = AtomicBoolean(false)
    private val dataSubId = AtomicInteger(Int.MIN_VALUE)
    private val otherLevel = AtomicInteger(0)
    private val subIdLevels = SparseIntArray()
    private val javaAdapterRef = AtomicReference<Any?>(null)
    private val adapterFlowHooked = AtomicBoolean(false)
    private val signalResToLevel = SparseIntArray()
    private val subIdCache = WeakHashMap<View, Int>()
    private val boundRoots = CopyOnWriteArrayList<WeakReference<ViewGroup>>()
    private val applyingIcons = ThreadLocal.withInitial { false }

    @Volatile
    private var lastUseTint = false
    @Volatile
    private var lastIsLight = true
    @Volatile
    private var cachedMobileSignalId = 0

    /** Called from HookEntry once the SystemUI application context exists. */
    fun onPackageReady(context: Context) {
        StackedSignalResources.setModuleContext(context)
        if (enabled) {
            ensureDrawables()
            DebugLog.i(TAG, "StackedSignal resources ready: ${StackedSignalResources.isRegistered()}")
        }
    }

    private fun styleSuffix(): String = ""

    private fun ensureDrawables() {
        if (StackedSignalResources.isRegistered()) return
        if (!StackedSignalResources.isReady()) return
        runCatching { StackedSignalResources.register(styleSuffix(), classLoader) }
            .onFailure { DebugLog.w(TAG, "drawable registration failed", it) }
    }

    override fun onPrepareHotReload() {
        enabled = false
        StackedSignalResources.resetForReload()
        removeAllSubMobileViews()
        installed.set(false)
        dataSubId.set(Int.MIN_VALUE)
        otherLevel.set(0)
        subIdLevels.clear()
        subIdCache.clear()
        cachedMobileSignalId = 0
        adapterFlowHooked.set(false)
        javaAdapterRef.set(null)
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        enabled = Preferences.getBoolean(Preferences.KEY_ICON_STACKED_ENABLED, false)
        scale = Preferences.getFloat(Preferences.KEY_ICON_STACKED_SCALE, 1f).coerceIn(0.5f, 1.5f)
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "StackedSignal", "disabled")
            return
        }
        if (!installed.compareAndSet(false, true)) return

        installTransformResId()
        installImageViewSetImageResource()
        installBinderBind()
        installAdapterStart()
        installMobileVisibility()
        installVisibilityGuard()
        DebugLog.hookRegistered(TAG, "StackedSignal view-level stacked signal (scale=$scale)")
    }

    // ─── 1. Dark/light/tint tracking ──────────────────────────────────────────

    private fun installTransformResId() {
        val helper = ICON_VIEW_HELPER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ICON_VIEW_HELPER, "class not found")
            return
        }
        helper.findMethodOrNull { name("transformResId"); paramCount(3) }?.hook {
            before { param ->
                val args = param.args
                if (args.size >= 3) {
                    (args[1] as? Boolean)?.let { lastUseTint = it }
                    (args[2] as? Boolean)?.let { lastIsLight = it }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ICON_VIEW_HELPER#transformResId", "method not found")
    }

    // ─── 2. Main interception: ImageView.setImageResource ─────────────────────

    private fun installImageViewSetImageResource() {
        val imageViewClass = runCatching {
            Class.forName("android.widget.ImageView", false, classLoader)
        }.getOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "ImageView", "class not found")
            return
        }
        imageViewClass.findMethodOrNull { name("setImageResource"); paramCount(1) }?.hook {
            before { param ->
                if (applyingIcons.get() == true) return@before
                if (!enabled) return@before
                val view = param.thisObject as? ImageView ?: return@before
                if (!isMobileSignalView(view)) return@before
                val resId = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@before
                runCatching { interceptMobileImage(view, resId, param) }
                    .onFailure { DebugLog.w(TAG, "interceptMobileImage failed", it) }
            }
        } ?: DebugLog.hookSkipped(TAG, "ImageView#setImageResource", "method not found")
    }

    private fun isMobileSignalView(view: View): Boolean {
        if (cachedMobileSignalId == 0) {
            cachedMobileSignalId = view.resources.getIdentifier(
                "mobile_signal", "id", "com.android.systemui"
            )
            if (cachedMobileSignalId == 0) return false
        }
        return view.id == cachedMobileSignalId
    }

    private fun interceptMobileImage(
        mobile: ImageView,
        resId: Int,
        param: io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
    ) {
        ensureDrawables()
        if (StackedSignalResources.isFakeId(resId)) {
            // Defense in depth: a fake id must never reach the framework's resource resolution
            // (it resolves below our hooks and throws Resources.NotFoundException, killing the
            // binder's collector scope — which breaks every other cellular feature). The
            // composite is already on the view; skip the original and keep it.
            param.result = null
            return
        }
        val subId = resolveMobileSubId(mobile) ?: return
        val level = levelFromResId(mobile, resId) ?: return
        val changed = noteLevel(subId, level)
        val outer = findMobileOuter(mobile)
        if (outer != null) {
            rememberMobileRoot(outer)
            applyNonDataSimVisibility(outer)
        }
        val dataId = dataSubId.get()
        if (dataId != Int.MIN_VALUE && subId != dataId) {
            // Non-data SIM: its own icon never shows (the composite carries its bars).
            param.result = null
            if (changed) refreshDataSimIcons()
            return
        }
        if (dataId == Int.MIN_VALUE || subId != dataId) {
            // Data SIM unknown yet: leave the stock icon until the adapter reports it.
            return
        }
        val container = mobile.parent as? ViewGroup
        val sub = container?.findViewById<ImageView>(StackedSignalResources.SUB_MOBILE_ID)
        if (applyDualSimIcons(mobile, sub)) {
            param.result = null
        }
    }

    /** Renders the composite on the data-SIM view (+ sub view) with module drawables. */
    private fun applyDualSimIcons(mobile: ImageView, sub: ImageView?): Boolean {
        val suffix = styleSuffix()
        val dataId = dataSubId.get()
        val dLevel = displayLevel(subIdLevels.get(dataId, 0))
        val oLevel = displayLevel(otherLevel.get())
        val baseName = "statusbar_signal_1_$dLevel$suffix"
        if (!StackedSignalResources.has(baseName)) return false
        applyingIcons.set(true)
        try {
            val baseDrawable = themedDrawable(baseName) ?: return false
            // Never write fake ids into the view tag: the binder's theme-change collector reads
            // the tag back and re-feeds it into setImageResource, which would resolve the fake id
            // below our hooks and throw Resources.NotFoundException (killing the collector scope).
            mobile.setImageDrawable(baseDrawable)
            if (sub != null) {
                val subName = "statusbar_signal_2_$oLevel$suffix"
                if (StackedSignalResources.has(subName)) {
                    themedDrawable(subName)?.let(sub::setImageDrawable)
                    runCatching {
                        sub.alpha = mobile.alpha
                        sub.visibility = mobile.visibility
                        sub.imageTintList = mobile.imageTintList
                    }
                }
            }
            return true
        } finally {
            applyingIcons.set(false)
        }
    }

    private fun themedDrawable(baseName: String): android.graphics.drawable.Drawable? {
        val variant = when {
            lastUseTint -> "${baseName}_tint"
            !lastIsLight -> "${baseName}_dark"
            else -> baseName
        }
        return StackedSignalResources.drawable(variant)
            ?: StackedSignalResources.drawable(baseName)
    }

    private fun refreshDataSimIcons() {
        val dataId = dataSubId.get()
        for (ref in boundRoots) {
            val root = ref.get() ?: continue
            if (intFieldOrZero(root, "subId", Int.MIN_VALUE) != dataId) continue
            val mobile = root.findViewById(idRes(root, "mobile_signal")) as? ImageView ?: continue
            val container = mobile.parent as? ViewGroup
            val sub = container?.findViewById<ImageView>(StackedSignalResources.SUB_MOBILE_ID)
            applyDualSimIcons(mobile, sub)
        }
    }

    // ─── 3. Binder bind: group bookkeeping, spacing, sub-view creation ────────

    private fun installBinderBind() {
        val binder = BINDER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BINDER_CLASS, "class not found")
            return
        }
        binder.findMethodOrNull { name("bind") }?.hook {
            after { param ->
                val root = param.args.firstOrNull() as? ViewGroup ?: return@after
                runCatching {
                    rememberMobileRoot(root)
                    applyNonDataSimVisibility(root)
                    initMobileBind(root)
                }.onFailure { DebugLog.w(TAG, "bind patch failed", it) }
            }
        } ?: DebugLog.hookSkipped(TAG, "$BINDER_CLASS#bind", "method not found")
    }

    private fun initMobileBind(root: ViewGroup) {
        val mobileGroup = root.findViewById(idRes(root, "mobile_group")) as? ViewGroup ?: return
        val mobile = mobileGroup.findViewById(idRes(root, "mobile_signal")) as? ImageView ?: return
        val container = mobile.parent as? ViewGroup ?: return
        if (container.findViewById<View>(StackedSignalResources.SUB_MOBILE_ID) != null) return

        // The pref is already a direct 0.5..1.5 factor (1.0 = full size). Do NOT scale it by 0.1:
        // that was the Flux Decor convention where the pref was an int (10 = 100%), and applying it
        // here would render the composite at 5..15% — invisible while its layout slot stays put.
        val f = scale
        if (f != 1f) {
            container.clipChildren = false
            container.clipToPadding = false
            mobile.scaleX = f
            mobile.scaleY = f
        }
        val sub = runCatching {
            val cls = ALPHA_IMAGE_VIEW.toClass()
            cls.getConstructor(Context::class.java).newInstance(root.context) as ImageView
        }.getOrElse { ImageView(root.context) }
        sub.id = StackedSignalResources.SUB_MOBILE_ID
        sub.adjustViewBounds = true
        sub.scaleType = ImageView.ScaleType.FIT_CENTER
        // Copy (not share) the mobile view's layout params: both views must sit at the same slot
        // in the container, but two children must never own the same LayoutParams instance.
        // ViewGroup.generateLayoutParams is protected, so copy through the params' own copy
        // constructor (ConstraintLayout.LayoutParams declares one) and fall back to sharing.
        sub.layoutParams = runCatching {
            val lp = mobile.layoutParams
            lp.javaClass.getConstructor(lp.javaClass).newInstance(lp) as ViewGroup.LayoutParams
        }.getOrElse { mobile.layoutParams }
        if (f != 1f) {
            sub.scaleX = f
            sub.scaleY = f
        }
        container.addView(sub)
        applyDualSimIcons(mobile, sub)
    }

    private fun rememberMobileRoot(root: ViewGroup) {
        if (boundRoots.any { it.get() === root }) return
        boundRoots.add(WeakReference(root))
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                boundRoots.removeAll { it.get() === root }
            }
        })
    }

    // ─── 4. Hiding the non-data SIM ───────────────────────────────────────────

    private fun applyNonDataSimVisibility(root: ViewGroup) {
        val subId = intFieldOrZero(root, "subId", Int.MIN_VALUE)
        val dataId = dataSubId.get()
        if (dataId == Int.MIN_VALUE || subId == Int.MIN_VALUE || subId == dataId) {
            clearForceGone(root)
        } else {
            forceGone(root)
        }
    }

    private fun updateVisibilityForDataChange(dataId: Int) {
        for (ref in boundRoots) {
            val root = ref.get() ?: continue
            applyNonDataSimVisibility(root)
        }
    }

    private fun forceGone(view: View) {
        view.setTag(StackedSignalResources.TAG_FORCE_GONE, true)
        if (view.visibility != View.GONE) view.visibility = View.GONE
        requestLayoutUp(view)
    }

    private fun clearForceGone(view: View) {
        if (view.getTag(StackedSignalResources.TAG_FORCE_GONE) == true) {
            view.setTag(StackedSignalResources.TAG_FORCE_GONE, null)
            if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
            requestLayoutUp(view)
        }
    }

    private fun requestLayoutUp(view: View) {
        var v: View? = view.parent as? View
        for (i in 0 until 8) {
            if (v == null) {
                view.requestLayout()
                return
            }
            if (v.javaClass.name.contains("StatusIconContainer")) {
                v.requestLayout()
                return
            }
            v = v.parent as? View
        }
        view.requestLayout()
    }

    /**
     * Keeps the force-gone root gone: the binder's own visibility collector re-applies
     * `View.setVisibility(0/8)` from the VM's original `isVisible` flow, which would undo the
     * GONE and leave a stale drawn icon at the non-data SIM's last layout bounds. The guard
     * rewrites those calls back to GONE for tagged views only.
     */
    private fun installVisibilityGuard() {
        runCatching {
            val viewClass = Class.forName("android.view.View", false, classLoader)
            viewClass.findMethodOrNull { name("setVisibility"); paramCount(1) }?.hook {
                before { param ->
                    val view = param.thisObject as? View ?: return@before
                    if (view.getTag(StackedSignalResources.TAG_FORCE_GONE) == true) {
                        val wanted = (param.args.getOrNull(0) as? Number)?.toInt()
                        if (wanted != View.GONE) param.args[0] = View.GONE
                    }
                }
            }
        }.onFailure { DebugLog.w(TAG, "View.setVisibility guard failed", it) }
        runCatching {
            MOBILE_ANIM_CONTAINER.toClassOrNull()
                ?.findMethodOrNull { name("setChildVisible"); paramCount(2) }
                ?.hook {
                    before { param ->
                        val child = param.args.getOrNull(0) as? View ?: return@before
                        if (child.getTag(StackedSignalResources.TAG_FORCE_GONE) == true) {
                            param.args[1] = false
                        }
                    }
                }
        }.onFailure { DebugLog.w(TAG, "setChildVisible guard failed", it) }
    }

    private fun installMobileVisibility() {
        val modernView = "com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView"
            .toClassOrNull() ?: return
        modernView.findMethodOrNull { name("isIconVisible"); noParams() }?.hook {
            before { param ->
                val view = param.thisObject as? View ?: return@before
                if (view.javaClass.name != MODERN_STATUS_BAR_MOBILE_VIEW) return@before
                val dataId = dataSubId.get()
                val subId = intFieldOrZero(view, "subId", Int.MIN_VALUE)
                if (dataId != Int.MIN_VALUE && subId != Int.MIN_VALUE && subId != dataId) {
                    param.result = false
                }
            }
        }
    }

    // ─── 5. Adapter flows: data sub id + javaAdapter ──────────────────────────

    private fun installAdapterStart() {
        val adapterClass = ADAPTER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ADAPTER_CLASS, "class not found")
            return
        }
        adapterClass.findMethodOrNull { name("start"); noParams() }?.hook {
            after { param ->
                runCatching { setupAdapterFlows(param.thisObject) }
                    .onFailure { DebugLog.w(TAG, "setupAdapterFlows failed", it) }
            }
        } ?: DebugLog.hookSkipped(TAG, "$ADAPTER_CLASS#start", "method not found")
    }

    private fun setupAdapterFlows(adapter: Any) {
        if (adapterFlowHooked.get()) return
        val iconsVmRaw = readField(adapter, "mobileIconsViewModel") ?: return
        val iconsVm = unwrapLazy(iconsVmRaw) ?: iconsVmRaw
        val dataSubIdFlow = readField(iconsVm, "activeMobileDataSubscriptionId") ?: return
        val javaAdapter = resolveJavaAdapter(adapter) ?: return
        if (!adapterFlowHooked.compareAndSet(false, true)) return
        javaAdapterRef.set(javaAdapter)

        IconTunerFlows.readFlowValue(dataSubIdFlow)
            ?.let { (it as? Number)?.toInt() }
            ?.let { newDataId ->
                dataSubId.set(newDataId)
                updateVisibilityForDataChange(newDataId)
            }
        alwaysCollectFlow(javaAdapter, dataSubIdFlow) { value ->
            (value as? Number)?.toInt()?.let { newDataId ->
                dataSubId.set(newDataId)
                updateVisibilityForDataChange(newDataId)
                refreshDataSimIcons()
            }
        }
    }

    // ─── 6. Shared helpers ────────────────────────────────────────────────────

    private fun noteLevel(subId: Int, level: Int): Boolean {
        val previous = subIdLevels.get(subId, Int.MIN_VALUE)
        subIdLevels.put(subId, level)
        val dataId = dataSubId.get()
        if (dataId == Int.MIN_VALUE) return previous != level
        return if (subId == dataId) {
            previous != level
        } else {
            otherLevel.set(level)
            previous != level
        }
    }

    private fun displayLevel(raw: Int): Int = raw.coerceIn(0, 5)

    private fun levelFromResId(view: View, resId: Int): Int? {
        if (resId == 0 || StackedSignalResources.isFakeId(resId)) return null
        signalResToLevel.get(resId, -1).let { if (it >= 0) return it }
        val name = runCatching { view.resources.getResourceEntryName(resId) }.getOrNull()
            ?: return null
        val level = signalLevelName.matcher(name).let { m ->
            if (m.find()) m.group(1)?.toIntOrNull()?.coerceIn(0, 5) else null
        } ?: return null
        signalResToLevel.put(resId, level)
        return level
    }

    private fun resolveMobileSubId(mobile: View): Int? {
        subIdCache[mobile]?.let { return it }
        var v: View? = mobile
        for (i in 0 until 6) {
            if (v == null) break
            val subId = intFieldOrZero(v, "subId", Int.MIN_VALUE)
            if (subId != Int.MIN_VALUE) {
                subIdCache[mobile] = subId
                return subId
            }
            v = v.parent as? View
        }
        return null
    }

    private fun findMobileOuter(mobile: View): ViewGroup? {
        var v: View? = mobile
        for (i in 0 until 8) {
            val parent = v?.parent as? View ?: break
            if (parent.javaClass.name == MODERN_STATUS_BAR_MOBILE_VIEW) {
                return parent as? ViewGroup
            }
            v = parent
        }
        return null
    }

    private fun resolveJavaAdapter(adapter: Any): Any? {
        readField(adapter, "mJavaAdapter")?.let { return it }
        readField(adapter, "javaAdapter")?.let { return it }
        val hd = readField(adapter, "hdController")
        if (hd != null) {
            readField(hd, "javaAdapter")?.let { return it }
            readField(hd, "mJavaAdapter")?.let { return it }
        }
        return null
    }

    private fun alwaysCollectFlow(javaAdapter: Any, flow: Any, consumer: (Any?) -> Unit) {
        runCatching {
            val flowClass = Class.forName("kotlinx.coroutines.flow.Flow", false, classLoader)
            val consumerClass = java.util.function.Consumer::class.java
            javaAdapter.javaClass.getMethod("alwaysCollectFlow", flowClass, consumerClass)
                .invoke(javaAdapter, flow, java.util.function.Consumer<Any?> { consumer(it) })
        }.onFailure { DebugLog.w(TAG, "alwaysCollectFlow failed", it) }
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun unwrapLazy(raw: Any): Any? = runCatching {
        raw.javaClass.getMethod("get").invoke(raw)
    }.getOrNull()

    private fun intFieldOrZero(target: Any, name: String, missing: Int = 0): Int = runCatching {
        val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        if (f.type == Int::class.javaPrimitiveType) f.getInt(target) else f.get(target) as? Int ?: missing
    }.getOrDefault(missing)

    private fun idRes(view: View, name: String): Int =
        // Must resolve from the host view's resources (SystemUI). The module package context's
        // AssetManager cannot see SystemUI resource tables, so a module-context lookup returns 0.
        view.resources.getIdentifier(name, "id", "com.android.systemui")

    private fun removeAllSubMobileViews() {
        runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            val rootViews = global.getMethod("getWindowViews").invoke(instance) as? Array<*> ?: return
            for (root in rootViews) scanRemoveSubMobile(root as? View)
        }.onFailure { DebugLog.w(TAG, "removeAllSubMobileViews failed", it) }
    }

    private fun scanRemoveSubMobile(view: View?) {
        if (view !is ViewGroup) return
        val victims = ArrayList<View>()
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.id == StackedSignalResources.SUB_MOBILE_ID) {
                victims.add(child)
            } else {
                scanRemoveSubMobile(child)
            }
        }
        for (victim in victims) {
            (victim.parent as? ViewGroup)?.removeView(victim)
        }
    }
}
