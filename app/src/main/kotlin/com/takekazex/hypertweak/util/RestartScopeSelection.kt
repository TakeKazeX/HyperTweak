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
    val xmsf: Boolean = false,
    /** Scope packages that are not part of the historical fixed-field set. */
    val additionalPackages: Set<String> = emptySet()
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
            xmsf = xmsf || other.xmsf,
            additionalPackages = additionalPackages + other.additionalPackages
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
            xmsf = xmsf && !other.xmsf,
            additionalPackages = additionalPackages - other.additionalPackages
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
            xmsf = xmsf && other.xmsf,
            additionalPackages = additionalPackages.intersect(other.additionalPackages)
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
            !xmsf &&
            additionalPackages.isEmpty()
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
        keys += additionalPackages.map { "$KEY_ADDITIONAL_PREFIX$it" }
        return keys
    }

    /** Converts the selection to actual package names for the dynamic restart protocol. */
    fun toPackageSet(): Set<String> = buildSet {
        if (systemUi) add(PACKAGE_SYSTEM_UI)
        if (miuiHome) add(PACKAGE_MIUI_HOME)
        if (settings) add(PACKAGE_SETTINGS)
        if (aod) add(PACKAGE_AOD)
        if (securityCenter) add(PACKAGE_SECURITY_CENTER)
        if (scanner) add(PACKAGE_SCANNER)
        if (milink) add(PACKAGE_MILINK)
        if (bluetooth) add(PACKAGE_BLUETOOTH)
        if (powerkeeper) add(PACKAGE_POWERKEEPER)
        if (gms) add(PACKAGE_GMS)
        if (xmsf) add(PACKAGE_XMSF)
        addAll(additionalPackages)
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
        private const val KEY_ADDITIONAL_PREFIX = "package:"

        const val PACKAGE_SYSTEM_UI = "com.android.systemui"
        const val PACKAGE_MIUI_HOME = "com.miui.home"
        const val PACKAGE_SETTINGS = "com.android.settings"
        const val PACKAGE_AOD = "com.miui.aod"
        const val PACKAGE_SECURITY_CENTER = "com.miui.securitycenter"
        const val PACKAGE_SCANNER = "com.xiaomi.scanner"
        const val PACKAGE_MILINK = "com.milink.service"
        const val PACKAGE_BLUETOOTH = "com.xiaomi.bluetooth"
        const val PACKAGE_POWERKEEPER = "com.miui.powerkeeper"
        const val PACKAGE_GMS = "com.google.android.gms"
        const val PACKAGE_XMSF = "com.xiaomi.xmsf"

        // These targets are kept as additional packages because the legacy selection fields are
        // also used by old persisted restart-scope sets. Keeping named constants here prevents
        // feature pages from drifting to the wrong Google/Xiaomi process.
        const val PACKAGE_GOOGLE_APP = "com.google.android.googlequicksearchbox"
        const val PACKAGE_MEDIA_EDITOR = "com.miui.mediaeditor"
        const val PACKAGE_PERSONAL_ASSISTANT = "com.miui.personalassistant"
        const val PACKAGE_CAMERA = "com.android.camera"

        private val KNOWN_PACKAGES = setOf(
            PACKAGE_SYSTEM_UI,
            PACKAGE_MIUI_HOME,
            PACKAGE_SETTINGS,
            PACKAGE_AOD,
            PACKAGE_SECURITY_CENTER,
            PACKAGE_SCANNER,
            PACKAGE_MILINK,
            PACKAGE_BLUETOOTH,
            PACKAGE_POWERKEEPER,
            PACKAGE_GMS,
            PACKAGE_XMSF
        )

        /** Builds the legacy fields plus arbitrary package entries from a live LSPosed scope. */
        fun fromPackageSet(packages: Set<String>): RestartScopeSelection {
            val normalized = packages.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
            return RestartScopeSelection(
                systemUi = PACKAGE_SYSTEM_UI in normalized,
                miuiHome = PACKAGE_MIUI_HOME in normalized,
                settings = PACKAGE_SETTINGS in normalized,
                aod = PACKAGE_AOD in normalized,
                securityCenter = PACKAGE_SECURITY_CENTER in normalized,
                scanner = PACKAGE_SCANNER in normalized,
                milink = PACKAGE_MILINK in normalized,
                bluetooth = PACKAGE_BLUETOOTH in normalized,
                powerkeeper = PACKAGE_POWERKEEPER in normalized,
                gms = PACKAGE_GMS in normalized,
                xmsf = PACKAGE_XMSF in normalized,
                additionalPackages = normalized - KNOWN_PACKAGES
            )
        }

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
                xmsf = KEY_XMSF in keys,
                additionalPackages = keys
                    .asSequence()
                    .filter { it.startsWith(KEY_ADDITIONAL_PREFIX) }
                    .map { it.removePrefix(KEY_ADDITIONAL_PREFIX) }
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                    .toSet()
            )
        }
    }
}
