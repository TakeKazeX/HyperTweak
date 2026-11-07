package com.takekazex.hypertweak.util.platform

import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Controls the running adbd TCP listener without persisting an app preference. */
internal object AdbTcpPort {
    private const val PORT = 5555
    private const val PROPERTY = "service.adb.tcp.port"
    private const val LISTENER = """
        listener() {
            sockets=$(ss -H -ltnp 'sport = :5555') || return 1
            if printf '%s\n' "${'$'}sockets" | grep -Fq '"adbd"'; then
                echo on
            else
                echo off
            fi
        }
    """

    suspend fun read(): Boolean? = run("$LISTENER\nlistener")

    suspend fun setEnabled(enabled: Boolean): Boolean? {
        val port = if (enabled) PORT else 0
        val expected = if (enabled) "on" else "off"
        return run(
            """
            $LISTENER
            state=$(listener) || exit 1
            if [ "${'$'}state" = $expected ]; then echo "${'$'}state"; exit 0; fi
            previous=$(getprop $PROPERTY)
            setprop $PROPERTY $port || exit 1
            if setprop ctl.restart adbd; then
                for attempt in 1 2 3 4 5 6 7 8 9 10; do
                    sleep 0.2
                    state=$(listener) || break
                    if [ "${'$'}state" = $expected ]; then echo "${'$'}state"; exit 0; fi
                done
            fi
            setprop $PROPERTY "${'$'}previous" && setprop ctl.restart adbd
            exit 1
            """.trimIndent()
        )
    }

    private suspend fun run(command: String): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            when (NativeRootCommand.run(command, maxBytes = 4096).decodeToString().trim()) {
                "on" -> true
                "off" -> false
                else -> error("Unexpected adbd listener status")
            }
        }.onFailure { DebugLog.w("AdbTcpPort", "Could not read or change the TCP listener", it) }.getOrNull()
    }
}
