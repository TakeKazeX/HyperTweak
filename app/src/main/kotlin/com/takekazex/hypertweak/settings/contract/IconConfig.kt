package com.takekazex.hypertweak.settings.contract

import java.util.Collections

/** The selected replacement for the host signal icons. */
enum class SignalStyle { NORMAL, STACKED, DUO }

data class SlotsIconConfig internal constructor(val values: Map<String, Any>, val explicitSlotKeys: Set<String>) {
    fun value(key: String): Any? = values[key]
    fun boolean(key: String): Boolean = values[key] as Boolean
    fun int(key: String): Int = values[key] as Int
    fun float(key: String): Float = values[key] as Float
    fun string(key: String): String = values[key] as String
    val leftZen: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_ZEN) as Boolean
    val leftVolume: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_VOLUME) as Boolean
    val leftHotspot: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_HOTSPOT) as Boolean
    val leftAlarmClock: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_ALARM_CLOCK) as Boolean
    val leftLocation: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_LOCATION) as Boolean
    val leftBluetooth: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_BLUETOOTH) as Boolean
    val leftNfc: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_NFC) as Boolean
    val leftVpn: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_VPN) as Boolean
    val leftAirplane: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE) as Boolean
    val leftHeadset: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_HEADSET) as Boolean
    val leftRotationLock: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_LEFT_ROTATION_LOCK) as Boolean
    val extBlocked: String get() = values.getValue(IconSettingsKeys.KEY_ICON_EXT_BLOCKED) as String
    val ignoreSysHide: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE) as Boolean
    val hidePrivacy: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_PRIVACY) as Boolean
    val position: Int get() = values.getValue(IconTunerContract.KEY_POSITION) as Int
    val positionValues: Set<String> get() = values.getValue(IconTunerContract.KEY_POSITION_VALUES) as Set<String>
    val positionReorder: Boolean get() = values.getValue(IconTunerContract.KEY_POSITION_REORDER) as Boolean

    /** Explicit per-slot modes with the extra-hidden list folded in; unset slots keep their default. */
    val slotModes: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        IconSettingsPreferencePolicy.slotModes(
            read = { slot ->
                IconSettingsPreferencePolicy.slotKey(slot).takeIf { it in explicitSlotKeys }?.let { values[it] as? Int }
            },
            extraHidden = IconSlotPolicy.parseSlotList(extBlocked)
        )
    }
    val leftMode: Int get() = values.getValue(IconTunerContract.KEY_LEFT_MODE) as Int
}

data class CellularIconConfig internal constructor(val values: Map<String, Any>) {
    fun value(key: String): Any? = values[key]
    fun boolean(key: String): Boolean = values[key] as Boolean
    fun int(key: String): Int = values[key] as Int
    fun float(key: String): Float = values[key] as Float
    fun string(key: String): String = values[key] as String
    val hideCellularActivity: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY) as Boolean
    val hideCellularType: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE) as Boolean
    val cellularRoamMode: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE) as Int
    val hideCellularVowifi: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOWIFI) as Boolean
    val hideCellularVolte: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE) as Boolean
    val hideCellularVolteNoService: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE) as Boolean
    val hideCellularSpeechHd: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SPEECH_HD) as Boolean
    val cellularTypeSingle: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE) as Boolean
    val cellularTypeOrder: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER) as Int
    val cellularTypeCustom: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM) as Boolean
    val cellularTypeCustomVal: String get() = values.getValue(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL) as String
    val typeTypography: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY) as Boolean
    val typeSizePercent: Float get() = values.getValue(IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT) as Float
    val typeWeight: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT) as Int
    val cellularTypeSmall5ga: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA) as Boolean
    val stackedTypeHideDisconnect: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT) as Boolean
    val cellularTypeKeepOnWifi: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI) as Boolean
}

