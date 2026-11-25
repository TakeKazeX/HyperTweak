package com.takekazex.hypertweak.util.restart

object RestartProtocol {
    const val ACTION = "com.takekazex.hypertweak.action.RESTART_SCOPE"
    const val PERMISSION = "com.takekazex.hypertweak.permission.RESTART_SCOPE"

    /**
     * Diagnostics only: every hooked process that registered the restart receiver logs its hook
     * invocation counters (`HookHits`). Same permission as restart; send it as root or from the app.
     * Boolean extra [EXTRA_RESET] restarts the counting window after the dump.
     */
    const val ACTION_DUMP_HOOK_HITS = "com.takekazex.hypertweak.action.DUMP_HOOK_HITS"
    const val EXTRA_RESET = "reset"
    const val EXTRA_SYSTEM_UI = "systemui"
    const val EXTRA_MIUI_HOME = "miuihome"
    const val EXTRA_SETTINGS = "settings"
    const val EXTRA_AOD = "aod"
    const val EXTRA_SECURITY_CENTER = "securitycenter"
    const val EXTRA_SCANNER = "scanner"
    const val EXTRA_MILINK = "milink"
    const val EXTRA_BLUETOOTH = "bluetooth"
    const val EXTRA_POWERKEEPER = "powerkeeper"
    const val EXTRA_GMS = "gms"
    const val EXTRA_XMSF = "xmsf"

    /**
     * Package names to restart, for targets that have no [RestartScopeSelection] field — currently
     * the user-selected input methods.
     */
    const val EXTRA_PACKAGES = "packages"
}
