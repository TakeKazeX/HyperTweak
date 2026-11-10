package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Executable

/**
 * An enabled hooker that installs nothing must say why: either its own skip reason or the audit's
 * warning, never silence.
 */
class InstallationAuditTest {
    private class Host { fun event() {} }

    private class Handle : XposedInterface.HookHandle {
        override fun getId(): String = "installation-audit-test"
        override fun getExecutable(): Executable = Host::class.java.getDeclaredMethod("event")
        override fun unhook() {}
        override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = error("unused")
    }

    private class Silent : DynamicHooker()
    private class Hooked : DynamicHooker() { override fun onHook() { adoptManagedHookHandle(Handle()) } }
    private class Explained : DynamicHooker() {
        override fun onHook() { DebugLog.hookSkippedDebug("Explained", "target", "not the main process") }
    }
    private class NoHooksByDesign : DynamicHooker() { override val expectsHooks = false }

    private fun reportsAfterInstall(hooker: BaseHooker): Int {
        val before = DebugLog.reportedProblemCount()
        hooker.updateParentState(true)
        return DebugLog.reportedProblemCount() - before
    }

    @Test fun `a hooker that installs nothing without a reason is reported`() {
        assertEquals(1, reportsAfterInstall(Silent()))
    }

    @Test fun `an installed hook is not reported`() {
        assertEquals(0, reportsAfterInstall(Hooked()))
    }

    @Test fun `an explained skip is reported once, by the hooker itself`() {
        assertEquals(1, reportsAfterInstall(Explained()))
    }

    @Test fun `a hooker without hooks by design is not reported`() {
        assertEquals(0, reportsAfterInstall(NoHooksByDesign()))
    }
}
