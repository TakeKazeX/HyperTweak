package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SecurityPreferenceReadContractTest {
    private val key = "permcenter_install_intercept_enabled"

    @Test fun computedDefaultsAndRenamedHelpersKeepThePreferenceReadContract() {
        val read = ImmutableMethodReference("Lfixture/Renamed;", "readRenamed", listOf("Ljava/lang/String;", "Z"), "Z")
        val computed = CameraDexIndex.CallSite(0, read, listOf(CameraDexIndex.Value.Text(key), null))
        assertTrue(SecurityPreferenceReadContract.matches(computed, key))
        assertTrue(SecurityPreferenceReadContract.matches(computed.copy(arguments =
            listOf(CameraDexIndex.Value.Text(key), CameraDexIndex.Value.Number(1))), key))
        assertFalse(SecurityPreferenceReadContract.matches(computed, "other_key"))
        assertFalse(SecurityPreferenceReadContract.matches(computed.copy(arguments =
            listOf(CameraDexIndex.Value.Argument(-1)) + computed.arguments), key))
        assertFalse(SecurityPreferenceReadContract.matches(computed.copy(method =
            ImmutableMethodReference("Lfixture/Renamed;", "write", listOf("Ljava/lang/String;", "Z"), "V")), key))
    }

    @Test fun actualApkResolvesAllThreeIndependentGettersIncludingComputedUsbDefault() {
        val apk = System.getenv("SECURITY_CENTER_APK")?.let(::File)
        assumeTrue("Set SECURITY_CENTER_APK to run host-artifact verification", apk?.isFile == true)
        val dex = CameraDexIndex.open(apk!!.absolutePath)
        val getters = listOf("key_safepay_auto_scan_state", "key_check_item_root", key).associateWith { preferenceKey ->
            val matches = dex.strings(preferenceKey).filter { method ->
                method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                    dex.code(method).calls.count { SecurityPreferenceReadContract.matches(it, preferenceKey) } == 1
            }
            assertEquals("unique getter for $preferenceKey", 1, matches.size)
            matches.single()
        }
        val usb = getters.getValue(key)
        val read = dex.code(usb).calls.single { SecurityPreferenceReadContract.matches(it, key) }
        assertFalse("USB's native computed default must not be mistaken for a literal", read.arguments[1] is CameraDexIndex.Value.Number)
        val activityCallers = dex.declared("Lcom/miui/permcenter/install/AdbInstallActivity;").filter { method ->
            method.name == "onActivityCreated" && method.parameterTypes.isEmpty() &&
                dex.code(method).calls.any { CameraDexIndex.descriptor(it.method) == CameraDexIndex.descriptor(usb) }
        }
        assertEquals(1, activityCallers.size)
    }
}
