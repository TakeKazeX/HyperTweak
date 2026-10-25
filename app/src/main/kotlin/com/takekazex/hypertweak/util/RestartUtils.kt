package com.takekazex.hypertweak.util

import android.content.Context
import android.widget.Toast
import com.takekazex.hypertweak.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal class RestartUtils(private val flushSettings: () -> Unit) {
    // A page leaving composition must not cancel a restart that has already reached Root.
    private val operations = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = RestartFlights()
    val runningPackages get() = flights.running

    fun forceStopPackages(
        context: Context,
        coroutineScope: CoroutineScope,
        packages: Set<String>,
        onCompleted: (Set<String>) -> Unit = {},
    ): Job = request(context, coroutineScope, packages, onCompleted)

    fun restartScope(
        context: Context,
        coroutineScope: CoroutineScope,
        selection: RestartScopeSelection,
        onCompleted: (RestartScopeSelection) -> Unit = {},
    ): Job = request(context, coroutineScope, selection.toPackageSet()) {
        onCompleted(RestartScopeSelection.fromPackageSet(it))
    }

    private fun request(
        context: Context,
        coroutineScope: CoroutineScope,
        targets: Set<String>,
        onCompleted: (Set<String>) -> Unit,
    ): Job {
        val packages = restartablePackages(targets)
        if (packages.isEmpty()) return SupervisorJob().apply { complete() }
        val appContext = context.applicationContext ?: context
        // Claims happen synchronously, before launching observers, so simultaneous clicks share work.
        val pending = packages.associateWith { pkg ->
            flights.getOrStart(pkg) {
                operations.async {
                    val success = runCatching {
                        flushSettings()
                        restartViaRoot(pkg)
                    }.onFailure { DebugLog.e("RestartUtils", "restart failed target=$pkg", it) }
                        .getOrDefault(false)
                    if (success) runCatching { RestartHistory.record(appContext, setOf(pkg)) }
                        .onFailure { DebugLog.w("RestartUtils", "restart history unavailable", it) }
                    success
                }
            }
        }
        return coroutineScope.launch {
            val completed = pending.filterValues { it.await() }.keys
            withContext(Dispatchers.Main) {
                if (completed.isNotEmpty()) onCompleted(completed)
                val message = if (completed.size == packages.size) {
                    appContext.getString(R.string.restart_completed)
                } else {
                    appContext.getString(R.string.restart_failed)
                }
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
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

    private fun restartViaRoot(packageName: String): Boolean {
        val script = RestartRootScript.forPackage(packageName)
        // One channel only. A timeout or ambiguous exit never triggers a second broadcast kill.
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
        try {
            if (!process.waitFor(150, TimeUnit.SECONDS)) {
                DebugLog.e("RestartUtils", "root restart timed out target=$packageName")
                return false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val errors = process.errorStream.bufferedReader().use { it.readText() }
            DebugLog.i("RestartUtils", "target=$packageName exit=${process.exitValue()} $output $errors")
            return process.exitValue() == 0 && output.lineSequence().any { it == "HT_RESTART_OK" }
        } finally {
            process.destroy()
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
