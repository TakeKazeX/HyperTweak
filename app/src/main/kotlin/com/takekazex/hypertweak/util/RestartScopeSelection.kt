package com.takekazex.hypertweak.util

data class RestartScopeSelection(
    val systemUi: Boolean = false,
    val settings: Boolean = false,
    val aod: Boolean = false,
    val securityCenter: Boolean = false,
    val scanner: Boolean = false,
    val milink: Boolean = false,
    val bluetooth: Boolean = false,
    val powerkeeper: Boolean = false
) {
    fun merge(other: RestartScopeSelection): RestartScopeSelection {
        return RestartScopeSelection(
            systemUi = systemUi || other.systemUi,
            settings = settings || other.settings,
            aod = aod || other.aod,
            securityCenter = securityCenter || other.securityCenter,
            scanner = scanner || other.scanner,
            milink = milink || other.milink,
            bluetooth = bluetooth || other.bluetooth,
            powerkeeper = powerkeeper || other.powerkeeper
        )
    }

    fun without(other: RestartScopeSelection): RestartScopeSelection {
        return RestartScopeSelection(
            systemUi = systemUi && !other.systemUi,
            settings = settings && !other.settings,
            aod = aod && !other.aod,
            securityCenter = securityCenter && !other.securityCenter,
            scanner = scanner && !other.scanner,
            milink = milink && !other.milink,
            bluetooth = bluetooth && !other.bluetooth,
            powerkeeper = powerkeeper && !other.powerkeeper
        )
    }

    fun toKeySet(): Set<String> {
        val keys = mutableSetOf<String>()
        if (systemUi) keys += KEY_SYSTEM_UI
        if (settings) keys += KEY_SETTINGS
        if (aod) keys += KEY_AOD
        if (securityCenter) keys += KEY_SECURITY_CENTER
        if (scanner) keys += KEY_SCANNER
        if (milink) keys += KEY_MILINK
        if (bluetooth) keys += KEY_BLUETOOTH
        if (powerkeeper) keys += KEY_POWERKEEPER
        return keys
    }

    companion object {
        val Empty = RestartScopeSelection()

        private const val KEY_SYSTEM_UI = "systemui"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_AOD = "aod"
        private const val KEY_SECURITY_CENTER = "securitycenter"
        private const val KEY_SCANNER = "scanner"
        private const val KEY_MILINK = "milink"
        private const val KEY_BLUETOOTH = "bluetooth"
        private const val KEY_POWERKEEPER = "powerkeeper"

        fun fromKeySet(keys: Set<String>): RestartScopeSelection {
            return RestartScopeSelection(
                systemUi = KEY_SYSTEM_UI in keys,
                settings = KEY_SETTINGS in keys,
                aod = KEY_AOD in keys,
                securityCenter = KEY_SECURITY_CENTER in keys,
                scanner = KEY_SCANNER in keys,
                milink = KEY_MILINK in keys,
                bluetooth = KEY_BLUETOOTH in keys,
                powerkeeper = KEY_POWERKEEPER in keys
            )
        }
    }
}
