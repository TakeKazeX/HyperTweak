package com.takekazex.hypertweak.util.platform

import android.content.Context
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Owns the legacy adbd TCP listener. Android's paired wireless debugging is separate. */
internal object AdbTcpPort {
    data class Wifi(val ssid: String, val bssid: String) {
        val name: String get() = ssid.removeSurrounding("\"")
    }

    enum class RestartMode { OFF, BOUND_WIFI, ALWAYS_RANDOM }

    data class State(
        val systemTls: Boolean,
        val restartMode: RestartMode,
        val bound: Wifi?,
        val current: Wifi?,
        val endpoint: String?,
        val failure: Int?,
    )

    private const val DIRECTORY = "/data/adb/hypertweak-wireless-adb"
    private const val MANAGED = "wifi_guard_managed"
    private val lock = Mutex()

    fun options(context: Context) =
        context.getSharedPreferences("adb_tcp_options", Context.MODE_PRIVATE)

    fun shouldRestore(context: Context): Boolean {
        val options = options(context)
        if (options.getBoolean(MANAGED, false)) return true
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

    suspend fun restore(context: Context, afterBoot: Boolean = false): Boolean = execute {
        installGuard(context)
        val owner = ownerFile(context)
        if (!owner.isFile) owner.writeText(UUID.randomUUID().toString())
        if (!markManaged(context)) return@execute false
        val operation = if (afterBoot) "restore" else "prepare"
        val savedOptions = options(context)
        val port = if (savedOptions.getBoolean("custom", false)) {
            savedOptions.getString("port", "")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: -1
        } else 0
        command("$operation ${NativeRootCommand.quote(owner.path)} ${NativeRootCommand.quote(owner.readText())} $port")
        true
    } ?: false

    suspend fun read(): State? = execute {
        val fields = command("status").decodeToString().trimEnd('\n').split('\n')
        check(fields.size == 10 && fields[0] == "state" && fields[9] == "end")
        fun wifi(index: Int): Wifi? = fields[index + 1].takeIf(String::isNotEmpty)?.let {
            Wifi(fields[index], it)
        }
        State(
            systemTls = fields[8] == "system",
            restartMode = RestartMode.entries[fields[1].toInt()],
            bound = wifi(2),
            current = wifi(4),
            endpoint = fields[6].takeIf(String::isNotEmpty),
            failure = fields[7].toIntOrNull(),
        )
    }

    suspend fun bind(wifi: Wifi?, port: Int): Boolean =
        change("bind $port ${wifi.arguments()}")

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
            val cleared = options(context).edit().clear().commit()
            installGuard(context)
            command("reset")
            cleared
        } ?: false
    }

    private fun ownerFile(context: Context) = File(context.noBackupFilesDir, "wireless_adb_owner")

    private fun markManaged(context: Context) =
        options(context).edit().putBoolean(MANAGED, true).commit()

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