data class WifiIconConfig internal constructor(val values: Map<String, Any>) {
    fun value(key: String): Any? = values[key]
    fun boolean(key: String): Boolean = values[key] as Boolean
    fun int(key: String): Int = values[key] as Int
    fun float(key: String): Float = values[key] as Float
    fun string(key: String): String = values[key] as String
    val hideWifiActivity: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY) as Boolean
    val hideWifiUnavailable: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE) as Boolean
    val wifiActivityRight: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_WIFI_ACTIVITY_RIGHT) as Boolean
    val wifiStandardMode: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE) as Int
    val wifiStandardMap: String get() = values.getValue(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MAP) as String
    val wifiPadding: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_WIFI_PADDING) as Boolean
    val wifiPaddingStartVal: Float get() = values.getValue(IconSettingsKeys.KEY_ICON_WIFI_PADDING_START_VAL) as Float
    val wifiPaddingEndVal: Float get() = values.getValue(IconSettingsKeys.KEY_ICON_WIFI_PADDING_END_VAL) as Float
}

data class NetworkIconConfig internal constructor(val values: Map<String, Any>) {
    fun value(key: String): Any? = values[key]
    fun boolean(key: String): Boolean = values[key] as Boolean
    fun int(key: String): Int = values[key] as Int
    fun float(key: String): Float = values[key] as Float
    fun string(key: String): String = values[key] as String
    val hideNonDefaultSim: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM) as Boolean
    val hideSimOne: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE) as Boolean
    val hideSimTwo: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO) as Boolean
    val hideMobileOnWifi: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI) as Boolean
}

data class StackedIconConfig internal constructor(val values: Map<String, Any>) {
    fun value(key: String): Any? = values[key]
    fun boolean(key: String): Boolean = values[key] as Boolean
    fun int(key: String): Int = values[key] as Int
    fun float(key: String): Float = values[key] as Float
    fun string(key: String): String = values[key] as String
    val mobileRowStyle: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE) as Int
    val stackedSignalStyle: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE) as Int
    val stackedWeight: Float get() = values.getValue(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT) as Float
}

data class DuoIconConfig internal constructor(val values: Map<String, Any>) {
    fun value(key: String): Any? = values[key]
    fun boolean(key: String): Boolean = values[key] as Boolean
    fun int(key: String): Int = values[key] as Int
    fun float(key: String): Float = values[key] as Float
    fun string(key: String): String = values[key] as String
    val duoTopPercent: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT) as Boolean
    val duoCellularDots: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_DOTS) as Boolean
    val duoDualDots: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_DUAL_DOTS) as Boolean
    val duoClickExpand: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_CLICK_EXPAND) as Boolean
    val duoExpanded: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_EXPANDED) as Int
    val duoSize: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_SIZE) as Int
    val duoRingScale: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_RING_SCALE) as Int
    val duoWifiScale: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE) as Int
    val duoCellularScale: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE) as Int
    val duoTypeScale: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE) as Int
    val duoDotsScale: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE) as Int
    val duoAirplaneScale: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE) as Int
    val duoBatteryScale: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE) as Int
    val duoPercentScale: Int get() = values.getValue(IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE) as Int
}

data class ControlCenterIconConfig internal constructor(val values: Map<String, Any>) {
    fun value(key: String): Any? = values[key]
    fun boolean(key: String): Boolean = values[key] as Boolean
    fun int(key: String): Int = values[key] as Int
    fun float(key: String): Float = values[key] as Float
    fun string(key: String): String = values[key] as String
    val hideCarrierOne: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_ONE) as Boolean
    val hideCarrierTwo: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO) as Boolean
    val hideCarrierHd: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD) as Boolean
    val hideLsCarrierOne: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE) as Boolean
    val hideLsCarrierTwo: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_TWO) as Boolean
    val carrierLeft: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_CARRIER_LEFT) as Boolean
    val hideDate: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_HIDE_DATE) as Boolean
    val networkFirstRow: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_NETWORK_FIRST_ROW) as Boolean
    val carrierTwoLine: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE) as Boolean
    val iconRowOverrides: Set<String> get() = values.getValue(SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES) as Set<String>
    val carrierShowNonDataType: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE) as Boolean
    val carrierShowBadge: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_BADGE) as Boolean
    val carrierBadgeOne: String get() = values.getValue(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_ONE) as String
    val carrierBadgeTwo: String get() = values.getValue(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_TWO) as String
    val privacyEnabled: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_ENABLED) as Boolean
    val privacyCenter: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_CENTER) as Boolean
    val privacyBackground: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_BACKGROUND) as Boolean
    val privacyColored: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_COLORED) as Boolean
    val privacyNames: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_NAMES) as Boolean
    val privacyAllApps: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_ALL_APPS) as Boolean
    val privacyArrow: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_ARROW) as Boolean
    val privacyDot: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_DOT) as Boolean
    val privacyIconSize: Int get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_ICON_SIZE) as Int
    val privacyTextSize: Int get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_TEXT_SIZE) as Int
    val privacyOffset: Int get() = values.getValue(SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET) as Int
    val batteryPercentStyle: Int get() = values.getValue(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE) as Int
    val rotationLockIcon: Boolean get() = values.getValue(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON) as Boolean
}

