package com.takekazex.hypertweak.util.platform

import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** Controls the legacy adbd TCP listener. Android's paired wireless debugging is separate. */
internal object AdbTcpPort {
    data class State(val port: Int?, val persistent: Boolean)
    data class Result(val state: State?, val occupied: Boolean = false)

    private val random = SecureRandom()
    private const val PROPERTY = "service.adb.tcp.port"
    private const val PERSISTENT = "persist.adb.tcp.port"
    private const val SCRIPT = """
        sockets_at() {
            ss -H -ltnp "sport = :${'$'}1"
        }
        is_port() {
            case "${'$'}1" in ''|*[!0-9]*) return 1;; esac
            [ "${'$'}1" -ge 1 ] && [ "${'$'}1" -le 65535 ]
        }
        read_state() {
            persisted=$(getprop $PERSISTENT)
            configured=$(getprop $PROPERTY)
            candidate=${'$'}{configured:-${'$'}persisted}
            active=0
            if is_port "${'$'}candidate"; then
                sockets=$(sockets_at "${'$'}candidate") || return 1
                if printf '%s\n' "${'$'}sockets" | grep -Fq '"adbd"'; then active=${'$'}candidate; fi
            fi
        }
        report() {
            keep=0
            if is_port "${'$'}persisted"; then keep=1; fi
            echo "state ${'$'}active ${'$'}keep"
        }
        wait_for_port() {
            for attempt in 1 2 3 4 5 6 7 8 9 10; do
                sleep 0.2
                read_state || return 1
                [ "${'$'}active" = "${'$'}1" ] || continue
                if [ "${'$'}1" != 0 ]; then return 0; fi
                [ "${'$'}(getprop init.svc.adbd)" = running ] || continue
                if is_port "${'$'}old_active"; then
                    sockets=$(sockets_at "${'$'}old_active") || return 1
                    printf '%s\n' "${'$'}sockets" | grep -Fq '"adbd"' && continue
                fi
                return 0
            done
            return 1
        }
    """

    suspend fun read(): State? = run("read_state && report").state

    suspend fun setEnabled(enabled: Boolean, customPort: Int?): Result {
        require(customPort == null || customPort in 1..65535)
        val candidates = if (!enabled) listOf(0) else if (customPort != null) listOf(customPort) else
            generateSequence { 49152 + random.nextInt(16384) }.distinct().take(8).toList()
        return run("""
            read_state || exit 1
            old_active=${'$'}active
            old_configured=${'$'}configured
            old_persisted=${'$'}persisted
            port=
            for candidate in ${candidates.joinToString(" ")}; do
                if [ "${'$'}candidate" = 0 ] || [ "${'$'}candidate" = "${'$'}active" ]; then
                    port=${'$'}candidate; break
                fi
                sockets=$(ss -H -tan "sport = :${'$'}candidate") || exit 1
                if [ -z "${'$'}sockets" ]; then port=${'$'}candidate; break; fi
            done
            if [ -z "${'$'}port" ]; then echo occupied; exit 0; fi
            if [ "${'$'}port" = "${'$'}active" ] && [ "${'$'}port" != 0 ]; then report; exit 0; fi
            if [ "${'$'}port" = 0 ] && [ "${'$'}active" = 0 ]; then
                if [ -n "${'$'}persisted" ]; then setprop $PERSISTENT '' || exit 1; fi
                read_state && report
                exit ${'$'}?
            fi
            if setprop $PROPERTY "${'$'}port" && setprop ctl.restart adbd && wait_for_port "${'$'}port"; then
                # Keep an existing opt-in attached to the verified live port. Turning off clears it.
                keep_port=''
                if [ "${'$'}port" != 0 ] && is_port "${'$'}old_persisted"; then keep_port=${'$'}port; fi
                if { [ "${'$'}keep_port" = "${'$'}old_persisted" ] || setprop $PERSISTENT "${'$'}keep_port"; } &&
                    read_state && [ "${'$'}active" = "${'$'}port" ] && [ "${'$'}persisted" = "${'$'}keep_port" ]; then
                    report
                    exit 0
                fi
            fi
            setprop $PERSISTENT "${'$'}old_persisted"
            setprop $PROPERTY "${'$'}old_configured" && setprop ctl.restart adbd
            exit 1
        """.trimIndent())
    }

    suspend fun setPersistent(enabled: Boolean): Result = run("""
        read_state || exit 1
        if [ "$enabled" = true ]; then
            is_port "${'$'}active" || exit 1
            setprop $PERSISTENT "${'$'}active" || exit 1
        else
            # A boot-restored listener may have no service property. Retain its running port.
            if is_port "${'$'}active" && [ -z "${'$'}configured" ]; then
                setprop $PROPERTY "${'$'}active" || exit 1
            fi
            setprop $PERSISTENT '' || exit 1
        fi
        read_state && report
    """.trimIndent())

    private suspend fun run(command: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val output = NativeRootCommand.run("$SCRIPT\n$command", maxBytes = 4096).decodeToString().trim()
            if (output == "occupied") return@runCatching Result(null, occupied = true)
            val fields = output.split(' ')
            check(fields.size == 3 && fields[0] == "state" && fields[2] in setOf("0", "1"))
            val port = fields[1].toInt()
            check(port in 0..65535)
            Result(State(port.takeIf { it != 0 }, fields[2] == "1"))
        }.onFailure { DebugLog.w("AdbTcpPort", "Could not read or change the TCP listener", it) }
            .getOrElse { Result(null) }
    }
}
