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
 * Java-capable launcher processes can use [applyRuleSwitches] as a fallback. The pure HYOS
 * launcher uses the authenticated SystemUI settings transport; it does not depend on ART or JNI
 * initialization. Its native code cannot read the module's shared-media configuration file.
 *
 * JNI loading and status are restricted to the exact launcher owner. Diagnostics in other
 * processes stay passive. Observe the launcher through the upstream native log tag or its
 * authenticated runtime status path.
 */
object NativeRules {
    private const val LIBRARY_NAME = "hypertweak_native"

    @Volatile private var processName = ""

    /** The native bridge has exactly one process owner, matching the payload's cmdline guard. */
    fun bindProcess(name: String) { processName = name }
    internal fun ownsBridge(name: String): Boolean = name == "com.miui.home"

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
        if (!ownsBridge(processName)) return false
        if (state != State.NOT_ATTEMPTED) return state == State.LOADED
        synchronized(this) {
            if (state != State.NOT_ATTEMPTED) return state == State.LOADED
            state = try {
                System.loadLibrary(LIBRARY_NAME)
                State.LOADED
            } catch (t: UnsatisfiedLinkError) {
                DebugLog.w("NativeRules", "native payload unavailable", t)
                State.UNAVAILABLE
            }
        }
        return state == State.LOADED
    }

    /** One-line payload status, or null when the payload is not loaded in this process. */
    fun status(): String? {
        if (!ownsBridge(processName) || state != State.LOADED) return null
        return try {
            nativeStatus()
        } catch (t: Throwable) {
            DebugLog.w("NativeRules", "native status query failed", t)
            null
        }
    }

    /** Always-renderable summary for the debug log. */
    fun describe(): String = status()?.let { "native payload $it" } ?: "native payload state=$state"

    /**
     * Hands the launcher-side rule switches to the payload in this process.
     *
     * Java-capable launchers read remote Preferences and pass them here. Pure native HYOS
     * launchers receive the same subset through SystemUI's authenticated broadcasts. The shared
     * media file is not a launcher-readable configuration source under scoped storage.
     *
     * The launcher runtime saves the received snapshot in its own device-encrypted storage,
     * so boot does not depend on the unavailable shared-media file. Preparation runs on a
     * native worker; installation follows at the next input/load maintenance boundary.
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
            DebugLog.w("NativeRules", "native rule switch push failed", t)
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
