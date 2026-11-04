package com.takekazex.hypertweak.util.platform

import org.junit.Assert.*
import org.junit.Test

class SystemHelperControlTest {
    @Test
    fun `commands are pinned to the carrier package and current user`() {
        assertEquals("com.mobiletools.systemhelper", SystemHelperPackage.NAME)
        assertEquals("pm disable-user --user 0 com.mobiletools.systemhelper", SystemHelperPackage.DISABLE_COMMAND)
        assertEquals("pm enable --user 0 com.mobiletools.systemhelper", SystemHelperPackage.ENABLE_COMMAND)
    }

    private class Device {
        var installed = true
        var enabled = true
        var version = "4.0.0-20260910"
        var root = "0"
        var acceptWrite = true
        var beforeReadback: (() -> Unit)? = null
        val commands = mutableListOf<String>()
        val remembered = mutableListOf<Boolean>()
        val engine = SystemHelperEngine(
            readState = {
                if (!installed) null else SystemHelperPackageState(
                    installed = installed,
                    enabled = enabled,
                    versionName = version
                )
            },
            run = { command ->
                commands += command
                when {
                    command == "id -u" -> root
                    command == SystemHelperPackage.DISABLE_COMMAND -> {
                        check(acceptWrite) { "package manager rejected disable" }
                        enabled = false
                        beforeReadback?.invoke()
                        ""
                    }
                    command == SystemHelperPackage.ENABLE_COMMAND -> {
                        check(acceptWrite) { "package manager rejected enable" }
                        enabled = true
                        beforeReadback?.invoke()
                        ""
                    }
                    else -> error("Unexpected command: " + command)
                }
            },
            rememberDisabled = { remembered += it }
        )
    }

    @Test
    fun `query returns the live package state`() {
        val d = Device()
        val first = d.engine.query()
        assertNull(first.failure)
        assertTrue(first.state!!.installed)
        assertTrue(first.state!!.enabled)
        assertEquals("4.0.0-20260910", first.state!!.versionName)

        d.enabled = false
        val second = d.engine.query()
        assertTrue(second.state!!.installed)
        assertFalse(second.state!!.enabled)
        assertTrue(d.commands.isEmpty())
    }

    @Test
    fun `query distinguishes an absent package`() {
        val d = Device().apply { installed = false }
        val result = d.engine.query()
        assertNull(result.failure)
        assertFalse(result.state!!.installed)
        assertFalse(result.state!!.enabled)
    }

    @Test
    fun `disable uses the fixed package and verifies readback`() {
        val d = Device()
        val result = d.engine.setEnabled(false)
        assertNull(result.failure)
        assertFalse(result.state!!.enabled)
        assertEquals(listOf(SystemHelperPackage.DISABLE_COMMAND), d.commands.filter { it != "id -u" })
        assertEquals(listOf(true), d.remembered)
    }

    @Test
    fun `enable uses the fixed package and verifies readback`() {
        val d = Device().apply { enabled = false }
        val result = d.engine.setEnabled(true)
        assertNull(result.failure)
        assertTrue(result.state!!.enabled)
        assertEquals(listOf(SystemHelperPackage.ENABLE_COMMAND), d.commands.filter { it != "id -u" })
        assertEquals(listOf(false), d.remembered)
    }

    @Test
    fun `root denial is reported before any package command`() {
        val d = Device().apply { root = "2000" }
        val result = d.engine.setEnabled(false)
        assertEquals(SystemHelperFailure.NO_ROOT, result.failure)
        assertTrue(result.state!!.enabled)
        assertEquals(listOf("id -u"), d.commands)
        assertTrue(d.remembered.isEmpty())
    }

    @Test
    fun `a successful command with unchanged state is a set failure`() {
        val d = Device().apply { beforeReadback = { enabled = true } }
        val result = d.engine.setEnabled(false)
        assertEquals(SystemHelperFailure.SET, result.failure)
        assertTrue(result.state!!.enabled)
        assertTrue(d.remembered.isEmpty())
    }

    @Test
    fun `a failed command preserves the current state and never remembers it`() {
        val d = Device().apply { acceptWrite = false }
        val result = d.engine.setEnabled(false)
        assertEquals(SystemHelperFailure.SET, result.failure)
        assertTrue(result.detail.contains("rejected"))
        assertTrue(result.state!!.enabled)
        assertTrue(d.remembered.isEmpty())
    }

    @Test
    fun `absent package cannot be disabled or enabled`() {
        val d = Device().apply { installed = false }
        assertEquals(SystemHelperFailure.NOT_INSTALLED, d.engine.setEnabled(false).failure)
        assertEquals(SystemHelperFailure.NOT_INSTALLED, d.engine.setEnabled(true).failure)
        assertTrue(d.commands.isEmpty())
        assertTrue(d.remembered.isEmpty())
    }

    @Test
    fun `already disabled target needs no root call while reconciling`() {
        val d = Device().apply { enabled = false }
        val result = d.engine.reconcile(true)
        assertNull(result.failure)
        assertFalse(result.state!!.enabled)
        assertTrue(d.commands.isEmpty())
        assertTrue(d.remembered.isEmpty())
    }

    @Test
    fun `reconcile disables an enabled target after an OTA`() {
        val d = Device()
        val result = d.engine.reconcile(true)
        assertNull(result.failure)
        assertFalse(result.state!!.enabled)
        assertEquals(SystemHelperPackage.DISABLE_COMMAND, d.commands.filter { it != "id -u" }.single())
        assertEquals(listOf(true), d.remembered)
    }

    @Test
    fun `reconcile restores a disabled target after an OTA`() {
        val d = Device().apply { enabled = false }
        val result = d.engine.reconcile(false)
        assertNull(result.failure)
        assertTrue(result.state!!.enabled)
        assertEquals(SystemHelperPackage.ENABLE_COMMAND, d.commands.filter { it != "id -u" }.single())
        assertEquals(listOf(false), d.remembered)
    }

    @Test
    fun `readback failure is a query failure rather than a false success`() {
        var reads = 0
        val engine = SystemHelperEngine(
            readState = {
                reads++
                if (reads == 1) SystemHelperPackageState(true, true, "4.0.0")
                else throw IllegalStateException("readback denied")
            },
            run = { if (it == "id -u") "0" else "" }
        )
        val result = engine.setEnabled(false)
        assertEquals(SystemHelperFailure.QUERY, result.failure)
        assertNull(result.state)
    }
}
