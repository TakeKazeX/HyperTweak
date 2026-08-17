package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field

/**
 * Cellular type display, ported from Hyper Helper's `CellularTypeIcon` (OS4_ADAPTATION_PLAN.md T4a).
 *
 * MIUI decides the per-SIM type text and whether the single-carrier type row is shown through
 * `IOperatorCustomizedPolicy$OperatorConfig`. On OS4 that object is rebuilt inside
 * `MiuiOperatorCustomizedPolicy.getMiuiOperatorConfig(int)` on every call (verified in smali —
 * the `new` + field writes live in the getter), so an after-constructor write would be clobbered;
 * the hook instead mutates the freshly returned config: it forces `showMobileDataTypeSingle` true
 * (show the type text even on layouts that hide it) and replaces `mobileTypeName` with the custom
 * text when set (a single value fills all 15 per-SIM entries; exactly 15 comma-separated values
 * map one-to-one, mirroring upstream). The upstream font half (`MobileTypeDrawable` paints) is not
 * ported — font replacement is a separate feature slice in HyperTweak. Requires a SystemUI restart.
 */
object CellularTypeIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val POLICY_CLASS = "com.android.systemui.MiuiOperatorCustomizedPolicy"
    private const val CONFIG_CLASS = "com.miui.interfaces.IOperatorCustomizedPolicy\$OperatorConfig"

    @Volatile private var forceSingle = false
    @Volatile private var customType = ""
    @Volatile private var useCustom = false

    private var singleField: Field? = null
    private var nameField: Field? = null

    override fun onPrepareHotReload() {
        forceSingle = false
        customType = ""
        useCustom = false
    }

    override fun onHook() {
        forceSingle = Preferences.getBoolean(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE, false)
        useCustom = Preferences.getBoolean(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM, false)
        customType = Preferences.getString(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, "").trim()
        if (!forceSingle && !useCustom) {
            DebugLog.hookSkipped(TAG, "CellularTypeIcon", "disabled")
            return
        }

        val policyClass = POLICY_CLASS.toClassOrNull()
        if (policyClass == null) {
            DebugLog.hookSkipped(TAG, POLICY_CLASS, "class not found")
            return
        }
        val configClass = CONFIG_CLASS.toClassOrNull()
        if (configClass == null) {
            DebugLog.hookSkipped(TAG, CONFIG_CLASS, "class not found")
            return
        }
        singleField = runCatching {
            configClass.getDeclaredField("showMobileDataTypeSingle").apply { isAccessible = true }
        }.getOrNull()
        nameField = runCatching {
            configClass.getDeclaredField("mobileTypeName").apply { isAccessible = true }
        }.getOrNull()
        if (singleField == null && nameField == null) {
            DebugLog.hookSkipped(TAG, CONFIG_CLASS, "fields not found")
            return
        }

        policyClass.findMethodOrNull { name("getMiuiOperatorConfig"); paramCount(1) }?.let { method ->
            method.hook {
                after { param ->
                    val config = param.result ?: return@after
                    if (!configClass.isInstance(config)) return@after
                    if (forceSingle && singleField != null) {
                        runCatching { singleField?.setBoolean(config, true) }
                            .onFailure { t -> DebugLog.w(TAG, "showMobileDataTypeSingle write failed", t) }
                    }
                    if (useCustom && nameField != null && customType.isNotEmpty()) {
                        // Mirrors upstream: a single value fills all 15 per-SIM entries; exactly 15
                        // comma-separated values map one-to-one; anything else is ignored.
                        val parts = customType.split(',').map { it.trim() }
                        val names = when {
                            parts.size == 1 && parts[0].isNotEmpty() -> List(15) { parts[0] }
                            parts.size == 15 -> parts
                            else -> null
                        }
                        if (names != null) {
                            runCatching { nameField?.set(config, names) }
                                .onFailure { t -> DebugLog.w(TAG, "mobileTypeName write failed", t) }
                        }
                    }
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$POLICY_CLASS#getMiuiOperatorConfig", "method not found")
        DebugLog.i(TAG, "CellularTypeIcon installed: single=$forceSingle custom=$useCustom")
    }
}