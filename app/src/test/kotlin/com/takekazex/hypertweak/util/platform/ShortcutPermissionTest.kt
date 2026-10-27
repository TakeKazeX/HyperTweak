package com.takekazex.hypertweak.util.platform

import org.junit.Assert.*
import org.junit.Test

/** Execute the production shell flow against a simulated OEM AppOps command. */
class ShortcutPermissionTest {
    private fun runScript(initial: String = "ignore", setExit: Int = 0, apply: Boolean = true,
        miui: Boolean = true, uidMode: String? = null): Pair<Int, String> {
        val script = """
            mode='$initial'
            getprop() { ${if (miui) "printf OS3" else ":"}; }
            cmd() {
                [ "${'$'}1" = appops ] || exit 90
                [ "${'$'}3" = --user ] && [ "${'$'}4" = 10 ] || exit 91
                [ "${'$'}5" = com.takekazex.hypertweak ] && [ "${'$'}6" = 10017 ] || exit 92
                if [ "${'$'}2" = get ]; then
                    ${if (uidMode != null) "printf 'Uid mode: MIUIOP(10017): $uidMode\\n'" else ":"}
                    printf 'MIUIOP(10017): %s\n' "${'$'}mode"
                elif [ "${'$'}2" = set ]; then
                    printf 'SET\n'
                    [ "${'$'}7" = allow ] || exit 93
                    ${if (apply) "mode=allow" else ":"}
                    return $setExit
                else
                    exit 94
                fi
            }
            ${ShortcutPermission.command("com.takekazex.hypertweak", 10)}
        """.trimIndent()
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }

    @Test fun `denied permission is granted only for the caller package and user then verified`() {
        val (exit, output) = runScript()
        assertEquals(0, exit)
        assertTrue(output.contains("SET\n"))
        assertTrue(ShortcutPermission.isReady(output))
    }

    @Test fun `existing grant does not write and non Xiaomi system does not invoke appops`() {
        for (result in listOf(runScript(initial = "allow"), runScript(miui = false))) {
            assertEquals(0, result.first)
            assertFalse(result.second.contains("SET"))
            assertTrue(ShortcutPermission.isReady(result.second))
        }
    }

    @Test fun `rejected or ineffective grants never become ready`() {
        for (result in listOf(runScript(setExit = 1), runScript(apply = false))) {
            assertNotEquals(0, result.first)
            assertFalse(ShortcutPermission.isReady(result.second))
        }
    }

    @Test fun `uid allow cannot mask a denied package operation`() {
        val (exit, output) = runScript(uidMode = "allow")
        assertEquals(0, exit)
        assertTrue(output.contains("SET\n"))
        assertTrue(ShortcutPermission.isReady(output))
    }

    @Test fun `uid rejection cannot be masked by an allowed package operation`() {
        val (exit, output) = runScript(initial = "allow", uidMode = "ignore")
        assertNotEquals(0, exit)
        assertFalse(ShortcutPermission.isReady(output))
    }

    @Test fun `unsupported operation cannot be mistaken for permission granted`() {
        val (exit, output) = runScript(initial = "default", apply = false)
        assertEquals(2, exit)
        assertFalse(ShortcutPermission.isReady(output))
        assertFalse(ShortcutPermission.isReady("INSTALL_SHORTCUT: allow"))
    }

    @Test fun `invalid command targets are rejected before starting root`() {
        assertThrows(IllegalArgumentException::class.java) { ShortcutPermission.command("app; id", 0) }
        assertThrows(IllegalArgumentException::class.java) { ShortcutPermission.command("com.example", -1) }
    }
}
