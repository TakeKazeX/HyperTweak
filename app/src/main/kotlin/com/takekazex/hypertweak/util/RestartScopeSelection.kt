package com.takekazex.hypertweak.util

data class RestartScopeSelection(
    val systemUi: Boolean = false,
    val miuiHome: Boolean = false,
    val settings: Boolean = false,
    val aod: Boolean = false,
    val securityCenter: Boolean = false,
    val scanner: Boolean = false,
    val milink: Boolean = false,
    val bluetooth: Boolean = false,
    val powerkeeper: Boolean = false,
    val gms: Boolean = false,
    val xmsf: Boolean = false
) {
    fun merge(other: RestartScopeSelection): RestartScopeSelection {
        return RestartScopeSelection(
            systemUi = systemUi || other.systemUi,
            miuiHome = miuiHome || other.miuiHome,
            settings = settings || other.settings,
            aod = aod || other.aod,
            securityCenter = securityCenter || other.securityCenter,
            scanner = scanner || other.scanner,
            milink = milink || other.milink,
            bluetooth = bluetooth || other.bluetooth,
            powerkeeper = powerkeeper || other.powerkeeper,
            gms = gms || other.gms,
            xmsf = xmsf || other.xmsf
        )
    }

    fun without(other: RestartScopeSelection): RestartScopeSelection {
        return RestartScopeSelection(
            systemUi = systemUi && !other.systemUi,
            miuiHome = miuiHome && !other.miuiHome,
            settings = settings && !other.settings,
            aod = aod && !other.aod,
            securityCenter = securityCenter && !other.securityCenter,
            scanner = scanner && !other.scanner,
            milink = milink && !other.milink,
            bluetooth = bluetooth && !other.bluetooth,
            powerkeeper = powerkeeper && !other.powerkeeper,
            gms = gms && !other.gms,
            xmsf = xmsf && !other.xmsf
        )
    }

    fun intersect(other: RestartScopeSelection): RestartScopeSelection {
        return RestartScopeSelection(
            systemUi = systemUi && other.systemUi,
            miuiHome = miuiHome && other.miuiHome,
            settings = settings && other.settings,
            aod = aod && other.aod,
            securityCenter = securityCenter && other.securityCenter,
            scanner = scanner && other.scanner,
            milink = milink && other.milink,
            bluetooth = bluetooth && other.bluetooth,
            powerkeeper = powerkeeper && other.powerkeeper,
            gms = gms && other.gms,
            xmsf = xmsf && other.xmsf
        )
    }

    fun covers(other: RestartScopeSelection): Boolean {
        return other.without(this).isEmpty()
    }

    fun isEmpty(): Boolean {
        return !systemUi &&
            !miuiHome &&
            !settings &&
            !aod &&
            !securityCenter &&
            !scanner &&
            !milink &&
            !bluetooth &&
            !powerkeeper &&
            !gms &&
            !xmsf
    }

    fun toKeySet(): Set<String> {
        val keys = mutableSetOf<String>()
        if (systemUi) keys += KEY_SYSTEM_UI
        if (miuiHome) keys += KEY_MIUI_HOME
        if (settings) keys += KEY_SETTINGS
        if (aod) keys += KEY_AOD
        if (securityCenter) keys += KEY_SECURITY_CENTER
        if (scanner) keys += KEY_SCANNER
        if (milink) keys += KEY_MILINK
        if (bluetooth) keys += KEY_BLUETOOTH
        if (powerkeeper) keys += KEY_POWERKEEPER
        if (gms) keys += KEY_GMS
        if (xmsf) keys += KEY_XMSF
        return keys
    }

    companion object {
        val Empty = RestartScopeSelection()

        private const val KEY_SYSTEM_UI = "systemui"
        private const val KEY_MIUI_HOME = "miuihome"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_AOD = "aod"
        private const val KEY_SECURITY_CENTER = "securitycenter"
        private const val KEY_SCANNER = "scanner"
        private const val KEY_MILINK = "milink"
        private const val KEY_BLUETOOTH = "bluetooth"
        private const val KEY_POWERKEEPER = "powerkeeper"
        private const val KEY_GMS = "gms"
        private const val KEY_XMSF = "xmsf"

        fun fromKeySet(keys: Set<String>): RestartScopeSelection {
            return RestartScopeSelection(
                systemUi = KEY_SYSTEM_UI in keys,
                miuiHome = KEY_MIUI_HOME in keys,
                settings = KEY_SETTINGS in keys,
                aod = KEY_AOD in keys,
                securityCenter = KEY_SECURITY_CENTER in keys,
                scanner = KEY_SCANNER in keys,
                milink = KEY_MILINK in keys,
                bluetooth = KEY_BLUETOOTH in keys,
                powerkeeper = KEY_POWERKEEPER in keys,
                gms = KEY_GMS in keys,
                xmsf = KEY_XMSF in keys
            )
        }
    }
}
