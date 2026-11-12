package com.takekazex.hypertweak.settings.contract

import com.takekazex.hypertweak.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconConfigMigrationTest {
    private fun decode(vararg stored: Pair<String, Any>): IconConfig = IconConfigDecoder.decode(mapOf(*stored)::get)

    @Test
    fun retiredStyleSwitchesSelectTheSignalStyleAndDuoWins() {
        assertEquals(SignalStyle.STACKED, decode(LEGACY_STACKED to true).signalStyle)
        assertEquals(SignalStyle.STACKED, decode(IconSettingsKeys.KEY_ICON_STACKED_ENABLED to true).signalStyle)
        assertEquals(
            SignalStyle.DUO,
            decode(IconSettingsKeys.KEY_ICON_DUO_ENABLED to true, LEGACY_STACKED to true).signalStyle
        )
    }

    @Test
    fun anExplicitCurrentValueWinsEvenWhenItIsTheDefault() {
        val config = decode(
            IconSettingsKeys.KEY_ICON_SIGNAL_STYLE to SignalStyle.NORMAL.ordinal,
            IconSettingsKeys.KEY_ICON_DUO_ENABLED to true,
            IconSettingsKeys.KEY_ICON_HIDE_PRIVACY to false,
            IconConfigMigration.LEGACY_HIDE_PRIVACY to true,
            SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE to 0,
            SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_LEFT to true
        )
        assertEquals(SignalStyle.NORMAL, config.signalStyle)
        assertEquals(false, config.slots.hidePrivacy)
        assertEquals(0, config.controlCenter.batteryPercentStyle)
    }

    @Test
    fun retiredBooleansMoveToTheirCurrentKeys() {
        val config = decode(
            IconConfigMigration.LEGACY_IGNORE_SYS_HIDE to true,
            IconConfigMigration.LEGACY_HIDE_PRIVACY to true,
            IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO to true,
            IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI to false,
            "icon_tuner_left_zen" to true
        )
        assertEquals(true, config.slots.ignoreSysHide)
        assertEquals(true, config.slots.hidePrivacy)
        assertEquals(true, config.network.hideNonDefaultSim)
        assertEquals(true, config.value(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI))
        assertEquals(true, config.slots.leftZen)
    }

    @Test
    fun retiredSwitchesBecomeDropdownValues() {
        val config = decode(
            IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP to true,
            IconSettingsKeys.KEY_ICON_HIDE_WIFI_TYPE to true,
            SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_OUTSIDE to true,
            IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM to true,
            IconConfigMigration.LEGACY_STACKED_SVG_STYLE to 3,
            IconSettingsPreferencePolicy.LEGACY_LEFT_CONTAINER to true
        )
        assertEquals(IconSettingsPreferencePolicy.TYPE_ORDER_RIGHT, config.value(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER))
        assertEquals(1, config.value(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE))
        assertEquals(CcBatteryPercentStyle.OUTSIDE_TRAILING.ordinal, config.controlCenter.batteryPercentStyle)
        assertEquals(2, config.cellular.cellularRoamMode)
        assertEquals(SignalSvgStyle.IOS27.storedValue, config.value(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE))
        assertEquals(IconTunerContract.LEFT_MODE_HOME, config.slots.int(IconTunerContract.KEY_LEFT_MODE))
        assertEquals(
            CcBatteryPercentStyle.OUTSIDE_LEADING.ordinal,
            decode(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_LEFT to true).controlCenter.batteryPercentStyle
        )
        assertEquals(3, decode(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL to true).cellular.cellularRoamMode)
    }

    @Test
    fun slotModesReadOlderHostNamesAndTheRetiredHiddenList() {
        val config = decode(
            IconSettingsPreferencePolicy.slotKey("location") to IconSlotMode.HIDE_EVERYWHERE.value,
            IconSettingsPreferencePolicy.slotKey("wifi") to IconSlotMode.STATUS_BAR_ONLY.value,
            IconSettingsPreferencePolicy.LEGACY_EXTRA_BLOCKED to "mute"
        )
        assertEquals(IconSlotMode.HIDE_EVERYWHERE, IconSlotPolicy.modeFor("gps", config.slots.slotModes))
        assertEquals(IconSlotMode.STATUS_BAR_ONLY, IconSlotPolicy.modeFor("demo_wifi", config.slots.slotModes))
        assertEquals(IconSlotMode.HIDE_EVERYWHERE, IconSlotPolicy.modeFor("mute", config.slots.slotModes))

        val explicit = decode(
            IconSettingsPreferencePolicy.slotKey("gps") to IconSlotMode.FOLLOW_SYSTEM.value,
            IconSettingsPreferencePolicy.slotKey("location") to IconSlotMode.HIDE_EVERYWHERE.value
        )
        assertEquals(IconSlotMode.FOLLOW_SYSTEM, IconSlotPolicy.modeFor("gps", explicit.slots.slotModes))
    }

    @Test
    fun retiredKeysStopMigratingAfterTheAgreedRelease() {
        fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val current = parts(BuildConfig.VERSION_NAME)
        val last = parts(IconConfigMigration.LAST_MIGRATING_VERSION)
        val order = current.zip(last).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
        assertTrue(
            "Version ${BuildConfig.VERSION_NAME} is past ${IconConfigMigration.LAST_MIGRATING_VERSION}: " +
                "delete IconConfigMigration and these tests",
            order <= 0
        )
    }

    private companion object {
        const val LEGACY_STACKED = IconConfigMigration.LEGACY_STACKED_ENABLED
    }
}
