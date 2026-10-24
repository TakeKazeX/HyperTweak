package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Verify the actual boot DEX rather than framework.jar's resource-only wrapper after an OTA. */
class ForceScreenshotHostContractTest {
    private fun dex(env: String): DexOperandIndex {
        val path = System.getenv(env)
        assumeTrue("Set $env for target-bytecode checks", path != null && File(path).isFile)
        return DexOperandIndex.fixture(path!!)
    }
    private fun FieldReference.key() = "$definingClass->$name:$type"
    private fun MethodReference.params() = parameterTypes.map { it.toString() }

    @Test fun secureOperandFlowsThroughConstructorsLayerCopyAndParcel() {
        val dex = dex("FORCE_SCREENSHOT_FRAMEWORK_DEX")
        val prefix = "Landroid/window/ScreenCaptureInternal"
        val base = "$prefix\$CaptureArgs;"
        val builder = "$prefix\$CaptureArgs\$Builder;"
        val setter = dex.declared(builder).single { it.name == "setSecureContentPolicy" }
        val source = dex.code(setter).writes.single().first
        assertEquals("I", source.type)
        val constructor = dex.declared(base).single { it.name == "<init>" && it.params() == listOf(builder) }
        val destination = dex.code(constructor).writes.single { (_, value) ->
            val call = value as? DexOperandIndex.Value.Call
            call != null && dex.code(call.method).reads.any { it.key() == source.key() }
        }.first
        assertEquals(base, destination.definingClass)
        val copy = dex.declared("$prefix\$LayerCaptureArgs\$Builder;").single {
            it.name == "<init>" && it.params() == listOf("Landroid/view/SurfaceControl;", base)
        }
        assertTrue(dex.code(copy).calls.any { call -> call.method.name == setter.name &&
            call.arguments.any { it is DexOperandIndex.Value.Field && it.field.key() == destination.key() } })
        val parcel = dex.declared(base).single { it.name == "writeToParcel" }
        assertTrue(dex.code(parcel).calls.any { call -> call.method.name == "writeInt" &&
            call.arguments.any { it is DexOperandIndex.Value.Field && it.field.key() == destination.key() } })
        val natives = dex.declared("$prefix;").filter { it.name in setOf("nativeCaptureDisplay", "nativeCaptureLayers") }
        assertEquals(2, natives.size)
        assertTrue(natives.all { it.accessFlags and 0x108 == 0x108 })
    }

    @Test fun systemDisplayCaptureOwnsLayerCaptureAndSnapshotRemainsIndependent() {
        val dex = dex("FORCE_SCREENSHOT_SERVICES_JAR")
        val wm = "Lcom/android/server/wm/WindowManagerService;"
        val captures = dex.declared(wm).filter { it.name == "captureDisplay" &&
            "Landroid/window/ScreenCaptureInternal\$CaptureArgs;" in it.params() }
        assertTrue(captures.isNotEmpty())
        assertTrue(captures.any { method -> dex.code(method).calls.any { it.method.name == "captureLayers" } })
        val secure = dex.declared("Lcom/android/server/wm/WindowState;").single { it.name == "isSecureLocked" }
        assertEquals("Z", secure.returnType)
        assertTrue(dex.code(secure).calls.any { it.method.name == "shouldBlockScreenCaptureForApp" })
        val snapshots = dex.declared("Lcom/android/server/wm/AbsAppSnapshotController;").filter { it.name == "createSnapshot" }
        assertTrue(snapshots.isNotEmpty())
        assertTrue(snapshots.all { method -> dex.code(method).calls.none { it.method.definingClass == wm && it.method.name == "captureDisplay" } })
        assertTrue(dex.declared("Lcom/android/server/wm/WindowStateAnimator;").any { it.name == "createSurfaceLocked" })
        assertTrue(dex.declared("Lcom/android/server/wm/WindowState;").any { it.name == "setInitialSurfaceControlProperties" })
    }

