package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.util.platform.StaticFieldWriter
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker

/** Updates the native cached PWM capability and restores the vendor value on disable/retirement. */
object AdaptiveRefreshRuntimeHooker : StaticHooker() {
    /** Installs no method hook by design: it writes a host static field and deoptimizes its readers. */
    override val expectsHooks = false

    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingUnlockAdaptiveRefreshPro by hookSetting(false) { Preferences.unlockAdaptiveRefreshPro() }

    private const val TAG = "AdaptiveRefreshRuntime"
    private const val PROP_PWM_SWITCH = "ro.display.enable_pwm_switch"

    private var supportField: java.lang.reflect.Field? by generationState { null }
    private var nativeSupport by generationState { false }
    private var hostType: Class<*>? by generationState { null }

    override fun onHook() {
        val props = Class.forName("android.os.SystemProperties", true, classLoader)
        nativeSupport = HostAccess.requireDeclaredMethod(
            props, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType, accessible = true
        ).invoke(null, PROP_PWM_SWITCH, false) as? Boolean ?: false
        val type = Class.forName("com.android.server.display.DisplayFeatureManagerService", true, classLoader)
        val field = HostAccess.requireDeclaredField(type, "MIMOTION_PWM_SUPPORTED", accessible = true)
        require(field.type == Boolean::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(field.modifiers))
        supportField = field
        hostType = type
        // These are all non-clinit readers in the verified host image.
        type.declaredMethods.filter { it.name in setOf("setDimmingMode") }.forEach { deoptimize(it) }
        refreshSettings()
    }

    fun refreshSettings(): Boolean = HookFailurePolicy.open(TAG, "refresh PWM capability", false) {
        val field = supportField ?: return@open false
        val desired = nativeSupport || settingUnlockAdaptiveRefreshPro
        StaticFieldWriter.setBoolean(field, desired)
        val type = hostType ?: return@open false
        val service = SystemServiceHosts.binderOwner(classLoader, "displayfeature", type)
        if (service != null && desired) {
            val handler = HostAccess.read(service, "mHandler") as? android.os.Handler
                ?: return@open false
            HostAccess.onHandler(handler) {
                HostAccess.requireDeclaredMethod(type, "setDimmingMode", accessible = true).invoke(service)
            }
        }
        field.getBoolean(null) == desired
    }

    override fun recoverHotReloadHosts() { refreshSettings() }

    override fun onPrepareHotReload() {
        supportField?.let { StaticFieldWriter.setBoolean(it, nativeSupport) }
    }
}
