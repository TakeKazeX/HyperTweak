package com.takekazex.hypertweak.hook.rules.ime

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.ImeLayout

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences

// Published on settings acceptance; hook callbacks perform no storage reads.
private val settingAospImeEnabled by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_IME_ENABLED, false) }
private val settingAospImeMiuiImeList by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_AOSP_IME_MIUI_IME_LIST, false) }
private val settingAospImeRaiseStyle by hookSetting(ImeLayout.RAISE_STYLE_AOSP) {
    Preferences.getInt(PlatformSettingsKeys.KEY_AOSP_IME_RAISE_STYLE, ImeLayout.RAISE_STYLE_AOSP)
}
private val settingAospImePackages by hookSetting(emptySet<String>()) { Preferences.getStringSet(PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES).map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
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

    fun raiseStyle(): Int =
        settingAospImeRaiseStyle

    fun selectedPackages(): Set<String> =
        settingAospImePackages

    /** Accepts either a package name or an `IInputMethod` id of the form `pkg/.Service`. */
    fun isSelectedIme(packageOrId: String?): Boolean {
        val packageName = normalize(packageOrId?.substringBefore('/')) ?: return false
        return packageName in selectedPackages()
    }

    /** Install dormant hooks in selected keyboards so the master can be enabled live. */
    fun shouldHookImePackage(packageName: String): Boolean {
        val selected = normalize(packageName)?.let { it in selectedPackages() } == true
        return selected
    }

    fun navBarLayoutStart(): String =
        ImeLayout.button(settingAospImeNavBarStart, ImeLayout.BUTTON_HIDE_IME)

    fun navBarLayoutEnd(): String =
        ImeLayout.button(settingAospImeNavBarEnd, ImeLayout.BUTTON_IME_SWITCHER)

    fun navBarLayoutHandle(): String =
        "${navBarLayoutStart()}$SIDE_BUTTON_SPEC;${ImeLayout.BUTTON_HOME_HANDLE};${navBarLayoutEnd()}$SIDE_BUTTON_SPEC"

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
