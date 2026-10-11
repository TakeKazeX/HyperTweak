package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Identity is per user, while repeated permission uses by one app share a label. */
internal object ControlCenterPrivacyPolicy {
    const val DEFAULT_ICON_SIZE = 18
    const val DEFAULT_TEXT_SIZE = 12
    const val DEFAULT_OFFSET = 8
    const val RETENTION_MS = 15_000L
    enum class Permission { CAMERA, MICROPHONE, LOCATION, SCREEN }
    data class App(val uid: Int, val packageName: String, val label: String, val active: Boolean,
                   val permission: Permission = Permission.LOCATION, val inactiveSinceMs: Long = 0)
    data class Presentation(val names: String, val permissions: Set<Permission>, val nextExpiryMs: Long? = null)

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
