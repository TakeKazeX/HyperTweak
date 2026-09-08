package com.takekazex.hypertweak.util

import android.content.Context
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.XposedServiceManager
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Reads and edits the module's LSPosed scope through the libxposed service.
 *
 * App process only; hook processes have no service binding. Every call can throw an unchecked
 * `XposedService.ServiceException`, and `requestScope`'s callbacks arrive on a Binder thread.
 */
object ScopeManager {
    sealed interface Result {
        /** The live scope already matched the target. */
        data object NoChange : Result

        data class Applied(val added: Set<String>, val removed: Set<String>) : Result

        /** The user declined part of the request. */
        data class Rejected(val missing: Set<String>) : Result

        data class Failed(val message: String) : Result

        data object ServiceUnavailable : Result
    }

    private const val SYSTEM_SERVER = "system"

    /** What LSPosed builds before the `system` rename call the system server. */
    private const val LEGACY_SYSTEM_SERVER = "android"

    /** The system launcher; listed in the scope for Launcher 7 and older only. */
    private const val LAUNCHER_PACKAGE = "com.miui.home"

    private val NON_APPLICATION_SCOPE = setOf(SYSTEM_SERVER, LEGACY_SYSTEM_SERVER, "system_server")

    private val service: XposedService?
        get() = XposedServiceManager.currentService

    /**
     * Packages the module's own features need. `R.array.xposed_scope` is the single source of
     * truth; `META-INF/xposed/scope.list` is the LSPosed-facing copy of the same list.
     */
    fun requiredScope(context: Context): Set<String> =
        context.resources.getStringArray(R.array.xposed_scope).mapNotNull(::normalize).toSet()

    suspend fun currentScope(): Set<String>? = withContext(Dispatchers.IO) {
        val active = service ?: return@withContext null
        runCatching { active.scope.mapNotNull(::normalize).toSet() }.getOrNull()
    }

    /**
     * Required packages the user has removed in LSPosed, or null when the scope cannot be read.
     *
     * The module's own package is skipped: `getScope()` does not report self-scope, and the Home
     * page's module-status card already tells the user to check HyperTweak itself when the module
     * is not active. Older LSPosed builds name the system server `android` rather than `system`,
     * so either satisfies the `system` entry. The launcher is intentionally not part of the
     * static recommended scope: OS3 may still need it for the Java predictive-back route, while
     * OS4 no longer has a hookable launcher-side gesture stack.
     */
    suspend fun missingRequiredScope(context: Context): Set<String>? {
        val live = currentScope() ?: return null
        val systemPresent = live.any {
            it == SYSTEM_SERVER || it == LEGACY_SYSTEM_SERVER || it == "system_server"
        }
        return requiredScope(context).filterNot { required ->
            required in live ||
                required == context.packageName ||
                (required == SYSTEM_SERVER && systemPresent)
        }.toSet()
    }

    /**
     * Returns the launcher recommendation for the current platform. It is intentionally separate
     * from [requiredScope]: `com.miui.home` is not recommended to every installation, but OS3
     * users still need a restore prompt when the launcher-side back route is unavailable. On OS4,
     * an old installation may still retain the entry, so offer the inverse migration prompt.
     */
    suspend fun launcherScopeRecommendation(): ScopePrompt? {
        val live = currentScope() ?: return null
        return when {
            PlatformLevel.isOs4 && LAUNCHER_PACKAGE in live ->
                ScopePrompt(ScopePromptAction.REMOVE, LAUNCHER_PACKAGE)
            !PlatformLevel.isOs4 && LAUNCHER_PACKAGE !in live ->
                ScopePrompt(ScopePromptAction.RESTORE, LAUNCHER_PACKAGE)
            else -> null
        }
    }

    suspend fun request(packages: Set<String>): Result {
        val normalized = packages.mapNotNull(::normalize).toSet()
        if (normalized.isEmpty()) return Result.NoChange
        val active = service ?: return Result.ServiceUnavailable
        return requestScope(active, normalized)
    }

