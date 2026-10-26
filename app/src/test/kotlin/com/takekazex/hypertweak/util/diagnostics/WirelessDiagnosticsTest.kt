package com.takekazex.hypertweak.util.diagnostics

import com.takekazex.hypertweak.util.platform.NativeRootCommand
import org.junit.Assert.*
import org.junit.Test

class WirelessDiagnosticsTest {
    private class Device {
        var uid = "0"
        var cnss = "true"
        var cnssService = "running"
        var tcp = "false"
        var tcpService = "stopped"
        var size = "441344\t/data/vendor/wlan_logs"
        var acceptProperty = true
        var followProperty = true
        var rejectWrite = false
        var rejectCleanup = false
        var queryFailure = false
        var queryCount = 0
        var queryFailsAt = Int.MAX_VALUE
        var transitionAt = Int.MAX_VALUE
        var revertAt = Int.MAX_VALUE
        var now = 0L
        val commands = mutableListOf<String>()
        val saved = mutableListOf<Pair<WirelessLogger, Boolean>>()
        val engine = WirelessDiagnosticsEngine(
            run = { command ->
                commands += command
                when {
                    command == "id -u" -> uid
                    command.startsWith("a=") -> {
                        queryCount++
                        check(!queryFailure && queryCount < queryFailsAt) { "getprop denied" }
                        if (queryCount >= revertAt) cnss = "true"
                        if (queryCount >= transitionAt) cnssService = if (parseWirelessBoolean(cnss) == true) "running" else "stopped"
                        "HT_WIRELESS|$cnss|$cnssService|$tcp|$tcpService|END\n"
                    }
                    command.startsWith("setprop ") -> {
                        check(!rejectWrite) { "SELinux denied setprop" }
                        val parts = command.split(' ')
                        if (acceptProperty) {
                            if (parts[1] == WirelessLogger.CNSS.property) {
                                cnss = parts[2]
                                if (followProperty) cnssService = if (cnss == "true") "running" else "stopped"
                            } else {
                                tcp = parts[2]
                                if (followProperty) tcpService = if (tcp == "true") "running" else "stopped"
                            }
                        }
                        ""
                    }
                    command.contains("rm -rf") -> {
                        check(!rejectCleanup) { "cleanup denied" }
                        size = "0\t/data/vendor/wlan_logs"
                        ""
                    }
                    command.contains("du -sk") -> size
                    else -> error("Unexpected command: $command")
                }
            },
            rememberApplied = { logger, enabled -> saved += logger to enabled },
            nanoTime = { now },
            sleep = { now += it * 1_000_000 }
        )
    }

    @Test fun `property parsing handles vendor booleans without treating absence as false`() {
        listOf("true", "1", "yes", "on", "Y", " TRUE ").forEach { assertEquals(true, parseWirelessBoolean(it)) }
        listOf("false", "0", "no", "off", "N").forEach { assertEquals(false, parseWirelessBoolean(it)) }
        listOf("", "unknown", "stopped", "2").forEach { assertNull(parseWirelessBoolean(it)) }
    }

    @Test fun `inconsistent running service takes precedence over disabled property`() {
        assertEquals(WirelessState.ABNORMAL, WirelessReading("false", "running").state)
        assertEquals(WirelessState.RUNNING, WirelessReading("true", "running").state)
        assertEquals(WirelessState.STOPPED, WirelessReading("true", "stopped").state)
        assertEquals(WirelessState.STOPPED, WirelessReading("false", "stopped").state)
        assertEquals(WirelessState.UNKNOWN, WirelessReading("true", "restarting").state)
        listOf("", "restarting", "stopping", "unexpected").forEach {
            assertEquals(WirelessState.UNKNOWN, WirelessReading("false", it).state)
        }
        assertEquals(WirelessState.UNKNOWN, WirelessReading("unexpected", "running").state)
        assertEquals(WirelessState.UNSUPPORTED, WirelessReading("", "").state)
    }

    @Test fun `page reads preserve enabled device state without applying default false`() {
        val d = Device()
        val result = d.engine.query()
        assertNull(result.failure)
        assertEquals(WirelessState.RUNNING, result.snapshot!!.cnss.state)
        assertEquals(441344L, result.snapshot.sizeKiB)
        assertFalse(d.commands.any { it.startsWith("setprop") || it.contains("rm -rf") })
        assertTrue(d.saved.isEmpty())
    }

    @Test fun `root denial stops before property or storage commands`() {
        val d = Device().apply { uid = "2000" }
        assertEquals(WirelessFailure.NO_ROOT, d.engine.setEnabled(WirelessLogger.CNSS, false).failure)
        assertEquals(listOf("id -u"), d.commands)
        assertTrue(d.saved.isEmpty())
    }

