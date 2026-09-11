package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object RestartUtils {
    /**
     * Restarts packages that have no fixed [RestartScopeSelection] field, such as user-selected
     * input methods or targets discovered from the live LSPosed scope. The in-process receiver is
     * registered from [com.takekazex.hypertweak.hook.HookEntry] for every hooked package.
     *
     * Returns the launched [Job]; callers that must sequence work after the restart can `join()` it.
     */
    fun forceStopPackages(
        context: Context,
        coroutineScope: CoroutineScope,
        packages: Set<String>
    ): Job {
        val normalized = restartablePackages(packages)
        if (normalized.isEmpty()) return SupervisorJob().apply { complete() }
        return coroutineScope.launch {
            withContext(Dispatchers.IO) {
                RestartHistory.record(context, normalized)
            }
            val intent = Intent(RestartProtocol.ACTION).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(RestartProtocol.EXTRA_PACKAGES, normalized.toTypedArray())
            }
            // No receiver permission: that argument demands the *receiver* hold it, and the hooked
            // system apps never will. Senders are already restricted by the receivers' permission.
            runCatching { context.sendBroadcast(intent) }

            val rootSuccess = forceStopViaRoot(normalized)
            withContext(Dispatchers.Main) {
                val message = if (rootSuccess) {
                    "Restarted ${normalized.size} app(s) via Root"
                } else {
                    "Broadcast sent to restart ${normalized.size} app(s)"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Restarts the selected fixed and dynamically discovered scope packages. */
    fun restartScope(
        context: Context,
        coroutineScope: CoroutineScope,
        selection: RestartScopeSelection
    ): Job {
        val packages = restartablePackages(selection.toPackageSet())
        if (packages.isEmpty()) return SupervisorJob().apply { complete() }

        return coroutineScope.launch {
            withContext(Dispatchers.IO) {
                RestartHistory.record(context, packages)
            }
            val intent = Intent(RestartProtocol.ACTION).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(RestartProtocol.EXTRA_SYSTEM_UI, RestartScopeSelection.PACKAGE_SYSTEM_UI in packages)
                putExtra(RestartProtocol.EXTRA_MIUI_HOME, RestartScopeSelection.PACKAGE_MIUI_HOME in packages)
                putExtra(RestartProtocol.EXTRA_SETTINGS, RestartScopeSelection.PACKAGE_SETTINGS in packages)
                putExtra(RestartProtocol.EXTRA_AOD, RestartScopeSelection.PACKAGE_AOD in packages)
                putExtra(RestartProtocol.EXTRA_SECURITY_CENTER, RestartScopeSelection.PACKAGE_SECURITY_CENTER in packages)
                putExtra(RestartProtocol.EXTRA_SCANNER, RestartScopeSelection.PACKAGE_SCANNER in packages)
                putExtra(RestartProtocol.EXTRA_MILINK, RestartScopeSelection.PACKAGE_MILINK in packages)
                putExtra(RestartProtocol.EXTRA_BLUETOOTH, RestartScopeSelection.PACKAGE_BLUETOOTH in packages)
                putExtra(RestartProtocol.EXTRA_POWERKEEPER, RestartScopeSelection.PACKAGE_POWERKEEPER in packages)
                putExtra(RestartProtocol.EXTRA_GMS, RestartScopeSelection.PACKAGE_GMS in packages)
                putExtra(RestartProtocol.EXTRA_XMSF, RestartScopeSelection.PACKAGE_XMSF in packages)
                putExtra(
                    RestartProtocol.EXTRA_PACKAGES,
                    selection.additionalPackages.filter { it in packages }.toTypedArray()
                )
            }
            // No receiver permission: that argument demands the *receiver* hold it, and the hooked
            // system apps never will. Senders are already restricted by the receivers' permission.
            runCatching { context.sendBroadcast(intent) }

            val rootSuccess = forceStopViaRoot(packages)
            withContext(Dispatchers.Main) {
                val targets = packages.joinToString(", ")
                if (rootSuccess) {
                    Toast.makeText(context, "Restarted $targets via Root", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Broadcast sent to restart $targets", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Legacy overload retained for callers outside the app; it now shares the same package-based
     * path as the dynamic dialog.
     */
    fun restartScope(
        context: Context,
        coroutineScope: CoroutineScope,
        systemUi: Boolean,
        miuiHome: Boolean = false,
        settings: Boolean,
        aod: Boolean,
        securityCenter: Boolean,
        scanner: Boolean,
        milink: Boolean,
        bluetooth: Boolean,
        powerkeeper: Boolean = false,
        gms: Boolean = false,
        xmsf: Boolean = false
    ) {
        restartScope(
            context,
            coroutineScope,
            RestartScopeSelection(
                systemUi = systemUi,
                miuiHome = miuiHome,
                settings = settings,
                aod = aod,
                securityCenter = securityCenter,
                scanner = scanner,
                milink = milink,
                bluetooth = bluetooth,
                powerkeeper = powerkeeper,
                gms = gms,
                xmsf = xmsf
            )
        )
    }

    private suspend fun forceStopViaRoot(packages: Set<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.bufferedWriter().use { writer ->
                if (RestartScopeSelection.PACKAGE_SYSTEM_UI in packages) {
                    // SystemUI may have more than one process, so match the process name as before.
                    writer.write("pkill -f ${RestartScopeSelection.PACKAGE_SYSTEM_UI}\n")
                }
                packages
                    .filterNot { it == RestartScopeSelection.PACKAGE_SYSTEM_UI }
                    .forEach { writer.write("am force-stop $it\n") }
                writer.write("exit\n")
                writer.flush()
            }
            val completed = process.waitFor(8, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                DebugLog.e("RestartUtils", "root restart timed out")
                false
            } else {
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                if (stderr.isNotBlank()) DebugLog.e("RestartUtils", "root stderr: $stderr")
                DebugLog.d("RestartUtils", "root restart exit=${process.exitValue()}")
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            DebugLog.e("RestartUtils", "root restart failed", e)
            false
        }
    }

    private fun restartablePackages(packages: Set<String>): Set<String> = packages
        .map { it.trim() }
        .filter(::isSafePackageName)
        .filterNot { it in NON_RESTARTABLE_PACKAGES }
        .toSet()

    private val NON_RESTARTABLE_PACKAGES = setOf("system", "system_server", "android", "com.takekazex.hypertweak")
    private val SAFE_PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    private fun isSafePackageName(packageName: String): Boolean = SAFE_PACKAGE_NAME.matches(packageName)
}
