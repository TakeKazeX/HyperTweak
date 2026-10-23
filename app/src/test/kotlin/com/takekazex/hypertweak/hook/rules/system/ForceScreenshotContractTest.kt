package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.*
import org.junit.Test

class ForceScreenshotContractTest {
    open class Builder {
        @JvmField var renamed = 0
        @JvmField var protected = 0
        fun setSecureContentPolicy(value: Int): Builder { renamed = value; return this }
    }
    class Args(b: Builder) {
        @JvmField var obfuscated = b.renamed
        @JvmField var protected = b.protected
    }
    class Surface
    class CopyBuilder(@Suppress("UNUSED_PARAMETER") surface: Surface?, args: Args) : Builder() {
        init { renamed = args.obfuscated; protected = args.protected }
    }
    class BrokenCopy(@Suppress("UNUSED_PARAMETER") surface: Surface?, @Suppress("UNUSED_PARAMETER") args: Args) : Builder()
    class AmbiguousArgs(b: Builder) {
        @JvmField var first = b.renamed
        @JvmField var second = b.renamed
    }
    class AmbiguousCopy(@Suppress("UNUSED_PARAMETER") surface: Surface?, args: AmbiguousArgs) : Builder() {
        init { renamed = args.first }
    }
    open class BooleanBuilder {
        @JvmField var renamed = false
        fun setCaptureSecureLayers(value: Boolean): BooleanBuilder { renamed = value; return this }
    }
    class BooleanArgs(b: BooleanBuilder) { @JvmField var flag = b.renamed }
    class BooleanCopy(@Suppress("UNUSED_PARAMETER") surface: Surface?, args: BooleanArgs) : BooleanBuilder() {
        init { renamed = args.flag }
    }
    @Test fun legacyBooleanSchemaIsDiscoveredWithoutAVersionTable() {
        val c = ForceScreenshotContract.resolve(BooleanArgs::class.java, BooleanBuilder::class.java,
            BooleanCopy::class.java, Surface::class.java,
            BooleanBuilder::class.java.getDeclaredMethod("setCaptureSecureLayers", Boolean::class.javaPrimitiveType), true)
        val request = BooleanArgs(BooleanBuilder())
        c.withCapturePolicy(request, { throw AssertionError(it) }) { assertTrue(request.flag) }
        assertFalse(request.flag)
    }
    class Root
    class WindowLock
    class NativeRoot(private val monitor: WindowLock) {
        var windowSecure = true
        var surfaceSecure = false
        var refreshes = 0
        fun refreshSecureSurfaceState() {
            check(Thread.holdsLock(monitor))
            surfaceSecure = windowSecure
            refreshes++
        }
    }
    class LocalWm {
        @JvmField val nativeLock = WindowLock()
        @JvmField val nativeRoot = NativeRoot(nativeLock)
    }
    class AmbiguousWm {
        @JvmField val lock = WindowLock()
        @JvmField val first = NativeRoot(lock)
        @JvmField val second = NativeRoot(lock)
    }
    @Test fun upgradeAndDisableRepairStaleSurfacesUsingCurrentNativePolicyUnderTheHostLock() {
        val wm = LocalWm()
        val repair = ForceScreenshotSurfaceRecovery.resolve(LocalWm::class.java, NativeRoot::class.java, WindowLock::class.java)
        repair.restore(wm)
        assertTrue(wm.nativeRoot.windowSecure)
        assertTrue(wm.nativeRoot.surfaceSecure)
        // A later native policy change is honoured, rather than restoring a historical Boolean.
        wm.nativeRoot.windowSecure = false
        repair.restore(wm)
        assertFalse(wm.nativeRoot.surfaceSecure)
        assertEquals(2, wm.nativeRoot.refreshes)
        assertThrows(IllegalStateException::class.java) { repair.restore(Any()) }
    }
    @Test fun surfaceRepairRejectsAmbiguousOwnership() {
        assertThrows(IllegalArgumentException::class.java) {
            ForceScreenshotSurfaceRecovery.resolve(AmbiguousWm::class.java, NativeRoot::class.java, WindowLock::class.java)
        }
    }
    open class VendorStub {
        open fun notAllowCaptureDisplay(root: Root?, display: Int) = false
        companion object {
            @JvmStatic fun get(): VendorStub = VendorImpl()
        }
    }
    class VendorImpl : VendorStub() {
        override fun notAllowCaptureDisplay(root: Root?, display: Int) = true
    }
    class MissingVendorGate {
        companion object {
            @JvmStatic fun get(): MissingVendorGate = MissingVendorGate()
        }
    }
    @Test fun vendorResolutionUsesTheLiveOverrideInsteadOfThePermissiveStub() {
        val gate = ForceScreenshotVendorGate.resolve(VendorStub::class.java, Root::class.java)
        assertEquals(VendorImpl::class.java, gate.declaringClass)
        assertEquals(true, gate.invoke(VendorImpl(), null, 0))
        assertEquals(false, VendorStub::class.java.getMethod("notAllowCaptureDisplay", Root::class.java,
            Int::class.javaPrimitiveType).invoke(VendorStub(), null, 0))
        assertThrows(NoSuchMethodException::class.java) {
            ForceScreenshotVendorGate.resolve(MissingVendorGate::class.java, Root::class.java)
        }
    }
    private fun resolve(args: Class<*> = Args::class.java, copy: Class<*> = CopyBuilder::class.java) =
        ForceScreenshotContract.resolve(args, Builder::class.java, copy, Surface::class.java,
            Builder::class.java.getDeclaredMethod("setSecureContentPolicy", Int::class.javaPrimitiveType), 1)

    @Test fun discoversRenamedOperandAndRestoresReusedRequestAfterSuccessAndFailure() {
        val c = resolve()
        assertEquals("obfuscated", c.policy.name)
        val request = Args(Builder().apply { renamed = 2; protected = 2 })
        val fail: (Throwable) -> Unit = { throw AssertionError(it) }
        assertEquals(42, c.withCapturePolicy(request, fail) {
            assertEquals(1, request.obfuscated)
            assertEquals(2, request.protected)
            42
        })
        assertEquals(2, request.obfuscated)
        val original = IllegalStateException("native failure")
        try { c.withCapturePolicy(request, fail) { throw original }; fail("expected failure") }
        catch (t: IllegalStateException) { assertSame(original, t) }
        assertEquals(2, request.obfuscated)
        // Disable/replacement has no mutated request or Surface state to migrate.
        assertEquals(2, request.protected)
    }
    @Test fun rejectsAmbiguousOperandAndBrokenCopy() {
        assertThrows(IllegalStateException::class.java) { resolve(AmbiguousArgs::class.java, AmbiguousCopy::class.java) }
        assertThrows(IllegalStateException::class.java) { resolve(copy = BrokenCopy::class.java) }
    }
    @Test fun nestedCaptureRestoresTheOuterAndOriginalPolicies() {
        val c = resolve()
        val request = Args(Builder().apply { renamed = 2 })
        c.withCapturePolicy(request, { throw AssertionError(it) }) {
            c.withCapturePolicy(request, { throw AssertionError(it) }) { assertEquals(1, request.obfuscated) }
            assertEquals(1, request.obfuscated)
        }
        assertEquals(2, request.obfuscated)
    }
    @Test fun incompatibleRequestFailsOpenAndCallsOriginalExactlyOnce() {
        val c = resolve()
        var errors = 0
        var calls = 0
        assertEquals(7, c.withCapturePolicy(Any(), { errors++ }) { calls++; 7 })
        assertEquals(1, errors)
        assertEquals(1, calls)
    }
}
