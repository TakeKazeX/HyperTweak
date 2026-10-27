package com.takekazex.hypertweak.hook.rules.ime

import com.takekazex.hypertweak.hook.base.cachedDeclaredMethod
import com.takekazex.hypertweak.hook.base.HostAccess
import android.content.Context
import android.content.res.Resources
import android.graphics.Insets
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.RoundedCorner
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewParent
import android.view.WindowInsets
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Owns AOSP and HyperOS bottom styles in selected keyboard processes, including living services
 * recovered after an APK replacement. Framework capability checks remain authoritative.
 * Based on Howard20181's Mi_AOSP_IME (GPL-3.0).
 */
object AospImeHooker : StaticHooker() {
    private val raise = ImeRaiseController(this, ::aospRaiseActive, ::captionBarHeightPx)
    private val nativeAppearance = ImeNativeBottomAppearance(this)
    private val windowPolicy = ImeWindowPolicyController(this)
    private const val TAG = "AospIme"

    private const val INPUT_METHOD_SERVICE = "android.inputmethodservice.InputMethodService"
    private const val INPUT_METHOD_SERVICE_STUB = "android.inputmethodservice.InputMethodServiceStub"
    // OS4.0.0.15 had the nav-bar controller split into NavigationBarController$Impl; OS4.0.0.19
    // inlined it into NavigationBarController itself. Try the inner name first, fall back to the
    // outer class (same fields/methods on both shapes).
    private const val NAV_BAR_CONTROLLER = "android.inputmethodservice.NavigationBarController"
    private const val NAV_BAR_CONTROLLER_IMPL = "android.inputmethodservice.NavigationBarController\$Impl"
    private const val NAV_BAR_INFLATER_VIEW = "android.inputmethodservice.navigationbar.NavigationBarInflaterView"
    private const val NAV_BAR_VIEW = "android.inputmethodservice.navigationbar.NavigationBarView"
    private const val NAV_BAR_FRAME = "android.inputmethodservice.navigationbar.NavigationBarFrame"
    private const val DEAD_ZONE = "android.inputmethodservice.navigationbar.DeadZone"
    private const val INPUT_METHOD_MODULE_MANAGER = "android.inputmethodservice.InputMethodModuleManager"
    private const val NAV_BAR_KEY_BUTTON_VIEW = "android.inputmethodservice.navigationbar.KeyButtonView"

    private const val CAPTION_BAR_HEIGHT_DP = 48
    private const val NAV_BAR_SHADOW_DP = 4

    /** Padding a view had before the rounded-corner listener started adjusting it. */
    private val basePaddings = generationMainResource(WeakHashMap<View, IntArray>()) { it.clear() }

    private var insetsView: WeakReference<View>? by generationState { null }

