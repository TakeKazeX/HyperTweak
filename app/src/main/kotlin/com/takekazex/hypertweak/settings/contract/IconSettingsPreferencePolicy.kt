package com.takekazex.hypertweak.settings.contract

internal object IconSettingsPreferencePolicy {
    const val TYPE_ORDER_DEFAULT = 0
    const val TYPE_ORDER_SWAP = 1
    const val TYPE_ORDER_RIGHT = 2
    const val TYPE_ORDER_LEFT = 3

    fun cellularTypeOrder(explicit: Int?, legacySwap: Boolean): Int =
        explicit?.takeIf { it in TYPE_ORDER_DEFAULT..TYPE_ORDER_LEFT }
            ?: if (legacySwap) TYPE_ORDER_RIGHT else TYPE_ORDER_DEFAULT

    fun keepCellularTypeOnWifi(read: (String) -> Boolean?): Boolean =
        read(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI)
            ?: legacyStackedTypeKeepsOnWifi(read)

    fun legacyStackedTypeKeepsOnWifi(read: (String) -> Boolean?): Boolean =
        read(IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI) == false

    fun hideNonDefaultSim(read: (String) -> Boolean?): Boolean =
        read(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM)
            ?: (read(IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO) == true)

    fun iconBoolean(key: String, read: (String) -> Boolean?): Boolean {
        val legacy = when (key) {
            IconSettingsKeys.KEY_ICON_STACKED_ENABLED -> "icon_tuner_stacked_enabled"
            IconSettingsKeys.KEY_ICON_IGNORE_SYS_HIDE -> "statusbar_ignore_sys_hide"
            IconSettingsKeys.KEY_ICON_HIDE_PRIVACY -> "icon_tuner_hide_privacy"
            IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED -> LEGACY_LEFT_CONTAINER
            else -> IconTunerContract.leftPreferenceSlots[key]
        }
        return read(key) ?: legacy?.let(read) ?: false
    }

    fun extraHiddenSlots(read: (String) -> String?): List<String> =
        IconSlotPolicy.parseSlotList(read(IconSettingsKeys.KEY_ICON_EXT_BLOCKED) ?: read(LEGACY_EXTRA_BLOCKED).orEmpty())

    const val SLOT_KEY_PREFIX = "icon_tuner_slot_"

    fun slotKey(slot: String): String = SLOT_KEY_PREFIX + slot

    const val LEGACY_EXTRA_BLOCKED = "icon_tuner_ext_blocked"
    const val LEGACY_LEFT_CONTAINER = "icon_tuner_left_container"

    fun slotModes(read: (String) -> Int?, extraHidden: Iterable<String>): Map<String, Int> {
        val modes = IconSlotPolicy.configurableSlots.mapNotNull { slot ->
            IconSlotPolicy.preferenceSlotAliases(slot).firstNotNullOfOrNull(read)?.let { slot to it }
        }.toMap()
        return IconSlotPolicy.foldExtraHiddenIntoModes(modes, extraHidden)
    }

    fun leftMode(mode: Int?, legacyEnabled: Boolean): Int =
        (mode ?: if (legacyEnabled) IconTunerContract.LEFT_MODE_HOME else IconTunerContract.LEFT_MODE_DISABLED)
            .coerceIn(IconTunerContract.LEFT_MODE_DISABLED, IconTunerContract.LEFT_MODE_HOME_AND_LOCKSCREEN)

    fun wifiStandardMode(mode: Int?, legacyHide: Boolean): Int =
        (mode ?: if (legacyHide) 1 else 0).coerceIn(0, 3)

    fun ccBatteryPercentStyle(mode: Int?, outside: Boolean, leading: Boolean): CcBatteryPercentStyle {
        if (mode != null) return CcBatteryPercentStyle.entries.getOrNull(mode) ?: CcBatteryPercentStyle.SYSTEM
        return when {
            leading -> CcBatteryPercentStyle.OUTSIDE_LEADING
            outside -> CcBatteryPercentStyle.OUTSIDE_TRAILING
            else -> CcBatteryPercentStyle.SYSTEM
        }
    }

    fun nativeRoamingMode(mode: Int?, regular: Boolean, small: Boolean, global: Boolean): Int =
        mode?.coerceIn(0, 3) ?: ((if (regular || global) 1 else 0) + (if (small || global) 2 else 0))

