package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.settings.contract.MobileTypeTypography
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.hook.base.RuntimeSettingsGroups as Groups
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsLedger
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSnapshot
import org.junit.Assert.*
import org.junit.Test

class MobileTypeTypographyTest {
    @Test fun sizeAndWeightAreIndependentAndInvalidImportsAreBounded() {
        assertEquals(1f, MobileTypeTypography(false, 150f, 900).scale)
        assertEquals(.5f, MobileTypeTypography(true, 20f, 900).scale)
        assertEquals(1.5f, MobileTypeTypography(true, 200f, 100).scale)
        assertEquals(MobileTypeTypography(true, 100f, 100), MobileTypeTypography(true, Float.NaN, 0).safe())
        assertEquals(900, MobileTypeTypography(true, 100f, 10000).safe().weight)
    }
    @Test fun enableDisableResetAndUpgradeSnapshotsReachSystemUi() {
        val enabled = IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY
        val config = mapOf<String, Any>(enabled to true,
            IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT to 75f, IconSettingsKeys.KEY_ICON_TYPE_WEIGHT to 200)
        assertEquals(config, Groups.subset("com.android.systemui", config))
        config.keys.forEach { assertTrue(Groups.ICONS in Groups.affected(it)) }
        val old = RuntimeSettingsLedger()
        assertTrue(old.accept(RuntimeSettingsSnapshot.read(1, config, setOf(Groups.ICONS))!!))
        val upgraded = RuntimeSettingsLedger().also { it.restore(old.save()) }
        assertEquals(200, upgraded.value(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT))
        assertTrue(upgraded.accept(RuntimeSettingsSnapshot.read(2, config + (enabled to false), setOf(Groups.ICONS))!!))
        assertEquals(false, upgraded.value(enabled))
        assertTrue(upgraded.accept(RuntimeSettingsSnapshot.read(3, emptyMap<String, Any>(), setOf(Groups.ICONS))!!))
        assertNull(upgraded.value(enabled))
    }
}
