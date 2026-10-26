package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationIconFilterContract
import com.takekazex.hypertweak.hook.base.dex.DexOperandIndex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NotificationIconFilterHostContractTest {
    @Test fun actualPredicateDecodesCapturedFlagsAndNativeRebuildRatherThanSyntheticNames() {
        val path = System.getenv("SYSTEMUI_APK")
        assumeTrue(path != null)
        val dex = DexOperandIndex.fixture(path!!)
        val target = NotificationIconFilterContract.resolve(dex)
        assertNotNull(target)
        assertNotEquals(target!!.ambientFlag, target.silentFlag)
        val builder = "Lcom/android/systemui/statusbar/notification/domain/interactor/ActiveNotificationsStoreBuilder;"
        val method = dex.declared(builder).single { it.name == "toModel" && it.parameterTypes.map(CharSequence::toString) ==
            listOf("Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;") }
        assertTrue(dex.code(method).reads.any { it.name == "isSilent" || it.name == "silentSections" })
        val pipeline = dex.classDefinition("Lcom/android/systemui/statusbar/notification/collection/NotifPipeline;")!!
        val listBuilder = pipeline.fields.single { it.name == "mShadeListBuilder" }.type
        val schedule = dex.declared(listBuilder).single { it.name == "scheduleRebuild" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "Z") }
        assertTrue(dex.code(schedule).calls.any { it.method.name == "schedule" })
    }
}
