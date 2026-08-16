package com.takekazex.hypertweak.hook.rules.ime

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.StaticFieldWriter
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
    private const val TAG = "AospIme"

    private const val INPUT_METHOD_SERVICE = "android.inputmethodservice.InputMethodService"
    private const val INPUT_METHOD_SERVICE_STUB = "android.inputmethodservice.InputMethodServiceStub"
    private const val NAV_BAR_CONTROLLER_IMPL = "android.inputmethodservice.NavigationBarController\$Impl"
    private const val NAV_BAR_INFLATER_VIEW = "android.inputmethodservice.navigationbar.NavigationBarInflaterView"
    private const val NAV_BAR_VIEW = "android.inputmethodservice.navigationbar.NavigationBarView"
    private const val DEAD_ZONE = "android.inputmethodservice.navigationbar.DeadZone"
    private const val INPUT_METHOD_MODULE_MANAGER = "android.inputmethodservice.InputMethodModuleManager"

    private const val CAPTION_BAR_HEIGHT_DP = 48
    private const val NAV_BAR_SHADOW_DP = 4

    /** Padding a view had before the rounded-corner listener started adjusting it. */
    private val basePaddings = WeakHashMap<View, IntArray>()

    private var insetsView: WeakReference<View>? = null

    /** MIUI can side-load its dex more than once; attach the child hooker per ClassLoader. */
    private val hookedDexLoaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    private var internationalBuildField: Field? = null
    private var imeSupportMethod: Method? = null
    private var imeSupportResolved = false

    /** The injector singleton is process-stable; resolve `getInstance()` once instead of per call. */
    private var imeInjectorInstance: Any? = null
    private var imeInjectorResolved = false

    private var navBarServiceField: Field? = null
    private var navBarHorizontalField: Field? = null
    private var deadZoneSizeMinField: Field? = null

    override val hotReloadMode = HotReloadMode.RECREATE

    override fun onPrepareHotReload() {
        insetsView?.get()?.let { view ->
            runCatching { view.setOnApplyWindowInsetsListener(null) }
            basePaddings.remove(view)
        }
        basePaddings.clear()
        hookedDexLoaders.clear()
        internationalBuildField = null
        imeSupportMethod = null
        imeSupportResolved = false
        imeInjectorInstance = null
        imeInjectorResolved = false
        navBarServiceField = null
        navBarHorizontalField = null
        deadZoneSizeMinField = null
    }

    /** The nav bar view survives a reload, so its listener has to be reinstalled on the same view. */
    override fun saveHotReloadState(): Any? = insetsView?.get()

    override fun restoreHotReloadState(state: Any?) {
        val view = state as? View ?: return
        installRoundedCornerInsetsListener(view)
        runCatching { view.requestApplyInsets() }
    }

    override fun onHook() {
        hookHideImeRenderGesturalNavButtons()
        hookImeCaptionBarHeight()
        hookInflateLayout()
        hookOrientationViews()
        hookDeadZone()
        if (Preferences.getBoolean(Preferences.KEY_AOSP_IME_MIUI_IME_LIST, false)) {
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
                        if (!hookedDexLoaders.add(loader)) return@open
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
        internationalBuildField = runCatching {
            serviceClass.getDeclaredField("IS_INTERNATIONAL_BUILD").apply { isAccessible = true }
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
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "hideImeRenderGesturalNavButtons", Unit) {
                        forceInternationalBuild(param.thisObject)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$INPUT_METHOD_SERVICE#hideImeRenderGesturalNavButtons(String)", it)
        }
    }

    /**
     * MIUI already draws its own bottom view for the keyboards it recognises, so leave those alone
     * and only take the AOSP branch when `isImeSupport` says this keyboard is not one of them.
     */
    private fun forceInternationalBuild(serviceInstance: Any?) {
        val field = internationalBuildField ?: return
        if (field.getBoolean(null)) return

        val context = (serviceInstance as? Context)?.applicationContext
        if (context != null && isMiuiCustomisedIme(context)) return

        // IS_INTERNATIONAL_BUILD is a static field whose only reader is the method being hooked,
        // so leaving it set does not leak into anything else in this process. It is also final,
        // and ART on OS4 rejects the reflective write, so StaticFieldWriter falls back to Unsafe.
        StaticFieldWriter.setBoolean(field, true)
    }

    private fun isMiuiCustomisedIme(context: Context): Boolean {
        val method = resolveImeSupportMethod() ?: return false
        val injector = imeInjector() ?: return false
        return runCatching { method.invoke(injector, context) as? Boolean }.getOrNull() == true
    }

    private fun imeInjector(): Any? {
        if (imeInjectorResolved) return imeInjectorInstance
        imeInjectorResolved = true
        imeInjectorInstance = runCatching {
            INPUT_METHOD_SERVICE_STUB.toClassOrNull()
                ?.getDeclaredMethod("getInstance")
                ?.apply { isAccessible = true }
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
                current.getDeclaredMethod("isImeSupport", Context::class.java).apply { isAccessible = true }
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
        val implClass = NAV_BAR_CONTROLLER_IMPL.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, NAV_BAR_CONTROLLER_IMPL, "class not found")
            return
        }
        navBarServiceField = runCatching {
            implClass.getDeclaredField("mService").apply { isAccessible = true }
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
                        val service = navBarServiceField?.get(param.thisObject) as? Context ?: return@open
                        param.result = dpToPx(CAPTION_BAR_HEIGHT_DP, service.resources)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$NAV_BAR_CONTROLLER_IMPL#getImeCaptionBarHeight(boolean)", it)
        }
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
            navBarViewClass.getDeclaredField("mHorizontal").apply { isAccessible = true }
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
                        val view = navBarHorizontalField?.get(param.thisObject) as? View ?: return@open
                        installRoundedCornerInsetsListener(view)
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
            deadZoneClass.getDeclaredField("mSizeMin").apply { isAccessible = true }
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
