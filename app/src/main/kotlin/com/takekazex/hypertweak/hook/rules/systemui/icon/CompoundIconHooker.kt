package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Compound icon (合成图标), ported from Hyper Helper's `CompoundIcon` (OS4_ADAPTATION_PLAN.md T4b).
 *
 * The alarm-clock / DND / location / mute-vibrate states each keep their own `compound_*` status
 * slot, and a shared per-controller merged state shows exactly the highest-priority active source,
 * so the status bar shows a single "compound" icon instead of several. The individual system icons
 * (zen / mute / location / alarm) are hidden with the slot modes on the same page, exactly like
 * upstream.
 *
 * Hook targets, all verified on OS4.0.0.15.XPMCNXM:
 * - `MiuiPhoneStatusBarPolicy.updateVolumeZen()` — mute / zen state (deoptimized);
 * - `MiuiPhoneStatusBarPolicy.onLocationActiveChanged$1()` — location state (R8-renamed, deoptimized);
 * - `PhoneStatusBarPolicy$$ExternalSyntheticLambda3.accept` — zen lambda (classId 0 branch);
 * - `PhoneStatusBarPolicy$4.onAlarmChanged(boolean)` — alarm state;
 * - `MiuiPrivacyControllerImpl` constructor — mirrors the CTA-required location state.
 *
 * The merged state engine mirrors upstream's `KEY_MERGED_ICON_STATE` weak cache:
 * `MergedState.slots` is the priority-ordered slot list from `icon_tuner_compound_priority`, and
 * `apply(controller)` lazily installs the five system drawables onto the `compound_*` slots
 * (`StatusBarIconController.setIcon` / `setIconVisibility`) and flips visibility to the winner.
 * Requires a SystemUI restart.
 */
object CompoundIconHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "IconTuner"
    private const val POLICY_CLASS = "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarPolicy"
    private const val ICON_CONTROLLER_CLASS = "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl"
    private const val LAMBDA3_CLASS = "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy\$\$ExternalSyntheticLambda3"
    private const val ALARM_CALLBACK_CLASS = "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy\$4"
    private const val PRIVACY_CLASS = "com.android.systemui.statusbar.privacy.MiuiPrivacyControllerImpl"
    private const val LOCATION_CONTROLLER_CLASS = "com.android.systemui.statusbar.policy.LocationControllerImpl"

    private val compoundSlots = listOf(
        "compound_location", "compound_alarm_clock", "compound_zen",
        "compound_volume_mute", "compound_volume_vibrate"
    )

    /** Slot → SystemUI drawable resource name (upstream ResourcesUtils.D..H). */
    private val slotResNames = mapOf(
        "compound_location" to "stat_sys_gps_on",
        "compound_alarm_clock" to "stat_sys_alarm",
        "compound_zen" to "stat_sys_quiet_mode",
        "compound_volume_mute" to "stat_sys_ringer_silent",
        "compound_volume_vibrate" to "stat_sys_ringer_vibrate"
    )

    /** Per-controller merged icon state (upstream's `KEY_MERGED_ICON_STATE`). */
    private class MergedState(
        val slots: List<String>,
        val active: MutableMap<String, Boolean>,
        var shown: String?,
        var installed: Boolean = false
    )

    private val mergedStates = Collections.synchronizedMap(WeakHashMap<Any, MergedState>())

    // Reflection cache.
    private var iconControllerField: Field? = null
    private var zenVisibleField: Field? = null
    private var muteVisibleField: Field? = null
    private var muteIconResIdField: Field? = null
    private var locationControllerField: Field? = null
    private var activeLocationRequestsField: Field? = null
    private var hasAlarmField: Field? = null
    private var lambdaClassIdField: Field? = null
    private var lambdaOwnerField: Field? = null
    private var alarmCallbackOwnerField: Field? = null
    private var setIconMethod: Method? = null
    private var setIconVisibilityMethod: Method? = null
    private var isCtaRequiredLocationMethod: Method? = null
    private var privacyIconControllerField: Field? = null
    private var lazyGetMethod: Method? = null

    @Volatile private var alarmOn = false
    @Volatile private var zenOn = false
    @Volatile private var locationOn = false
    @Volatile private var volumeOn = false
    @Volatile private var priority = "location,alarm_clock,zen,volume"

    /** SystemUI drawable ids resolved once (0 = not yet resolved / missing). */
    @Volatile private var vibrateResId = 0
    @Volatile private var slotResIds: Map<String, Int> = emptyMap()

    private val activityThreadCurrentApplicationMethod by lazy {
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .apply { isAccessible = true }
        }.getOrNull()
    }

    @Volatile
    private var appContext: android.content.Context? = null

    private fun resolveSystemUiDrawables() {
        if (slotResIds.isNotEmpty()) return
        val resources = appContext?.resources ?: runCatching {
            (activityThreadCurrentApplicationMethod?.invoke(null) as? android.content.Context)?.resources
        }.getOrNull()
        if (resources == null) {
            DebugLog.w(TAG, "CompoundIcon: no SystemUI context for drawable resolution")
            return
        }
        val resolved = slotResNames.mapValues { (_, name) ->
            resources.getIdentifier(name, "drawable", "com.android.systemui")
        }
        if (resolved.values.any { it == 0 }) {
            DebugLog.w(TAG, "CompoundIcon: some drawables unresolved: $resolved")
        }
        slotResIds = resolved
        vibrateResId = resolved["compound_volume_vibrate"] ?: 0
    }

    override fun onPrepareHotReload() {
        alarmOn = false
        zenOn = false
        locationOn = false
        volumeOn = false
        mergedStates.clear()
        slotResIds = emptyMap()
        vibrateResId = 0
        appContext = null
    }

    override fun onHook() {
        IconTunerFlows.init(classLoader)
        // Upstream gates the whole feature on the compound-icon slot mode being 1..3 (g32.J);
        // mode 0 = follow system and 4 = hidden everywhere disable it.
        val slotMode = Preferences.getInt(Preferences.slotKey("compound_icon"), 0)
        if (slotMode !in 1..3) {
            DebugLog.hookSkipped(TAG, "CompoundIcon", "slot mode $slotMode not active")
            return
        }
        alarmOn = Preferences.getBoolean(Preferences.KEY_ICON_COMPOUND_ALARM, false)
        zenOn = Preferences.getBoolean(Preferences.KEY_ICON_COMPOUND_ZEN, false)
        locationOn = Preferences.getBoolean(Preferences.KEY_ICON_COMPOUND_LOCATION, false)
        volumeOn = Preferences.getBoolean(Preferences.KEY_ICON_COMPOUND_VOLUME, false)
        priority = Preferences.getString(
            Preferences.KEY_ICON_COMPOUND_PRIORITY, "location,alarm_clock,zen,volume"
        )
        appContext = runCatching {
            activityThreadCurrentApplicationMethod?.invoke(null) as? android.content.Context
        }.getOrNull()
        resolveSystemUiDrawables()

        val policyClass = POLICY_CLASS.toClassOrNull()
        if (policyClass == null) {
            DebugLog.hookSkipped(TAG, POLICY_CLASS, "class not found")
            return
        }
        // Fields live on the base PhoneStatusBarPolicy (mIconController, mZenVisible,
        // mLocationController) or the subclass (mMuteVisible, mMuteIconResId).
        iconControllerField = hierarchyField(policyClass, "mIconController")
        zenVisibleField = hierarchyField(policyClass, "mZenVisible")
        muteVisibleField = hierarchyField(policyClass, "mMuteVisible")
        muteIconResIdField = hierarchyField(policyClass, "mMuteIconResId")
        locationControllerField = hierarchyField(policyClass, "mLocationController")
        if (iconControllerField == null || zenVisibleField == null) {
            DebugLog.hookSkipped(TAG, POLICY_CLASS, "policy fields not found")
            return
        }

        val controllerClass = ICON_CONTROLLER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, ICON_CONTROLLER_CLASS, "class not found")
            return
        }
        // setIcon/setIconVisibility are declared on StatusBarIconControllerImpl (the interface
        // itself carries no icon methods on OS4); the field is typed as the interface, so resolve
        // the concrete impl.
        setIconMethod = controllerClass.findMethodOrNull {
            name("setIcon"); paramCount(3)
        }
        setIconVisibilityMethod = controllerClass.findMethodOrNull {
            name("setIconVisibility"); paramCount(2)
        }
        if (setIconMethod == null || setIconVisibilityMethod == null) {
            DebugLog.hookSkipped(TAG, "StatusBarIconController", "setIcon methods not found")
            return
        }

        val locationControllerClass = LOCATION_CONTROLLER_CLASS.toClassOrNull()
        activeLocationRequestsField = locationControllerClass?.let { hierarchyField(it, "mAreActiveLocationRequests") }
        val privacyClass = PRIVACY_CLASS.toClassOrNull()
        isCtaRequiredLocationMethod = privacyClass?.findMethodOrNull {
            name("isCTARequiredLocation"); isStatic()
        }
        privacyIconControllerField = privacyClass?.let { hierarchyField(it, "mStatusBarIconController") }
        lazyGetMethod = runCatching {
            Class.forName("dagger.Lazy", false, classLoader)
                .getMethod("get").apply { isAccessible = true }
        }.getOrNull()

        // 1. updateVolumeZen — mute/zen state.
        policyClass.findMethodOrNull { name("updateVolumeZen"); noParams() }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    val policy = param.thisObject
                    val controller = readField(iconControllerField, policy) ?: return@after
                    val zen = readBool(zenVisibleField, policy)
                    val mute = readBool(muteVisibleField, policy)
                    val iconResId = readInt(muteIconResIdField, policy)
                    val vibrate = mute && iconResId != 0 && iconResId == vibrateResId
                    val state = mergedState(controller)
                    state.active["compound_zen"] = zen
                    state.active["compound_volume_mute"] = mute && !vibrate
                    state.active["compound_volume_vibrate"] = vibrate
                    apply(controller, state)
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$POLICY_CLASS#updateVolumeZen", "method not found")

        // 2. onLocationActiveChanged$1 — location state (R8-renamed on OS4).
        policyClass.findMethodOrNull { name("onLocationActiveChanged\$1"); noParams() }?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    val policy = param.thisObject
                    // CTA-required location is owned by the privacy controller; the ctor hook
                    // mirrors it instead (mirrors upstream's guard).
                    if (invokeStaticBool(isCtaRequiredLocationMethod)) return@after
                    val controller = readField(iconControllerField, policy) ?: return@after
                    val locationController = readField(locationControllerField, policy) ?: return@after
                    val active = readBool(activeLocationRequestsField, locationController)
                    val state = mergedState(controller)
                    state.active["compound_location"] = active
                    apply(controller, state)
                }
            }
        } ?: DebugLog.hookSkipped(TAG, "$POLICY_CLASS#onLocationActiveChanged\$1", "method not found")

        // 3. Zen lambda — keeps compound_zen in sync when the ZenModeInfo flow drives the slot
        //    directly instead of updateVolumeZen.
        val lambda3Class = LAMBDA3_CLASS.toClassOrNull()
        if (lambda3Class == null) {
            DebugLog.hookSkipped(TAG, LAMBDA3_CLASS, "class not found")
        } else {
            lambdaClassIdField = hierarchyField(lambda3Class, "\$r8\$classId")
            lambdaOwnerField = hierarchyField(lambda3Class, "f\$0")
            lambda3Class.findMethodOrNull { name("accept"); paramCount(1) }?.let { method ->
                method.hook {
                    after { param ->
                        val lambda = param.thisObject
                        val classId = readInt(lambdaClassIdField, lambda)
                        if (classId != 0) return@after // classId 0 = the zen branch
                        val policy = readField(lambdaOwnerField, lambda) ?: return@after
                        val controller = readField(iconControllerField, policy) ?: return@after
                        val zen = readBool(zenVisibleField, policy)
                        val state = mergedState(controller)
                        state.active["compound_zen"] = zen
                        apply(controller, state)
                    }
                }
            } ?: DebugLog.hookSkipped(TAG, "$LAMBDA3_CLASS#accept", "method not found")
        }

        // 4. Alarm callback — alarm state.
        val alarmCallbackClass = ALARM_CALLBACK_CLASS.toClassOrNull()
        if (alarmCallbackClass == null) {
            DebugLog.hookSkipped(TAG, ALARM_CALLBACK_CLASS, "class not found")
        } else {
            alarmCallbackOwnerField = hierarchyField(alarmCallbackClass, "this\$0")
            hasAlarmField = policyClass.let { hierarchyField(it, "mHasAlarm") }
            alarmCallbackClass.findMethodOrNull { name("onAlarmChanged"); paramCount(1) }?.let { method ->
                method.hook {
                    after { param ->
                        val callback = param.thisObject
                        val policy = readField(alarmCallbackOwnerField, callback) ?: return@after
                        val controller = readField(iconControllerField, policy) ?: return@after
                        val hasAlarm = readBool(hasAlarmField, policy)
                        val state = mergedState(controller)
                        state.active["compound_alarm_clock"] = hasAlarm
                        apply(controller, state)
                    }
                }
            } ?: DebugLog.hookSkipped(TAG, "$ALARM_CALLBACK_CLASS#onAlarmChanged", "method not found")
        }

        // 5. Privacy controller — mirrors the CTA-required location state into compound_location
        //    (the system-mandated location icon is merged into the compound icon too).
        if (privacyClass != null && privacyIconControllerField != null &&
            isCtaRequiredLocationMethod != null && lazyGetMethod != null
        ) {
            privacyClass.hookAllConstructors {
                after { param ->
                    val controller = runCatching {
                        val lazy = readField(privacyIconControllerField, param.thisObject)
                        lazy?.let { lazyGetMethod?.invoke(it) }
                    }.getOrNull() ?: return@after
                    val cta = invokeStaticBool(isCtaRequiredLocationMethod)
                    if (!cta) return@after
                    Handler(Looper.getMainLooper()).post {
                        runCatching {
                            val state = mergedState(controller)
                            state.active["compound_location"] = true
                            apply(controller, state)
                        }.onFailure { t ->
                            DebugLog.w(TAG, "CompoundIcon privacy location mirror failed", t)
                        }
                    }
                }
            }
        }
        DebugLog.i(TAG, "CompoundIcon installed: slots=$compoundSlots priority=$priority")
    }

    // ─── Merged state engine ────────────────────────────────────────────────────

    private fun mergedState(controller: Any): MergedState {
        return mergedStates.getOrPut(controller) {
            val slots = prioritySlots()
            val active = LinkedHashMap<String, Boolean>()
            compoundSlots.forEach { active[it] = false }
            MergedState(slots, active, null)
        }
    }

    /** Priority-ordered compound slots honoring each source toggle (upstream `t(Object)`). */
    private fun prioritySlots(): List<String> {
        val list = mutableListOf<String>()
        priority.split(',', ' ', '\uFF0C').forEach { token ->
            when (token.trim()) {
                "volume" -> if (volumeOn) {
                    list += "compound_volume_vibrate"
                    list += "compound_volume_mute"
                }
                "zen" -> if (zenOn) list += "compound_zen"
                "alarm_clock" -> if (alarmOn) list += "compound_alarm_clock"
                "location" -> if (locationOn) list += "compound_location"
            }
        }
        return list
    }

    private fun apply(controller: Any, state: MergedState) {
        // Lazily install the five icons once, hidden.
        if (!state.installed) {
            state.installed = true
            compoundSlots.forEach { slot ->
                val resId = slotResIds[slot] ?: return@forEach
                runCatching {
                    setIconMethod?.invoke(controller, null, slot, resId)
                    setIconVisibilityMethod?.invoke(controller, slot, false)
                }.onFailure { t ->
                    DebugLog.w(TAG, "CompoundIcon install $slot failed", t)
                }
            }
        }
        val winner = state.slots.firstOrNull { state.active[it] == true }
        if (winner == state.shown) return
        state.shown?.let { old ->
            runCatching { setIconVisibilityMethod?.invoke(controller, old, false) }
                .onFailure { t -> DebugLog.w(TAG, "CompoundIcon hide $old failed", t) }
        }
        if (winner != null) {
            runCatching { setIconVisibilityMethod?.invoke(controller, winner, true) }
                .onFailure { t -> DebugLog.w(TAG, "CompoundIcon show $winner failed", t) }
        }
        state.shown = winner
    }

    // ─── Reflection helpers ─────────────────────────────────────────────────────

    private fun hierarchyField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null) {
            runCatching {
                return c.getDeclaredField(name).apply { isAccessible = true }
            }
            c = c.superclass
        }
        return null
    }

    private fun readField(field: Field?, target: Any): Any? =
        field?.let { runCatching { it.get(target) }.getOrNull() }

    private fun readBool(field: Field?, target: Any): Boolean =
        field?.let { runCatching { it.getBoolean(target) }.getOrDefault(false) } ?: false

    private fun readInt(field: Field?, target: Any): Int =
        field?.let { runCatching { it.getInt(target) }.getOrDefault(0) } ?: 0

    private fun invokeStaticBool(method: Method?): Boolean =
        method?.let { runCatching { it.invoke(null) as? Boolean }.getOrDefault(false) } ?: false
}