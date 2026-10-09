package com.takekazex.hypertweak.util

/** Root commands are bounded and fail closed; they never kill a replacement process. */
internal object RestartRootScript {
    fun forPackage(packageName: String): String {
        require(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+").matches(packageName))
        if (packageName != RestartScopeSelection.PACKAGE_SYSTEM_UI) {
            return "am force-stop '$packageName' || exit 1\nprintf '%s\\n' HT_RESTART_OK"
        }
        return SYSTEM_UI
    }

    // Restart SystemUI immediately, pinned to the PID and kernel start ticks seen at dispatch.
    internal val SYSTEM_UI = """
        identity() {
            [ -r /proc/"${'$'}1"/stat ] || return 1
            sed 's/.*) //' /proc/"${'$'}1"/stat | awk '{print ${'$'}20}'
        }
        target=${'$'}(pidof com.android.systemui) || exit 1
        case "${'$'}target" in ''|*[!0-9]*) exit 1;; esac
        original=${'$'}(identity "${'$'}target")
        case "${'$'}original" in ''|*[!0-9]*) exit 1;; esac
        [ "${'$'}(pidof com.android.systemui)" = "${'$'}target" ] || exit 2
        [ "${'$'}(identity "${'$'}target")" = "${'$'}original" ] || exit 2
        kill -9 "${'$'}target" || exit 3
        printf 'HT_RESTART_KILLED pid=%s\n' "${'$'}target"
        attempts=0
        while [ "${'$'}attempts" -lt 20 ]; do
            replacement=${'$'}(pidof com.android.systemui)
            case "${'$'}replacement" in
                ''|*[!0-9]*) ;;
                *) if [ "${'$'}replacement" != "${'$'}target" ]; then
                       printf 'HT_RESTART_STARTED pid=%s\nHT_RESTART_OK\n' "${'$'}replacement"
                       exit 0
                   fi;;
            esac
            attempts=${'$'}((attempts + 1))
            sleep 1
        done
        exit 4
    """.trimIndent()
}
