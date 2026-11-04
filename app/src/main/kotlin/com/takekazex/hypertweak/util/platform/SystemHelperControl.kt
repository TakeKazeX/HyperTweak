package com.takekazex.hypertweak.util.platform

import android.content.Context
import android.content.pm.PackageManager
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Carrier DM client shipped in some China Unicom system images. It is intentionally addressed by
 * its stable package name; the feature controls that exact package and never enumerates packages
 * or accepts a caller-supplied package name.
 */
internal object SystemHelperPackage {
    const val NAME = "com.mobiletools.systemhelper"
    const val DISABLE_COMMAND = "pm disable-user --user 0 " + NAME
    const val ENABLE_COMMAND = "pm enable --user 0 " + NAME
}

internal data class SystemHelperPackageState(
    val installed: Boolean,
    val enabled: Boolean,
    val versionName: String? = null,
)

internal enum class SystemHelperFailure { NO_ROOT, QUERY, SET, NOT_INSTALLED }

internal data class SystemHelperResult(
    val state: SystemHelperPackageState? = null,
    val failure: SystemHelperFailure? = null,
    val detail: String = "",
)

/**
 * Pure synchronous rule engine. Package state is read from the platform on every query; the
 * previous UI value is never trusted as the current state. A write is reported as successful only
 * after a fresh readback matches the requested state.
 */
internal class SystemHelperEngine(
    private val readState: () -> SystemHelperPackageState?,
    private val run: (String) -> String = { "" },
    private val rememberDisabled: (Boolean) -> Unit = {},
) {
    fun query(): SystemHelperResult {
        val state = try {
            readState()
        } catch (e: Exception) {
            return SystemHelperResult(failure = SystemHelperFailure.QUERY, detail = reason(e))
        }
        return if (state == null || !state.installed) {
            SystemHelperResult(SystemHelperPackageState(installed = false, enabled = false))
        } else {
            SystemHelperResult(state)
        }
    }

    fun setEnabled(enabled: Boolean): SystemHelperResult {
        val initial = try {
            readState()
        } catch (e: Exception) {
            return SystemHelperResult(failure = SystemHelperFailure.QUERY, detail = reason(e))
        }
        if (initial == null || !initial.installed) {
            return SystemHelperResult(SystemHelperPackageState(installed = false, enabled = false),
                failure = SystemHelperFailure.NOT_INSTALLED)
        }
        requireRoot(initial)?.let { return it }
        if (initial.enabled == enabled) return SystemHelperResult(initial)

        return try {
            run(if (enabled) SystemHelperPackage.ENABLE_COMMAND else SystemHelperPackage.DISABLE_COMMAND)
            val after = try {
                readState()
            } catch (e: Exception) {
                return SystemHelperResult(failure = SystemHelperFailure.QUERY, detail = reason(e))
            }
            if (after == null || !after.installed) {
                return SystemHelperResult(SystemHelperPackageState(installed = false, enabled = false),
                    failure = SystemHelperFailure.NOT_INSTALLED)
            }
            if (after.enabled != enabled) {
                return SystemHelperResult(after, SystemHelperFailure.SET,
                    "package state did not change to " + if (enabled) "enabled" else "disabled")
            }
            rememberDisabled(!enabled)
            SystemHelperResult(after)
        } catch (e: Exception) {
            SystemHelperResult(currentState(), SystemHelperFailure.SET, reason(e))
        }
    }

    /** Re-apply the persisted choice after boot, an OTA, or a package update. */
    fun reconcile(desiredDisabled: Boolean): SystemHelperResult {
        val current = try {
            readState()
        } catch (e: Exception) {
            return SystemHelperResult(failure = SystemHelperFailure.QUERY, detail = reason(e))
        }
        if (current == null || !current.installed) {
            return SystemHelperResult(current ?: SystemHelperPackageState(installed = false, enabled = false))
        }
        if (current.enabled == !desiredDisabled) return SystemHelperResult(current)
        return setEnabled(!desiredDisabled)
    }

    private fun requireRoot(state: SystemHelperPackageState): SystemHelperResult? = try {
        if (run("id -u").trim() != "0") {
            SystemHelperResult(state, SystemHelperFailure.NO_ROOT, "root uid was not granted")
        } else null
    } catch (e: Exception) {
        SystemHelperResult(state, SystemHelperFailure.NO_ROOT, reason(e))
    }

    private fun currentState(): SystemHelperPackageState? = runCatching { readState() }.getOrNull()

    private fun reason(e: Exception): String {
        val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
        val output = (cause as? NativeRootCommand.CommandFailure)?.output
            ?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(cause.message ?: cause.javaClass.simpleName, output)
            .joinToString(": ").take(300)
    }
}

/** App-process adapter; all root work remains on IO and is serialized with the other package control. */
internal class SystemHelperControl(
    private val readState: (Context) -> SystemHelperPackageState? = ::readSystemHelperPackageState,
    private val run: (String) -> String = { NativeRootCommand.run(it).toString(Charsets.UTF_8) },
    private val rememberDisabled: (Boolean) -> Unit = {},
) {
    private val mutex = Mutex()

    private fun engine(context: Context) = SystemHelperEngine({ readState(context) }, run, rememberDisabled)

    suspend fun query(context: Context): SystemHelperResult =
        mutex.withLock { withContext(Dispatchers.IO) { engine(context).query() } }

    suspend fun setEnabled(context: Context, enabled: Boolean): SystemHelperResult =
        mutex.withLock { withContext(Dispatchers.IO) { engine(context).setEnabled(enabled) } }

    /**
     * Called by the boot/package receiver. It blocks only the receiver's own background thread;
     * a disabled or absent target returns before any root invocation.
     */
    fun reconcileBlocking(context: Context, desiredDisabled: Boolean): SystemHelperResult = synchronized(this) {
        val result = engine(context).reconcile(desiredDisabled)
        if (result.failure != null) {
            DebugLog.w("SystemHelper", "reconcile failed: " + result.failure + " " + result.detail)
        }
        result
    }
}

internal fun readSystemHelperPackageState(context: Context): SystemHelperPackageState? {
    val pm = context.packageManager
    val info = try {
        pm.getPackageInfo(SystemHelperPackage.NAME, PackageManager.MATCH_DISABLED_COMPONENTS)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val appInfo = try {
        pm.getApplicationInfo(SystemHelperPackage.NAME, PackageManager.MATCH_DISABLED_COMPONENTS)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val setting = runCatching {
        pm.getApplicationEnabledSetting(SystemHelperPackage.NAME)
    }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
    val enabled = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> appInfo.enabled
    }
    return SystemHelperPackageState(
        installed = true,
        enabled = enabled,
        versionName = info.versionName,
    )
}