    @Test fun `missing su is reported as root unavailable`() {
        val e = WirelessDiagnosticsEngine(run = { throw java.io.IOException("su missing") })
        assertEquals(WirelessFailure.NO_ROOT, e.query().failure)
    }

    @Test fun `missing cnss property never permits mutation or cleanup`() {
        val d = Device().apply { cnss = ""; cnssService = "" }
        assertEquals(WirelessState.UNSUPPORTED, d.engine.query().snapshot!!.cnss.state)
        d.engine.setEnabled(WirelessLogger.CNSS, false)
        d.engine.clearLogs()
        assertFalse(d.commands.any { it.startsWith("setprop") || it.contains("rm -rf") || it.contains("du -sk") })
    }

    @Test fun `missing optional tcpdump does not prevent cnss management`() {
        val d = Device().apply { tcp = ""; tcpService = "" }
        assertNull(d.engine.setEnabled(WirelessLogger.CNSS, false).failure)
        d.commands.clear()
        assertEquals(WirelessState.UNSUPPORTED, d.engine.setEnabled(WirelessLogger.TCPDUMP, true).snapshot!!.tcpdump.state)
        assertFalse(d.commands.any { it.startsWith("setprop") })
    }

    @Test fun `successful disable and enable verify service and persist only matching values`() {
        val d = Device()
        assertNull(d.engine.setEnabled(WirelessLogger.CNSS, false).failure)
        assertEquals("stopped", d.cnssService)
        assertNull(d.engine.setEnabled(WirelessLogger.CNSS, true).failure)
        assertEquals("running", d.cnssService)
        assertEquals(listOf(WirelessLogger.CNSS to false, WirelessLogger.CNSS to true), d.saved)
        assertEquals("false", d.tcp)
    }

    @Test fun `tcpdump command targets its own property`() {
        val d = Device()
        assertNull(d.engine.setEnabled(WirelessLogger.TCPDUMP, true).failure)
        assertEquals("true", d.tcp)
        assertEquals("true", d.cnss)
        assertEquals(listOf(WirelessLogger.TCPDUMP to true), d.saved)
    }

    @Test fun `silent setprop failure cannot be saved or reported as success`() {
        val d = Device().apply { acceptProperty = false }
        val result = d.engine.setEnabled(WirelessLogger.CNSS, false)
        assertEquals(WirelessFailure.SET_PROPERTY, result.failure)
        assertEquals(true, result.snapshot!!.cnss.enabled)
        assertTrue(d.saved.isEmpty())
    }

    @Test fun `SELinux write rejection remains visible`() {
        val d = Device().apply { rejectWrite = true }
        val result = d.engine.setEnabled(WirelessLogger.CNSS, false)
        assertEquals(WirelessFailure.SET_PROPERTY, result.failure)
        assertTrue(result.detail.contains("SELinux"))
    }

    @Test fun `verified property with stuck service reports transition failure after bounded polling`() {
        val d = Device().apply { followProperty = false }
        val result = d.engine.setEnabled(WirelessLogger.CNSS, false)
        assertEquals(WirelessFailure.SERVICE_TRANSITION, result.failure)
        assertEquals(WirelessState.ABNORMAL, result.snapshot!!.cnss.state)
        assertEquals(5_000_000_000L, d.now)
        assertEquals(listOf(WirelessLogger.CNSS to false), d.saved)
    }

    @Test fun `delayed init transition succeeds within observation window`() {
        val d = Device().apply { followProperty = false; transitionAt = 5 }
        assertNull(d.engine.setEnabled(WirelessLogger.CNSS, false).failure)
        assertTrue(d.now in 1..4_999_999_999L)
    }

    @Test fun `property overridden during init transition is not saved as the requested value`() {
        val d = Device().apply { followProperty = false; revertAt = 3 }
        val result = d.engine.setEnabled(WirelessLogger.CNSS, false)
        assertEquals(WirelessFailure.SET_PROPERTY, result.failure)
        assertEquals(true, result.snapshot!!.cnss.enabled)
        assertTrue(d.saved.isEmpty())
    }

    @Test fun `malformed command output is a read error rather than a stopped state`() {
        val e = WirelessDiagnosticsEngine(run = { if (it == "id -u") "0" else "permission denied" })
        val result = e.query()
        assertEquals(WirelessFailure.QUERY, result.failure)
        assertNull(result.snapshot)
    }

    @Test fun `cleanup with root denied cannot execute filesystem commands`() {
        val d = Device().apply { uid = "2000" }
        assertEquals(WirelessFailure.NO_ROOT, d.engine.clearLogs().failure)
        assertEquals(listOf("id -u"), d.commands)
    }

