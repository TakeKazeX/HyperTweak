package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Customizes only OS4's new-control-center notification header
 * (`MiuiNotificationHeaderView`). The classic QS header, control-center header, status bar, and
 * lockscreen reuse some resource ids, so every target is first captured from this exact parent.
 */
object NotificationHeaderHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "NotificationHeader"
    private const val HEADER_CLASS = "com.android.systemui.qs.MiuiNotificationHeaderView"
    private const val CLOCK_CLASS = "com.android.systemui.statusbar.views.MiuiClock"
    private const val EXPAND_CONTROLLER_CLASS =
        "com.android.systemui.controlcenter.shade.NotificationHeaderExpandController"
    private const val CONFIG_CALLBACK_CLASS =
        "com.android.systemui.controlcenter.shade.NotificationHeaderExpandController\$configurationControllerCallback\$1"
    private const val COMBINED_HEADER_CLASS =
        "com.android.systemui.controlcenter.shade.CombinedHeaderController"
    private const val SHADE_HEADER_HEIGHT_ANIMATOR_CLASS =
        "com.miui.systemui.shade.header.ShadeHeaderHeightAnimator"
    private const val NOTIFICATION_TOP_PADDING_CONTROLLER_CLASS =
        "com.android.systemui.shade.NotificationTopPaddingControllerImpl"
    private const val NOTIFICATION_PANEL_VIEW_CONTROLLER_CLASS =
        "com.android.systemui.shade.NotificationPanelViewController"
    private const val QUICK_SETTINGS_CONTROLLER_CLASS =
        "com.android.systemui.shade.QuickSettingsControllerImpl"
    private const val COMBINED_HEADER_CONFIG_CALLBACK_CLASS =
        "com.android.systemui.controlcenter.shade.CombinedHeaderController\$configurationListener\$1"
    private const val NOTIFICATION_EXPANSION_CALLBACK_CLASS =
        "com.android.systemui.controlcenter.shade.NotificationHeaderExpandController\$notificationCallback\$1"
    private const val COMBINED_EXPANSION_CALLBACK_CLASS =
        "com.android.systemui.controlcenter.shade.CombinedHeaderExpandController\$notificationCallback\$1"
    private const val NOTIFICATION_STACK_CLASS =
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
    private const val MIUI_CONFIGS_CLASS = "com.miui.utils.configs.MiuiConfigs"

    private data class ControllerSizeState(
        val bigTimeSize: Int,
        val shadeSize: Int,
        val flipSize: Int
    )

    private val main = Handler(Looper.getMainLooper())
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val methods = HashMap<Triple<Class<*>, String, Int>, Method?>()
    private val layoutController = NotificationHeaderLayoutController(
        hostUsesVerticalMode = ::isVerticalMode,
        hostUsesLandscapeMode = ::isLandscapeMode
    ) { stage, error -> DebugLog.w(TAG, "$stage failed", error) }
    private val controllerSizes = WeakHashMap<Any, ControllerSizeState>()
    private val shadeHeaderHeightAnimators = WeakHashMap<Any, Boolean>()
    private val notificationTopPaddingControllers = WeakHashMap<Any, Boolean>()
    private val resettingHeaders = IdentityHashMap<ViewGroup, Int>()
    private val notificationTopPaddingRefreshDepth = ThreadLocal<Int>()
    private val notificationClockPositionDepth = ThreadLocal<Int>()

    @Volatile private var hideCarrier = false
    @Volatile private var hideTime = false
    @Volatile private var hideDate = false
    @Volatile private var dateAboveTime = false
    @Volatile private var dateAlignment = NotificationHeaderModel.ALIGN_START
    @Volatile private var timeAlignment = NotificationHeaderModel.ALIGN_START
    @Volatile private var timeScale = NotificationHeaderModel.DEFAULT_TIME_SCALE
    @Volatile private var restoring = false
    private var verticalModeMethod: Method? = null
    private var landscapeModeMethod: Method? = null

    override fun onPrepareHotReload() {
        restoring = true
        val restore = {
            val headerRoots = layoutController.roots()
            layoutController.restoreAll()
            shadeHeaderHeightAnimators.keys.toList().forEach(::restoreShadeHeaderClipHeight)
            refreshNotificationTopPadding()
            headerRoots.forEach(::updateCarrierFromHost)
            controllerSizes.forEach { (controller, state) ->
                runCatching {
                    field(controller.javaClass, "bigTimeSize")?.setInt(controller, state.bigTimeSize)
                    field(controller.javaClass, "shadeHeaderNotificationClockTextSize")
                        ?.setInt(controller, state.shadeSize)
                    field(controller.javaClass, "qsControlHeaderClockFlipSize")
                        ?.setInt(controller, state.flipSize)
                }
            }
            controllerSizes.clear()
            shadeHeaderHeightAnimators.clear()
            notificationTopPaddingControllers.clear()
            resettingHeaders.clear()
            notificationTopPaddingRefreshDepth.remove()
            notificationClockPositionDepth.remove()
            fields.clear()
            methods.clear()
            verticalModeMethod = null
            landscapeModeMethod = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            restore()
        } else {
            val latch = CountDownLatch(1)
            main.post { try { restore() } finally { latch.countDown() } }
            check(latch.await(5, TimeUnit.SECONDS)) { "notification header cleanup timed out" }
        }
        hideCarrier = false
        hideTime = false
        hideDate = false
        dateAboveTime = false
        dateAlignment = NotificationHeaderModel.ALIGN_START
        timeAlignment = NotificationHeaderModel.ALIGN_START
        timeScale = NotificationHeaderModel.DEFAULT_TIME_SCALE
    }

    override fun onHook() {
        restoring = false
        hideCarrier = Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_CARRIER, false)
        hideTime = Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_TIME, false)
        hideDate = Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_HIDE_DATE, false)
        dateAboveTime = Preferences.getBoolean(
            Preferences.KEY_NOTIFICATION_HEADER_DATE_ABOVE_TIME,
            false
        )
        dateAlignment = NotificationHeaderModel.normalizeAlignment(
            Preferences.getInt(
                Preferences.KEY_NOTIFICATION_HEADER_DATE_ALIGNMENT,
                NotificationHeaderModel.ALIGN_START
            )
        )
        timeAlignment = NotificationHeaderModel.normalizeAlignment(
            Preferences.getInt(
                Preferences.KEY_NOTIFICATION_HEADER_TIME_ALIGNMENT,
                NotificationHeaderModel.ALIGN_START
            )
        )
        timeScale = NotificationHeaderModel.normalizeTimeScale(
            Preferences.getNotificationHeaderTimeScale()
        )
        layoutController.configure(
            NotificationHeaderLayoutController.Options(
                hideCarrier = hideCarrier,
                hideTime = hideTime,
                hideDate = hideDate,
                dateAboveTime = dateAboveTime,
                dateAlignment = dateAlignment,
                timeAlignment = timeAlignment,
                timeScale = timeScale
            )
        )
        layoutController.setNotificationReserveChangedListener {
            syncShadeHeaderClipHeight()
            refreshNotificationTopPadding()
        }

        val hasViewWork = hideCarrier || hideTime || hideDate || dateAboveTime ||
            dateAlignment != NotificationHeaderModel.ALIGN_START ||
            timeAlignment != NotificationHeaderModel.ALIGN_START
        val hasSizeWork = timeScale != NotificationHeaderModel.DEFAULT_TIME_SCALE
        if (!hasViewWork && !hasSizeWork) return

        if (hasViewWork || hasSizeWork) installHeaderHooks()
        if (hasSizeWork) installClockSizeHooks()
        if (hasViewWork || hasSizeWork) installHeaderHeightAccounting()
        DebugLog.hookRegistered(
            TAG,
            "new-control-center header carrier=$hideCarrier time=$hideTime date=$hideDate " +
                "dateAbove=$dateAboveTime dateAlign=$dateAlignment timeAlign=$timeAlignment " +
                "timeScale=$timeScale"
        )
    }

    private fun installHeaderHooks() {
        val headerClass = HEADER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, HEADER_CLASS, "class not found")
            return
        }
        val lifecycleMethods = listOf(
            "onFinishInflate" to false,
            "onAttachedToWindow" to false,
            "updateLayout" to true,
            "updateHeaderResources" to true,
            "updateFlipResources" to true,
            "updateCarrierText\$1" to true
        )
        var hookCount = 0
        lifecycleMethods.forEach { (name, resetsLayout) ->
            headerClass.declaredMethods.filter { it.name == name && it.parameterCount == 0 }
                .forEach { method ->
                    deoptimize(method)
                    method.hook {
                        if (resetsLayout) before { param ->
                            if (!restoring) guarded("$name before layout") {
                                (param.thisObject as? ViewGroup)?.let(::beginHostLayoutReset)
                            }
                        }
                        after { param ->
                            if (!restoring) guarded(name) {
                                val root = param.thisObject as? ViewGroup ?: return@guarded
                                if (resetsLayout) endHostLayoutReset(root) else applyHeader(root)
                            }
                        }
                    }
                    hookCount++
                }
        }

        val hostConfigurationCallbacks = setOf(
            "onConfigChanged",
            "onConfigurationChanged",
            "onDensityOrFontScaleChanged",
            "onMaxBoundsChanged",
            "onUiModeChanged"
        )
        headerClass.declaredMethods
            .filter { it.name in hostConfigurationCallbacks }
            .forEach { method ->
                deoptimize(method)
                method.hook {
                    before { param ->
                        if (!restoring) guarded("${method.name} begin") {
                            (param.thisObject as? ViewGroup)?.let(::beginHostLayoutReset)
                        }
                    }
                    after { param ->
                        if (!restoring) guarded("${method.name} end") {
                            (param.thisObject as? ViewGroup)?.let(::endHostLayoutReset)
                        }
                    }
                }
                hookCount++
            }

        headerClass.declaredMethods
            .filter { it.name == "onDetachedFromWindow" && it.parameterCount == 0 }
            .forEach { method ->
                deoptimize(method)
                method.hook {
                    after { param ->
                        if (!restoring) guarded("header detach") {
                            (param.thisObject as? ViewGroup)?.let { root ->
                                resettingHeaders.remove(root)
                                layoutController.release(root)
                                updateCarrierFromHost(root)
                                syncShadeHeaderClipHeight()
                            }
                        }
                    }
                }
                hookCount++
            }

        ViewGroup::class.java.declaredMethods
            .firstOrNull {
                it.name == "dispatchApplyWindowInsets" &&
                    it.parameterTypes.contentEquals(arrayOf(WindowInsets::class.java))
            }?.let { method ->
                deoptimize(method)
                method.hook {
                    after { param ->
                        if (!restoring) guarded("header insets") {
                            val root = param.thisObject as? ViewGroup ?: return@guarded
                            if (resettingHeaders.containsKey(root) || !layoutController.contains(root)) return@guarded
                            layoutController.apply(root, param.args.firstOrNull() as? WindowInsets)
                            syncShadeHeaderClipHeight()
                            refreshNotificationTopPadding()
                        }
                    }
                }
                hookCount++
            }

        val clockClass = CLOCK_CLASS.toClassOrNull()
        listOf("setPolicyVisibility", "updateClockVisibility", "onAttachedToWindow")
            .forEach { name ->
                clockClass?.declaredMethods
                    ?.filter { it.name == name }
                    ?.forEach { method ->
                        deoptimize(method)
                        method.hook {
                            after { param ->
                                if (!restoring) guarded("$name visibility") {
                                    (param.thisObject as? View)?.let(::enforceVisibility)
                                }
                            }
                        }
                        hookCount++
                    }
            }

        if (hookCount == 0) {
            DebugLog.hookSkipped(TAG, "notification header view hooks", "no hook methods found")
        }
    }

    private fun applyHeader(root: ViewGroup) {
        if (!root.isAttachedToWindow || resettingHeaders.containsKey(root)) return
        layoutController.apply(root, root.rootWindowInsets)
        val time = layoutController.views(root)?.time as? TextView ?: return
        // Text/content and measured width can settle after the lifecycle/resource callback.
        time.post {
            if (!restoring && layoutController.onClockTextChanged(time)) {
                syncShadeHeaderClipHeight()
                refreshNotificationTopPadding()
            }
        }
        syncShadeHeaderClipHeight()
        refreshNotificationTopPadding()
    }

    private fun beginHostLayoutReset(root: ViewGroup) {
        resettingHeaders[root] = (resettingHeaders[root] ?: 0) + 1
        layoutController.beforeHostLayoutReset(root)
    }

    private fun endHostLayoutReset(root: ViewGroup) {
        val depth = resettingHeaders[root] ?: return
        if (depth > 1) {
            resettingHeaders[root] = depth - 1
            return
        }
        resettingHeaders.remove(root)
        applyHeader(root)
        root.post { if (!restoring) applyHeader(root) }
    }

    private fun enforceVisibility(view: View) {
        if (layoutController.rootFor(view) == null) return
        layoutController.enforceVisibility(view)
        syncShadeHeaderClipHeight()
        refreshNotificationTopPadding()
    }

    private fun installHeaderHeightAccounting() {
        NOTIFICATION_EXPANSION_CALLBACK_CLASS.toClassOrNull()
            ?.declaredMethods
            ?.filter { it.name == "onExpansionChanged" && it.parameterCount == 1 }
            ?.forEach { method ->
                deoptimize(method)
                method.hook {
                    after { guarded("notification expansion height") {
                        if (layoutController.onNativeExpansionChanged()) {
                            refreshNotificationTopPadding()
                        }
                        syncShadeHeaderClipHeight()
                    } }
                }
            }

        COMBINED_EXPANSION_CALLBACK_CLASS.toClassOrNull()
            ?.declaredMethods
            ?.filter { it.name == "onStretchHeightChanged" && it.parameterCount == 1 }
            ?.forEach { method ->
                deoptimize(method)
                method.hook {
                    after { param -> guarded("header stretch translation") {
                        val classId = field(param.thisObject.javaClass, "\$r8\$classId")
                            ?.getInt(param.thisObject) ?: return@guarded
                        if (classId != 0) return@guarded
                        val owner = read(param.thisObject, "this\$0") ?: return@guarded
                        val lazy = read(owner, "headerController") ?: return@guarded
                        val combined = lazy.javaClass.methods.firstOrNull {
                            it.name == "get" && it.parameterCount == 0
                        }?.invoke(lazy) ?: return@guarded
                        val root = read(combined, "notificationHeaderView") as? ViewGroup
                            ?: return@guarded
                        val translation = (param.args.firstOrNull() as? Number)?.toFloat() ?: 0f
                        layoutController.updateStretchTranslation(root, translation)
                        syncShadeHeaderClipHeight()
                    } }
                }
            }

        installNotificationTopPaddingHook()
        installShadeHeaderClipHooks()

        NOTIFICATION_STACK_CLASS.toClassOrNull()
            ?.findMethodOrNull { name("setOwnScrollY"); paramCount(4) }
            ?.let { method ->
                deoptimize(method)
                method.hook {
                    after { param -> guarded("notification list scroll") {
                        val scrollY = (read(param.thisObject, "mOwnScrollY") as? Number)?.toInt()
                            ?: (param.args.getOrNull(3) as? Number)?.toInt()
                            ?: return@guarded
                        layoutController.updateScrollForAll(scrollY)
                        syncShadeHeaderClipHeight()
                    } }
                }
            }

        val configCallback = COMBINED_HEADER_CONFIG_CALLBACK_CLASS.toClassOrNull()
        configCallback?.declaredMethods?.filter {
            it.name == "onConfigChanged" && it.parameterCount == 1
        }?.forEach { method ->
            deoptimize(method)
            method.hook {
                before { param -> guarded("shade header configuration before") {
                    val owner = read(param.thisObject, "this\$0") ?: return@guarded
                    val root = read(owner, "notificationHeaderView") as? ViewGroup
                        ?: return@guarded
                    beginHostLayoutReset(root)
                } }
                after { param -> guarded("shade header configuration") {
                    val owner = read(param.thisObject, "this\$0") ?: return@guarded
                    val root = read(owner, "notificationHeaderView") as? ViewGroup
                        ?: return@guarded
                    endHostLayoutReset(root)
                    syncShadeHeaderClipHeight()
                } }
            }
        }
    }

    private fun installShadeHeaderClipHooks() {
        val animatorClass = SHADE_HEADER_HEIGHT_ANIMATOR_CLASS.toClassOrNull() ?: return
        animatorClass.hookAllConstructors {
            after { param -> guarded("shade header height animator capture") {
                shadeHeaderHeightAnimators[param.thisObject] = true
                syncShadeHeaderClipHeight(param.thisObject)
            } }
        }
        animatorClass.declaredMethods
            .filter { it.name == "updateAnimateHeight" && it.parameterCount == 2 }
            .forEach { method ->
                deoptimize(method)
                method.hook {
                    after { param -> guarded("shade header clip height") {
                        shadeHeaderHeightAnimators[param.thisObject] = true
                        syncShadeHeaderClipHeight(param.thisObject)
                    } }
                }
            }
    }

    private fun syncShadeHeaderClipHeight() {
        shadeHeaderHeightAnimators.keys.toList().forEach(::syncShadeHeaderClipHeight)
    }

    private fun syncShadeHeaderClipHeight(heightAnimator: Any) {
        val shadeLazy = read(heightAnimator, "shadeHeaderController") ?: return
        val shadeController = shadeLazy.javaClass.methods.firstOrNull {
            it.name == "get" && it.parameterCount == 0
        }?.invoke(shadeLazy) ?: return
        val current = shadeController.javaClass.methods.firstOrNull {
            it.name == "getCurrent" && it.parameterCount == 0
        }?.invoke(shadeController) ?: return
        if (current.javaClass.name != COMBINED_HEADER_CLASS) return
        val root = read(current, "notificationHeaderView") as? ViewGroup ?: return
        val headerHeight = current.javaClass.methods.firstOrNull {
            it.name == "getHeight" && it.parameterCount == 0
        }?.invoke(current) as? Number ?: return
        val isCustomPortrait = layoutController.isCustomPortrait(root)
        val extraHeight = if (isCustomPortrait) layoutController.notificationPaddingExtra(root) else 0
        val scrollOffset = if (isCustomPortrait) layoutController.scrollY(root) else 0
        val targetHeight = NotificationHeaderMotionModel.remainingClipHeight(
            headerHeight.toFloat() + extraHeight,
            scrollOffset
        )
        val animator = read(heightAnimator, "animator") ?: return
        val update = animator.javaClass.methods.firstOrNull {
            it.name == "update" && it.parameterCount == 3
        } ?: return
        update.invoke(animator, targetHeight, null, false)
    }

    private fun restoreShadeHeaderClipHeight(heightAnimator: Any) {
        syncShadeHeaderClipHeight(heightAnimator)
    }

    private fun installNotificationTopPaddingHook() {
        val controllerClass = NOTIFICATION_TOP_PADDING_CONTROLLER_CLASS.toClassOrNull() ?: return
        controllerClass.declaredMethods
            .filter { it.name == "getTopPadding" && it.parameterCount == 0 }
            .forEach { method ->
                deoptimize(method)
                method.hook {
                    after { param -> guarded("custom notification top padding") {
                        val owner = param.thisObject
                        notificationTopPaddingControllers[owner] = true
                        if ((notificationTopPaddingRefreshDepth.get() ?: 0) == 0) return@guarded
                        if (!isUseControlCenter(owner) || isKeyguardOrUnoccluded(owner)) {
                            return@guarded
                        }
                        val root = notificationHeaderRoot(owner) ?: return@guarded
                        if (!layoutController.isCustomPortrait(root)) return@guarded
                        val extra = layoutController.notificationPaddingExtra(root)
                        if (extra <= 0) return@guarded
                        val stock = (param.result as? Number)?.toFloat() ?: return@guarded
                        param.result = stock + extra
                    } }
                }
            }

        val panelClass = NOTIFICATION_PANEL_VIEW_CONTROLLER_CLASS.toClassOrNull() ?: return
        panelClass.declaredMethods
            .filter { it.name == "requestScrollerTopPaddingUpdate" && it.parameterCount == 0 }
            .forEach { method ->
                deoptimize(method)
                method.hook {
                    before {
                        notificationTopPaddingRefreshDepth.set(
                            (notificationTopPaddingRefreshDepth.get() ?: 0) + 1
                        )
                    }
                    after {
                        val depth = notificationTopPaddingRefreshDepth.get() ?: 0
                        if (depth <= 1) notificationTopPaddingRefreshDepth.remove()
                        else notificationTopPaddingRefreshDepth.set(depth - 1)
                    }
                }
            }
        panelClass.declaredMethods
            .filter {
                it.name == "positionClockAndNotifications" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            .forEach { method ->
                deoptimize(method)
                method.hook {
                    before {
                        notificationClockPositionDepth.set(
                            (notificationClockPositionDepth.get() ?: 0) + 1
                        )
                    }
                    after {
                        val depth = notificationClockPositionDepth.get() ?: 0
                        if (depth <= 1) notificationClockPositionDepth.remove()
                        else notificationClockPositionDepth.set(depth - 1)
                    }
                }
            }

        val qsControllerClass = QUICK_SETTINGS_CONTROLLER_CLASS.toClassOrNull() ?: return
        qsControllerClass.declaredMethods
            .filter { it.name == "getHeaderHeight" && it.parameterCount == 0 }
            .forEach { method ->
                deoptimize(method)
                method.hook {
                    after { param -> guarded("notification-only header height") {
                        if ((notificationClockPositionDepth.get() ?: 0) == 0) return@guarded
                        val panelLazy = read(param.thisObject, "mPanelViewControllerLazy")
                            ?: return@guarded
                        val panel = invokeNoArgs(panelLazy, "get") ?: return@guarded
                        val extra = notificationExtraForPanel(panel)
                        if (extra <= 0) return@guarded
                        val stock = (param.result as? Number)?.toInt() ?: return@guarded
                        param.result = stock + extra
                    } }
                }
            }
    }

    private fun isUseControlCenter(topPaddingController: Any): Boolean {
        val controller = read(topPaddingController, "controlCenterSettingsController") ?: return false
        return invokeNoArgs(controller, "isUseControlCenter") as? Boolean ?: false
    }

    private fun isKeyguardOrUnoccluded(topPaddingController: Any): Boolean {
        val statusBarState = read(topPaddingController, "statusBarStateController")
            ?.let { invokeNoArgs(it, "getState") as? Number }
            ?.toInt() ?: return true
        if (statusBarState == KEYGUARD_STATE) return true

        val manager = read(topPaddingController, "notifUnoccludedManager") ?: return true
        val state = read(manager, "notifUnoccludedState") ?: return true
        val delegate = read(state, "\$\$delegate_0") ?: state
        return invokeNoArgs(delegate, "getValue") as? Boolean ?: true
    }

    private fun notificationHeaderRoot(topPaddingController: Any): ViewGroup? {
        val heightAnimatorLazy = read(topPaddingController, "shadeHeaderHeightAnimator") ?: return null
        val heightAnimator = invokeNoArgs(heightAnimatorLazy, "get") ?: return null
        val shadeControllerLazy = read(heightAnimator, "shadeHeaderController") ?: return null
        val shadeController = invokeNoArgs(shadeControllerLazy, "get") ?: return null
        val current = invokeNoArgs(shadeController, "getCurrent") ?: return null
        if (current.javaClass.name != COMBINED_HEADER_CLASS) return null
        return read(current, "notificationHeaderView") as? ViewGroup
    }

    private fun notificationExtraForPanel(panelController: Any): Int {
        val injector = read(panelController, "mNotifInjector") ?: return 0
        val topPaddingController = read(injector, "topPaddingController") ?: return 0
        if (!isUseControlCenter(topPaddingController) || isKeyguardOrUnoccluded(topPaddingController)) {
            return 0
        }
        if (read(panelController, "mSplitShadeEnabled") as? Boolean == true) return 0
        val root = notificationHeaderRoot(topPaddingController) ?: return 0
        if (!layoutController.isCustomPortrait(root)) return 0
        return layoutController.notificationPaddingExtra(root)
    }

    private fun refreshNotificationTopPadding() {
        notificationTopPaddingControllers.keys.toList().forEach { controller ->
            guarded("notification top padding refresh") {
                val panelControllerLazy = read(controller, "notificationPanelViewController")
                    ?: return@guarded
                val panelController = invokeNoArgs(panelControllerLazy, "get") ?: return@guarded
                invokeWithBoolean(panelController, "positionClockAndNotifications", false)
            }
        }
    }

    private fun installClockSizeHooks() {
        val controllerClass = EXPAND_CONTROLLER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, EXPAND_CONTROLLER_CLASS, "class not found")
            return
        }
        controllerClass.hookAllConstructors {
            after { param -> guarded("expand controller constructor") {
                applyClockSize(param.thisObject)
            } }
        }

        val callbackClass = CONFIG_CALLBACK_CLASS.toClassOrNull()
        callbackClass?.declaredMethods
            ?.filter { it.name == "onConfigChanged" && it.parameterCount == 1 }
            ?.forEach { method ->
                deoptimize(method)
                method.hook {
                    after { param -> guarded("clock size config") {
                        read(param.thisObject, "this\$0")?.let(::applyClockSize)
                    } }
                }
            }

        CLOCK_CLASS.toClassOrNull()?.declaredMethods
            ?.filter { it.name == "updateTime" && it.parameterCount == 0 }
            ?.forEach { method ->
                deoptimize(method)
                method.hook {
                    after { param -> guarded("expanded clock text width") {
                        val clock = param.thisObject as? TextView ?: return@guarded
                        if (layoutController.rootFor(clock) == null) return@guarded
                        clock.post {
                            if (!restoring && layoutController.onClockTextChanged(clock)) {
                                syncShadeHeaderClipHeight()
                                refreshNotificationTopPadding()
                            }
                        }
                    } }
                }
            }
    }

    @Suppress("DiscouragedApi")
    private fun applyClockSize(controller: Any) {
        val bigField = field(controller.javaClass, "bigTimeSize") ?: return
        val shadeField = field(controller.javaClass, "shadeHeaderNotificationClockTextSize") ?: return
        val flipField = field(controller.javaClass, "qsControlHeaderClockFlipSize") ?: return
        controllerSizes.putIfAbsent(
            controller,
            ControllerSizeState(
                bigField.getInt(controller),
                shadeField.getInt(controller),
                flipField.getInt(controller)
            )
        )
        val context = read(controller, "context") as? Context ?: return
        val resources = context.resources
        val shadeId = resources.getIdentifier(
            "shade_header_notification_clock_text_size",
            "dimen",
            "com.android.systemui"
        )
        val flipId = resources.getIdentifier(
            "qs_control_header_clock_flip_size",
            "dimen",
            "com.android.systemui"
        )
        if (shadeId == 0 || flipId == 0) return
        val sizeScale = if (isCustomPortraitMode(context)) {
            timeScale
        } else {
            NotificationHeaderModel.DEFAULT_TIME_SCALE
        }
        val shadeSize = (resources.getDimensionPixelSize(shadeId) * sizeScale).roundToInt()
        val flipSize = (resources.getDimensionPixelSize(flipId) * sizeScale).roundToInt()
        shadeField.setInt(controller, shadeSize)
        flipField.setInt(controller, flipSize)
        bigField.setInt(controller, if (isFlipTinyScreen(context)) flipSize else shadeSize)
    }

    private fun isVerticalMode(context: Context): Boolean {
        val method = verticalModeMethod ?: resolveMiuiConfigMethod("isVerticalMode")
            ?.also { verticalModeMethod = it }
        return runCatching { method?.invoke(null, context) as? Boolean }.getOrNull()
            ?: (context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun isLandscapeMode(context: Context): Boolean {
        val method = landscapeModeMethod ?: resolveMiuiConfigMethod("isLandscape")
            ?.also { landscapeModeMethod = it }
        return runCatching { method?.invoke(null, context) as? Boolean }.getOrNull()
            ?: (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun isCustomPortraitMode(context: Context): Boolean =
        !isLandscapeMode(context) &&
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
            isVerticalMode(context)

    private fun isFlipTinyScreen(context: Context): Boolean = runCatching {
        resolveMiuiConfigMethod("isFlipTinyScreen")?.invoke(null, context) as? Boolean
    }.getOrNull() ?: false

    private fun resolveMiuiConfigMethod(name: String): Method? =
        MIUI_CONFIGS_CLASS.toClassOrNull()?.declaredMethods?.firstOrNull {
            it.name == name && it.parameterCount == 1 &&
                Context::class.java.isAssignableFrom(it.parameterTypes[0])
        }?.apply { isAccessible = true }

    private fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)

    private fun invokeNoArgs(owner: Any, name: String): Any? {
        val method = methods.getOrPut(Triple(owner.javaClass, name, 0)) {
            owner.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        } ?: return null
        return runCatching { method.invoke(owner) }.getOrNull()
    }

    private fun invokeWithBoolean(owner: Any, name: String, value: Boolean): Any? {
        val method = methods.getOrPut(Triple(owner.javaClass, name, 1)) {
            owner.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
        } ?: return null
        return runCatching { method.invoke(owner, value) }.getOrNull()
    }

    private fun updateCarrierFromHost(root: ViewGroup) {
        runCatching {
            root.javaClass.methods.firstOrNull {
                it.name == "updateCarrierText\$1" && it.parameterCount == 0
            }?.invoke(root)
        }.onFailure { DebugLog.w(TAG, "failed to restore carrier visibility", it) }
    }

    private fun field(type: Class<*>, name: String): Field? = fields.getOrPut(type to name) {
        var current: Class<*>? = type
        var result: Field? = null
        while (current != null && result == null) {
            result = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            current = current.superclass
        }
        result
    }

    private inline fun guarded(stage: String, block: () -> Unit) {
        runCatching(block).onFailure { DebugLog.w(TAG, "$stage failed", it) }
    }

    private const val KEYGUARD_STATE = 1
}