    /**
     * The owners a settings change can be applied to without the shared icon transaction.
     *
     * Null requires the full transaction: every icon hook is recreated and the native icon groups
     * are rebuilt, which redraws the whole status bar. An empty set means no stored value changed.
     * Every other domain is applied by its own owners only, and none of them rebuilds the groups.
     */
    fun independentReloadDomains(
        before: Map<String, Any>?, after: Map<String, Any>?
    ): Set<IndependentIconDomain>? {
        if (before == null || after == null) return null
        val changed = (before.keys + after.keys).filter { before[it] != after[it] }
        return changed.flatMapTo(mutableSetOf()) { key -> domainsFor(key, after) ?: return null }
    }

    /** Null when [key] needs the full icon transaction; [after] holds the committed values. */
    fun domainsFor(key: String, after: Map<String, Any>): Set<IndependentIconDomain>? {
        val reorder = after[IconTunerContract.KEY_POSITION_REORDER] == true
        val stacked = iconBoolean(IconSettingsKeys.KEY_ICON_STACKED_ENABLED) { after[it] as? Boolean }
        // A separate native type label installs its Wi-Fi visibility hook from these options.
        val separateType = after[IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE] == true
        val mirrors = setOf(IndependentIconDomain.DUO, IndependentIconDomain.CONTROL_CENTER_HEADER)
        val domains = when {
            key == PlatformSettingsKeys.KEY_STATUSBAR_REGION_SAMPLING -> setOf(IndependentIconDomain.SAMPLING)
            key == PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_MAX ||
                key == PlatformSettingsKeys.KEY_STATUSBAR_NOTIFICATION_ICON_MAX -> setOf(IndependentIconDomain.NOTIFICATIONS)
            isLivePolicyKey(key) -> setOf(IndependentIconDomain.POLICY)
            // The row split re-runs on the next layout pass and never moves a slot.
            key == SystemUiSettingsKeys.KEY_CC_ICON_ROW_OVERRIDES -> return setOf(IndependentIconDomain.POLICY)
            key in duoLiveKeys -> setOf(IndependentIconDomain.DUO)
            // The two-line header also changes which control-center slots are blocked.
            key == SystemUiSettingsKeys.KEY_CC_HIDE_DATE || key == SystemUiSettingsKeys.KEY_CC_CARRIER_TWO_LINE ->
                setOf(IndependentIconDomain.CONTROL_CENTER_HEADER, IndependentIconDomain.DUO, IndependentIconDomain.POLICY)
            key in headerKeys -> setOf(IndependentIconDomain.CONTROL_CENTER_HEADER, IndependentIconDomain.DUO)
            // The privacy hook owns its own runtime group; the header only places the chip.
            key in headerPrivacyKeys -> setOf(IndependentIconDomain.CONTROL_CENTER_HEADER)
            key.startsWith(PRIVACY_KEY_PREFIX) -> emptySet()
            // Retired appearance options and keys another runtime group owns; nothing here reads them.
            key in unreadKeys -> emptySet()
            key in ccBatteryKeys -> setOf(IndependentIconDomain.CC_BATTERY, IndependentIconDomain.DUO)
            key == SystemUiSettingsKeys.KEY_CC_ROTATION_LOCK_ICON ->
                setOf(IndependentIconDomain.ROTATION_LOCK, IndependentIconDomain.POLICY)
            key == IconSettingsKeys.KEY_ICON_MIRROR_AIRPLANE ->
                setOf(IndependentIconDomain.AIRPLANE, IndependentIconDomain.DUO)
            key in cellularGateKeys -> setOf(IndependentIconDomain.CELLULAR)
            // Duo and the carrier block mirror the hide-when-connected rule.
            key == IconSettingsKeys.KEY_ICON_HIDE_WIFI_UNAVAILABLE -> mirrors + IndependentIconDomain.WIFI
            key in wifiKeys -> setOf(IndependentIconDomain.WIFI)
            // The stacked replacement owns the rows while it is on; otherwise the native rows do.
            key in simVisibilityKeys -> mirrors +
                if (stacked) IndependentIconDomain.STACKED else IndependentIconDomain.SIM_VISIBILITY
            key == IconSettingsKeys.KEY_ICON_STACKED_TYPE_ROAMING -> setOf(IndependentIconDomain.STACKED)
            key == IconSettingsKeys.KEY_ICON_STACKED_WEIGHT || key == IconSettingsKeys.KEY_ICON_MOBILE_ROW_STYLE ->
                mirrors + IndependentIconDomain.STACKED
            key in typeVisibilityKeys -> if (separateType) return null else mirrors + IndependentIconDomain.STACKED
            key in nativeTypeKeys -> setOf(IndependentIconDomain.CELLULAR_TYPE)
            key in typeStyleKeys -> mirrors + IndependentIconDomain.CELLULAR_TYPE + IndependentIconDomain.STACKED
            else -> return null
        }
        // Hiding icons first makes visibility part of the slot order, which only a group rebuild
        // can apply.
        return if (reorder && IndependentIconDomain.POLICY in domains) null else domains
    }

