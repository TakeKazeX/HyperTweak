package com.takekazex.hypertweak.hook.rules.sms

import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.SmsSettingsKeys
import org.junit.Assert.*
import org.junit.Test

class SmsSettingsRoutingTest {
    @Test fun `SMS enable disable and removal are acknowledged in the owning process`() {
        val key = SmsSettingsKeys.KEY_SMS_AUTO_COPY_CODE
        val target = RuntimeSettingsGroups.target(SmsSettingsKeys.PACKAGE)
        assertTrue(key in RuntimeSettingsGroups.strictBooleanKeys)
        assertTrue(RuntimeSettingsGroups.SMS in RuntimeSettingsGroups.forProcess(target))
        assertFalse(RuntimeSettingsGroups.SMS in RuntimeSettingsGroups.forProcess("com.android.systemui"))
        val ledger = RuntimeSettingsLedger()
        for ((revision, values) in listOf(1L to mapOf(key to true), 2L to mapOf(key to false),
            3L to emptyMap<String, Boolean>())) {
            assertEquals(values, RuntimeSettingsGroups.subset(target, values + ("foreign" to true)))
            val snapshot = RuntimeSettingsSnapshot.read(revision, values, setOf(RuntimeSettingsGroups.SMS))!!
            assertTrue(ledger.accept(snapshot))
            assertEquals(values[key], ledger.value(key))
        }
        val restored = RuntimeSettingsLedger().also { it.restore(ledger.save()) }
        assertNull(restored.value(key))
    }
}