    @Test fun vendorOverrideRejectsWindowsIndependentlyOfThePermissiveStub() {
        val services = dex("FORCE_SCREENSHOT_SERVICES_JAR")
        val vendor = dex("FORCE_SCREENSHOT_MIUI_SERVICES_JAR")
        val stub = services.declared("Lcom/android/server/wm/WindowManagerServiceStub;")
            .single { it.name == "notAllowCaptureDisplay" }
        assertTrue(services.code(stub).calls.isEmpty())
        val owner = "Lcom/android/server/wm/WindowManagerServiceImpl;"
        val gate = vendor.declared(owner).single { it.name == "notAllowCaptureDisplay" }
        assertEquals(listOf("Lcom/android/server/wm/RootWindowContainer;", "I"), gate.params())
        assertEquals("Z", gate.returnType)
        val calls = vendor.code(gate).calls
        assertTrue(calls.any { it.method.name == "forAllWindows" })
        // Follow the actual callback family; do not assume a synthetic lambda number.
        val callback = calls.map { it.method }.distinctBy { it.toString() }.single { call -> call.name == "<init>" &&
            vendor.classDefinition(call.definingClass)?.interfaces?.contains("Lcom/android/internal/util/ToBooleanFunction;") == true }
        val queue = java.util.ArrayDeque(vendor.declared(callback.definingClass))
        val seen = mutableSetOf<String>()
        var rawFlagCheck = false
        while (queue.isNotEmpty()) {
            val method = queue.removeFirst()
            val key = method.toString()
            if (!seen.add(key)) continue
            val code = vendor.code(method)
            if (code.reads.any { it.definingClass == "Landroid/view/WindowManager\$LayoutParams;" && it.name == "flags" }) {
                rawFlagCheck = true
                assertTrue(code.calls.any { it.method.name == "isSecureLocked" })
            }
            code.calls.filter { it.method.definingClass == owner }.forEach { call ->
                vendor.definition(call.method)?.let(queue::add)
            }
        }
        assertTrue("Vendor gate reads FLAG_SECURE before consulting isSecureLocked", rawFlagCheck)
        // WMS forwards the gate error separately from the secure-content policy to SF.
        val args = services.declared("Lcom/android/server/wm/WindowManagerService;").single { it.name == "getCaptureArgs" && it.params() ==
            listOf("I", "Landroid/window/ScreenCaptureInternal\$CaptureArgs;", "I") }
        assertTrue(services.code(args).calls.any { call -> call.method.name == "setMiCaptureErrorCode" &&
            call.arguments.contains(DexOperandIndex.Value.Argument(2)) })
    }

    @Test fun relayoutPersistsSecureVerdictsAndNativeRefreshRepairsThem() {
        val dex = dex("FORCE_SCREENSHOT_SERVICES_JAR")
        val wm = "Lcom/android/server/wm/WindowManagerService;"
        val window = "Lcom/android/server/wm/WindowState;"
        val root = "Lcom/android/server/wm/RootWindowContainer;"
        val relayout = dex.declared(wm).single { it.name == "relayoutWindow" }
        assertTrue("A global false verdict can persist into Surface state", dex.code(relayout).calls.any { call ->
            call.method.name == "setSecureLocked" && call.arguments.any {
                it is DexOperandIndex.Value.Call && it.method.definingClass == window && it.method.name == "isSecureLocked"
            }
        })
        val refresh = dex.declared(root).single { it.name == "refreshSecureSurfaceState" }
        assertTrue(dex.code(refresh).calls.any { it.method.name == "forAllWindows" })
        assertTrue(dex.declared(root).filter { it.name.startsWith("lambda\$refreshSecureSurfaceState\$") }.any {
            method -> dex.code(method).calls.any { it.method.name == "isSecureLocked" } &&
                dex.code(method).calls.any { it.method.name == "setSecureLocked" }
        })
        val set = dex.declared(window).single { it.name == "setSecureLocked" }
        assertTrue(dex.code(set).calls.any { it.method.name == "setSecure" })
        assertTrue(dex.code(set).calls.any { it.method.name == "scheduleAnimationLocked" })
        assertEquals(root, dex.classDefinition(wm)!!.fields.single { it.type == root }.type)
        assertEquals("Lcom/android/server/wm/WindowManagerGlobalLock;", dex.classDefinition(wm)!!.fields.single {
            it.type == "Lcom/android/server/wm/WindowManagerGlobalLock;"
        }.type)
    }

}