    /** Options the Duo hook reads per pass; its living bindings reconcile in place. */
    private val duoLiveKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_DUO_TOP_PERCENT, IconSettingsKeys.KEY_ICON_DUO_CELLULAR_DOTS,
        IconSettingsKeys.KEY_ICON_DUO_DUAL_DOTS, IconSettingsKeys.KEY_ICON_DUO_CLICK_EXPAND,
        IconSettingsKeys.KEY_ICON_DUO_EXPANDED, IconSettingsKeys.KEY_ICON_DUO_SIZE,
        IconSettingsKeys.KEY_ICON_DUO_RING_SCALE, IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE, IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE, IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE,
        IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE, IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE
    )

    /** Cellular accessory options; each one is a gate on its native view-model getter. */
    private val cellularGateKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ACTIVITY, IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_TYPE,
        IconSettingsKeys.KEY_ICON_CELLULAR_ROAM_MODE, IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM,
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM, IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL,
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOWIFI, IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE,
        IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, IconSettingsKeys.KEY_ICON_HIDE_CELLULAR_SPEECH_HD
    )

    private val wifiKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_HIDE_WIFI_ACTIVITY, IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MODE,
        IconSettingsKeys.KEY_ICON_HIDE_WIFI_TYPE, IconSettingsKeys.KEY_ICON_WIFI_STANDARD_MAP,
        IconSettingsKeys.KEY_ICON_WIFI_PADDING, IconSettingsKeys.KEY_ICON_WIFI_PADDING_START_VAL,
        IconSettingsKeys.KEY_ICON_WIFI_PADDING_END_VAL, IconSettingsKeys.KEY_ICON_WIFI_ACTIVITY_RIGHT
    )

    /** Type-label visibility of the stacked replacement, also mirrored by Duo and the carrier. */
    private val typeVisibilityKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI,
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI
    )

    /** Native type text and order; the type hook reinstalls alone and re-measures the bound label. */
    private val nativeTypeKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_ORDER, IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP,
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM, IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL
    )

    /** Type typography and the small 5GA suffix, drawn by the native label and every replacement. */
    private val typeStyleKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SMALL_5GA, IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY,
        IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT, IconSettingsKeys.KEY_ICON_TYPE_WEIGHT
    )

    private val simVisibilityKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM, IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO,
        IconSettingsKeys.KEY_ICON_HIDE_SIM_ONE, IconSettingsKeys.KEY_ICON_HIDE_SIM_TWO,
        IconSettingsKeys.KEY_ICON_HIDE_MOBILE_ON_WIFI
    )

    /** Control-center header family: header constraints, carrier block and carrier labels. */
    private val headerKeys: Set<String> = setOf(
        SystemUiSettingsKeys.KEY_CC_CARRIER_LEFT, SystemUiSettingsKeys.KEY_CC_NETWORK_FIRST_ROW,
        SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_NON_DATA_TYPE, SystemUiSettingsKeys.KEY_CC_CARRIER_SHOW_BADGE,
        SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_ONE, SystemUiSettingsKeys.KEY_CC_CARRIER_BADGE_TWO,
        IconSettingsKeys.KEY_ICON_HIDE_CARRIER_ONE, IconSettingsKeys.KEY_ICON_HIDE_CARRIER_TWO,
        IconSettingsKeys.KEY_ICON_HIDE_CARRIER_HD, IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_ONE,
        IconSettingsKeys.KEY_ICON_HIDE_LS_CARRIER_TWO
    )

    private const val PRIVACY_KEY_PREFIX = "systemui_cc_privacy_"

    private val unreadKeys: Set<String> = setOf(
        IconSettingsKeys.KEY_ICON_STACKED_ALPHA_FG, IconSettingsKeys.KEY_ICON_STACKED_ALPHA_BG,
        IconSettingsKeys.KEY_ICON_STACKED_ALPHA_ERROR, IconSettingsKeys.KEY_ICON_STACKED_PADDING_START,
        IconSettingsKeys.KEY_ICON_STACKED_PADDING_END, IconSettingsKeys.KEY_ICON_STACKED_TYPE_SIZE,
        IconSettingsKeys.KEY_ICON_STACKED_TYPE_WEIGHT, IconSettingsKeys.KEY_ICON_STACKED_TYPE_PADDING_START,
        IconSettingsKeys.KEY_ICON_STACKED_TYPE_PADDING_END, IconSettingsKeys.KEY_ICON_STACKED_TYPE_VERTICAL_OFFSET,
        SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND
    )

    private val headerPrivacyKeys: Set<String> = setOf(
        SystemUiSettingsKeys.KEY_CC_PRIVACY_ENABLED, SystemUiSettingsKeys.KEY_CC_PRIVACY_CENTER,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET
    )

    private val ccBatteryKeys: Set<String> = setOf(
        SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_STYLE, SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_OUTSIDE,
        SystemUiSettingsKeys.KEY_CC_BATTERY_PERCENT_LEFT
    )

    /**
     * Slots whose visibility is also decided by the cellular/Wi-Fi pipeline, the stacked and Duo
     * replacements and the network-speed hook. Those read their modes when they install, so a change
     * still rebuilds the icon hooks.
     */
    private val pipelineSlots: Set<String> = (IconSlotPolicy.SIGNAL_SLOTS + listOf(
        IconSlotPolicy.SLOT_MOBILE, "demo_mobile", "stacked_mobile", "no_sim",
        IconSlotPolicy.SLOT_WIFI, IconSlotPolicy.SLOT_DEMO_WIFI, "network_speed", "net_speed"
    )).toSet()

    private val leftPlacementKeys: Set<String> = buildSet {
        add(IconTunerContract.KEY_LEFT_MODE)
        add(IconSettingsKeys.KEY_ICON_LEFT_CONTAINER_ENABLED)
        add(LEGACY_LEFT_CONTAINER)
        addAll(IconTunerContract.leftPreferenceSlots.keys)
        addAll(IconTunerContract.leftPreferenceSlots.values)
    }

    /**
     * Keys that only feed the slot-visibility and left-placement policy. Living hosts apply them in
     * place: the managers re-block their existing views and the left clones reconcile, so the icons
     * keep their views and the host's own icon animation plays instead of a rebuild.
     */
    fun isLivePolicyKey(key: String): Boolean =
        key in leftPlacementKeys ||
            key.startsWith(SLOT_KEY_PREFIX) && key.removePrefix(SLOT_KEY_PREFIX) !in pipelineSlots

    fun affectsIcons(key: String?): Boolean = key == null || key == "prefs_epoch" ||
        key.startsWith("icon_") || key.startsWith("stacked_s_") ||
        key.startsWith("systemui_cc_") || key.startsWith("statusbar_hide_carrier_") ||
        key.startsWith("systemui_ls_hide_carrier_") || key == "statusbar_region_sampling" ||
        key == "statusbar_notif_max" || key == "statusbar_notif_icon_max" ||
        key == "statusbar_ignore_sys_hide"
}

/** Stable preference values in dropdown order. */
internal enum class CcBatteryPercentStyle { SYSTEM, OUTSIDE_TRAILING, OUTSIDE_LEADING }

internal enum class IndependentIconDomain {
    SAMPLING,
    NOTIFICATIONS,
    /** Slot visibility, left placement and the control-center row split, applied in place. */
    POLICY,
    /** Duo options; the living Duo bindings reconcile in place. */
    DUO,
    /** Header, carrier block and carrier labels; control-center/lock-screen owners only. */
    CONTROL_CENTER_HEADER,
    CC_BATTERY,
    ROTATION_LOCK,
    AIRPLANE,
    /** Cellular accessory gates; the bound binders re-emit in place. */
    CELLULAR,
    /** Wi-Fi gates and override flows re-publish; padding and placement re-apply in place. */
    WIFI,
    /** The stacked replacement re-renders its published slot in place. */
    STACKED,
    /** Native type text/order/typography; that hook reinstalls alone without a group rebuild. */
    CELLULAR_TYPE,
    /** SIM and on-Wi-Fi hiding of the native rows, re-masked in place. */
    SIM_VISIBILITY
}
