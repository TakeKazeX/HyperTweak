package com.takekazex.hypertweak.hook.rules.ime

import com.takekazex.hypertweak.hook.Preferences

/**
 * Shared reads of the AOSP IME settings. Usable from any process, since it only touches
 * [Preferences].
 */
object AospImeConfig {
    /** Layout tokens `NavigationBarInflaterView.createView` actually inflates. */
    const val BUTTON_HIDE_IME = "back"
    const val BUTTON_HOME_HANDLE = "home_handle"
    const val BUTTON_IME_SWITCHER = "ime_switcher"

    /** Placeholder for "no key"; keeps the handle centred where a real button would sit. */
    const val BUTTON_NONE = "space"

    /** `70AC` is an absolute 70dp width; the handle sits in its own centre group. */
    private const val SIDE_BUTTON_SPEC = "[70AC]"

    fun isEnabled(): Boolean = Preferences.getBoolean(Preferences.KEY_AOSP_IME_ENABLED, false)

    fun selectedPackages(): Set<String> =
        Preferences.getStringSet(Preferences.KEY_AOSP_IME_PACKAGES).mapNotNull(::normalize).toSet()

    /** Accepts either a package name or an `IInputMethod` id of the form `pkg/.Service`. */
    fun isSelectedIme(packageOrId: String?): Boolean {
        val packageName = normalize(packageOrId?.substringBefore('/')) ?: return false
        return packageName in selectedPackages()
    }

    fun shouldHookImePackage(packageName: String): Boolean =
        isEnabled() && normalize(packageName)?.let { it in selectedPackages() } == true

    fun navBarLayoutStart(): String =
        normalize(Preferences.getString(Preferences.KEY_AOSP_IME_NAV_BAR_START, BUTTON_HIDE_IME))
            ?: BUTTON_HIDE_IME

    fun navBarLayoutEnd(): String =
        normalize(Preferences.getString(Preferences.KEY_AOSP_IME_NAV_BAR_END, BUTTON_IME_SWITCHER))
            ?: BUTTON_IME_SWITCHER

    fun navBarLayoutHandle(): String =
        "${navBarLayoutStart()}$SIDE_BUTTON_SPEC;$BUTTON_HOME_HANDLE;${navBarLayoutEnd()}$SIDE_BUTTON_SPEC"

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
