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
import com.takekazex.hypertweak.util.platform.StaticFieldWriter
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max
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
object AospImeHooker : StaticHooker() {
    private val raise = ImeRaiseController(this, ::aospRaiseActive, ::captionBarHeightPx)
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

    private var imeSupportMethod: Method? by generationState { null }
    private var imeSupportResolved by generationState { false }

    /** The injector singleton is process-stable; resolve `getInstance()` once instead of per call. */
    private var imeInjectorInstance: Any? by generationState { null }
    private var imeInjectorResolved by generationState { false }

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

    override fun onPrepareHotReload() {
        raise.prepareReload()
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
    override fun saveHotReloadState(): Any? = ArrayList<Any?>(
        listOf(insetsView?.get(), raise.saveOwnedViews())
    )

    override fun restoreHotReloadState(state: Any?) {
        val parts = state as? ArrayList<*> ?: return
        (parts.getOrNull(0) as? View)?.let { view ->
            installRoundedCornerInsetsListener(view)
            runCatching { view.requestApplyInsets() }
        }
        raise.restoreOwnedViews(parts.getOrNull(1))
    }

    override fun onHook() {
        com.takekazex.hypertweak.hook.rules.ime.prepareAospImeConfigSettings()
        val barEnabled = AospImeConfig.isEnabled()
        if (barEnabled) {
            hookHideImeRenderGesturalNavButtons()
            hookImeCaptionBarHeight()
            hookImeCaptionBarInsets()
            hookSystemInsets()
            hookRaiseKeyboard()
            hookInflateLayout()
            hookOrientationViews()
            hookDeadZone()
            hookNavBarBackButtonDismiss()
        }
        // The MIUI bottom-view suppression is needed whenever the AOSP bar is on (OS4.0.0.19 calls
        // addMiuiBottomView unconditionally, so the bar and the MIUI bottom view would stack);
        // the switcher-list fix needs the dex hooks on their own too.
        if (AospImeConfig.showAllImeList() || barEnabled) {
            hookLoadDex()
        }
    }

    /**
     * `InputMethodBottomManager` lives in the dex MIUI side-loads into the keyboard process, so the
     * hooks on it have to wait for that ClassLoader to exist.
     */
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
                        if (!synchronized(hookedDexLoaders) { hookedDexLoaders.add(loader) }) return@open
                        attach(MiuiImeBottomHooker, loader)
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

        // On OS4.0.0.19 (decompiled from the OTA framework.jar) the method body still reads
        // `IS_INTERNATIONAL_BUILD`:
        //   if (IS_INTERNATIONAL_BUILD || TextUtils.isEmpty(inputMethodId) ||
        //       inputMethodId.contains(TEST_IME_PKG_NAME)) {
        //       return !canImeRenderGesturalNavButtons();
        //   }
        //   return true;
        // so setting the field (upstream's approach) lets the original method take the
        // international branch and render the AOSP caption bar, instead of short-circuiting the
        // return value and bypassing the original's own `canImeRenderGesturalNavButtons()` gate.
        val internationalBuildField = runCatching {
            HostAccess.requireDeclaredField(serviceClass, "IS_INTERNATIONAL_BUILD", accessible = true)
        }.getOrNull()
        if (internationalBuildField == null) {
            DebugLog.hookSkipped(TAG, "$INPUT_METHOD_SERVICE#IS_INTERNATIONAL_BUILD", "field not found")
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

        runCatching {
            deoptimize(method)
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "hideImeRenderGesturalNavButtons", Unit) {
                        val context = (param.thisObject as? Context)?.applicationContext
                        // No early return when disabled: aospRaiseActive is false then, so the
                        // branch below uniformly restores the ROM default (false). Leaving the
                        // field untouched instead would preserve OUR earlier true latch and
                        // resurrect the bar flow after the master switch is turned off.
                        //
                        // The field's only reader is the method being hooked, so flipping it for
                        // this process cannot leak elsewhere. Write true to take the international
                        // branch (the original then returns `!canImeRenderGesturalNavButtons()` and
                        // the caption bar renders); write false back otherwise so the MIUI bottom
                        // view stays the bar without a process restart.
                        if (aospRaiseActive(context)) {
                            if (!internationalBuildField.getBoolean(null)) {
                                StaticFieldWriter.setBoolean(internationalBuildField, true)
                            }
                        } else if (internationalBuildField.getBoolean(null)) {
                            StaticFieldWriter.setBoolean(internationalBuildField, false)
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$INPUT_METHOD_SERVICE#hideImeRenderGesturalNavButtons(String)", it)
        }
    }

    /**
     * Whether the AOSP caption bar is the active bottom bar for this keyboard process:
     * the bar master is on AND this keyboard is either not a MIUI-customized one or the "force
     * AOSP on optimized keyboards" option is set. When false, MIUI's own bottom view is the bar
     * (`addMiuiBottomView` must not be skipped) and the AOSP branch is suppressed.
     */
    internal fun aospBarActive(context: Context?): Boolean {
        if (!AospImeConfig.isEnabled()) return false
        val ctx = context ?: return true
        if (!AospImeConfig.forceAospForAll() && isMiuiCustomisedIme(ctx)) return false
        return true
    }

    /**
     * Whether the AOSP raise pipeline should act at all: the bar is active AND the user picked the
     * AOSP raise style. Under 小米样式 nothing may raise the keyboard — not this module's padding,
     * and crucially not the caption-bar inset injection that inset-honoring keyboards (微信输入法,
     * Gboard) consume and carve themselves by — so every style-aware hook gates on this.
     */
    internal fun aospRaiseActive(context: Context?): Boolean =
        aospBarActive(context) && AospImeConfig.raiseStyle() == com.takekazex.hypertweak.settings.contract.ImeLayout.RAISE_STYLE_AOSP

    private fun isMiuiCustomisedIme(context: Context): Boolean {
        // Primary on every OS4 build: InputMethodServiceInjector.isImeSupport(Context). The
        // injector still ships in miui-framework.jar on OS4.0.0.19 (decompiled from the OTA —
        // the earlier "gone on .19" claim was wrong) and is usable immediately, before the
        // side-loaded dex exists, so the first hideImeRenderGesturalNavButtons dispatch sees the
        // correct customized-keyboard answer instead of a not-yet-populated snapshot.
        val method = resolveImeSupportMethod()
        val injector = imeInjector()
        if (method != null && injector != null) {
            runCatching { method.invoke(injector, context) as? Boolean }.getOrNull()?.let { return it }
        }
        // Secondary snapshot: the dex's sImeMinVersionSupport allowlist, mirrored at loadDex time.
        // Only authoritative once the dex is loaded (after the first addMiuiBottomView call), so it
        // must not be the primary signal for the first hideImeRenderGesturalNavButtons dispatch.
        return MiuiCustomizedImePackages.isCustomized(context.packageName)
    }

    private fun imeInjector(): Any? {
        if (imeInjectorResolved) return imeInjectorInstance
        imeInjectorResolved = true
        imeInjectorInstance = runCatching {
            INPUT_METHOD_SERVICE_STUB.toClassOrNull()
                ?.cachedDeclaredMethod("getInstance", accessible = true)
                ?.invoke(null)
        }.getOrNull()
        return imeInjectorInstance
    }

    /** `isImeSupport` is private and declared on the injector, not on the stub interface. */
    private fun resolveImeSupportMethod(): Method? {
        if (imeSupportResolved) return imeSupportMethod
        imeSupportResolved = true
        val injector = imeInjector() ?: return null

        var clazz: Class<*>? = injector.javaClass
        while (clazz != null) {
            val current = clazz
            val found = runCatching {
                HostAccess.requireDeclaredMethod(current, "isImeSupport", Context::class.java, accessible = true)
            }.getOrNull()
            if (found != null) {
                imeSupportMethod = found
                return found
            }
            clazz = current.superclass
        }
        return null
    }

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
    private fun currentServiceContext(): Context? {
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
                        // Gate on the master only. Deactivation paths (style switch, force-all
                        // off, customized keyboard without force-all) MUST still run: the
                        // inactive branch of evaluateKeyboardRaise is what withdraws our padding
                        // and zeroes a stale caption-bar source; gating on aospBarActive here
                        // would leave that source alive until the keyboard process restarts.
                        if (!AospImeConfig.isEnabled()) return@open
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
                        DebugLog.d(TAG, "updateOrientationViews fired")
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
