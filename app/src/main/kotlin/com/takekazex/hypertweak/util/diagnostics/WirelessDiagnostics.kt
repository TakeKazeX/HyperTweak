package com.takekazex.hypertweak.util.diagnostics

import com.takekazex.hypertweak.util.platform.NativeRootCommand
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class WirelessLogger(val property: String, val service: String, val preference: String) {
    CNSS("persist.vendor.cnss_diag.enable", "init.svc.vendor.cnss_diag", PlatformSettingsKeys.KEY_WIFI_CNSS_DIAG_ENABLED),
    TCPDUMP("persist.vendor.tcpdump.enable", "init.svc.vendor.tcpdump", PlatformSettingsKeys.KEY_WIFI_TCPDUMP_ENABLED)
}

internal enum class WirelessState { RUNNING, STOPPED, ABNORMAL, UNKNOWN, UNSUPPORTED }
internal enum class WirelessFailure { NO_ROOT, QUERY, SET_PROPERTY, SERVICE_TRANSITION, CLEANUP, STORAGE }

internal data class WirelessReading(val property: String, val service: String) {
    val enabled: Boolean? get() = parseWirelessBoolean(property)
    val supported: Boolean get() = property.isNotEmpty()
    val state: WirelessState get() = when {
        !supported -> WirelessState.UNSUPPORTED
        enabled == false && service == "running" -> WirelessState.ABNORMAL
        enabled == true && service == "running" -> WirelessState.RUNNING
        service == "stopped" -> WirelessState.STOPPED
        else -> WirelessState.UNKNOWN
    }
}

internal fun parseWirelessBoolean(value: String): Boolean? = when (value.trim().lowercase()) {
    "1", "true", "y", "yes", "on" -> true
    "0", "false", "n", "no", "off" -> false
    else -> null
}

internal data class WirelessSnapshot(
    val cnss: WirelessReading,
    val tcpdump: WirelessReading,
    val sizeKiB: Long? = null
) {
    operator fun get(logger: WirelessLogger): WirelessReading = when (logger) {
        WirelessLogger.CNSS -> cnss
        WirelessLogger.TCPDUMP -> tcpdump
    }
}

internal data class WirelessResult(
    val snapshot: WirelessSnapshot? = null,
    val failure: WirelessFailure? = null,
    val detail: String = ""
)

/** Fixed directory capability: no UI, preference or caller can supply a deletion path. */
internal object WirelessLogPath {
    const val DIRECTORY = "/data/vendor/wlan_logs"

    // Pin the physical cwd before removing children. rm unlinks child symlinks instead of following
    // their targets; rejecting parent symlinks prevents a redirected log root from being cleaned.
    fun cleanupScript(directory: String = DIRECTORY): String {
        require(directory == DIRECTORY) { "cleanup is restricted to $DIRECTORY" }
        return """
        [ ! -L /data ] && [ ! -L /data/vendor ] && [ ! -L /data/vendor/wlan_logs ] || exit 71
        if [ ! -e /data/vendor/wlan_logs ]; then exit 0; fi
        cd -P /data/vendor/wlan_logs || exit 72
        [ "${'$'}(pwd -P)" = '/data/vendor/wlan_logs' ] || exit 73
        rm -rf -- ./* ./.[!.]* ./..?*
    """.trimIndent()
    }
}

