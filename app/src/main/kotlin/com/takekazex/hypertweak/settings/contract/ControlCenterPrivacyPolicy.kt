package com.takekazex.hypertweak.settings.contract

/** Identity is per user, while repeated permission uses by one app share a label. */
internal object ControlCenterPrivacyPolicy {
    const val DEFAULT_ICON_SIZE = 18
    const val DEFAULT_TEXT_SIZE = 12
    const val DEFAULT_OFFSET = 8
    const val RETENTION_MS = 15_000L

    enum class Placement { NATIVE, ABOVE_STATUS, CENTER_HEADER }

    /** Landscape has no room above its header; custom privacy uses the header's central space. */
    fun placement(enabled: Boolean, landscape: Boolean, centered: Boolean, offset: Int): Placement = when {
        !enabled -> Placement.NATIVE
        landscape -> Placement.CENTER_HEADER
        centered || offset != 0 -> Placement.ABOVE_STATUS
        else -> Placement.NATIVE
    }
    enum class Permission { CAMERA, MICROPHONE, LOCATION, SCREEN }
    data class App(val uid: Int, val packageName: String, val label: String, val active: Boolean,
                   val permission: Permission = Permission.LOCATION, val inactiveSinceMs: Long = 0)
    data class Presentation(val names: String, val permissions: Set<Permission>, val nextExpiryMs: Long? = null)

    /** Native arrays are camera/microphone/location/screen; Android prompt wins when present. */
    fun nativePermissions(miui: IntArray?, android: IntArray?, androidPresent: Boolean): Set<Permission> {
        val types = if (androidPresent) android else miui
        return Permission.entries.filterIndexed { index, _ -> types?.getOrNull(index) == 1 }.toSet()
    }

    /** An available but empty history is authoritative; it must not resurrect stale native icons. */
    fun displayPermissions(history: Set<Permission>?, native: Set<Permission>): Set<Permission> =
        history ?: native

    fun presentation(apps: List<App>, fallback: String, nowMs: Long = 0): Presentation {
        val active = apps.filter { it.packageName.isNotBlank() && (it.active ||
            (it.inactiveSinceMs > 0 && nowMs >= it.inactiveSinceMs && nowMs - it.inactiveSinceMs < RETENTION_MS)) }
        val names = active.asSequence()
            .distinctBy { it.uid to it.packageName }
            .map { it.label.ifBlank { it.packageName } }
            .toList().joinToString(" & ").ifBlank { fallback }
        // Aggregate before app-name deduplication: one app may hold several permissions.
        val expiry = active.filter { !it.active }.minOfOrNull { it.inactiveSinceMs + RETENTION_MS }
        return Presentation(names, active.map { it.permission }.toSet(), expiry)
    }

    fun names(apps: List<App>, fallback: String): String = presentation(apps, fallback).names
}
