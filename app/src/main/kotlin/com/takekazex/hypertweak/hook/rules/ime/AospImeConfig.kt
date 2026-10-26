package com.takekazex.hypertweak.hook.rules.ime

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.ImeLayout

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences

// Published on settings acceptance; hook callbacks perform no storage reads.
private val settingAospImeEnabled by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_IME_ENABLED, false) }
private val settingAospImeMiuiImeList by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_IME_MIUI_IME_LIST, false) }
private val settingAospImeForceAll by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_IME_FORCE_ALL, false) }
private val settingAospImeRaiseStyle by hookSetting(ImeLayout.RAISE_STYLE_AOSP) {
    Preferences.getInt(PlatformSettingsKeys.KEY_AOSP_IME_RAISE_STYLE, ImeLayout.RAISE_STYLE_AOSP)
}
private val settingAospImePackages by hookSetting(emptySet<String>()) { Preferences.getStringSet(PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES) }
private val settingAospImeNavBarStart by hookSetting(ImeLayout.BUTTON_HIDE_IME) {
    Preferences.getString(PlatformSettingsKeys.KEY_AOSP_IME_NAV_BAR_START, ImeLayout.BUTTON_HIDE_IME)
}
private val settingAospImeNavBarEnd by hookSetting(ImeLayout.BUTTON_IME_SWITCHER) {
    Preferences.getString(PlatformSettingsKeys.KEY_AOSP_IME_NAV_BAR_END, ImeLayout.BUTTON_IME_SWITCHER)
}

internal fun prepareAospImeConfigSettings() { settingAospImeEnabled }

/**
 * Shared reads of the AOSP IME settings. Usable from any process, since it only touches
 * [Preferences].
 */
object AospImeConfig {
    /** `70AC` is an absolute 70dp width; the handle sits in its own centre group. */
    private const val SIDE_BUTTON_SPEC = "[70AC]"

    fun isEnabled(): Boolean = settingAospImeEnabled

    /** Whether MIUI's keyboard switcher should list every enabled input method. */
    fun showAllImeList(): Boolean = settingAospImeMiuiImeList

    /**
     * Whether the AOSP bar is forced even on MIUI-customized keyboards (全面屏优化). Without this,
     * keyboards HyperOS recognizes as MIUI-customized keep their own bottom view.
     */
    fun forceAospForAll(): Boolean = settingAospImeForceAll

    fun raiseStyle(): Int =
        settingAospImeRaiseStyle

    fun selectedPackages(): Set<String> =
        settingAospImePackages.mapNotNull(::normalize).toSet()

    /** Accepts either a package name or an `IInputMethod` id of the form `pkg/.Service`. */
    fun isSelectedIme(packageOrId: String?): Boolean {
        val packageName = normalize(packageOrId?.substringBefore('/')) ?: return false
        return packageName in selectedPackages()
    }

    /**
     * A package is hooked when it is selected and at least one of the two features is on: the AOSP
     * bar itself, or the "list all keyboards in the switcher" fix (which only needs the side-loaded
     * MIUI dex hooks).
     */
    fun shouldHookImePackage(packageName: String): Boolean {
        val selected = normalize(packageName)?.let { it in selectedPackages() } == true
        return selected && (isEnabled() || showAllImeList())
    }

    fun navBarLayoutStart(): String =
        ImeLayout.button(settingAospImeNavBarStart, ImeLayout.BUTTON_HIDE_IME)

    fun navBarLayoutEnd(): String =
        ImeLayout.button(settingAospImeNavBarEnd, ImeLayout.BUTTON_IME_SWITCHER)

    fun navBarLayoutHandle(): String =
        "${navBarLayoutStart()}$SIDE_BUTTON_SPEC;${ImeLayout.BUTTON_HOME_HANDLE};${navBarLayoutEnd()}$SIDE_BUTTON_SPEC"

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
