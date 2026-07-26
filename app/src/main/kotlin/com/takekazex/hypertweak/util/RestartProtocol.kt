package com.takekazex.hypertweak.util

object RestartProtocol {
    const val ACTION = "com.takekazex.hypertweak.action.RESTART_SCOPE"
    const val PERMISSION = "com.takekazex.hypertweak.permission.RESTART_SCOPE"
    const val EXTRA_SYSTEM_UI = "systemui"
    const val EXTRA_MIUI_HOME = "miuihome"
    const val EXTRA_SETTINGS = "settings"
    const val EXTRA_AOD = "aod"
    const val EXTRA_SECURITY_CENTER = "securitycenter"
    const val EXTRA_SCANNER = "scanner"
    const val EXTRA_MILINK = "milink"
    const val EXTRA_BLUETOOTH = "bluetooth"
    const val EXTRA_POWERKEEPER = "powerkeeper"

    /**
     * Package names to restart, for targets that have no [RestartScopeSelection] field — currently
     * the user-selected input methods.
     */
    const val EXTRA_PACKAGES = "packages"
}
