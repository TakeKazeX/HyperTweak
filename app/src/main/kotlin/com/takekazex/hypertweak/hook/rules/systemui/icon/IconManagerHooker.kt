package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Status-bar slot show/hide, ported from Hyper Helper's `IconManager` hooker.
 *
 * `MiuiIconManagerUtils.RIGHT_BLOCK_LIST` / `CONTROL_CENTER_BLOCK_LIST` are static ArrayLists
 * populated once in the static initializer; SystemUI consumers (`HomeStatusBarIconBlockListInteractor`
 * and friends) hold the same instance, so mutating the list contents at hook time takes effect
 * immediately. Each slot has a mode: 0 = follow system (lists untouched), 1 = visible everywhere,
 * 2 = status bar only (block control center), 3 = control center only, 4 = hidden everywhere.
 * Only the two single-SIM stacked slots default to 4 so the stacked icon can take over; every
 * other slot leaves the system lists alone on 0, mirroring Hyper Helper's `IconManager.v()`.
 * Verified present unchanged on OS4. Requires a SystemUI restart.
 */
object IconManagerHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val UTILS_CLASS = "com.android.systemui.statusbar.phone.MiuiIconManagerUtils"

    /** Slots every build understands. */
    private val baseSlots = listOf(
        "no_sim", "airplane", "wifi", "demo_wifi", "hotspot", "vpn", "network_speed",
        "bluetooth", "bluetooth_handsfree_battery", "handle_battery", "nfc", "gps",
        "location", "wireless_headset", "phone", "pad", "pc", "sound_box_group",
        "stereo", "sound_box_screen", "sound_box", "tv", "glasses", "car", "camera",
        "dist_compute", "headset", "alarm_clock", "zen", "volume", "second_space"
    )

    /** Only active while the compound icon feature is on. */
    private val compoundSlots = listOf(
        "compound_location", "compound_alarm_clock", "compound_zen",
        "compound_volume_vibrate", "compound_volume_mute"
    )

    /** Only active while the stacked signal feature is on. */
    private val stackedSlots = listOf(
        "stacked_mobile_icon", "stacked_mobile_type", "single_mobile_sim1", "single_mobile_sim2"
    )

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        val utilsClass = UTILS_CLASS.toClassOrNull()
        if (utilsClass == null) {
            DebugLog.hookSkipped(TAG, UTILS_CLASS, "class not found")
            return
        }
        // Reading the static field initializes the class (and its lists) if needed.
        val right = readStringList(utilsClass, "RIGHT_BLOCK_LIST") ?: run {
            DebugLog.hookSkipped(TAG, "$UTILS_CLASS#RIGHT_BLOCK_LIST", "field not found")
            return
        }
        val cc = readStringList(utilsClass, "CONTROL_CENTER_BLOCK_LIST") ?: run {
            DebugLog.hookSkipped(TAG, "$UTILS_CLASS#CONTROL_CENTER_BLOCK_LIST", "field not found")
            return
        }

        baseSlots.forEach { slot ->
            applyMode(slotMode(slot), slot, right, cc)
        }
        if (Preferences.getBoolean(Preferences.KEY_ICON_COMPOUND_ALARM, false) ||
            Preferences.getBoolean(Preferences.KEY_ICON_COMPOUND_ZEN, false) ||
            Preferences.getBoolean(Preferences.KEY_ICON_COMPOUND_LOCATION, false) ||
            Preferences.getBoolean(Preferences.KEY_ICON_COMPOUND_VOLUME, false)
        ) {
            compoundSlots.forEach { slot ->
                applyMode(slotMode(slot), slot, right, cc)
            }
        }
        if (Preferences.getBoolean(Preferences.KEY_ICON_STACKED_ENABLED, false)) {
            stackedSlots.forEach { slot ->
                applyMode(slotMode(slot), slot, right, cc)
            }
        }

        // Extra blocked slots from a free-form list.
        Preferences.getString(Preferences.KEY_ICON_EXT_BLOCKED, "")
            .split(',', ' ', '\uFF0C')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { slot ->
                if (!right.contains(slot)) right.add(slot)
                if (!cc.contains(slot)) cc.add(slot)
            }

        DebugLog.i(TAG, "IconManager applied slot modes: right=${right.size} cc=${cc.size}")
    }

    private fun slotMode(slot: String): Int {
        val mode = Preferences.getInt(Preferences.slotKey(slot), 0)
        // Only the single-SIM stacked slots default to "hidden everywhere" (4) so the stacked
        // icon can take over; every other unset slot (0) must leave the system lists untouched,
        // otherwise resetting to defaults blocks every base slot in both areas.
        return if (mode == 0 && (slot == "single_mobile_sim1" || slot == "single_mobile_sim2")) 4 else mode
    }

    private fun applyMode(
        mode: Int,
        slot: String,
        right: MutableList<String>,
        cc: MutableList<String>
    ) {
        when (mode) {
            1 -> {
                right.remove(slot)
                cc.remove(slot)
            }
            2 -> {
                right.remove(slot)
                if (!cc.contains(slot)) cc.add(slot)
            }
            3 -> {
                if (!right.contains(slot)) right.add(slot)
                cc.remove(slot)
            }
            4 -> {
                if (!right.contains(slot)) right.add(slot)
                if (!cc.contains(slot)) cc.add(slot)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readStringList(clazz: Class<*>, name: String): MutableList<String>? {
        return runCatching {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            field.get(null) as? MutableList<String>
        }.getOrNull()
    }
}
