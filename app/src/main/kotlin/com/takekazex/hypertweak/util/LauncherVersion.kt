package com.takekazex.hypertweak.util

import android.content.Context
import android.content.pm.PackageManager
import com.takekazex.hypertweak.hook.Preferences

/**
 * Version of the installed Xiaomi System Launcher, used to decide whether the AOSP back gesture
 * may hook the launcher at all.
 *
 * The predictive return-home animation reaches into `com.miui.home` Java classes
 * (`recents.anim.*`, `RectFSpringAnim`, `ClipAnimationHelper`, ...). Launcher 8 ships with
 * `android:hasCode="false"` and no dex at all — its gesture stack is native — so those hooks
 * cannot resolve there. Everything else in the back-gesture feature lives in SystemUI and the
 * system server and is unaffected by the launcher version.
 *
 * Detection runs in the module UI process, where a [Context] is available, and is cached in
 * [Preferences] so hook processes can read it. Hook processes that run before the cache is
 * populated fall back to probing the launcher's own class loader.
 */
object LauncherVersion {
    const val PACKAGE = "com.miui.home"

    /** Highest launcher major that still exposes the Java gesture classes. */
    const val MAX_SUPPORTED_MAJOR = 7

    /** Present on Launcher 7, absent on Launcher 8, which has no dex to load it from. */
    private const val GESTURE_STUB_CLASS = "com.miui.home.recents.GestureStubView"

    /** Cached major version, or 0 when the launcher has not been inspected yet. */
    val major: Int
        get() = Preferences.getInt(Preferences.KEY_LAUNCHER_MAJOR, 0)

    val versionName: String
        get() = Preferences.getString(Preferences.KEY_LAUNCHER_VERSION_NAME, "")

    val isDetected: Boolean
        get() = major > 0

    /** True when the installed launcher still exposes the classes the launcher route needs. */
    val isSupported: Boolean
        get() = major in 1..MAX_SUPPORTED_MAJOR

    /**
     * Whether a launcher-side gesture arbiter could exist in this process's world.
     *
     * Deliberately fails safe: an undetected launcher counts as "might arbitrate". SystemUI uses
     * this to decide whether it may claim a gesture directly, and claiming one that the launcher
     * is also arbitrating corrupts both sides' ownership state — so the only case that may take
     * the direct path is one where the launcher provably has no gesture code to arbitrate with.
     */
    val mayArbitrate: Boolean
        get() = !isDetected || isSupported

    /**
     * Reads the launcher version through [context] and caches it. Returns the major version, or
     * 0 when the launcher is absent or its version is unparseable.
     */
    fun refresh(context: Context): Int {
        val info = runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0)
        }.getOrElse { throwable ->
            if (throwable is PackageManager.NameNotFoundException) {
                DebugLog.d("LauncherVersion", "$PACKAGE is not installed")
            } else {
                DebugLog.w("LauncherVersion", "failed to read $PACKAGE version", throwable)
            }
            cache(0, "")
            return 0
        }
        val name = info.versionName.orEmpty()
        val parsedMajor = parseMajor(name)
        cache(parsedMajor, name)
        DebugLog.d("LauncherVersion", "$PACKAGE version=$name major=$parsedMajor supported=${parsedMajor in 1..MAX_SUPPORTED_MAJOR}")
        return parsedMajor
    }

    /**
     * Whether the launcher hook route may be installed. Prefers the cached version and falls
     * back to probing [classLoader], which is definitive: the route needs exactly these classes.
     */
    fun isRouteSupported(classLoader: ClassLoader?): Boolean {
        if (isDetected) return isSupported
        if (classLoader == null) return false
        return runCatching { Class.forName(GESTURE_STUB_CLASS, false, classLoader) }.isSuccess
    }

    /** Leading numeric component of a version name such as `8.00.02.2771` or `RELEASE-7.00.20`. */
    private fun parseMajor(versionName: String): Int =
        Regex("""\d+""").find(versionName)?.value?.toIntOrNull() ?: 0

    private fun cache(major: Int, versionName: String) {
        Preferences.putInt(Preferences.KEY_LAUNCHER_MAJOR, major)
        Preferences.putString(Preferences.KEY_LAUNCHER_VERSION_NAME, versionName)
    }
}