data class MiscIconConfig internal constructor(val values: Map<String, Any>) {
    fun value(key: String): Any? = values[key]
    fun boolean(key: String): Boolean = values[key] as Boolean
    fun int(key: String): Int = values[key] as Int
    fun float(key: String): Float = values[key] as Float
    fun string(key: String): String = values[key] as String
    val mirrorAirplane: Boolean get() = values.getValue(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE) as Boolean
    val regionSampling: Int get() = values.getValue(PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING) as Int
    val notificationMax: Boolean get() = values.getValue(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_MAX) as Boolean
    val notificationIconMax: Int get() = values.getValue(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_ICON_MAX) as Int
}
data class IconConfig internal constructor(
    val signalStyle: SignalStyle,
    val slots: SlotsIconConfig,
    val cellular: CellularIconConfig,
    val wifi: WifiIconConfig,
    val network: NetworkIconConfig,
    val stacked: StackedIconConfig,
    val duo: DuoIconConfig,
    val controlCenter: ControlCenterIconConfig,
    val misc: MiscIconConfig,
) {
    val separateCellularType: Boolean get() =
        signalStyle == SignalStyle.STACKED || cellular.boolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE)
    val keepCellularTypeOnWifi: Boolean get() = cellular.boolean(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI)
    val typeHiddenWhenDisconnected: Boolean get() = cellular.boolean(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT)

    fun boolean(key: String): Boolean = value(key) as Boolean
    fun int(key: String): Int = value(key) as Int
    fun float(key: String): Float = value(key) as Float
    fun string(key: String): String = value(key) as String
    @Suppress("UNCHECKED_CAST")
    fun stringSet(key: String): Set<String> = value(key) as Set<String>

    fun value(key: String): Any? = when (key) {
        IconSettingsKeys.KEY_ICON_SIGNAL_STYLE -> signalStyle.ordinal
        IconSettingsKeys.KEY_ICON_STACKED_ENABLED -> signalStyle == SignalStyle.STACKED
        IconSettingsKeys.KEY_ICON_DUO_ENABLED -> signalStyle == SignalStyle.DUO
        else -> slots.value(key) ?: cellular.value(key) ?: wifi.value(key) ?: network.value(key) ?:
            stacked.value(key) ?: duo.value(key) ?: controlCenter.value(key) ?: misc.value(key)
    }
}

