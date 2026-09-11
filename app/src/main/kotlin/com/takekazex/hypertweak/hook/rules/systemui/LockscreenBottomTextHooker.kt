package com.takekazex.hypertweak.hook.rules.systemui

import android.view.View
import android.view.ViewParent
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Filters only the text/status elements at the bottom of the interactive lockscreen.
 *
 * The rotating keyguard indication controller is filtered by semantic type rather than by the
 * localized text, so a TrustAgent message such as HyperTrust's dynamic wording remains covered.
 * HyperOS 4 renders the compact DND and notification-count labels in a separate
 * NotificationNumStateView; those child views are filtered independently and no notification
 * card or per-app notification gate is touched.
 */
object LockscreenBottomTextHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "LockscreenBottomText"
    private const val INDICATION_CONTROLLER =
        "com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController"
    private const val NUM_STATE_VIEW =
        "com.miui.systemui.notification.view.NotificationNumStateView"
    private const val NUM_STATE_ANIMATE_EXT =
        "com.miui.systemui.notification.ext.NumStateViewAnimateExt"

    private const val OWNER_INFO_TYPE = 0
    private const val DISCLOSURE_TYPE = 1
    private const val LOGOUT_TYPE = 2
    private const val BATTERY_TYPE = 3
    private const val ALIGNMENT_TYPE = 4
    private const val TRANSIENT_TYPE = 5
    private const val TRUST_TYPE = 6
    private const val PERSISTENT_UNLOCK_TYPE = 7
    private const val USER_LOCKED_TYPE = 8
    private const val UNKNOWN_TYPE = 9
    private const val REVERSE_CHARGING_TYPE = 10
    private const val BIOMETRIC_TYPE = 11
    private const val BIOMETRIC_FOLLOW_UP_TYPE = 12
    private const val DISMISSIBLE_TYPE = 13
    private const val ADAPTIVE_AUTH_TYPE = 14
    private const val WATCH_DISCONNECTED_TYPE = 15
    private const val SECURE_LOCK_DEVICE_TYPE = 16
    private const val CLICK_TO_UNLOCK_TYPE = 17
    private const val KEY_TO_UNLOCK_TYPE = 18
    private const val ENTER_TO_UNLOCK_TYPE = 19

    const val MODE_BLACKLIST = 0
    const val MODE_WHITELIST = 1

    const val CATEGORY_CHARGING = 1
    const val CATEGORY_DND = 1 shl 1
    const val CATEGORY_NOTIFICATION_COUNT = 1 shl 2
    const val CATEGORY_TRUST = 1 shl 3
    const val CATEGORY_SWIPE = 1 shl 4
    const val CATEGORY_OWNER_INFO = 1 shl 5
    const val CATEGORY_DISCLOSURE = 1 shl 6
    const val CATEGORY_LOGOUT = 1 shl 7
    const val CATEGORY_ALIGNMENT = 1 shl 8
    const val CATEGORY_TRANSIENT = 1 shl 9
    const val CATEGORY_PERSISTENT_UNLOCK = 1 shl 10
    const val CATEGORY_USER_LOCKED = 1 shl 11
    const val CATEGORY_BIOMETRIC = 1 shl 12
    const val CATEGORY_BIOMETRIC_FOLLOW_UP = 1 shl 13
    const val CATEGORY_ADAPTIVE_AUTH = 1 shl 14
    const val CATEGORY_WATCH_DISCONNECTED = 1 shl 15
    const val CATEGORY_SECURE_LOCK_DEVICE = 1 shl 16
    const val CATEGORY_CLICK_TO_UNLOCK = 1 shl 17
    const val CATEGORY_KEY_TO_UNLOCK = 1 shl 18
    const val CATEGORY_ENTER_TO_UNLOCK = 1 shl 19
    const val CATEGORY_UNKNOWN = 1 shl 20

    const val DEFAULT_MODE = MODE_BLACKLIST
    const val DEFAULT_MASK = 0

    @Volatile
    private var enabled = false

    @Volatile
    private var numStateClass: Class<*>? = null

    @Volatile
    private var zenViewId = 0

    @Volatile
    private var notificationCountViewId = 0

    @Volatile
    private var dividingLineId = 0

    @Volatile
    private var compactIdsLogged = false

    override fun onPrepareHotReload() {
        enabled = false
        numStateClass = null
        zenViewId = 0
        notificationCountViewId = 0
        dividingLineId = 0
        compactIdsLogged = false
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT, false)
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "lockscreen bottom text hooks", "disabled")
            return
        }

        hookRotatingIndications()
        hookCompactStatusText()
        hookCompactStatusAnimations()
        hookCompactStatusMeasurement()
    }

    private fun hookRotatingIndications() {
        val controller = runCatching { classLoader.loadClass(INDICATION_CONTROLLER) }.getOrElse {
            DebugLog.hookSkipped(TAG, INDICATION_CONTROLLER, "class not found")
            return
        }
        val updateIndication = controller.declaredMethods.firstOrNull {
            it.name == "updateIndication" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[2] == Boolean::class.javaPrimitiveType
        } ?: run {
            DebugLog.hookSkipped(TAG, "$INDICATION_CONTROLLER#updateIndication", "method not found")
            return
        }

        runCatching {
            updateIndication.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "updateIndication", Unit) {
                        val type = (param.args.getOrNull(0) as? Number)?.toInt() ?: return@open
                        if (shouldHideIndicationType(type)) {
                            // Let the stock controller remove the type from its map/queue and
                            // rotate to the next allowed message. We only suppress additions.
                            param.args[1] = null
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$INDICATION_CONTROLLER#updateIndication", it)
        }
    }

    private fun hookCompactStatusText() {
        val target = runCatching { classLoader.loadClass(NUM_STATE_VIEW) }.getOrElse {
            DebugLog.hookSkipped(TAG, NUM_STATE_VIEW, "class not found")
            return
        }
        numStateClass = target
        val idClass = runCatching { classLoader.loadClass("com.android.systemui.R\$id") }.getOrNull()
        zenViewId = idClass?.staticId("zen_view") ?: 0
        notificationCountViewId = idClass?.staticId("notification_count_view") ?: 0
        dividingLineId = idClass?.staticId("dividing_line") ?: 0
        if (zenViewId == 0 && notificationCountViewId == 0 && dividingLineId == 0) {
            // R$id is commonly absent at runtime because aapt inlines the target's resource
            // constants. Resolve the IDs lazily from the actual NotificationNumStateView resources
            // when the first child/animation reaches this hook.
            DebugLog.d(TAG, "$NUM_STATE_VIEW child ids deferred to target resources")
        }

        runCatching {
            View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType).hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "compact status visibility", Unit) {
                        val view = param.thisObject as? View ?: return@open
                        if (isCompactStatusContainer(view) && !hasAllowedCompactStatus()) {
                            param.args[0] = View.GONE
                            return@open
                        }
                        if (!isCompactStatusChild(view)) return@open
                        if (shouldHideCompactView(view)) {
                            param.args[0] = View.GONE
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "View#setVisibility(Int)", it)
        }
    }

    /**
     * The compact labels are normally shown/hidden by Folme rather than by a stable direct
     * visibility call. Force the stock animation helper into its hidden, non-animated branch so a
     * later DND/count update cannot make a filtered child visible again.
     */
    private fun hookCompactStatusAnimations() {
        val animateClass = runCatching { classLoader.loadClass(NUM_STATE_ANIMATE_EXT) }.getOrElse {
            DebugLog.hookSkipped(TAG, NUM_STATE_ANIMATE_EXT, "class not found")
            return
        }
        val animateVisibility = animateClass.declaredMethods.firstOrNull {
            it.name == "animateUpdateViewVisibility" &&
                it.parameterTypes.size == 7 &&
                it.parameterTypes[0] == View::class.java &&
                it.parameterTypes[2] == Float::class.javaPrimitiveType &&
                it.parameterTypes[3] == Float::class.javaPrimitiveType &&
                it.parameterTypes[4] == Float::class.javaPrimitiveType &&
                it.parameterTypes[5] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[6] == Boolean::class.javaPrimitiveType
        } ?: run {
            DebugLog.hookSkipped(
                TAG,
                "$NUM_STATE_ANIMATE_EXT#animateUpdateViewVisibility",
                "method not found"
            )
            return
        }

        runCatching {
            animateVisibility.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "compact status animation", Unit) {
                        val view = param.args.getOrNull(0) as? View ?: return@open
                        if (isCompactStatusView(view) && shouldHideCompactView(view)) {
                            // Parameters 5/6 are the stock show/animate flags. Suppressing the
                            // animation prevents its asynchronous onBegin callback from restoring
                            // VISIBLE after this hook returns.
                            param.args[5] = false
                            param.args[6] = false
                        }
                    }
                }
                after { param ->
                    HookFailurePolicy.open(TAG, "compact status animation result", Unit) {
                        val view = param.args.getOrNull(0) as? View ?: return@open
                        if (isCompactStatusView(view) && shouldHideCompactView(view)) {
                            view.visibility = View.GONE
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$NUM_STATE_ANIMATE_EXT#animateUpdateViewVisibility", it)
        }
    }

    /**
     * NotificationNumStateView measures all three children manually, even when a child is GONE.
     * Give filtered children an exact zero-width measure spec so the parent also loses their
     * horizontal slot instead of leaving an empty placeholder.
     */
    private fun hookCompactStatusMeasurement() {
        val measure = runCatching {
            View::class.java.getMethod(
                "measure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrElse {
            DebugLog.hookFailed(TAG, "View#measure(Int,Int)", it)
            return
        }

        runCatching {
            measure.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "compact status measurement", Unit) {
                        val view = param.thisObject as? View ?: return@open
                        if (isCompactStatusContainer(view) && !hasAllowedCompactStatus()) {
                            param.args[0] = View.MeasureSpec.makeMeasureSpec(
                                0,
                                View.MeasureSpec.EXACTLY
                            )
                            param.args[1] = View.MeasureSpec.makeMeasureSpec(
                                0,
                                View.MeasureSpec.EXACTLY
                            )
                            return@open
                        }
                        if (isCompactStatusView(view) && shouldHideCompactView(view)) {
                            param.args[0] = View.MeasureSpec.makeMeasureSpec(
                                0,
                                View.MeasureSpec.EXACTLY
                            )
                            param.args[1] = View.MeasureSpec.makeMeasureSpec(
                                0,
                                View.MeasureSpec.EXACTLY
                            )
                        }
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "View#measure(Int,Int)", it)
        }
    }

    private fun shouldHideIndicationType(type: Int): Boolean {
        val category = when (type) {
            OWNER_INFO_TYPE -> CATEGORY_OWNER_INFO
            DISCLOSURE_TYPE -> CATEGORY_DISCLOSURE
            LOGOUT_TYPE -> CATEGORY_LOGOUT
            BATTERY_TYPE -> CATEGORY_CHARGING
            ALIGNMENT_TYPE -> CATEGORY_ALIGNMENT
            TRANSIENT_TYPE -> CATEGORY_TRANSIENT
            TRUST_TYPE -> CATEGORY_TRUST
            PERSISTENT_UNLOCK_TYPE -> CATEGORY_PERSISTENT_UNLOCK
            USER_LOCKED_TYPE -> CATEGORY_USER_LOCKED
            UNKNOWN_TYPE -> CATEGORY_UNKNOWN
            // HyperOS 4's controller intentionally ignores type 10; reverse charging is emitted
            // through the same type-3 indication as normal charging.
            REVERSE_CHARGING_TYPE -> CATEGORY_CHARGING
            BIOMETRIC_TYPE -> CATEGORY_BIOMETRIC
            BIOMETRIC_FOLLOW_UP_TYPE -> CATEGORY_BIOMETRIC_FOLLOW_UP
            DISMISSIBLE_TYPE -> CATEGORY_SWIPE
            ADAPTIVE_AUTH_TYPE -> CATEGORY_ADAPTIVE_AUTH
            WATCH_DISCONNECTED_TYPE -> CATEGORY_WATCH_DISCONNECTED
            SECURE_LOCK_DEVICE_TYPE -> CATEGORY_SECURE_LOCK_DEVICE
            CLICK_TO_UNLOCK_TYPE -> CATEGORY_CLICK_TO_UNLOCK
            KEY_TO_UNLOCK_TYPE -> CATEGORY_KEY_TO_UNLOCK
            ENTER_TO_UNLOCK_TYPE -> CATEGORY_ENTER_TO_UNLOCK
            else -> CATEGORY_UNKNOWN
        }
        return !isAllowed(category)
    }

    private fun shouldHideCompactView(view: View): Boolean {
        return when (view.id) {
            zenViewId -> !isAllowed(CATEGORY_DND)
            notificationCountViewId -> !isAllowed(CATEGORY_NOTIFICATION_COUNT)
            dividingLineId ->
                !isAllowed(CATEGORY_DND) || !isAllowed(CATEGORY_NOTIFICATION_COUNT)
            else -> false
        }
    }

    private fun isAllowed(category: Int): Boolean {
        if (!enabled) return true
        val mask = Preferences.getInt(
            Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
            DEFAULT_MASK
        )
        val selected = (mask and category) != 0
        return if (Preferences.getInt(
                Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE,
                DEFAULT_MODE
            ) == MODE_WHITELIST
        ) {
            selected
        } else {
            !selected
        }
    }

    private fun isCompactStatusChild(view: View): Boolean {
        if (!isCompactStatusView(view)) {
            return false
        }
        var parent: ViewParent? = view.parent
        while (parent is View) {
            if (numStateClass?.isInstance(parent) == true) return true
            parent = parent.parent
        }
        return false
    }

    private fun isCompactStatusContainer(view: View): Boolean {
        return numStateClass?.isInstance(view) == true
    }

    private fun hasAllowedCompactStatus(): Boolean {
        return isAllowed(CATEGORY_DND) || isAllowed(CATEGORY_NOTIFICATION_COUNT)
    }

    private fun isCompactStatusView(view: View): Boolean {
        if (view.id == View.NO_ID) return false
        resolveCompactIds(view)
        return (zenViewId != 0 && view.id == zenViewId) ||
            (notificationCountViewId != 0 && view.id == notificationCountViewId) ||
            (dividingLineId != 0 && view.id == dividingLineId)
    }

    private fun resolveCompactIds(view: View) {
        if (zenViewId != 0 && notificationCountViewId != 0 && dividingLineId != 0) return
        val resources = view.resources
        val packageNames = listOf("com.android.systemui", view.context.packageName).distinct()
        fun id(name: String): Int = packageNames.firstNotNullOfOrNull { packageName ->
            runCatching { resources.getIdentifier(name, "id", packageName) }
                .getOrNull()
                ?.takeIf { it != 0 }
        } ?: 0
        if (zenViewId == 0) zenViewId = id("zen_view")
        if (notificationCountViewId == 0) notificationCountViewId = id("notification_count_view")
        if (dividingLineId == 0) dividingLineId = id("dividing_line")
        if (!compactIdsLogged && (zenViewId != 0 || notificationCountViewId != 0 || dividingLineId != 0)) {
            compactIdsLogged = true
            DebugLog.i(
                TAG,
                "resolved compact ids zen=$zenViewId notification=$notificationCountViewId divider=$dividingLineId"
            )
        }
    }

    private fun Class<*>.staticId(name: String): Int = runCatching {
        getField(name).getInt(null)
    }.getOrDefault(0)
}