    /** Removes [packages] from the scope without touching anything else. */
    suspend fun remove(packages: Set<String>): Result {
        val normalized = packages.mapNotNull(::normalize).toSet()
        if (normalized.isEmpty()) return Result.NoChange
        val active = service ?: return Result.ServiceUnavailable
        val removed = withContext(Dispatchers.IO) {
            runCatching { active.removeScope(normalized.toList()) }
        }
        removed.exceptionOrNull()?.let { t ->
            return Result.Failed(t.message ?: "Could not update the scope")
        }
        return Result.Applied(added = emptySet(), removed = normalized)
    }

    /**
     * Returns the currently scoped application packages, excluding system-server aliases and the
     * module itself. A null result means the service could not be queried; callers can then fall
     * back to [declaredRestartableScope] for a useful static list.
     */
    suspend fun restartableScope(context: Context): Set<String>? {
        val live = currentScope() ?: return null
        return live.filterNot { it in NON_APPLICATION_SCOPE || it == context.packageName }.toSet()
    }

    /** The declared application scope, used while the Xposed service is still connecting. */
    fun declaredRestartableScope(context: Context): Set<String> =
        requiredScope(context).filterNot { it in NON_APPLICATION_SCOPE || it == context.packageName }.toSet()

    /**
     * Moves [managed] packages from [current] to [target] without touching anything else.
     *
     * Removals are intersected with [managed] so unchecking an entry gives its scope back while a
     * package another feature needs is never revoked, and [requiredScope] is never removed.
     */
    suspend fun applyManagedDiff(
        context: Context,
        target: Set<String>,
        managed: Set<String>
    ): Result {
        val active = service ?: return Result.ServiceUnavailable
        val live = currentScope() ?: return Result.Failed("Could not read the current scope")

        val normalizedTarget = target.mapNotNull(::normalize).toSet()
        val normalizedManaged = managed.mapNotNull(::normalize).toSet()
        val required = requiredScope(context)

        val toRemove = live.intersect(normalizedManaged) - normalizedTarget - required
        val toAdd = normalizedTarget - live
        if (toRemove.isEmpty() && toAdd.isEmpty()) return Result.NoChange

        if (toRemove.isNotEmpty()) {
            val removed = withContext(Dispatchers.IO) {
                runCatching { active.removeScope(toRemove.toList()) }
            }
            removed.exceptionOrNull()?.let { t ->
                return Result.Failed(t.message ?: "Could not update the scope")
            }
        }

        if (toAdd.isEmpty()) return Result.Applied(added = emptySet(), removed = toRemove)

        return when (val result = requestScope(active, toAdd)) {
            is Result.Applied -> Result.Applied(added = result.added, removed = toRemove)
            else -> result
        }
    }

    private suspend fun requestScope(active: XposedService, packages: Set<String>): Result =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                // A partial approval can still be followed by a failure callback.
                val settled = AtomicBoolean(false)
                val listener = object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        if (!settled.compareAndSet(false, true)) return
                        val granted = approved.mapNotNull(::normalize).toSet()
                        val missing = packages - granted
                        continuation.resume(
                            if (missing.isEmpty()) Result.Applied(added = packages, removed = emptySet())
                            else Result.Rejected(missing)
                        )
                    }

                    override fun onScopeRequestFailed(message: String) {
                        if (!settled.compareAndSet(false, true)) return
                        continuation.resume(Result.Failed(message))
                    }
                }
                runCatching { active.requestScope(packages.toList(), listener) }
                    .onFailure { t ->
                        if (settled.compareAndSet(false, true)) {
                            continuation.resume(Result.Failed(t.message ?: "Scope request failed"))
                        }
                    }
            }
        }

    private fun normalize(packageName: String?): String? = packageName?.trim()?.takeIf { it.isNotEmpty() }
}
