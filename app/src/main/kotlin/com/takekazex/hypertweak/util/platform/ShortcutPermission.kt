package com.takekazex.hypertweak.util.platform

/** Xiaomi's shortcut AppOp, also used by KernelSU's manager. Never changes another package. */
internal object ShortcutPermission {
    private const val READY = "HYPERTWEAK_SHORTCUT_PERMISSION_READY"

    fun isReady(output: String): Boolean = output.lineSequence().any { it == READY }

    fun command(packageName: String, userId: Int): String {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(userId >= 0)
        val target = "--user $userId ${NativeRootCommand.quote(packageName)} 10017"
        return """
            if [ -z "${'$'}(getprop ro.miui.ui.version.name)${'$'}(getprop ro.mi.os.version.name)" ]; then
                printf '%s\n' '$READY'
                exit 0
            fi
            shortcut_allowed() {
                current=${'$'}(cmd appops get $target) || return 1
                uid_mode=${'$'}(printf '%s\n' "${'$'}current" | grep '^[[:space:]]*Uid mode:')
                if [ -n "${'$'}uid_mode" ]; then
                    printf '%s\n' "${'$'}uid_mode" | grep -Eq ': allow([;[:space:]]|${'$'})' || return 1
                fi
                printf '%s\n' "${'$'}current" | grep -v '^[[:space:]]*Uid mode:' | grep -Eq '^[[:space:]]*[^:]+: allow([;[:space:]]|${'$'})'
            }
            if ! shortcut_allowed; then
                cmd appops set $target allow || exit 1
                shortcut_allowed || exit 2
            fi
            printf '%s\n' '$READY'
        """.trimIndent()
    }
}