    /** MIUI can side-load its dex more than once; attach the child hooker per ClassLoader. */
    private val hookedDexLoaders = generationResource(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())) { it.clear() }

    private var navBarServiceField: Field? by generationState { null }
    private var navBarHorizontalField: Field? by generationState { null }
    private var deadZoneSizeMinField: Field? by generationState { null }
    private var navBarFrameClass: Class<*>? by generationState { null }

    /** Resolved controller class (`NavigationBarController` or the .15 `$Impl` shape). */
    private var navBarControllerClass: Class<*>? by generationState { null }

    /** The live controller instance, captured by the caption-height hook; source of `mService`. */
    private var navBarControllerInstance: Any? by generationState { null }

    /** The framework dimen the caption bar is sized from, cached per density (IMS processes
     *  survive configuration changes, so a bare value cache would go stale on density change). */
    private var captionBarHeightPxCache: Pair<Int, Int>? by generationState { null }

    /** `KeyButtonView.setCode(int)`; used to take the bar's back button off the key pipeline. */
    private var backButtonCodeMethod: Method? by generationState { null }
    private var navBackIdCached: Int by generationState { -1 }

    /** Back buttons already rewired for dismiss-only semantics (dedupes repeat inflations). */
    private val wiredBackViews = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }

    @Volatile private var generationAlive = true

    override fun onPrepareHotReload() {
        generationAlive = false
        HostAccess.onMain { nativeAppearance.retire(); windowPolicy.retire(); raise.prepareReload() }
        HostAccess.onMain {
            insetsView?.get()?.let { view ->
                runCatching { view.setOnApplyWindowInsetsListener(null) }
                basePaddings.remove(view)
            }
        }
    }

    /**
     * The nav bar view and our padded views survive a reload; carry both so ownership of applied
     * padding is not lost across the reload (otherwise stale padding would look foreign).
     */
    override fun saveHotReloadState(): Any? = HostAccess.onMain {
        ArrayList<Any?>(listOf(insetsView?.get(), raise.saveOwnedViews(), windowPolicy.saveCaptions()))
    }

    override fun restoreHotReloadState(state: Any?) = HostAccess.onMain {
        val parts = state as? ArrayList<*> ?: return@onMain
        (parts.getOrNull(0) as? View)?.let { view ->
            installRoundedCornerInsetsListener(view)
            runCatching { view.requestApplyInsets() }
        }
        raise.restoreOwnedViews(parts.getOrNull(1))
        windowPolicy.restoreCaptions(parts.getOrNull(2))
    }

    override fun onHook() {
        generationAlive = true
        com.takekazex.hypertweak.hook.rules.ime.prepareAospImeConfigSettings()
        // Install dormant callbacks too: switching on must work in an existing keyboard process.
        hookHideImeRenderGesturalNavButtons()
        hookImeCaptionBarHeight()
        hookImeCaptionBarInsets()
        hookSystemInsets()
        hookRaiseKeyboard()
        hookInflateLayout()
        hookOrientationViews()
        hookDeadZone()
        hookNavBarBackButtonDismiss()
        hookLoadDex()
        hookNativeEligibility()
        hookServiceLifecycle()
        hookNavFlags()
        nativeAppearance.install()
        windowPolicy.install()
    }

    /**
     * `InputMethodBottomManager` lives in the dex MIUI side-loads into the keyboard process, so the
     * hooks on it have to wait for that ClassLoader to exist.
     */
    private val services = generationMainResource(WeakHashMap<InputMethodService, Boolean>()) { it.clear() }
    private val nativeFlags = generationMainResource(WeakHashMap<Any, Int>()) { it.clear() }
    private val appliedLayouts = generationMainResource(WeakHashMap<InputMethodService, String>()) { it.clear() }
    private var refreshing by generationState { false }

    private fun hookServiceLifecycle() {
        val type = INPUT_METHOD_SERVICE.toClassOrNull() ?: return
        HostAccess.declaredMethod(type, "initViews", accessible = true)?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "capture input service", Unit) {
                        val service = param.thisObject as? InputMethodService ?: return@open
                        services[service] = true
                        // initViews can run during construction; wait until the host completes it.
                        service.window?.window?.decorView?.post { refreshSettings() }
                    }
                }
            }
        }
        HostAccess.declaredMethod(type, "setInputView", View::class.java, accessible = true)?.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "native input view changed", Unit) {
                    (param.thisObject as? InputMethodService)?.let { nativeAppearance.refresh(it) }
                }
            }
        }
        HostAccess.declaredMethod(type, "onDestroy", accessible = true)?.hook {
            before { param ->
                (param.thisObject as? InputMethodService)?.let { service ->
                    services.remove(service)
                    appliedLayouts.remove(service)
                }
            }
        }
    }

    private fun hookNativeEligibility() {
        val injector = imeInjector() ?: return
        HostAccess.declaredMethod(injector.javaClass, "isImeSupport", Context::class.java,
            accessible = true)?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    if (miuiStyleActive(param.args.firstOrNull() as? Context)) param.result = true
                }
            }
        }
    }

    private fun imeInjector(): Any? = INPUT_METHOD_SERVICE_STUB.toClassOrNull()
        ?.cachedDeclaredMethod("getInstance", accessible = true)?.invoke(null)

    private fun hookNavFlags() {
        val type = resolveNavBarControllerClass() ?: return
        HostAccess.declaredMethod(type, "onNavButtonFlagsChanged", Int::class.javaPrimitiveType!!,
            accessible = true)?.let { method ->
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "nav flags", Unit) {
                        val controller = param.thisObject
                        val flags = param.args[0] as? Int ?: return@open
                        if (!refreshing) nativeFlags[controller] = flags
                        navBarControllerInstance = controller
                        val service = navBarServiceField?.get(controller) as? InputMethodService ?: return@open
                        services[service] = true
                        if (managed(service)) param.args[0] = if (aospRaiseActive(service)) flags or 1 else flags and 1.inv()
                    }
                }
            }
        }
    }

    /** Discover the framework-owned services as well as those seen by this generation's hooks. */
    private fun discoverServices() {
        val thread = "android.app.ActivityThread".toClassOrNull()
            ?.cachedDeclaredMethod("currentActivityThread", accessible = true)?.invoke(null) ?: return
        val living = HostAccess.read(thread, "mServices") as? Map<*, *> ?: return
        living.values.filterIsInstance<InputMethodService>().forEach { services[it] = true }
    }

    override fun recoverHotReloadHosts() { refreshSettings() }

    fun refreshSettings(): Boolean = runCatching {
        if (!generationAlive || !isHooked) return true
        HostAccess.onMain {
            if (refreshing) return@onMain
            refreshing = true
            try {
                discoverServices()
                val injector = imeInjector()
                val loader = injector?.let { HostAccess.read(it, "sClassLoader") as? ClassLoader }
                if (loader != null) attachBottomLoader(loader)
                services.keys.toList().forEach { service -> refreshService(service, injector, loader) }
            } finally { refreshing = false }
        }
        true
    }.getOrElse { DebugLog.w(TAG, "live IME refresh failed", it); false }

    private fun attachBottomLoader(loader: ClassLoader) {
        if (!synchronized(hookedDexLoaders) { hookedDexLoaders.add(loader) }) return
        try { attach(MiuiImeBottomHooker(), loader) }
        catch (error: Throwable) {
            synchronized(hookedDexLoaders) { hookedDexLoaders.remove(loader) }
            throw error
        }
    }

    private fun refreshService(service: InputMethodService, injector: Any?, loader: ClassLoader?) {
        val root = HostAccess.read(service, "mRootView") as? View ?: return
        val controller = HostAccess.read(service, "mNavigationBarController") ?: return
        navBarControllerInstance = controller
        val layout = "${managed(service)}:${AospImeConfig.raiseStyle()}:${AospImeConfig.navBarLayoutHandle()}:${AospImeConfig.showAllImeList()}"
        if (appliedLayouts[service] != layout) {
            // Retire the native bottom's observers/popups before replacing its view. Reuse the
            // service-owned input view: initViews would discard the keyboard's composition UI.
            val bottom = loader?.let { runCatching { it.loadClass("com.miui.inputmethod.InputMethodBottomManager") }.getOrNull() }
            val helper = bottom?.let { HostAccess.requireDeclaredField(it, "sBottomViewHelper", accessible = true).get(null) }
            if (bottom != null && helper != null) {
                val previousRoot = HostAccess.read(helper, "mRootView") as? View ?: root
                val listener = HostAccess.requireDeclaredField(bottom, "sGlobalLayoutListener", accessible = true).get(null)
                    as? android.view.ViewTreeObserver.OnGlobalLayoutListener
                if (listener != null && previousRoot.viewTreeObserver.isAlive) previousRoot.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                HostAccess.requireDeclaredMethod(bottom, "onDestroy", accessible = true).invoke(null)
                restoreNativeLayoutMutations(service, root)
            }
            val areaId = service.resources.getIdentifier("miui_bottom_area", "id", "android")
            val area = if (areaId != 0) root.findViewById<View>(areaId) as? android.view.ViewGroup else null
            area?.removeAllViews()
            if (aospRaiseActive(service)) area?.visibility = View.GONE
            if (bottom != null) {
                val util = bottom.classLoader?.loadClass("com.miui.inputmethod.InputMethodUtil")
                if (util != null) HostAccess.requireDeclaredField(util, "sCanShowMiuiBottom", accessible = true).setBoolean(null, false)
            }
            HostAccess.method(controller, "uninstallNavigationBarFrameIfNecessary")?.invoke(controller)
            val flags = nativeFlags[controller] ?: 6
            val nativeHidden = HostAccess.method(service, "hideImeRenderGesturalNavButtons", String::class.java)
                ?.invoke(service, service.packageName) as? Boolean ?: true
            val wanted = if (aospRaiseActive(service)) flags or 1 else if (nativeHidden) flags and 1.inv() else flags
            HostAccess.method(controller, "onNavButtonFlagsChanged", Int::class.javaPrimitiveType!!)?.invoke(controller, wanted)
            if (!aospRaiseActive(service) && injector != null && area != null) {
                HostAccess.method(injector, "addMiuiBottomView", android.view.LayoutInflater::class.java,
                    android.view.ViewGroup::class.java, android.view.ViewGroup::class.java, View::class.java,
                    android.view.ViewGroup::class.java, android.view.inputmethod.InputMethodManager::class.java,
                    InputMethodService::class.java)?.invoke(injector,
                    HostAccess.read(service, "mInflater"), HostAccess.read(service, "mFullscreenArea"),
                    HostAccess.read(service, "mInputFrame"), root, area, HostAccess.read(service, "mImm"), service)
            }
            appliedLayouts[service] = layout
            root.requestApplyInsets()
            root.requestLayout()
        }
        windowPolicy.refresh(service)
        nativeAppearance.refresh(service)
        raise.onWindowShownForRaise(service)
    }

    internal fun prepareNativeInputColor(type: Class<*>) {
        prepareDexTargets(name = "ime.native-bottom.color.${type.name}",
            resolve = { nativeAppearance.resolveInputColor(type) },
            install = { field -> nativeAppearance.publishInputColor(type, field) })
    }

    internal fun onNativeBottomReady(service: InputMethodService, manager: Class<*>) {
        nativeAppearance.bind(service, manager)
    }

    /** Restore only the two layout mutations made by changeViewForMiuiBottom, using this ROM's
     * own detached input_method layout as the baseline when upgrading without saved state. */
    @Suppress("ResourceType")
    private fun restoreNativeLayoutMutations(service: InputMethodService, root: View) {
        if (service.isFullscreenMode) return
        val layoutId = service.resources.getIdentifier("input_method", "layout", "android")
        if (layoutId == 0) return
        val template = android.view.LayoutInflater.from(service).inflate(layoutId, null)
        val input = root.findViewById<View>(16908318)
        val nativeInput = template.findViewById<View>(16908318)
        val lp = input?.layoutParams as? android.widget.LinearLayout.LayoutParams
        val baseline = nativeInput?.layoutParams as? android.widget.LinearLayout.LayoutParams
        if (lp != null && baseline != null && lp.height == 0 && lp.weight == 1f) {
            lp.height = baseline.height
            lp.weight = baseline.weight
            input.layoutParams = lp
        }
        val fullscreen = HostAccess.read(service, "mFullscreenArea") as? View ?: return
        val fullParams = fullscreen.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        if (fullParams.height >= 0) {
            // InputMethodService.updateFullscreenMode's native non-extract contract.
            fullParams.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            fullParams.weight = 0f
            fullscreen.layoutParams = fullParams
        }
    }

    private fun hookLoadDex() {
        val moduleManager = INPUT_METHOD_MODULE_MANAGER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, INPUT_METHOD_MODULE_MANAGER, "class not found")
            return
        }
        val method = CompatibleMethodResolver.find(
            moduleManager,
            "loadDex",
            parameterTypes = listOf(ClassLoader::class.java, String::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$INPUT_METHOD_MODULE_MANAGER#loadDex(ClassLoader,String)",
                "method not found"
            )
            return
        }

        runCatching {
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "loadDex", Unit) {
                        // loadDex throws for anything that is not a BaseDexClassLoader.
                        if (param.throwable != null) return@open
                        val loader = param.args.getOrNull(0) as? ClassLoader ?: return@open
                        attachBottomLoader(loader)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$INPUT_METHOD_MODULE_MANAGER#loadDex(ClassLoader,String)", it)
        }
    }

    private fun hookHideImeRenderGesturalNavButtons() {
        val serviceClass = INPUT_METHOD_SERVICE.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, INPUT_METHOD_SERVICE, "class not found")
            return
        }

        val method = CompatibleMethodResolver.find(
            serviceClass,
            "hideImeRenderGesturalNavButtons",
            parameterTypes = listOf(String::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$INPUT_METHOD_SERVICE#hideImeRenderGesturalNavButtons(String)",
                "method not found"
            )
            return
        }

        val properties = "android.os.SystemProperties".toClassOrNull() ?: return
        val modDevice = HostAccess.requireDeclaredMethod(properties, "get", String::class.java,
            String::class.java, accessible = true).invoke(null, "ro.product.mod_device", "") as? String ?: ""
        val nativeInternational = modDevice.contains("_global")
        runCatching {
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "hideImeRenderGesturalNavButtons", Unit) {
                        val service = param.thisObject as? InputMethodService ?: return@open
                        val id = param.args.firstOrNull() as? String
                        val nativeAosp = nativeInternational || id.isNullOrEmpty() || id.contains("com.android.cts.mock")
                        // Reconstruct the native branch from its immutable property as well: an
                        // older APK may have left IS_INTERNATIONAL_BUILD latched true in this host.
                        if (aospRaiseActive(service) || (!managed(service) && nativeAosp)) {
                            val canRender = HostAccess.requireDeclaredMethod(serviceClass,
                                "canImeRenderGesturalNavButtons", accessible = true)
                                .invoke(null) as? Boolean ?: return@open
                            param.result = !canRender
                        } else param.result = true
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$INPUT_METHOD_SERVICE#hideImeRenderGesturalNavButtons(String)", it)
        }
    }

    internal fun managed(context: Context?): Boolean =
        AospImeConfig.isEnabled() && context != null && AospImeConfig.isSelectedIme(context.packageName)

    internal fun aospBarActive(context: Context?): Boolean = managed(context)

    internal fun aospRaiseActive(context: Context?): Boolean = managed(context) &&
        AospImeConfig.raiseStyle() == com.takekazex.hypertweak.settings.contract.ImeLayout.RAISE_STYLE_AOSP

    internal fun miuiStyleActive(context: Context?): Boolean = managed(context) && !aospRaiseActive(context)

    private fun hookImeCaptionBarHeight() {
        val implClass = resolveNavBarControllerClass() ?: run {
            DebugLog.hookSkipped(TAG, "$NAV_BAR_CONTROLLER_IMPL/$NAV_BAR_CONTROLLER", "class not found")
            return
        }
        navBarServiceField = runCatching {
            HostAccess.requireDeclaredField(implClass, "mService", accessible = true)
        }.getOrNull()

        val method = CompatibleMethodResolver.find(
            implClass,
            "getImeCaptionBarHeight",
            returnType = Int::class.javaPrimitiveType,
            parameterTypes = listOf(Boolean::class.javaPrimitiveType!!)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$NAV_BAR_CONTROLLER_IMPL#getImeCaptionBarHeight(boolean)",
                "method not found"
            )
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "getImeCaptionBarHeight", Unit) {
                        if (param.args.getOrNull(0) != true) return@open
                        navBarControllerInstance = param.thisObject
                        val service = navBarServiceField?.get(param.thisObject) as? Context ?: return@open
                        // 小米样式 passes the ROM value through; the whole bar stack is native then.
                        if (!aospRaiseActive(service)) return@open
                        param.result = captionBarHeightPx(service.resources)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$NAV_BAR_CONTROLLER_IMPL#getImeCaptionBarHeight(boolean)", it)
        }
    }

    /**
     * The caption-bar insets height is applied to the IME window by
     * `InsetsController.setImeCaptionBarInsetsHeight(int)`, which both carves the keyboard's
     * content area and is what inset-honoring keyboards (微信输入法, Gboard) pad themselves by.
     * The controller reads it from `getImeCaptionBarHeight(...)` first, but that method is small
     * enough to be AOT-inlined into the controller on OS4.0.0.19 and the getter hook is bypassed;
     * this hook overrides the value at the actual injection point instead.
     *
     * Style-aware: AOSP样式 forces 144px; 小米样式 forces **0** — an injected phantom source would
     * make self-carving keyboards raise their content even with none of this module's padding
     * present (the "小米样式不生效" bug). Forcing 0 also actively heals a source left over from an
     * earlier AOSP-style session, because `InsetsController` re-applies its stored non-zero height
     * on every frame change and would otherwise keep it alive indefinitely.
     */
    private fun hookImeCaptionBarInsets() {
        val insetsControllerClass = "android.view.InsetsController".toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "android.view.InsetsController", "class not found")
            return
        }
        val method = CompatibleMethodResolver.find(
            insetsControllerClass,
            "setImeCaptionBarInsetsHeight",
            parameterTypes = listOf(Int::class.javaPrimitiveType!!)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "android.view.InsetsController#setImeCaptionBarInsetsHeight(int)",
                "method not found"
            )
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "setImeCaptionBarInsetsHeight", Unit) {
                        val context = currentServiceContext() ?: return@open
                        when {
                            !aospBarActive(context) -> Unit // ROM default untouched
                            AospImeConfig.raiseStyle() == com.takekazex.hypertweak.settings.contract.ImeLayout.RAISE_STYLE_AOSP ->
                                param.args[0] = captionBarHeightPx(context.resources)
                            else -> param.args[0] = 0
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "android.view.InsetsController#setImeCaptionBarInsetsHeight(int)", it)
        }
    }

    /** The IME service is the controller's `mService`; `ActivityThread.currentApplication()` is a fallback. */
    internal fun currentServiceContext(): Context? {
        navBarControllerInstance?.let { instance ->
            runCatching { navBarServiceField?.get(instance) as? Context }.getOrNull()?.let { return it }
        }
        return runCatching {
            "android.app.ActivityThread".toClassOrNull()
                ?.cachedDeclaredMethod("currentApplication", accessible = true)
                ?.invoke(null) as? Context
        }.getOrNull()
    }

    /**
     * OS4.0.0.19 keyboards that ignore the window's caption-bar inset (e.g. 搜狗小米版) keep their
     * keys laid out to the very bottom of the window, where the taller AOSP bar then covers them.
     * The keyboard container (the service-owned `input_method` root) is bottom-aligned inside the
     * decor, so adding a bottom padding equal to the bar height raises every keyboard's keys to
     * end exactly at the bar top, whatever the keyboard's own inset handling is.
     *
     * Keyboards that DO honor the caption-bar inset (e.g. 微信输入法) settle their own padding at
     * an unpredictable time after the window shows — observed on device as a correct first show
     * followed by a second show where the keyboard reset its own padding and re-applied it only
     * after our evaluation had already run. A single evaluation therefore cannot work: the raise
     * is re-evaluated on a short post-show schedule AND continuously on root layout changes; the
     * shortfall math below counts our own current padding back in, so every re-evaluation
     * converges on the same stable value no matter which side moved last.
     */
    private fun hookRaiseKeyboard() {
        val implClass = resolveNavBarControllerClass() ?: return
        val method = CompatibleMethodResolver.find(implClass, "onWindowShown") ?: run {
            DebugLog.hookSkipped(TAG, "$NAV_BAR_CONTROLLER_IMPL#onWindowShown()", "method not found")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "onWindowShown", Unit) {
                        val service = navBarServiceField?.get(param.thisObject) as? Context ?: return@open
                        // Disabled and native-style paths must withdraw any owned AOSP padding.
                        (service as? InputMethodService)?.let { nativeAppearance.refresh(it) }
                        raise.onWindowShownForRaise(service)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$NAV_BAR_CONTROLLER_IMPL#onWindowShown()", it)
        }
    }

    /**
     * The caption-bar insets height and the visible bar's frame height must agree, or the bar
     * renders squeezed / stretched (`NavigationBarController` sizes the frame from
     * `getSystemInsets().bottom` in `installNavigationBarFrameIfNecessary`, `scheduleRelayout` and
     * `onWindowShown`, and `updateTouchableInsets` derives the touch region from the same value).
     * The device's bottom system-bar inset is not guaranteed to equal the caption-bar height, so
     * pin it to the same value the caption bar is forced to — a single choke point that covers all
     * four consumers and keeps the bar looking like stock AOSP regardless of the ROM's insets.
     */
    private fun hookSystemInsets() {
        val implClass = resolveNavBarControllerClass() ?: run {
            DebugLog.hookSkipped(TAG, "$NAV_BAR_CONTROLLER_IMPL/$NAV_BAR_CONTROLLER", "class not found")
            return
        }
        val method = CompatibleMethodResolver.find(implClass, "getSystemInsets") ?: run {
            DebugLog.hookSkipped(TAG, "$NAV_BAR_CONTROLLER_IMPL#getSystemInsets()", "method not found")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "getSystemInsets", Unit) {
                        val real = param.result as? Insets ?: return@open
                        val service = navBarServiceField?.get(param.thisObject) as? Context ?: return@open
                        // 小米样式 leaves the real insets: the bar is gone, so neither the frame
                        // height nor the touchable-region union may claim a phantom 48dp strip.
                        if (!aospRaiseActive(service)) return@open
                        val forced = Insets.of(
                            real.left,
                            real.top,
                            real.right,
                            captionBarHeightPx(service.resources)
                        )
                        param.result = forced
                        DebugLog.d(TAG, "getSystemInsets bottom ${real.bottom} -> ${forced.bottom}")
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$NAV_BAR_CONTROLLER_IMPL#getSystemInsets()", it)
        }
    }

    /** `NavigationBarController$Impl` on OS4.0.0.15, plain `NavigationBarController` on .19. */
    private fun resolveNavBarControllerClass(): Class<*>? {
        navBarControllerClass?.let { return it }
        val resolved = NAV_BAR_CONTROLLER_IMPL.toClassOrNull() ?: NAV_BAR_CONTROLLER.toClassOrNull()
        navBarControllerClass = resolved
        return resolved
    }

    /**
     * The bar's 返回 button must only dismiss the keyboard, never leak a BACK key into the host
     * app. Stock `KeyButtonView` synthesizes `KEYCODE_BACK` down/up through
     * `InputMethodService.onKeyDown/onKeyUp` and falls back to `InputConnection.sendKeyEvent(ev)`
     * whenever the IMS declines an event. 微信输入法's NormalImeProxy
     * (`com.tencent.wetype.plugin.hld.k#g(int)`) handles the BACK DOWN itself with
     * `requestHideSelf(0)` and returns true **without** calling `KeyEvent.startTracking()`, so
     * `mTracking` stays false; on UP the untracked event is declined by
     * `InputMethodService.onKeyUp` (`isTracking()==false`) and forwarded through the
     * InputConnection to the host app — pressing the bar's 返回 key closed the keyboard on
     * touch-down and navigated the app back the moment the finger lifted.
     *
     * Fix: take the button off the key pipeline entirely (`KeyButtonView.setCode(0)`) and wire a
     * click listener that calls `InputMethodService.requestHideSelf(0)` directly. This matches the
     * button's own semantics — in gestural mode `orientBackButton` rotates the back icon 90°
     * because flag bit 0 (`isBackDismissIme`) is always set from `(showImeSwitcher?4:0)|3`.
     *
     * Resolution note: `CompatibleMethodResolver` treats an empty parameter list as "must be a
     * zero-arg method", which silently skipped this single-overload method once (HOOK_SKIPPED
     * "method not found" on device); resolve it by name over `declaredMethods` instead. Do not
     * move this to an `InputMethodService.onKeyDown` hook: keyboard services override that method,
     * and a virtual-method hook on the base class never fires for the override.
     */
    private fun hookNavBarBackButtonDismiss() {
        val navBarViewClass = NAV_BAR_VIEW.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, NAV_BAR_VIEW, "class not found")
            return
        }
        val method = navBarViewClass.declaredMethods
            .singleOrNull { it.name == "prepareNavButtons" }
            ?.apply { isAccessible = true } ?: run {
            DebugLog.hookSkipped(TAG, "$NAV_BAR_VIEW#prepareNavButtons(...)", "method not found")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "wireNavBarBackButtonDismiss", Unit) {
                        val navBar = param.thisObject as? View ?: return@open
                        wireBackButtonToDismiss(navBar)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$NAV_BAR_VIEW#prepareNavButtons(...)", it)
        }
    }

    private fun wireBackButtonToDismiss(navBar: View) {
        if (!aospRaiseActive(navBar.context)) return
        val backButtonId = resolveNavBackId(navBar.resources)
        if (backButtonId == 0) return
        val backButton = navBar.findViewById<View>(backButtonId)
        if (backButton == null || wiredBackViews.put(backButton, true) == true) return
        val keyButtonClass = NAV_BAR_KEY_BUTTON_VIEW.toClassOrNull()
        if (keyButtonClass != null && keyButtonClass.isInstance(backButton)) {
            val setCode = backButtonCodeMethod ?: runCatching {
                HostAccess.requireDeclaredMethod(keyButtonClass, "setCode", Int::class.javaPrimitiveType, accessible = true)
            }.getOrNull()?.also { backButtonCodeMethod = it }
            if (setCode != null) {
                runCatching { setCode.invoke(backButton, 0) }
                    .onFailure { DebugLog.w(TAG, "back-dismiss: setCode failed", it) }
            }
        }
        backButton.setOnClickListener(OnClickListener { view ->
            // The KeyButtonView context is always its InputMethodService (see its constructor).
            val service = view.context as? InputMethodService ?: return@OnClickListener
            runCatching { service.requestHideSelf(0) }
                .onFailure { DebugLog.w(TAG, "back-dismiss: requestHideSelf failed", it) }
        })
        DebugLog.i(TAG, "back-dismiss wired: ${backButton.javaClass.simpleName}")
    }

    private fun resolveNavBackId(resources: Resources): Int {
        if (navBackIdCached != -1) return navBackIdCached
        val resolved = resources.getIdentifier("input_method_nav_back", "id", "android")
        navBackIdCached = resolved
        return resolved
    }

    /**
     * The caption bar height, pinned to 48dp. The ROM's own dimen
     * (`input_method_navigation_bar_height`) resolves to 20dp on OS4.0.0.19 — MIUI deliberately
     * shrinks the caption bar to its gesture area — which is exactly the "太矮" symptom; a normal
     * AOSP bar is 48dp. The caption-bar insets height, the visible frame height and the keyboard's
     * content carve are all pinned to this one value so they can never disagree.
     */
    private fun captionBarHeightPx(resources: Resources): Int {
        val dpi = resources.displayMetrics.densityDpi
        captionBarHeightPxCache?.takeIf { it.first == dpi }?.let { return it.second }
        val px = dpToPx(CAPTION_BAR_HEIGHT_DP, resources)
        captionBarHeightPxCache = dpi to px
        return px
    }

    private fun hookInflateLayout() {
        val inflaterClass = NAV_BAR_INFLATER_VIEW.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, NAV_BAR_INFLATER_VIEW, "class not found")
            return
        }
        val method = CompatibleMethodResolver.find(
            inflaterClass,
            "inflateLayout",
            parameterTypes = listOf(String::class.java)
        ) ?: run {
            DebugLog.hookSkipped(TAG, "$NAV_BAR_INFLATER_VIEW#inflateLayout(String)", "method not found")
            return
        }

        runCatching {
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "inflateLayout", Unit) {
                        if (!aospRaiseActive(currentServiceContext())) return@open
                        val handle = AospImeConfig.navBarLayoutHandle()
                        if (handle.isNotBlank()) param.args[0] = handle
                    }
                }
                // Third wiring anchor: every re-inflation creates fresh button views, so re-run
                // the dismiss wiring right after the buttons exist.
                after { param ->
                    HookFailurePolicy.open(TAG, "wireNavBarBackButtonDismiss", Unit) {
                        val inflaterView = param.thisObject as? View ?: return@open
                        (inflaterView.parent as? View)?.let { wireBackButtonToDismiss(it) }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$NAV_BAR_INFLATER_VIEW#inflateLayout(String)", it)
        }
    }

    private fun hookOrientationViews() {
        val navBarViewClass = NAV_BAR_VIEW.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, NAV_BAR_VIEW, "class not found")
            return
        }
        // NavigationBarInflaterView declares its own mHorizontal, so read this one off NavigationBarView.
        navBarHorizontalField = runCatching {
            HostAccess.requireDeclaredField(navBarViewClass, "mHorizontal", accessible = true)
        }.getOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "$NAV_BAR_VIEW#mHorizontal", "field not found")
            return
        }

        val method = CompatibleMethodResolver.find(navBarViewClass, "updateOrientationViews") ?: run {
            DebugLog.hookSkipped(TAG, "$NAV_BAR_VIEW#updateOrientationViews()", "method not found")
            return
        }

        runCatching {
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "updateOrientationViews", Unit) {
                        if (!aospRaiseActive(currentServiceContext())) return@open
                        val view = navBarHorizontalField?.get(param.thisObject) as? View ?: return@open
                        installRoundedCornerInsetsListener(view)
                        // Pin the frame height too: `updateOrientationViews` runs at inflate time,
                        // before the controller has any window insets to size the frame from.
                        forceNavBarFrameHeight(view)
                        // Second wiring anchor: the buttons are already inflated when the bar's
                        // onFinishInflate runs this, even if prepareNavButtons never fires again.
                        (param.thisObject as? View)?.let { wireBackButtonToDismiss(it) }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$NAV_BAR_VIEW#updateOrientationViews()", it)
        }
    }

    private fun hookDeadZone() {
        val deadZoneClass = DEAD_ZONE.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, DEAD_ZONE, "class not found")
            return
        }
        deadZoneSizeMinField = runCatching {
            HostAccess.requireDeclaredField(deadZoneClass, "mSizeMin", accessible = true)
        }.getOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "$DEAD_ZONE#mSizeMin", "field not found")
            return
        }

        val method = CompatibleMethodResolver.find(
            deadZoneClass,
            "onConfigurationChanged",
            parameterTypes = listOf(Int::class.javaPrimitiveType!!)
        ) ?: run {
            DebugLog.hookSkipped(TAG, "$DEAD_ZONE#onConfigurationChanged(int)", "method not found")
            return
        }

        runCatching {
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "deadZoneConfigurationChanged", Unit) {
                        if (!aospRaiseActive(currentServiceContext())) return@open
                        deadZoneSizeMinField?.setInt(param.thisObject, 0)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$DEAD_ZONE#onConfigurationChanged(int)", it)
        }
    }

    /**
     * Forces the `NavigationBarFrame` around the given child view to the caption-bar height. The
     * frame is the full-height container of the AOSP bar; without this it follows the window's
     * bottom system-bar inset, which on some ROMs differs from the caption-bar height and makes
     * the bar look too tall or too short. The `getSystemInsets()` hook keeps it there on later
     * relayouts; this covers the first inflation when no insets exist yet.
     */
    private fun forceNavBarFrameHeight(child: View) {
        val frameClass = navBarFrameClass ?: NAV_BAR_FRAME.toClassOrNull()?.also { navBarFrameClass = it }
            ?: return
        var parent: ViewParent? = child.parent
        while (parent is View) {
            if (frameClass.isInstance(parent)) {
                val frame = parent as View
                val target = captionBarHeightPx(frame.resources)
                val lp = frame.layoutParams
                if (lp != null && lp.height != target) {
                    lp.height = target
                    frame.requestLayout()
                    DebugLog.d(TAG, "frame height pinned ${lp.height} -> $target")
                }
                return
            }
            parent = parent.parent
        }
    }

    /** Keeps the side buttons clear of the display's rounded corners. */
    private fun installRoundedCornerInsetsListener(view: View) {
        val shadow = dpToPx(NAV_BAR_SHADOW_DP, view.resources)
        runCatching {
            view.setOnApplyWindowInsetsListener { target, insets ->
                HookFailurePolicy.open(TAG, "applyRoundedCornerPadding", insets) {
                    applyRoundedCornerPadding(target, insets, shadow)
                }
            }
            insetsView = WeakReference(view)
        }.onFailure { DebugLog.w(TAG, "failed to install rounded corner insets listener", it) }
    }

    private fun applyRoundedCornerPadding(view: View, insets: WindowInsets, shadow: Int): WindowInsets {
        val base = basePaddings.getOrPut(view) {
            intArrayOf(
                view.paddingLeft + shadow,
                view.paddingTop,
                view.paddingRight + shadow,
                view.paddingBottom
            )
        }
        val leftRadius = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        val rightRadius = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
        view.setPadding(
            if (leftRadius > 0) max(base[0], leftRadius - base[0]) else base[0],
            base[1],
            if (rightRadius > 0) max(base[2], rightRadius - base[2]) else base[2],
            base[3]
        )
        return insets
    }

    private fun dpToPx(dp: Int, resources: Resources): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics)
            .roundToInt()
}
