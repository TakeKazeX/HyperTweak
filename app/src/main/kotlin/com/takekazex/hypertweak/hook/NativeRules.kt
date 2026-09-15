package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.util.DebugLog

/**
 * Access to the native payload LSPosed injects alongside the module's dex.
 *
 * `libhypertweak_native.so` is declared in `META-INF/xposed/native_init.list`, so the framework
 * maps it before [HookEntry] runs. The upstream payload initializes its native launcher state only
 * in the HYOS launcher process family; other processes return from the native entry without
 * installing hooks.
 *
 * The launcher carries no dex of its own for the module's hooks, but it is in the declared scope,
 * so LSPosed injects both the payload and this module's Java there. That is what makes
 * [applyRuleSwitches] work: the launcher-side rules cannot read the module's preferences or the
 * config file [NativeRuleConfig] writes, but this process's Java can, and it hands the values to
 * the payload through JNI.
 *
 * The status below therefore describes the payload **in the process that calls it**. Reading it
 * from the module's own process proves packaging and JNI binding, not the launcher's state.
 * Observe the launcher through the upstream native log tag or its authenticated runtime status
 * path.
 */
object NativeRules {
    private const val LIBRARY_NAME = "hypertweak_native"

    enum class State { NOT_ATTEMPTED, LOADED, UNAVAILABLE }

    @Volatile
    private var state: State = State.NOT_ATTEMPTED

    /**
     * Loads the payload into this process. Returns false when it cannot be loaded here.
     *
     * Never throws: a process where the payload is absent must observe "unavailable" rather than
     * fail the host's module-load path.
     */
    fun ensureLoaded(): Boolean {
        if (state == State.LOADED) return true
        synchronized(this) {
            if (state == State.LOADED) return true
            state = try {
                System.loadLibrary(LIBRARY_NAME)
                State.LOADED
            } catch (t: UnsatisfiedLinkError) {
                DebugLog.w("NativeRules", "native payload unavailable: ${t.message}")
                State.UNAVAILABLE
            }
        }
        return state == State.LOADED
    }

    /** One-line payload status, or null when the payload is not loaded in this process. */
    fun status(): String? {
        if (!ensureLoaded()) return null
        return try {
            nativeStatus()
        } catch (t: Throwable) {
            DebugLog.w("NativeRules", "native status query failed: ${t.message}")
            null
        }
    }

    /** Always-renderable summary for the debug log. */
    fun describe(): String = status()?.let { "native payload $it" } ?: "native payload unavailable"

    /**
     * Hands the launcher-side rule switches to the payload in this process.
     *
     * This is the channel the rules actually use. [NativeRuleConfig] publishes the same values as
     * a file as well, but only the module's own process can read it back: the launcher runs as
     * `platform_app_36` with an ordinary app uid, is neither the file's owner nor in its
     * `media_rw` group, and scoped storage refuses it the module's `Android/media` directory. The
     * module's Java is injected into the launcher too, so it reads the preferences through the
     * remote channel and passes them here.
     *
     * Returns false when the payload is not loaded in this process. Never throws.
     */
    fun applyRuleSwitches(
        hideRecentsClearButton: Boolean,
        openedFolderColumns: Int,
        contextualSearchLongPress: Boolean
    ): Boolean {
        if (!ensureLoaded()) return false
        return try {
            nativeApplyRuleSwitches(
                hideRecentsClearButton,
                openedFolderColumns,
                contextualSearchLongPress
            )
            true
        } catch (t: Throwable) {
            DebugLog.w("NativeRules", "native rule switch push failed: ${t.message}")
            false
        }
    }

    // Bound by name, so this class and this method must survive R8 unchanged.
    private external fun nativeStatus(): String

    // Bound by name, so this class and this method must survive R8 unchanged.
    private external fun nativeApplyRuleSwitches(
        hideRecentsClearButton: Boolean,
        openedFolderColumns: Int,
        contextualSearchLongPress: Boolean
    )
}
