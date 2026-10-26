package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.InstallPolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Each feature owns its handles, cached switch and independent installation lifecycle. */
internal abstract class PickerHooker(private val key: String) : StaticHooker() {
    protected val tag: String get() = hookerName
    override val installPolicy = InstallPolicy.WHEN_ENABLED
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED
    @Volatile protected var enabled = false

    override fun isFeatureEnabled() = Preferences.nativePickerEnabled(key)

    fun refreshSettings() {
        Preferences.invalidateRuntimeReadCache()
        enabled = isFeatureEnabled()
        refreshFeatureState()
    }

    override fun onPrepareHotReload() { enabled = false }
    override fun recoverHotReloadHosts() { refreshSettings() }

    protected fun install(label: String, resolve: () -> Method?, hook: (Method) -> Unit) {
        runCatching {
            val method = resolve()
            if (method == null) {
                DebugLog.hookSkipped(tag, label, "missing or ambiguous contract")
                return
            }
            check(deoptimize(method)) { "Cannot deoptimize $label" }
            hook(method)
        }.onFailure { DebugLog.hookFailed(tag, label, it) }
    }
}

/** Filters only implicit requests; explicit app-selected components/packages stay usable. */
internal object NativePhotoPickerHooker : PickerHooker(PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER) {
    override fun onHook() {
        enabled = isFeatureEnabled()
        install("picker candidates", {
            "com.android.server.pm.ComputerEngine".toClassOrNull()?.declaredMethods?.singleOrNull {
                it.returnType == List::class.java && !Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(
                        Intent::class.java, String::class.java,
                        Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                    ))
            }
        }) { method ->
            method.hook {
                after { param ->
                    if (enabled) HookFailurePolicy.open(tag, "picker candidates", Unit) {
                        val intent = param.args[0] as? Intent
                        if (intent != null && PickerReroutePolicy.handles(
                                intent.action, intent.component != null || intent.`package` != null)) {
                            val candidates = param.result as? List<*>
                            if (candidates != null) {
                                // Copy only when necessary; works with immutable host lists and leaves
                                // the host's shared result untouched. Preserve every non-Hyper candidate.
                                val filtered = PickerReroutePolicy.filter(candidates) {
                                    (it as? ResolveInfo)?.activityInfo?.packageName
                                }
                                if (filtered !== candidates) param.result = filtered
                            }
                        }
                    }
                }
            }
        }
    }

}

internal object NativeFilePickerHooker : PickerHooker(PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER) {
    const val PACKAGE = "com.android.photopicker"
    override fun onHook() {
        enabled = isFeatureEnabled()
        if (hookParam.packageName == PACKAGE) hookBrowse() else hookFileRedirect()
    }

    private fun hookFileRedirect() {
        // The vendor's public wrapper also runs cloud redirects and camera/scenario callbacks.
        // Bypass only its unique private (Intent, String) -> Intent helper, before it mutates input.
        install("file redirect", {
            val stub = "com.android.server.wm.ActivityTaskManagerServiceStub".toClassOrNull()
            val impl = "com.android.server.wm.ActivityTaskManagerServiceImpl".toClassOrNull()
            if (stub == null || impl == null || !stub.isAssignableFrom(impl)) null
            else impl.declaredMethods.singleOrNull {
                Modifier.isPrivate(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                    it.returnType == Intent::class.java &&
                    it.parameterTypes.contentEquals(arrayOf(Intent::class.java, String::class.java))
            }
        }) { method ->
            method.hook {
                before { param ->
                    if (enabled) HookFailurePolicy.open(tag, "file redirect", Unit) {
                        val intent = param.args[0] as? Intent
                        if (intent?.action == Intent.ACTION_OPEN_DOCUMENT) param.result = intent
                    }
                }
            }
        }
    }

    private fun hookBrowse() {
        install("Hyper browse destination", {
            "com.android.photopicker.hyper.HyperMainActivity".toClassOrNull()
                ?.takeIf { Activity::class.java.isAssignableFrom(it) }
                ?.declaredMethods?.singleOrNull {
                    it.name == "getHyperFilePickerName" && it.parameterCount == 0 &&
                        it.returnType == ComponentName::class.java && !Modifier.isStatic(it.modifiers)
                }
        }) { method ->
            method.hook {
                before { param ->
                    if (enabled) HookFailurePolicy.open(tag, "Hyper browse destination", Unit) {
                        val activity = param.thisObject as? Activity
                        if (activity != null) {
                            val destination = Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                                .resolveActivity(activity.packageManager)
                            // The original helper owns the fallback itself. Returning null here
                            // would bypass it and launch an implicit GET_CONTENT again.
                            if (destination != null) param.result = destination
                            else DebugLog.w(tag, "DocumentsUI unavailable; preserving host browse destination")
                        }
                    }
                }
            }
        }
    }
}
