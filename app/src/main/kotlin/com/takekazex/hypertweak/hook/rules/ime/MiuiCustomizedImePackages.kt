package com.takekazex.hypertweak.hook.rules.ime

import java.util.concurrent.ConcurrentHashMap

/**
 * Packages the side-loaded MIUI dex (com.miui.phrase) treats as supporting its bottom view —
 * the keys of `InputMethodBottomManager.sImeMinVersionSupport`. On OS4.0.0.19 the old
 * `InputMethodServiceInjector.isImeSupport(Context)` is gone from the framework, so this set is
 * the only reliable "customized IME" signal: it is populated by [MiuiImeBottomHooker] as soon as
 * the dex is loaded into the keyboard process.
 */
object MiuiCustomizedImePackages {
    private val packages = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var populated = false

    fun update(packages: Set<String>) {
        this.packages.clear()
        this.packages.addAll(packages)
        populated = true
    }

    fun isPopulated(): Boolean = populated

    fun isCustomized(packageName: String?): Boolean =
        packageName != null && populated && packageName in packages
}