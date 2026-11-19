package com.takekazex.hypertweak.util.platform

import android.content.Context
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.takekazex.hypertweak.util.storage.commitEdit

/** Owns the legacy adbd TCP listener. Android's paired wireless debugging is separate. */
internal object AdbTcpPort {
    data class Wifi(val ssid: String, val bssid: String) {
        val name: String get() = ssid.removeSurrounding("\"")
    }

    enum class RestartMode { OFF, BOUND_WIFI, ALWAYS_RANDOM }

    data class State(
        val systemTls: Boolean,
        val restartMode: RestartMode,
        val bound: List<Wifi>,
        val current: Wifi?,
        val endpoint: String?,
        val failure: Int?,
    )

    private const val DIRECTORY = "/data/adb/hypertweak-wireless-adb"
    private const val MANAGED = "wifi_guard_managed"
    const val MAX_BOUND = 3
    private const val RESTART_MODE = "restart_mode"
    private val lock = Mutex()

    fun options(context: Context) =
        context.getSharedPreferences("adb_tcp_options", Context.MODE_PRIVATE)

    fun shouldRestore(context: Context): Boolean {
        val options = options(context)
        // Visiting the page marks the guard managed; only an automatic restart mode needs the
        // boot/update receiver. A one-time manual listener never survives a reboot. Managed
        // installs that have not reported a mode yet keep the receiver until the page reads one.
        if (options.getBoolean(MANAGED, false)) {
            return options.getInt(RESTART_MODE, RestartMode.ALWAYS_RANDOM.ordinal) != RestartMode.OFF.ordinal
        }
        // Older versions stored only the optional custom port, and wrote boot persistence to
        // a system property. Probe it when readable so untouched installations never request root.
        if (options.contains("custom") || options.contains("port")) return true
        return runCatching {
            val getprop = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            listOf("persist.adb.tcp.port", "service.adb.tcp.port").any { property ->
                ((getprop.invoke(null, property) as? String)?.toIntOrNull() ?: 0) in 1..65535
            }
        }.getOrDefault(false)
    }

    /** Boot/update restoration is only needed once this module owns the listener. */
    fun syncWakeReceiver(context: Context) =
        WakeReceiverGate.set(context, WakeReceiverGate.WIRELESS_ADB, shouldRestore(context))

    suspend fun restore(context: Context, afterBoot: Boolean = false): Boolean = execute {
        installGuard(context)
        val owner = ownerFile(context)
        if (!owner.isFile) owner.writeText(UUID.randomUUID().toString())
        if (!markManaged(context)) return@execute false
        syncWakeReceiver(context)
        val operation = if (afterBoot) "restore" else "prepare"
        val savedOptions = options(context)
        val port = if (savedOptions.getBoolean("custom", false)) {
            savedOptions.getString("port", "")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: -1
        } else 0
        command("$operation ${NativeRootCommand.quote(owner.path)} ${NativeRootCommand.quote(owner.readText())} $port")
        true
    } ?: false

    suspend fun read(context: Context): State? = execute {
        val fields = command("status").decodeToString().trimEnd('\n').split('\n')
        // state, mode, binding count, ssid/bssid pairs, current ssid/bssid, endpoint, error,
        // transport, end.
        check(fields.size >= 9 && fields[0] == "state" && fields.last() == "end")
        val count = fields[2].toInt()
        check(count in 0..MAX_BOUND && fields.size == 9 + count * 2)
        fun wifi(index: Int): Wifi? = fields[index + 1].takeIf(String::isNotEmpty)?.let {
            Wifi(fields[index], it)
        }
        val rest = 3 + count * 2
        State(
            systemTls = fields[rest + 4] == "system",
            restartMode = RestartMode.entries[fields[1].toInt()],
            bound = (0 until count).mapNotNull { wifi(3 + it * 2) },
            current = wifi(rest),
            endpoint = fields[rest + 2].takeIf(String::isNotEmpty),
            failure = fields[rest + 3].toIntOrNull(),
        ).also { rememberMode(context, it.restartMode) }
    }

    /** The guard owns the mode; the app keeps a copy so it can gate its boot receiver. */
    private fun rememberMode(context: Context, mode: RestartMode) {
        val options = options(context)
        if (options.getInt(RESTART_MODE, -1) == mode.ordinal) return
        options.commitEdit { putInt(RESTART_MODE, mode.ordinal) }
        syncWakeReceiver(context)
    }

    /** Binds the access point in use; the guard rejects any other one and a fourth binding. */
    suspend fun addBinding(wifi: Wifi): Boolean = change("bind add ${wifi.arguments()}")

    suspend fun removeBinding(wifi: Wifi): Boolean = change("bind remove ${wifi.arguments()}")

    suspend fun setEnabled(enabled: Boolean, wifi: Wifi?, port: Int): Boolean =
        if (enabled && wifi != null) change("manual-on $port ${wifi.arguments()}")
        else if (!enabled) change("manual-off")
        else false

    suspend fun setRestartMode(mode: RestartMode): Boolean = change("mode ${mode.ordinal}")

    suspend fun setSystemTls(enabled: Boolean): Boolean =
        change("transport ${if (enabled) "system" else "legacy"}")

    suspend fun updatePort(port: Int): Boolean = change("port $port")

    private fun Wifi?.arguments(): String =
        NativeRootCommand.quote(this?.ssid.orEmpty()) + " " +
            NativeRootCommand.quote(this?.bssid.orEmpty())

    private suspend fun change(arguments: String): Boolean = execute {
        command(arguments)
        true
    } ?: false

    suspend fun reset(context: Context): Boolean {
        if (!shouldRestore(context)) return true
        return execute {
            // Revoking the local marker also disarms an existing watchdog if su is unavailable.
            val owner = ownerFile(context)
            if (owner.exists() && !owner.delete()) return@execute false
            val cleared = options(context).commitEdit { clear() }
            installGuard(context)
            command("reset")
            syncWakeReceiver(context)
            cleared
        } ?: false
    }

    private fun ownerFile(context: Context) = File(context.noBackupFilesDir, "wireless_adb_owner")

    private fun markManaged(context: Context) =
        options(context).commitEdit { putBoolean(MANAGED, true) }

    private fun installGuard(context: Context) {
        val source = context.assets.open("diagnostics/wireless-adb.sh").bufferedReader().use { it.readText() }
        NativeRootCommand.run(
            "umask 077 && mkdir -p $DIRECTORY && chmod 700 $DIRECTORY && cd $DIRECTORY && " +
                "printf '%s\\n' ${NativeRootCommand.quote(context.applicationInfo.sourceDir)} > apk.new && " +
                "mv -f apk.new apk && printf %s ${NativeRootCommand.quote(source)} > guard.new && " +
                "if cmp -s guard.new guard; then rm -f guard.new; else mv -f guard.new guard; fi",
            maxBytes = 4096,
        )
    }

    private fun command(arguments: String): ByteArray =
        NativeRootCommand.run("sh $DIRECTORY/guard $arguments", maxBytes = 4096)

    private suspend fun <T> execute(block: () -> T): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching(block)
                .onFailure { DebugLog.w("AdbTcpPort", "Wireless ADB operation failed", it) }
                .getOrNull()
        }
    }
}
