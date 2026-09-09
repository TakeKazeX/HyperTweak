package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.PlatformLevel
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Enables the vendor ToastStub blur/material path for standard SystemUI text Toasts.
 *
 * This follows the small ToastBlur hook used by the reference module: it calls
 * `android.widget.ToastStub.get().addBlur(...)` from ToastPresenter's accessibility boundary.
 * Android 16/17 vendor builds expose different second-argument types, so both Context and
 * WeakReference<Context> are resolved reflectively. If the platform already installed the same
 * ToastStub listener, this reuses the vendor implementation and leaves the rendering details to
 * the framework.
 */
object SystemUiToastAdvancedMaterialHooker : StaticHooker() {
    private const val TAG = "SystemUiToastAdvancedMaterial"
    private const val TOAST_PRESENTER = "android.widget.ToastPresenter"
    private const val TOAST_STUB = "android.widget.ToastStub"
    private const val VIEW_MESSAGE_ID = android.R.id.message

    @Volatile
    private var enabled = false

    private var presenterViewField: Field? = null
    private var customizeViewMethod: Method? = null
    private var customizeViewResolved = false
    private var toastStubClass: Class<*>? = null
    private var toastStubGetMethod: Method? = null
    private var addBlurTarget: AddBlurTarget? = null
    private var addBlurResolved = false
    private var loggedBlurPath = false

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onPrepareHotReload() {
        enabled = false
        presenterViewField = null
        customizeViewMethod = null
        customizeViewResolved = false
        toastStubClass = null
        toastStubGetMethod = null
        addBlurTarget = null
        addBlurResolved = false
        loggedBlurPath = false
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_SYSTEMUI_TOAST_ADVANCED_MATERIAL, false)
        if (!enabled) {
            DebugLog.hookSkipped(TAG, TOAST_PRESENTER, "advanced Toast material disabled")
            return
        }
        if (!PlatformLevel.isOs3OrOs4) {
            DebugLog.hookSkipped(TAG, TOAST_PRESENTER, "only Android 16/17 are supported")
            return
        }

        val presenterClass = TOAST_PRESENTER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, TOAST_PRESENTER, "class not found")
            return
        }
        presenterViewField = findField(presenterClass, "mView")
        if (presenterViewField == null) {
            DebugLog.hookSkipped(TAG, "$TOAST_PRESENTER#mView", "field not found")
            return
        }

        val accessibilityMethod = CompatibleMethodResolver.find(
            presenterClass,
            "trySendAccessibilityEvent",
            parameterTypes = listOf(View::class.java, String::class.java)
        ) ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$TOAST_PRESENTER#trySendAccessibilityEvent(View,String)",
                "method not found"
            )
            return
        }

        runCatching {
            deoptimize(accessibilityMethod)
            accessibilityMethod.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "trySendAccessibilityEvent", Unit) {
                        if (!enabled) return@open
                        val view = presenterViewField?.let { field ->
                            runCatching { field.get(param.thisObject) as? View }.getOrNull()
                        } ?: (param.args.getOrNull(0) as? View) ?: return@open
                        if (!isStandardTextToast(view)) return@open
                        addToastBlur(view)
                    }
                }
            }
            DebugLog.hookRegistered(
                TAG,
                "$TOAST_PRESENTER#trySendAccessibilityEvent(View,String)"
            )
        }.onFailure {
            DebugLog.hookFailed(
                TAG,
                "$TOAST_PRESENTER#trySendAccessibilityEvent(View,String)",
                it
            )
        }
    }

    private fun isStandardTextToast(view: View): Boolean {
        val customMethod = resolveCustomizeViewMethod()
        if (customMethod != null) {
            val custom = runCatching { customMethod.invoke(view) as? Boolean }.getOrNull()
            if (custom == true) return false
        }

        // The OS4 SystemUI layout is ToastLinearLayout. AOSP Android 16 can use a plain
        // LinearLayout for the framework text layout, identified by android.R.id.message.
        val className = view.javaClass.name
        val namedToastLayout = className.endsWith("ToastLinearLayout")
        val plainTextLayout = className == "android.widget.LinearLayout" && runCatching {
            view.findViewById<TextView>(VIEW_MESSAGE_ID) != null
        }.getOrDefault(false)
        return namedToastLayout || plainTextLayout
    }

    private fun resolveCustomizeViewMethod(): Method? {
        if (customizeViewResolved) return customizeViewMethod
        customizeViewResolved = true
        customizeViewMethod = runCatching {
            View::class.java.getMethod("getCustomizeView").apply { isAccessible = true }
        }.getOrNull()
        return customizeViewMethod
    }

    private fun addToastBlur(view: View) {
        val stubClass = resolveToastStubClass() ?: return
        val getMethod = toastStubGetMethod ?: return
        val stub = runCatching { getMethod.invoke(null) }.getOrNull() ?: return
        val target = resolveAddBlurTarget(stubClass, stub.javaClass) ?: return
        val context = view.context
        val contextArg = if (target.usesWeakReference) {
            WeakReference(context)
        } else {
            context
        }

        runCatching {
            // The reference hook passes false because this route is specifically for ordinary
            // text Toasts; custom Toasts are filtered before reaching this point.
            target.method.invoke(stub, view, contextArg, false)
        }.onSuccess {
            if (!loggedBlurPath) {
                loggedBlurPath = true
                DebugLog.i(TAG, "ToastStub.addBlur path=${target.method.parameterTypes[1].name}")
            }
        }.onFailure {
            DebugLog.w(TAG, "ToastStub.addBlur invocation failed", it)
        }
    }

    private fun resolveToastStubClass(): Class<*>? {
        if (toastStubClass != null) return toastStubClass
        val type = TOAST_STUB.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, TOAST_STUB, "class not found")
            return null
        }
        val getter = runCatching {
            type.getDeclaredMethod("get").apply { isAccessible = true }
        }.getOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "$TOAST_STUB#get()", "method not found")
            return null
        }
        toastStubClass = type
        toastStubGetMethod = getter
        return type
    }

    private fun resolveAddBlurTarget(
        stubClass: Class<*>,
        implementationClass: Class<*>
    ): AddBlurTarget? {
        if (addBlurResolved) return addBlurTarget
        addBlurResolved = true

        val classes = buildList {
            add(implementationClass)
            if (stubClass != implementationClass) add(stubClass)
            var parent = implementationClass.superclass
            while (parent != null) {
                add(parent)
                parent = parent.superclass
            }
        }
        val method = classes.asSequence()
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { candidate ->
                val params = candidate.parameterTypes
                candidate.name == "addBlur" &&
                    params.size == 3 &&
                    params[0] == View::class.java &&
                    params[2] == Boolean::class.javaPrimitiveType &&
                    (Context::class.java.isAssignableFrom(params[1]) ||
                        WeakReference::class.java.isAssignableFrom(params[1]))
            }?.apply { isAccessible = true }

        addBlurTarget = method?.let {
            AddBlurTarget(
                method = it,
                usesWeakReference = WeakReference::class.java.isAssignableFrom(it.parameterTypes[1])
            )
        }
        if (addBlurTarget == null) {
            DebugLog.hookSkipped(TAG, "$TOAST_STUB#addBlur", "compatible method not found")
        }
        return addBlurTarget
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            if (field != null) return field
            current = current.superclass
        }
        return null
    }

    private data class AddBlurTarget(
        val method: Method,
        val usesWeakReference: Boolean
    )
}