/** Reload effects are declared against decoded sections, so retired keys have no effect. */
internal object IconConfigReload {
    fun domains(before: IconConfig?, after: IconConfig?): Set<IndependentIconDomain>? {
        if (before == null || after == null || before.signalStyle != after.signalStyle) return null
        val effects = mutableSetOf<IndependentIconDomain>()
        if (before.slots != after.slots) {
            // Network slots and ordering affect installed pipelines and the native icon groups.
            val changed = (before.slots.values.keys + after.slots.values.keys).filter {
                before.slots.value(it) != after.slots.value(it) ||
                    (it in before.slots.explicitSlotKeys) != (it in after.slots.explicitSlotKeys)
            }
            val networkSlots = IconSlotPolicy.SIGNAL_SLOTS + listOf(
                IconSlotPolicy.SLOT_MOBILE, "demo_mobile", "stacked_mobile", "no_sim",
                IconSlotPolicy.SLOT_WIFI, IconSlotPolicy.SLOT_DEMO_WIFI, "network_speed", "net_speed"
            )
            if (changed.any { it == IconSettingsKeys.KEY_ICON_EXT_BLOCKED ||
                it == IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE || it == IconSettingsKeys.KEY_ICON_HIDE_PRIVACY ||
                it == IconTunerContract.KEY_POSITION || it == IconTunerContract.KEY_POSITION_VALUES ||
                it == IconTunerContract.KEY_POSITION_REORDER ||
                it.startsWith(IconSettingsPreferencePolicy.SLOT_KEY_PREFIX) &&
                it.removePrefix(IconSettingsPreferencePolicy.SLOT_KEY_PREFIX) in networkSlots } ||
                after.slots.boolean(IconTunerContract.KEY_POSITION_REORDER)) return null
            effects += IndependentIconDomain.POLICY
        }
        val oldCell = before.cellular
        val newCell = after.cellular
        if (oldCell.cellularTypeSingle != newCell.cellularTypeSingle) return null
        if (oldCell.hideCellularActivity != newCell.hideCellularActivity || oldCell.hideCellularType != newCell.hideCellularType ||
            oldCell.cellularRoamMode != newCell.cellularRoamMode || oldCell.hideCellularVowifi != newCell.hideCellularVowifi ||
            oldCell.hideCellularVolte != newCell.hideCellularVolte || oldCell.hideCellularVolteNoService != newCell.hideCellularVolteNoService ||
            oldCell.hideCellularSpeechHd != newCell.hideCellularSpeechHd) effects += IndependentIconDomain.CELLULAR
        val typeVisibilityChanged = oldCell.cellularTypeKeepOnWifi != newCell.cellularTypeKeepOnWifi ||
            oldCell.stackedTypeHideDisconnect != newCell.stackedTypeHideDisconnect
        if (typeVisibilityChanged) {
            if (after.separateCellularType) return null
            effects += setOf(IndependentIconDomain.DUO, IndependentIconDomain.CONTROL_CENTER_HEADER)
        }
        if (oldCell.cellularTypeOrder != newCell.cellularTypeOrder || oldCell.cellularTypeCustom != newCell.cellularTypeCustom ||
            oldCell.cellularTypeCustomVal != newCell.cellularTypeCustomVal) effects += IndependentIconDomain.CELLULAR_TYPE
        if (oldCell.cellularTypeSmall5ga != newCell.cellularTypeSmall5ga || oldCell.typeTypography != newCell.typeTypography ||
            oldCell.typeSizePercent != newCell.typeSizePercent || oldCell.typeWeight != newCell.typeWeight)
            effects += setOf(IndependentIconDomain.CELLULAR_TYPE, IndependentIconDomain.DUO, IndependentIconDomain.CONTROL_CENTER_HEADER)
        if (before.wifi != after.wifi) {
            effects += IndependentIconDomain.WIFI
            if (before.wifi.hideWifiUnavailable != after.wifi.hideWifiUnavailable)
                effects += setOf(IndependentIconDomain.DUO, IndependentIconDomain.CONTROL_CENTER_HEADER)
        }
        if (before.network != after.network) effects += setOf(IndependentIconDomain.MOBILE_SIGNAL,
            IndependentIconDomain.DUO, IndependentIconDomain.CONTROL_CENTER_HEADER)
        if (before.stacked != after.stacked) {
            if (before.stacked.int(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE) !=
                after.stacked.int(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE)) return null
            effects += setOf(IndependentIconDomain.MOBILE_SIGNAL, IndependentIconDomain.DUO,
                IndependentIconDomain.CONTROL_CENTER_HEADER)
        }
        if (before.duo != after.duo) effects += IndependentIconDomain.DUO
        val oldCc = before.controlCenter
        val newCc = after.controlCenter
        if (oldCc.carrierLeft != newCc.carrierLeft || oldCc.networkFirstRow != newCc.networkFirstRow ||
            oldCc.carrierShowNonDataType != newCc.carrierShowNonDataType ||
            oldCc.carrierShowBadge != newCc.carrierShowBadge || oldCc.carrierBadgeOne != newCc.carrierBadgeOne ||
            oldCc.carrierBadgeTwo != newCc.carrierBadgeTwo || oldCc.hideCarrierOne != newCc.hideCarrierOne ||
            oldCc.hideCarrierTwo != newCc.hideCarrierTwo || oldCc.hideCarrierHd != newCc.hideCarrierHd ||
            oldCc.hideLsCarrierOne != newCc.hideLsCarrierOne || oldCc.hideLsCarrierTwo != newCc.hideLsCarrierTwo)
            effects += setOf(IndependentIconDomain.CONTROL_CENTER_HEADER, IndependentIconDomain.DUO)
        if (oldCc.hideDate != newCc.hideDate || oldCc.carrierTwoLine != newCc.carrierTwoLine)
            effects += setOf(IndependentIconDomain.CONTROL_CENTER_HEADER, IndependentIconDomain.DUO, IndependentIconDomain.POLICY)
        if (oldCc.iconRowOverrides != newCc.iconRowOverrides) effects += IndependentIconDomain.POLICY
        if (oldCc.privacyEnabled != newCc.privacyEnabled || oldCc.privacyCenter != newCc.privacyCenter ||
            oldCc.privacyOffset != newCc.privacyOffset) effects += IndependentIconDomain.CONTROL_CENTER_HEADER
        if (oldCc.batteryPercentStyle != newCc.batteryPercentStyle)
            effects += setOf(IndependentIconDomain.CC_BATTERY, IndependentIconDomain.DUO)
        if (oldCc.rotationLockIcon != newCc.rotationLockIcon)
            effects += setOf(IndependentIconDomain.ROTATION_LOCK, IndependentIconDomain.POLICY)
        if (after.slots.positionReorder && (oldCc.hideDate != newCc.hideDate ||
                oldCc.carrierTwoLine != newCc.carrierTwoLine || oldCc.rotationLockIcon != newCc.rotationLockIcon)) return null
        fun miscChanged(key: String) = before.misc.value(key) != after.misc.value(key)
        if (miscChanged(PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING)) effects += IndependentIconDomain.SAMPLING
        if (miscChanged(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_MAX) ||
            miscChanged(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_ICON_MAX)) effects += IndependentIconDomain.NOTIFICATIONS
        if (miscChanged(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE)) effects += setOf(
            IndependentIconDomain.AIRPLANE, IndependentIconDomain.DUO)
        return effects
    }
}

