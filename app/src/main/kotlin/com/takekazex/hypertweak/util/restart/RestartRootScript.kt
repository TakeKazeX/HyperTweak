package com.takekazex.hypertweak.util.restart

/** Root commands are bounded and fail closed; they never kill a replacement process. */
internal object RestartRootScript {
    fun forPackage(packageName: String): String {
        require(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName))
        if (packageName != RestartScopeSelection.PACKAGE_SYSTEM_UI) {
            return "am force-stop '$packageName' || exit 1\nprintf '%s\\n' HT_RESTART_OK"
        }
        return SYSTEM_UI
    }

    // Restart every SystemUI instance immediately, each pinned to the PID and kernel start ticks
    // seen at dispatch. A device may list several (secondary users, or a process that has not
    // finished exiting); confirmation requires a new user-0 process outside the killed set.
    internal val SYSTEM_UI = """
        identity() {
            [ -r /proc/"${'$'}1"/stat ] || return 1
            sed 's/.*) //' /proc/"${'$'}1"/stat | awk '{print ${'$'}20}'
        }
        owner() {
            [ -r /proc/"${'$'}1"/status ] || return 1
            awk '/^Uid:/ { print int(${'$'}2 / 100000); exit }' /proc/"${'$'}1"/status
        }
        instances() {
            pidof com.android.systemui | tr ' ' '\n' | sort -n | tr '\n' ' '
        }
        pidof com.android.systemui >/dev/null || exit 1
        targets=${'$'}(instances)
        pinned=''
        for pid in ${'$'}targets; do
            case "${'$'}pid" in *[!0-9]*) exit 1;; esac
            ticks=${'$'}(identity "${'$'}pid")
            case "${'$'}ticks" in ''|*[!0-9]*) exit 1;; esac
            pinned="${'$'}pinned ${'$'}pid:${'$'}ticks"
        done
        [ -n "${'$'}pinned" ] || exit 1
        [ "${'$'}(instances)" = "${'$'}targets" ] || exit 2
        for entry in ${'$'}pinned; do
            [ "${'$'}(identity "${'$'}{entry%%:*}")" = "${'$'}{entry#*:}" ] || exit 2
        done
        for entry in ${'$'}pinned; do
            pid=${'$'}{entry%%:*}
            if ! kill -9 "${'$'}pid" 2>/dev/null; then
                [ "${'$'}(identity "${'$'}pid")" = "${'$'}{entry#*:}" ] && exit 3
            fi
            printf 'HT_RESTART_KILLED pid=%s\n' "${'$'}pid"
        done
        attempts=0
        while [ "${'$'}attempts" -lt 20 ]; do
            for replacement in ${'$'}(instances); do
                case "${'$'}replacement" in ''|*[!0-9]*) continue;; esac
                case " ${'$'}targets " in *" ${'$'}replacement "*) continue;; esac
                [ "${'$'}(owner "${'$'}replacement")" = 0 ] || continue
                printf 'HT_RESTART_STARTED pid=%s\nHT_RESTART_OK\n' "${'$'}replacement"
                exit 0
            done
            attempts=${'$'}((attempts + 1))
            sleep 1
        done
        exit 4
    """.trimIndent()
}
