package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.view.ViewGroup
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Wi-Fi visibility and standard-label options for the existing MIUI Wi-Fi pipeline.
 *
 * Standard mapping is applied to the host's concrete combine transform, so the original Flow
 * object remains intact (the binder and AOD consumers continue to receive the expected type).
 * Padding is applied after the verified `MiuiWifiViewBinder.bind` layout setup and is limited to
 * the host's `wifi_group` view.
 */
object WifiIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val WIFI_ICON_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon"
    private const val ACTIVE_CLASS = "com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel\$Active"
    private const val HIDDEN_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon\$Hidden"
    private const val VM_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel"
    private const val BINDER_CLASS = "com.android.systemui.statusbar.pipeline.wifi.ui.binder.MiuiWifiViewBinder"
    private const val STANDARD_TRANSFORM_CLASS =
        "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModelInject\$special\$\$inlined\$combine\$2\$3"

    /** Reflective field lookups, cached per (declaring class, field name); see [resolveField]. */
    private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Field>()
    private val fieldMisses = ConcurrentHashMap.newKeySet<Pair<Class<*>, String>>()

    @Volatile private var hideActivity = false
    @Volatile private var hideType = false
    @Volatile private var hideUnavailable = false
    @Volatile private var standardMode = 0
    @Volatile private var standardMap = WifiStandardPolicy.DEFAULT_MAP
    @Volatile private var paddingEnabled = false
    @Volatile private var paddingStart = 0f
    @Volatile private var paddingEnd = 0f
    @Volatile private var activityRight = false

    override fun onPrepareHotReload() {
        hideActivity = false
        hideType = false
        hideUnavailable = false
        standardMode = 0
        standardMap = WifiStandardPolicy.DEFAULT_MAP
        paddingEnabled = false
        paddingStart = 0f
        paddingEnd = 0f
        activityRight = false
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        hideActivity = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_WIFI_ACTIVITY, false)
        hideType = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_WIFI_TYPE, false)
        hideUnavailable = Preferences.getBoolean(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false)
        standardMode = Preferences.getInt(Preferences.KEY_ICON_WIFI_STANDARD_MODE, 0).coerceIn(0, 3)
        standardMap = WifiStandardPolicy.parseMap(
            Preferences.getString(Preferences.KEY_ICON_WIFI_STANDARD_MAP, "4,5,6,7,8")
        )
        paddingEnabled = Preferences.getBoolean(Preferences.KEY_ICON_WIFI_PADDING, false)
        paddingStart = safeFloat(
            Preferences.getFloat(Preferences.KEY_ICON_WIFI_PADDING_START_VAL, 0f)
        )
        paddingEnd = safeFloat(
            Preferences.getFloat(Preferences.KEY_ICON_WIFI_PADDING_END_VAL, 0f)
        )
        activityRight = Preferences.getBoolean(Preferences.KEY_ICON_WIFI_ACTIVITY_RIGHT, false)
        if (!hideActivity && !hideType && !hideUnavailable && standardMode == 0 &&
            !paddingEnabled && !activityRight
        ) {
            DebugLog.hookSkipped(TAG, "WifiIcon", "no icon tuner wifi switches enabled")
            return
        }

        // 1. Substitute a connected Wi-Fi model with the Hidden icon model. This preserves the
        // host's unavailable/no-internet branches and only hides an Active model.
        if (hideUnavailable) {
            val companionClass = "$WIFI_ICON_CLASS\$Companion".toClassOrNull()
            val activeClass = ACTIVE_CLASS.toClassOrNull()
            val hiddenInstance = runCatching {
                HIDDEN_CLASS.toClass().getField("INSTANCE").get(null)
            }.getOrNull()
            val fromModel = companionClass?.findMethodOrNull { name("fromModel") }
            if (fromModel == null || activeClass == null || hiddenInstance == null) {
                DebugLog.hookSkipped(
                    TAG,
                    "$WIFI_ICON_CLASS\$Companion#fromModel",
                    "method/class/INSTANCE not found"
                )
            } else {
                fromModel.hook {
                    before { param ->
                        val model = param.args.getOrNull(0)
                        val showUnavailable = param.args.getOrNull(4)
                        val noFlag = showUnavailable !is Boolean || !showUnavailable
                        if (activeClass.isInstance(model) && noFlag) param.result = hiddenInstance
                    }
                }
            }
        }

        // 2. These are stable getter boundaries used by MiuiWifiViewBinder. A field write would be
        // lost because the factory assigns the flows after construction.
        if (hideActivity || hideType || standardMode == 1) hookVisibilityGetters()
        if (standardMode in 2..3) hookStandardTransform()
        if (paddingEnabled) hookPadding()
        if (activityRight && !hideActivity) hookActivityDirection()
        DebugLog.hookRegistered(
            TAG,
            "WifiIcon: standardMode=$standardMode padding=$paddingEnabled activityRight=$activityRight"
        )
    }

    private fun hookVisibilityGetters() {
        val vmClass = VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        val getters = buildList {
            if (hideActivity) add("getActivityInOutRes")
            if (hideType || standardMode == 1) add("getWifiStandard")
        }
        getters.forEach { getter ->
            vmClass.findMethodOrNull { name(getter); noParams() }?.hook {
                before { param -> param.result = IconTunerFlows.zeroFlow }
            } ?: DebugLog.hookSkipped(TAG, "$VM_CLASS#$getter", "method not found")
        }
    }

    /** Changes only the integer emitted by the host's combine transform; the concrete Flow stays. */
    private fun hookStandardTransform() {
        val transformClass = STANDARD_TRANSFORM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, STANDARD_TRANSFORM_CLASS, "class not found")
            return
        }
        val invoke = transformClass.findMethodOrNull { name("invoke"); paramCount(3) } ?: run {
            DebugLog.hookSkipped(TAG, "$STANDARD_TRANSFORM_CLASS#invoke", "method not found")
            return
        }
        deoptimize(invoke)
        invoke.hook {
            after { param ->
                runCatching {
                    val original = (param.result as? Number)?.toInt() ?: return@runCatching
                    val raw = rawStandard(param.args.getOrNull(1))
                    param.result = WifiStandardPolicy.resolve(standardMode, raw, original, standardMap)
                }.onFailure { DebugLog.w(TAG, "Wi-Fi standard mapping failed", it) }
            }
        }
    }

    /** The combine array's fourth item is WifiNetworkModel.Active on the verified OS4 target. */
    private fun rawStandard(value: Any?): Int? {
        val values = value as? Array<*> ?: return null
        val network = values.getOrNull(3) ?: return null
        val ext = readField(network, "ext")
        readInt(ext, "wifiStandard")?.let { return it }
        return readInt(network, "wifiStandard")
    }

    private fun hookPadding() {
        val binderClass = BINDER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, BINDER_CLASS, "class not found")
            return
        }
        val bind = binderClass.findMethodOrNull { name("bind"); paramCount(2) } ?: run {
            DebugLog.hookSkipped(TAG, "$BINDER_CLASS#bind", "method not found")
            return
        }
        bind.hook {
            after { param ->
                runCatching {
                    val root = param.args.getOrNull(0) as? ViewGroup ?: return@runCatching
                    val groupId = root.resources.getIdentifier(
                        "wifi_group",
                        "id",
                        "com.android.systemui"
                    )
                    val group = if (groupId != 0) root.findViewById<ViewGroup>(groupId) else null
                    (group ?: root).setPaddingRelative(
                        dpToPx(root, paddingStart),
                        (group ?: root).paddingTop,
                        dpToPx(root, paddingEnd),
                        (group ?: root).paddingBottom
                    )
                }.onFailure { DebugLog.w(TAG, "Wi-Fi padding update failed", it) }
            }
        }
    }

    /** `inoutLeft=false` is the verified host value for placing the activity indicator right. */
    private fun hookActivityDirection() {
        val vmClass = VM_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, VM_CLASS, "class not found")
            return
        }
        vmClass.hookAllConstructors {
            after { param ->
                val field = findField(param.thisObject.javaClass, "inoutLeft") ?: return@after
                runCatching {
                    IconTunerFlows.writeField(param.thisObject, field, IconTunerFlows.falseFlow)
                }.onFailure { DebugLog.w(TAG, "Wi-Fi activity direction update failed", it) }
            }
        }
    }

    private fun dpToPx(group: ViewGroup, value: Float): Int =
        (value * group.resources.displayMetrics.density).roundToInt().coerceIn(-2048, 2048)

    private fun safeFloat(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(-48f, 48f) ?: 0f

    private fun readField(target: Any?, name: String): Any? {
        var type = target?.javaClass
        while (type != null) {
            val field = resolveField(type, name)
            if (field != null) return runCatching { field.get(target) }.getOrNull()
            type = type.superclass
        }
        return null
    }

    /**
     * Cached `getDeclaredField` lookup. The wifi-standard callback runs on every icon update, so
     * walking the superclass chain reflectively each time was measurable overhead in SystemUI.
     * Misses are remembered too, so a name absent from the whole chain is not re-resolved.
     */
    private fun resolveField(type: Class<*>, name: String): Field? {
        val key = type to name
        fieldCache[key]?.let { return it }
        if (key in fieldMisses) return null
        val field = runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        if (field == null) fieldMisses.add(key) else fieldCache[key] = field
        return field
    }

    private fun readInt(target: Any?, name: String): Int? =
        (readField(target, name) as? Number)?.toInt()

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        return null
    }
}
