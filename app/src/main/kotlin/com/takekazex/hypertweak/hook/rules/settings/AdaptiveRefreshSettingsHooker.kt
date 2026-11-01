package com.takekazex.hypertweak.hook.rules.settings

import com.takekazex.hypertweak.util.platform.StaticFieldWriter
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker

/** Updates the native cached PWM capability and restores the vendor value on disable/retirement. */
object AdaptiveRefreshSettingsHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingUnlockAdaptiveRefreshPro by hookSetting(false) { Preferences.unlockAdaptiveRefreshPro() }

    private const val TAG = "AdaptiveRefreshSettings"
    private const val PROP_PWM_SWITCH = "ro.display.enable_pwm_switch"

    private var supportField: java.lang.reflect.Field? by generationState { null }
    private var nativeSupport by generationState { false }

    override fun onHook() {
        val props = Class.forName("android.os.SystemProperties", true, classLoader)
        nativeSupport = HostAccess.requireDeclaredMethod(
            props, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType, accessible = true
        ).invoke(null, PROP_PWM_SWITCH, false) as? Boolean ?: false
        val type = Class.forName("com.android.settings.MiuiDisplaySettings", true, classLoader)
        val field = HostAccess.requireDeclaredField(type, "MIMOTION_PWM_SUPPORTED", accessible = true)
        require(field.type == Boolean::class.javaPrimitiveType && java.lang.reflect.Modifier.isStatic(field.modifiers))
        supportField = field
        // These are all non-clinit readers in the verified host image.
        type.declaredMethods.filter { it.name in setOf("onCreate", "onPreferenceChange") }.forEach { deoptimize(it) }
        refreshSettings()
    }

    fun refreshSettings(): Boolean = HookFailurePolicy.open(TAG, "refresh PWM capability", false) {
        val field = supportField ?: return@open false
        val desired = nativeSupport || settingUnlockAdaptiveRefreshPro
        StaticFieldWriter.setBoolean(field, desired)
        field.getBoolean(null) == desired
    }

    override fun recoverHotReloadHosts() { refreshSettings() }

    override fun onPrepareHotReload() {
        supportField?.let { StaticFieldWriter.setBoolean(it, nativeSupport) }
    }
}
