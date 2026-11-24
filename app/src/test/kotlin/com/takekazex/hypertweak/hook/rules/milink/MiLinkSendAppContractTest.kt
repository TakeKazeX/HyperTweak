package com.takekazex.hypertweak.hook.rules.milink

import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import com.takekazex.hypertweak.hook.rules.mirror.MirrorAllAppsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs against verified host APKs: `MILINK_APK` (com.milink.service) and `MIRROR_APK` (com.xiaomi.mirror). */
class MiLinkSendAppContractTest {
    private fun apk(name: String): DexOperandIndex {
        val path = System.getenv(name)
        assumeTrue(!path.isNullOrBlank() && File(path).isFile)
        return DexOperandIndex.fixture(requireNotNull(path))
    }

    @Test fun `MiLink resolves the universal clipboard region check`() {
        val roles = MiLinkClipboardRegionContract.resolve(apk("MILINK_APK"))
        assertEquals(
            "Lcom/xiaomi/dist/universalclipboardservice/UniversalClipDataReceiver;->processMessageData(Ljava/lang/String;Lcom/xiaomi/continuity/messagecenter/MessageData;)V",
            roles["receiver"],
        )
        assertEquals(
            "Lcom/xiaomi/dist/universalclipboardservice/utils/LyraUtil;->isSameRegionWithLocal(Landroid/content/Context;Ljava/lang/String;)Z",
            roles["check"],
        )
    }

    @Test fun `MiLink resolves the sub-screen allow list and its callers`() {
        val roles = MiLinkSubScreenAppsContract.resolve(apk("MILINK_APK"))
        val member = requireNotNull(roles["member"])
        // Obfuscated names below are the verified 18.2.4.1 build, not resolution inputs.
        assertEquals("Lcom/milink/util/z0;->c(Ljava/lang/String;)Z", member)
        val callers = requireNotNull(roles["callers"]).lines()
        assertTrue(callers.toString(), callers.any { it.startsWith("Lcom/milink/server/SubScreenManager;->") })
        assertTrue(callers.toString(), callers.any { it.startsWith(member.substringBefore("->")) && it.endsWith("()Z") })
    }

    @Test fun `Mirror resolves its all-apps relay switch and initializer`() {
        val roles = MirrorAllAppsContract.resolve(apk("MIRROR_APK"))
        val flag = requireNotNull(roles["flag"])
        val init = requireNotNull(roles["init"])
        // Obfuscated names below are the verified 18.03.01 build, not resolution inputs.
        assertEquals("LZ3/c;->y:Z", flag)
        assertEquals("LZ3/c;->q()V", init)
    }
}
