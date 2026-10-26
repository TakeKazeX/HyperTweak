package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.rules.systemui.NativeViewAttachmentReplay
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.LockscreenBottomTextOptions

import android.view.View
import android.view.ViewParent
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog

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
    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) {
            recoverExistingViews(it.views)
        }
    }

    override val hotReloadMode = HotReloadMode.RECREATE

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

    @Volatile
    private var enabled = false

    private var numStateClass: Class<*>? by generationState { null }

    private var zenViewId by generationState { 0 }

    private var notificationCountViewId by generationState { 0 }

    private var dividingLineId by generationState { 0 }

    private var compactIdsLogged by generationState { false }

    internal fun recoverExistingViews(views: List<View>) {
        views.filter { it.javaClass.name == NUM_STATE_VIEW }.forEach { knownIndicationViews += it }
        // The stock repeatWhenAttached binder replays DND/count state, visibility and Folme.
        // Recollect the real native inputs instead of guessing whether a masked label was visible.
        views.filter { it.javaClass.name == NUM_STATE_VIEW && it.isAttachedToWindow }
            .forEach(NativeViewAttachmentReplay::replay)
    }

    override fun onPrepareHotReload() {
        enabled = false
    }

    /**
     * Accepted-snapshot refresh. Enabling binds the living indication/compact views right away
     * (the stock binder replays DND/count state) and starts hiding; disabling replays the native
     * attachment path so the current message returns without waiting for the next system event.
     */
    fun refreshSettings() {
        val wasEnabled = enabled
        enabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT, false)
        if (wasEnabled != enabled) {
            com.takekazex.hypertweak.hook.base.HostAccess.onMain {
                knownIndicationViews.toList().filter { it.isAttachedToWindow }
                    .forEach { runCatching { NativeViewAttachmentReplay.replay(it) } }
            }
        }
        DebugLog.i(TAG, "settings applied enabled=" + enabled)
    }

    /** Living rotating-indication views; weak, because the keyguard owns them. */
    private val knownIndicationViews: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    override fun onHook() {
        enabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT, false)
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
            HostAccess.requirePublicMethod(View::class.java, "setVisibility", Int::class.javaPrimitiveType, accessible = false).hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "compact status visibility", Unit) {
                        val view = param.thisObject as? View ?: return@open
                        // The interceptor stays installed while the feature is off; only the
                        // accepted snapshot decides whether it may change the host's result.
                        if (!enabled) return@open
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
            HostAccess.requirePublicMethod(View::class.java,
                "measure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, accessible = false)
        }.getOrElse {
            DebugLog.hookFailed(TAG, "View#measure(Int,Int)", it)
            return
        }

        runCatching {
            measure.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "compact status measurement", Unit) {
                        val view = param.thisObject as? View ?: return@open
                        if (!enabled) return@open
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
        if (!enabled) return false
        val category = when (type) {
            OWNER_INFO_TYPE -> LockscreenBottomTextOptions.CATEGORY_OWNER_INFO
            DISCLOSURE_TYPE -> LockscreenBottomTextOptions.CATEGORY_DISCLOSURE
            LOGOUT_TYPE -> LockscreenBottomTextOptions.CATEGORY_LOGOUT
            BATTERY_TYPE -> LockscreenBottomTextOptions.CATEGORY_CHARGING
            ALIGNMENT_TYPE -> LockscreenBottomTextOptions.CATEGORY_ALIGNMENT
            TRANSIENT_TYPE -> LockscreenBottomTextOptions.CATEGORY_TRANSIENT
            TRUST_TYPE -> LockscreenBottomTextOptions.CATEGORY_TRUST
            PERSISTENT_UNLOCK_TYPE -> LockscreenBottomTextOptions.CATEGORY_PERSISTENT_UNLOCK
            USER_LOCKED_TYPE -> LockscreenBottomTextOptions.CATEGORY_USER_LOCKED
            UNKNOWN_TYPE -> LockscreenBottomTextOptions.CATEGORY_UNKNOWN
            // HyperOS 4's controller intentionally ignores type 10; reverse charging is emitted
            // through the same type-3 indication as normal charging.
            REVERSE_CHARGING_TYPE -> LockscreenBottomTextOptions.CATEGORY_CHARGING
            BIOMETRIC_TYPE -> LockscreenBottomTextOptions.CATEGORY_BIOMETRIC
            BIOMETRIC_FOLLOW_UP_TYPE -> LockscreenBottomTextOptions.CATEGORY_BIOMETRIC_FOLLOW_UP
            DISMISSIBLE_TYPE -> LockscreenBottomTextOptions.CATEGORY_SWIPE
            ADAPTIVE_AUTH_TYPE -> LockscreenBottomTextOptions.CATEGORY_ADAPTIVE_AUTH
            WATCH_DISCONNECTED_TYPE -> LockscreenBottomTextOptions.CATEGORY_WATCH_DISCONNECTED
            SECURE_LOCK_DEVICE_TYPE -> LockscreenBottomTextOptions.CATEGORY_SECURE_LOCK_DEVICE
            CLICK_TO_UNLOCK_TYPE -> LockscreenBottomTextOptions.CATEGORY_CLICK_TO_UNLOCK
            KEY_TO_UNLOCK_TYPE -> LockscreenBottomTextOptions.CATEGORY_KEY_TO_UNLOCK
            ENTER_TO_UNLOCK_TYPE -> LockscreenBottomTextOptions.CATEGORY_ENTER_TO_UNLOCK
            else -> LockscreenBottomTextOptions.CATEGORY_UNKNOWN
        }
        return !isAllowed(category)
    }

    private fun shouldHideCompactView(view: View): Boolean {
        return when (view.id) {
            zenViewId -> !isAllowed(LockscreenBottomTextOptions.CATEGORY_DND)
            notificationCountViewId -> !isAllowed(LockscreenBottomTextOptions.CATEGORY_NOTIFICATION_COUNT)
            dividingLineId ->
                !isAllowed(LockscreenBottomTextOptions.CATEGORY_DND) || !isAllowed(LockscreenBottomTextOptions.CATEGORY_NOTIFICATION_COUNT)
            else -> false
        }
    }

    private fun isAllowed(category: Int): Boolean {
        if (!enabled) return true
        val mask = Preferences.getInt(
            SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
            LockscreenBottomTextOptions.DEFAULT_MASK
        )
        val selected = (mask and category) != 0
        return if (Preferences.getInt(
                SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE,
                LockscreenBottomTextOptions.DEFAULT_MODE
            ) == LockscreenBottomTextOptions.MODE_WHITELIST
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
        return isAllowed(LockscreenBottomTextOptions.CATEGORY_DND) || isAllowed(LockscreenBottomTextOptions.CATEGORY_NOTIFICATION_COUNT)
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
        HostAccess.requirePublicField(this, name, accessible = false).getInt(null)
    }.getOrDefault(0)
}