    @Test fun `failed post-write read never displays the pre-write snapshot as current`() {
        val d = Device().apply { queryFailsAt = 2 }
        val result = d.engine.setEnabled(WirelessLogger.CNSS, false)
        assertEquals("false", d.cnss)
        assertEquals(WirelessFailure.QUERY, result.failure)
        assertNull(result.snapshot)
        assertTrue(d.saved.isEmpty())
    }

    @Test fun `query failure cannot masquerade as unsupported hardware`() {
        val d = Device().apply { queryFailure = true }
        val result = d.engine.query()
        assertEquals(WirelessFailure.QUERY, result.failure)
        assertNull(result.snapshot)
    }

    @Test fun `usage failure preserves independently verified service status`() {
        val d = Device().apply { size = "du: permission denied" }
        val result = d.engine.query()
        assertEquals(WirelessFailure.STORAGE, result.failure)
        assertEquals(WirelessState.RUNNING, result.snapshot!!.cnss.state)
        assertNull(result.snapshot.sizeKiB)
    }

    @Test fun `cleanup refreshes usage without changing properties`() {
        val d = Device()
        val result = d.engine.clearLogs()
        assertNull(result.failure)
        assertEquals(0L, result.snapshot!!.sizeKiB)
        assertFalse(d.commands.any { it.startsWith("setprop") })
        assertEquals(1, d.commands.count { it.contains("rm -rf") })
    }

    @Test fun `cleanup rejection is visible and retains measured usage`() {
        val d = Device().apply { rejectCleanup = true }
        val result = d.engine.clearLogs()
        assertEquals(WirelessFailure.CLEANUP, result.failure)
        assertEquals(441344L, result.snapshot!!.sizeKiB)
    }

    @Test fun `write rejection survives a failed readback without exposing stale state`() {
        val d = Device().apply { rejectWrite = true; queryFailsAt = 2 }
        val result = d.engine.setEnabled(WirelessLogger.CNSS, false)
        assertEquals(WirelessFailure.SET_PROPERTY, result.failure)
        assertNull(result.snapshot)
        assertTrue(result.detail.contains("SELinux"))
        assertTrue(result.detail.contains("getprop denied"))
    }

    @Test fun `cleanup rejection survives a failed refresh without exposing stale state`() {
        val d = Device().apply { rejectCleanup = true; queryFailsAt = 2 }
        val result = d.engine.clearLogs()
        assertEquals(WirelessFailure.CLEANUP, result.failure)
        assertNull(result.snapshot)
        assertTrue(result.detail.contains("cleanup denied"))
        assertTrue(result.detail.contains("getprop denied"))
    }

    @Test fun `usage requires a single measurement for the exact directory`() {
        listOf("12 /other", "12", "12 /data/vendor/wlan_logs\nwarning", "12 /data/vendor/wlan_logs extra").forEach {
            val result = Device().apply { size = it }.engine.query()
            assertEquals(WirelessFailure.STORAGE, result.failure)
            assertNull(result.snapshot!!.sizeKiB)
        }
    }

    @Test fun `transition timeout is bounded across monotonic clock overflow`() {
        val d = Device().apply { followProperty = false; now = Long.MAX_VALUE - 100_000_000L }
        val started = d.now
        val result = d.engine.setEnabled(WirelessLogger.CNSS, false)
        assertEquals(WirelessFailure.SERVICE_TRANSITION, result.failure)
        assertEquals(5_000_000_000L, d.now - started)
        assertTrue(result.detail.contains("expected stopped"))
    }

    @Test fun `root command diagnostics retain bounded stderr through executor wrapping`() {
        val engine = WirelessDiagnosticsEngine(run = {
            if (it == "id -u") "0" else throw java.util.concurrent.ExecutionException(
                NativeRootCommand.CommandFailure(61, ("getprop denied " + "x".repeat(1000)).toByteArray())
            )
        })
        val result = engine.query()
        assertEquals(WirelessFailure.QUERY, result.failure)
        assertTrue(result.detail.contains("status 61"))
        assertTrue(result.detail.contains("getprop denied"))
        assertTrue(result.detail.length <= 300)
    }

    @Test fun `cleanup accepts only the literal log directory and rejects traversal or shell payloads`() {
        val script = WirelessLogPath.cleanupScript()
        assertTrue(script.contains("cd -P /data/vendor/wlan_logs"))
        assertTrue(script.contains("[ ! -L /data/vendor ]"))
        assertTrue(script.contains("pwd -P"))
        listOf("/", "/data/vendor", "/data/vendor/wlan_logs/../other", "/data/vendor/wlan_logs/", "", "/data/vendor/wlan_logs;id", "/data/vendor/wlan_logs\n/").forEach {
            assertThrows(IllegalArgumentException::class.java) { WirelessLogPath.cleanupScript(it) }
        }
    }
}