object IconConfigDecoder {
    private val defaults: Map<String, Any> = buildMap {
        put(IconSettingsKeys.KEY_ICON_LEFT_ZEN, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_VOLUME, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_HOTSPOT, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_ALARM_CLOCK, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_LOCATION, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_BLUETOOTH, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_NFC, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_VPN, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_HEADSET, false)
        put(IconSettingsKeys.KEY_ICON_LEFT_ROTATION_LOCK, false)
        put(IconSettingsKeys.KEY_ICON_EXT_BLOCKED, "")
        put(IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_PRIVACY, false)
        put(IconTunerContract.KEY_POSITION, 0)
        put(IconTunerContract.KEY_POSITION_VALUES, emptySet<String>())
        put(IconTunerContract.KEY_POSITION_REORDER, false)
        put(IconTunerContract.KEY_LEFT_MODE, 0)
        put(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE, false)
        put(IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE, 0)
        put(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOWIFI, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, false)
        put(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE, false)
        put(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER, 0)
        put(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM, false)
        put(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, "")
        put(IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY, false)
        put(IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT, 100f)
        put(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT, 400)
        put(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, false)
        put(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, false)
        put(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false)
        put(IconSettingsKeys.KEY_ICON_WIFI_ACTIVITY_RIGHT, false)
        put(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE, 0)
        put(IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MAP, "4,5,6,7,8")
        put(IconSettingsKeys.KEY_ICON_WIFI_PADDING, false)
        put(IconSettingsKeys.KEY_ICON_WIFI_PADDING_START_VAL, 0f)
        put(IconSettingsKeys.KEY_ICON_WIFI_PADDING_END_VAL, 0f)
        put(IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI, false)
        put(IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE, 0)
        put(IconSettingsKeys.KEY_ICON_STACKED_WEIGHT, 0f)
        put(IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT, false)
        put(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_DOTS, false)
        put(IconSettingsKeys.KEY_ICON_DUO_DUAL_DOTS, false)
        put(IconSettingsKeys.KEY_ICON_DUO_CLICK_EXPAND, true)
        put(IconSettingsKeys.KEY_ICON_DUO_EXPANDED, 1)
        put(IconSettingsKeys.KEY_ICON_DUO_SIZE, 26)
        put(IconSettingsKeys.KEY_ICON_DUO_RING_SCALE, 100)
        put(IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE, 100)
        put(IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE, 100)
        put(IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE, 100)
        put(IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE, 100)
        put(IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE, 100)
        put(IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE, 100)
        put(IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE, 100)
        put(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_ONE, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_TWO, false)
        put(SystemUiSettingsKeys.KEY_CC_CARRIER_LEFT, false)
        put(SystemUiSettingsKeys.KEY_CC_HIDE_DATE, false)
        put(SystemUiSettingsKeys.KEY_CC_NETWORK_FIRST_ROW, false)
        put(SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE, false)
        put(SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES, emptySet<String>())
        put(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE, false)
        put(SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_BADGE, true)
        put(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_ONE, "1")
        put(SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_TWO, "2")
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_ENABLED, false)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_CENTER, true)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_BACKGROUND, false)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_COLORED, true)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_NAMES, true)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_ALL_APPS, true)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_ARROW, true)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_DOT, false)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_ICON_SIZE, ControlCenterPrivacyPolicy.DEFAULT_ICON_SIZE)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_TEXT_SIZE, ControlCenterPrivacyPolicy.DEFAULT_TEXT_SIZE)
        put(SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET, ControlCenterPrivacyPolicy.DEFAULT_OFFSET)
        put(SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE, 0)
        put(SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON, false)
        put(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM, false)
        put(IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE, false)
        put(IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE, 0)
        put(PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING, 0)
        put(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_MAX, false)
        put(PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_ICON_MAX, 3)
        put(IconSettingsKeys.KEY_ICON_SIGNAL_STYLE, 0)
        IconSlotPolicy.configurableSlots.forEach { put(IconSettingsPreferencePolicy.slotKey(it), 0) }
    }
    fun defaultValue(key: String): Any? = defaults[key]
    fun decode(raw: (String) -> Any?): IconConfig {
        val read = IconConfigMigration.reader(raw)
        val explicitSlotKeys = mutableSetOf<String>()
        fun section(keys: Set<String>): Map<String, Any> = Collections.unmodifiableMap(keys.associateWith { key ->
            val fallback = defaults.getValue(key)
            val value = read(key)
            if (key.startsWith(IconSettingsPreferencePolicy.SLOT_KEY_PREFIX) && value is Int) explicitSlotKeys += key
            if (value != null && (fallback is Set<*> && value is Set<*> || fallback !is Set<*> && fallback::class == value::class)) {
                when {
                    value is Set<*> -> Collections.unmodifiableSet(value.filterIsInstance<String>().toSet())
                    key == IconTunerContract.KEY_LEFT_MODE -> (value as Int).coerceIn(0, 2)
                    key == IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE ||
                        key == IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE -> (value as Int).coerceIn(0, 3)
                    key == IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE -> SignalSvgStyle.fromStored(value as Int).storedValue
                    key == IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER -> (value as Int).takeIf { it in 0..3 } ?: 0
                    key == SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE -> (value as Int).takeIf { it in 0..2 } ?: 0
                    else -> value
                }
            } else if (fallback is Set<*>) Collections.unmodifiableSet(fallback.filterIsInstance<String>().toSet()) else fallback
        })
        val style = SignalStyle.entries.getOrNull(read(IconSettingsKeys.KEY_ICON_SIGNAL_STYLE) as? Int ?: 0) ?: SignalStyle.NORMAL
        return IconConfig(style,
            SlotsIconConfig(section(slotsKeys), Collections.unmodifiableSet(explicitSlotKeys.toSet())),
            CellularIconConfig(section(cellularKeys)),
            WifiIconConfig(section(wifiKeys)),
            NetworkIconConfig(section(networkKeys)),
            StackedIconConfig(section(stackedKeys)),
            DuoIconConfig(section(duoKeys)),
            ControlCenterIconConfig(section(controlCenterKeys)),
            MiscIconConfig(section(miscKeys)),
        )
    }
    private val slotsKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_LEFT_ZEN,
        IconSettingsKeys.KEY_ICON_LEFT_VOLUME,
        IconSettingsKeys.KEY_ICON_LEFT_HOTSPOT,
        IconSettingsKeys.KEY_ICON_LEFT_ALARM_CLOCK,
        IconSettingsKeys.KEY_ICON_LEFT_LOCATION,
        IconSettingsKeys.KEY_ICON_LEFT_BLUETOOTH,
        IconSettingsKeys.KEY_ICON_LEFT_NFC,
        IconSettingsKeys.KEY_ICON_LEFT_VPN,
        IconSettingsKeys.KEY_ICON_LEFT_AIRPLANE,
        IconSettingsKeys.KEY_ICON_LEFT_HEADSET,
        IconSettingsKeys.KEY_ICON_LEFT_ROTATION_LOCK,
        IconSettingsKeys.KEY_ICON_EXT_BLOCKED,
        IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE,
        IconSettingsKeys.KEY_ICON_HIDE_PRIVACY,
        IconTunerContract.KEY_POSITION,
        IconTunerContract.KEY_POSITION_VALUES,
        IconTunerContract.KEY_POSITION_REORDER,
        IconTunerContract.KEY_LEFT_MODE,
    ) + IconSlotPolicy.configurableSlots.map(IconSettingsPreferencePolicy::slotKey)
    private val cellularKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY,
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE,
        IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE,
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOWIFI,
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE,
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE,
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SPEECH_HD,
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE,
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER,
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM,
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL,
        IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY,
        IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT,
        IconSettingsKeys.KEY_ICON_TYPE_WEIGHT,
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA,
        IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT,
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI,
    )
    private val wifiKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY,
        IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE,
        IconSettingsKeys.KEY_ICON_WIFI_ACTIVITY_RIGHT,
        IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE,
        IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MAP,
        IconSettingsKeys.KEY_ICON_WIFI_PADDING,
        IconSettingsKeys.KEY_ICON_WIFI_PADDING_START_VAL,
        IconSettingsKeys.KEY_ICON_WIFI_PADDING_END_VAL,
    )
    private val networkKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM,
        IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE,
        IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO,
        IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI,
    )
    private val stackedKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE,
        IconSettingsKeys.KEY_ICON_STACKED_SIGNAL_STYLE,
        IconSettingsKeys.KEY_ICON_STACKED_WEIGHT,
    )
    private val duoKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT,
        IconSettingsKeys.KEY_ICON_DUO_CELLULAR_DOTS,
        IconSettingsKeys.KEY_ICON_DUO_DUAL_DOTS,
        IconSettingsKeys.KEY_ICON_DUO_CLICK_EXPAND,
        IconSettingsKeys.KEY_ICON_DUO_EXPANDED,
        IconSettingsKeys.KEY_ICON_DUO_SIZE,
        IconSettingsKeys.KEY_ICON_DUO_RING_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE,
    )
    private val controlCenterKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_HIDE_CARRIER_ONE,
        IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO,
        IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD,
        IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE,
        IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_TWO,
        SystemUiSettingsKeys.KEY_CC_CARRIER_LEFT,
        SystemUiSettingsKeys.KEY_CC_HIDE_DATE,
        SystemUiSettingsKeys.KEY_CC_NETWORK_FIRST_ROW,
        SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE,
        SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES,
        SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE,
        SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_BADGE,
        SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_ONE,
        SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_TWO,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_ENABLED,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_CENTER,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_BACKGROUND,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_COLORED,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_NAMES,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_ALL_APPS,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_ARROW,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_DOT,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_ICON_SIZE,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_TEXT_SIZE,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET,
        SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE,
        SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON,
    )
    private val miscKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE,
        PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING,
        PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_MAX,
        PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_ICON_MAX,
    )
}