/** Pure synchronous engine; the app adapter serializes it on IO, including verification waits. */
internal class WirelessDiagnosticsEngine(
    private val run: (String) -> String,
    private val rememberApplied: (WirelessLogger, Boolean) -> Unit = { _, _ -> },
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = Thread::sleep
) {
    private fun requireRoot(): WirelessResult? = try {
        check(run("id -u").trim() == "0") { "root uid was not granted" }
        null
    } catch (e: Exception) {
        WirelessResult(failure = WirelessFailure.NO_ROOT, detail = reason(e))
    }

    private fun readings(): WirelessSnapshot {
        // A delimiter preserves missing properties; merged stderr cannot look like a valid snapshot.
        val fields = run("""
            a=${'$'}(getprop persist.vendor.cnss_diag.enable) || exit 61
            b=${'$'}(getprop init.svc.vendor.cnss_diag) || exit 61
            c=${'$'}(getprop persist.vendor.tcpdump.enable) || exit 61
            d=${'$'}(getprop init.svc.vendor.tcpdump) || exit 61
            printf 'HT_WIRELESS|%s|%s|%s|%s|END\n' "${'$'}a" "${'$'}b" "${'$'}c" "${'$'}d"
        """.trimIndent()).trim().split('|')
        check(fields.size == 6 && fields.first() == "HT_WIRELESS" && fields.last() == "END") {
            "invalid property response"
        }
        return WirelessSnapshot(WirelessReading(fields[1], fields[2]), WirelessReading(fields[3], fields[4]))
    }

    private fun withSize(snapshot: WirelessSnapshot): WirelessResult {
        if (!snapshot.cnss.supported) return WirelessResult(snapshot)
        return try {
            val output = run("""
                [ ! -L /data ] && [ ! -L /data/vendor ] && [ ! -L /data/vendor/wlan_logs ] || exit 71
                if [ -d /data/vendor/wlan_logs ]; then du -sk /data/vendor/wlan_logs; else
                    [ ! -e /data/vendor/wlan_logs ] || exit 72
                    printf '0\t/data/vendor/wlan_logs\n'
                fi
            """.trimIndent()).trim()
            val columns = output.split(Regex("\\s+"))
            check(columns.size == 2 && columns[1] == WirelessLogPath.DIRECTORY) {
                "invalid disk usage response"
            }
            val size = columns[0].toLongOrNull()
            check(size != null && size >= 0) { "invalid disk usage response" }
            WirelessResult(snapshot.copy(sizeKiB = size))
        } catch (e: Exception) {
            WirelessResult(snapshot, WirelessFailure.STORAGE, reason(e))
        }
    }

    fun query(): WirelessResult {
        requireRoot()?.let { return it }
        return try { withSize(readings()) }
        catch (e: Exception) { WirelessResult(failure = WirelessFailure.QUERY, detail = reason(e)) }
    }

    fun setEnabled(logger: WirelessLogger, enabled: Boolean): WirelessResult {
        requireRoot()?.let { return it }
        return try {
            var last = readings()
            if (!last.cnss.supported || !last[logger].supported) return WirelessResult(last)
            var writeFailure: String? = null
            try { run("setprop ${logger.property} $enabled") }
            catch (e: Exception) { writeFailure = reason(e) }
            // A failed verification must not hide a known write error or show pre-write state.
            last = try { readings() } catch (e: Exception) {
                return WirelessResult(
                    failure = if (writeFailure != null) WirelessFailure.SET_PROPERTY else WirelessFailure.QUERY,
                    detail = listOfNotNull(writeFailure, "readback: ${reason(e)}").joinToString("; ")
                )
            }
            if (last[logger].enabled != enabled) {
                return withSize(last).copy(failure = WirelessFailure.SET_PROPERTY,
                    detail = writeFailure ?: "property readback differs from requested value")
            }
            // These persist properties drive vendor init directly and survive reboot on the tested
            // device. Store only verified values; no boot receiver or service-stop workaround.
            if (writeFailure != null) {
                rememberApplied(logger, enabled)
                return withSize(last).copy(failure = WirelessFailure.SET_PROPERTY, detail = writeFailure)
            }
            val expected = if (enabled) "running" else "stopped"
            val started = nanoTime()
            while (last[logger].service != expected && nanoTime() - started < 5_000_000_000L) {
                sleep(200)
                last = readings()
                if (last[logger].enabled != enabled) break
            }
            val refreshed = withSize(last)
            if (last[logger].enabled == enabled) rememberApplied(logger, enabled)
            when {
                last[logger].enabled != enabled -> refreshed.copy(failure = WirelessFailure.SET_PROPERTY,
                    detail = "${logger.property} changed during verification: ${last[logger].property}")
                last[logger].service != expected -> refreshed.copy(failure = WirelessFailure.SERVICE_TRANSITION,
                    detail = "${logger.property}=$enabled; ${logger.service}=${last[logger].service}; expected $expected")
                else -> refreshed
            }
        } catch (e: Exception) {
            WirelessResult(failure = WirelessFailure.QUERY, detail = reason(e))
        }
    }

    fun clearLogs(): WirelessResult {
        requireRoot()?.let { return it }
        return try {
            val before = readings()
            if (!before.cnss.supported) return WirelessResult(before)
            try { run(WirelessLogPath.cleanupScript()) }
            catch (e: Exception) {
                // Refresh is secondary: preserve the cleanup failure even if root/read access is
                // lost afterward, and never reuse the snapshot from before the deletion attempt.
                val refreshed = try { withSize(readings()) } catch (readError: Exception) {
                    WirelessResult(failure = WirelessFailure.QUERY, detail = reason(readError))
                }
                return refreshed.copy(failure = WirelessFailure.CLEANUP,
                    detail = listOf(reason(e), refreshed.detail).filter { it.isNotBlank() }.joinToString("; "))
            }
            withSize(readings())
        } catch (e: Exception) {
            WirelessResult(failure = WirelessFailure.QUERY, detail = reason(e))
        }
    }

    private fun reason(e: Exception): String {
        val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
        val output = (cause as? NativeRootCommand.CommandFailure)?.output
            ?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(cause.message ?: cause.javaClass.simpleName, output).joinToString(": ").take(300)
    }
}

internal class WirelessDiagnostics(rememberApplied: (WirelessLogger, Boolean) -> Unit) {
    private val mutex = Mutex()
    private val engine = WirelessDiagnosticsEngine(
        run = { NativeRootCommand.run(it).toString(Charsets.UTF_8) },
        rememberApplied = rememberApplied
    )
    suspend fun query(): WirelessResult = mutex.withLock { withContext(Dispatchers.IO) { engine.query() } }
    suspend fun setEnabled(logger: WirelessLogger, enabled: Boolean): WirelessResult =
        mutex.withLock { withContext(Dispatchers.IO) { engine.setEnabled(logger, enabled) } }
    suspend fun clearLogs(): WirelessResult = mutex.withLock { withContext(Dispatchers.IO) { engine.clearLogs() } }
}
