package com.takekazex.hypertweak.hook.rules.freeform

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.TaskInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Display
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.aicr.AicrCopyDirectBrowserPolicy
import com.takekazex.hypertweak.hook.rules.aicr.AicrCopyDirectAction
import com.takekazex.hypertweak.hook.rules.aicr.AicrCopyDirectFreeformSettings
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.FreeformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val WM = "com.android.server.wm."
private const val UTILS = "android.util.MiuiMultiWindowUtils"
private const val ADAPTER = "android.util.MiuiMultiWindowAdapter"
private val BOOL = Boolean::class.javaPrimitiveType!!
private val INT = Int::class.javaPrimitiveType!!
private val VOID = Void.TYPE

// These named framework contracts are checked in the host loader. Deoptimize the verified
// callers as well as the leaves so already compiled code can see the hooks on APK replacement.
internal abstract class FreeformHooker : StaticHooker() {
    protected val forceAllow by hookSetting(false) { Preferences.getBoolean(FreeformSettingsKeys.KEY_FORCE_ALLOW, false) }

    protected fun host(name: String): Class<*> = Class.forName(name, false, classLoader)

    protected fun target(
        owner: String,
        name: String,
        result: Class<*>,
        vararg parameters: Class<*>,
        static: Boolean = false,
    ): Method = host(owner).declaredMethods.singleOrNull {
        it.name == name && it.returnType == result &&
            it.parameterTypes.contentEquals(parameters) &&
            Modifier.isStatic(it.modifiers) == static && !Modifier.isAbstract(it.modifiers)
    } ?: error("Missing or ambiguous freeform contract: $owner#$name")

    protected fun Method.accessible(): Method = apply { isAccessible = true }

    protected fun freeformOptionsFactory() = target(
        UTILS, "getActivityOptions", ActivityOptions::class.java,
        Context::class.java, String::class.java, BOOL, BOOL, static = true,
    ).accessible()

    protected fun packageEligibility() =
        target(UTILS, "packageSupportFreeform", BOOL, Context::class.java, String::class.java, static = true)

    protected fun installFeature(name: String, block: () -> Unit) =
        HookFailurePolicy.open(hookerName, name, Unit) {
            block()
            DebugLog.i(hookerName, "freeform hooks ready feature=$name")
        }

    protected fun Method.overrideWhen(id: String, value: Any, enabled: () -> Boolean) {
        deoptimize(this)
        hook(id) { before { param -> if (enabled()) param.result = value } }
    }
}

internal object SystemServerFreeformHooker : FreeformHooker() {
    override val hookerName = "SystemServerFreeform"

    private val multipleWindows by hookSetting(false) { Preferences.getBoolean(FreeformSettingsKeys.KEY_COUNT, false) }
    private val jump by hookSetting(false) { Preferences.getBoolean(FreeformSettingsKeys.KEY_JUMP, false) }
    private val jumpShare by hookSetting(false) { Preferences.getBoolean(FreeformSettingsKeys.KEY_JUMP_SHARE, false) }
    private val jumpLinks by hookSetting(false) { Preferences.getBoolean(FreeformSettingsKeys.KEY_JUMP_CONTENT_LINKS, false) }
    private val foregroundPin by hookSetting(false) { Preferences.getBoolean(FreeformSettingsKeys.KEY_FOREGROUND_PIN, false) }
    private const val STRATEGY = WM + "MiuiFreeFormStackDisplayStrategy"
    private const val STACK = WM + "MiuiFreeFormActivityStack"
    private const val GESTURE = WM + "MiuiFreeFormGestureController"
    private const val SERVICE = WM + "MiuiFreeformServiceImpl"

    override fun onHook() {
        installFeature("count") {
            val (limit, added) = countContract()
            deoptimize(added)
            limit.overrideWhen("freeform_count", 256) { multipleWindows }
        }
        installFeature("foreground pin") {
            val decision = target(GESTURE, "needForegroundPin", BOOL, host(STACK))
            val caller = target(WM + "MiuiFreeFormManagerService", "shouldForegroundPin", BOOL, INT)
            deoptimize(caller)
            decision.overrideWhen("freeform_foreground_pin", true) { foregroundPin }
        }
        installFeature("force allow") { installForceAllow() }
        installFeature("jump") { installJump() }
    }

    private fun installJump() {
        val starter = host(WM + "ActivityStarter")
        val activity = host(WM + "ActivityRecord")
        val root = host(WM + "RootWindowContainer")
        val atm = host(WM + "ActivityTaskManagerService")
        val options = ActivityOptions::class.java
        val launch = target(
            starter.name, "startActivityUnchecked", INT,
            activity, activity, host("android.service.voice.IVoiceInteractionSession"),
            host("com.android.internal.app.IVoiceInteractor"), INT, options,
            host(WM + "Task"), host(WM + "TaskFragment"),
            host(WM + "BackgroundActivityStartController\$BalVerdict"),
            host("com.android.server.uri.NeededUriGrants"), INT, host(WM + "Transition"), BOOL, INT,
        )
        val intentField = activity.instanceField("intent", Intent::class.java)
        val infoField = activity.instanceField("info", ActivityInfo::class.java)
        val callerField = activity.instanceField("launchedFromPackage", String::class.java)
        val rootField = starter.instanceField("mRootWindowContainer", root)
        val atmField = starter.instanceField("mService", atm)
        val contextField = atm.instanceField("mContext", Context::class.java)
        val supportsFreeform = atm.instanceField("mSupportsFreeformWindowManagement", BOOL)
        val stub = host(WM + "MiuiFreeformServiceStub")
        val service = target(stub.name, "getInstance", stub, static = true).accessible()
        val desktop = target(WM + "MiuiDesktopModeUtils", "isDesktopActive", BOOL, static = true).accessible()
        val blackList = target(ADAPTER, "getStartFromFreeformBlackList", List::class.java, static = true).accessible()
        val requestFreeform = freeformOptionsFactory()
        val checkOptions = target(
            SERVICE, "modifyLaunchActivityOptionIfNeed", options, root, options, Intent::class.java,
        ).accessible()
        val mergeOptions = target(SERVICE, "modifyOptionsByFreeFormOptions", VOID, options, options, static = true)
            .accessible()
        val fromBundle = target(options.name, "fromBundle", options, Bundle::class.java, static = true).accessible()
        val miuiFlags = target(options.name, "getMiuiConfigFlag", INT).accessible()
        val setMiuiFlags = target(options.name, "setMiuiConfigFlag", VOID, INT).accessible()
        val windowMode = target(options.name, "getLaunchWindowingMode", INT).accessible()
        val launchDisplay = target(options.name, "getLaunchDisplayId", INT).accessible()
        val untargetedOptions = mapOf(
            "getLaunchTaskId" to -1,
            "getCallerDisplayId" to -1,
            "getLaunchTaskDisplayAreaFeatureId" to -1,
            "getLaunchActivityType" to 0,
        ).map { (name, default) -> target(options.name, name, INT).accessible() to default } +
            listOf(
                target(options.name, "getLaunchRootTask", host("android.window.WindowContainerToken")).accessible() to null,
                target(options.name, "getLaunchTaskDisplayArea", host("android.window.WindowContainerToken")).accessible() to null,
                target(options.name, "getLaunchTaskFragmentToken", host("android.os.IBinder")).accessible() to null,
                target(options.name, "isLaunchIntoPip", BOOL).accessible() to false,
                target(options.name, "getLockTaskMode", BOOL).accessible() to false,
                target(options.name, "getForceLaunchNewTask", BOOL).accessible() to false,
            )
        deoptimize(target(starter.name, "executeRequest", INT, host(WM + "ActivityStarter\$Request")))
        deoptimize(launch)

        // This receives the final activity after chooser redirection, SafeActivityOptions permission
        // checks and the background-start decision. Only add windowing options to that launch.
        launch.hook("freeform_jump_launch_options") {
            before { param ->
                HookFailurePolicy.open(hookerName, "jump launch options", Unit) {
                    val record = param.args[0] ?: return@open
                    val caller = callerField.get(record) as? String
                    val isAicr = caller == AicrCopyDirectBrowserPolicy.AICR_PACKAGE
                    if (!jump && !isAicr) return@open
                    val intent = intentField.get(record) as? Intent ?: return@open
                    val copyDirectAction = if (isAicr && intent.hasExtra(AicrCopyDirectAction.EXTRA)) {
                        val action = AicrCopyDirectAction.fromMarker(intent.getStringExtra(AicrCopyDirectAction.EXTRA))
                        intent.removeExtra(AicrCopyDirectAction.EXTRA)
                        action
                    } else null
                    if (!jump || (!jumpShare && !jumpLinks && copyDirectAction == null) ||
                        param.args[6] != null || param.args[7] != null
                    ) return@open
                    val info = infoField.get(record) as? ActivityInfo ?: return@open
                    val pkg = info.packageName ?: return@open
                    val name = info.name ?: return@open
                    val scenario = if (copyDirectAction != null) {
                        if (!AicrCopyDirectFreeformSettings.enabled(copyDirectAction) ||
                            !isFreeformLaunchTarget(pkg) || caller == pkg
                        ) return@open
                        null
                    } else {
                        val openSource = if (isAicr) intent.getStringExtra(AicrCopyDirectBrowserPolicy.OPEN_SOURCE)
                            else null
                        val launchScenario = freeformLaunchScenario(
                            caller, pkg, name, intent.action, intent.scheme, openSource,
                        ) ?: return@open
                        if (!launchScenario.isEnabled(share = jumpShare, contentLinks = jumpLinks)) return@open
                        launchScenario
                    }

                    val scenarioLabel = copyDirectAction?.let { "COPY_DIRECT_${it.name}" } ?: scenario?.name
                    val original = param.args[5] as? ActivityOptions
                    if (original != null) {
                        val mode = windowMode.invoke(original)
                        val contentFullscreen = scenario == FreeformLaunchScenario.CONTENT_LINK && mode == 1
                        val display = launchDisplay.invoke(original)
                        val copyDirectOnDefaultDisplay =
                            (copyDirectAction != null || scenario == FreeformLaunchScenario.COPY_DIRECT_LINK) &&
                                display == Display.DEFAULT_DISPLAY
                        if ((mode != 0 && !contentFullscreen) || original.launchBounds != null ||
                            (display != Display.INVALID_DISPLAY && !copyDirectOnDefaultDisplay) ||
                            untargetedOptions.any { (getter, default) -> getter.invoke(original) != default }
                        ) {
                            DebugLog.i(hookerName, "freeform launch skipped scenario=$scenarioLabel reason=explicit options")
                            return@open
                        }
                    }
                    val atmInstance = atmField.get(param.thisObject) ?: return@open
                    val context = contextField.get(atmInstance) as? Context ?: return@open
                    if (!supportsFreeform.getBoolean(atmInstance) || desktop.invoke(null) != false) return@open
                    val blocked = blackList.invoke(null) as? List<*> ?: return@open
                    if (ComponentName(pkg, name).flattenToString() in blocked) return@open
                    val native = requestFreeform.invoke(null, context, pkg, true, false) as? ActivityOptions
                    if (native == null) {
                        DebugLog.i(hookerName, "freeform launch skipped scenario=$scenarioLabel reason=native options unavailable")
                        return@open
                    }
                    val candidate = if (original == null) {
                        native
                    } else {
                        val copy = fromBundle.invoke(null, original.toBundle()) as? ActivityOptions ?: return@open
                        mergeOptions.invoke(null, copy, native)
                        val flags = miuiFlags.invoke(copy) as Int
                        setMiuiFlags.invoke(copy, flags or (miuiFlags.invoke(native) as Int))
                        copy
                    }
                    val checked = checkOptions.invoke(service.invoke(null), rootField.get(param.thisObject), candidate, intent)
                        as? ActivityOptions ?: return@open
                    if (windowMode.invoke(checked) == 5) {
                        param.args[5] = checked
                        DebugLog.i(hookerName, "freeform launch prepared scenario=$scenarioLabel mode=5")
                    } else {
                        DebugLog.i(hookerName, "freeform launch skipped scenario=$scenarioLabel reason=native policy")
                    }
                }
            }
        }
    }

    private fun Class<*>.instanceField(name: String, expectedType: Class<*>) =
        getDeclaredField(name).apply {
            check(type == expectedType && !Modifier.isStatic(modifiers)) {
                "Unexpected freeform field contract: ${declaringClass.name}#$name"
            }
            isAccessible = true
        }

    private fun countContract() =
        target(STRATEGY, "getMaxMiuiFreeFormStackCount", INT, String::class.java, host(STACK)) to
            target(
                STRATEGY, "onMiuiFreeFormStasckAdded", VOID,
                ConcurrentHashMap::class.java, host(GESTURE), host(STACK),
            )

    private fun installForceAllow() {
        val displayArea = host(WM + "TaskDisplayArea")
        val taskCheck = target(WM + "Task", "supportsMultiWindowInDisplayAreaForFreeForm", BOOL, displayArea)
        val activityCheck = target(
            WM + "ActivityRecordImpl", "supportsMultiWindowInDisplayAreaForFreeForm", BOOL, displayArea,
        )
        val recentsCheck = target(WM + "ActivityTaskManagerServiceImpl", "getTaskResizeableForFreeform", BOOL, INT)
        val normalResize = target(WM + "Task", "isResizeable", BOOL)
        val recentsResize = target(WM + "Task", "isResizeable", BOOL, BOOL)
        val nonResizable = target(WM + "TaskDisplayArea", "supportsNonResizableMultiWindow", BOOL)
        val forceResize = target(UTILS, "isForceResizeable", BOOL, static = true)
        val packageCheck = packageEligibility()
        val blacklists = listOf(
            target(ADAPTER, "getFreeformBlackList", List::class.java, static = true),
            target(ADAPTER, "getStartFromFreeformBlackList", List::class.java, static = true),
            target(ADAPTER, "getAbnormalFreeformBlackList", List::class.java, BOOL, static = true),
            target(UTILS, "getFreeformBlackList", List::class.java, Context::class.java, HashMap::class.java, BOOL, static = true),
        )
        val capabilityReaders = listOf(
            target(WM + "ActivityTaskSupervisorImpl", "supportsFreeform", BOOL, static = true),
            target(SERVICE, "supportsFreeform", BOOL),
            target(SERVICE, "isForceResizeable", BOOL),
            target(UTILS, "isNormalFreeForm", BOOL, Context::class.java, String::class.java, TaskInfo::class.java, static = true),
            target(ADAPTER, "calFreeformSuggestionList", Map::class.java, Context::class.java, List::class.java, HashMap::class.java, static = true),
        )
        val listReaders = listOf(
            target(SERVICE, "getFreeformBlackList", List::class.java),
            target(SERVICE, "getStartFromFreeformBlackList", List::class.java),
            target(SERVICE, "getAbnormalFreeformBlackList", List::class.java, BOOL),
            target(SERVICE, "getFreeformBlackList", List::class.java, Context::class.java, HashMap::class.java, BOOL),
            target(WM + "MiuiFreeFormManagerService", "isNormalFreeForm", BOOL, host(WM + "Task"), String::class.java),
        )
        val displayReaders = listOf("Task", "ActivityRecord").map { name ->
            target(WM + name, "supportsFreeformInDisplayArea", BOOL, displayArea)
        }
        (listOf(packageCheck, normalResize) + capabilityReaders + listReaders + displayReaders).forEach(::deoptimize)

        // Activation is atomic even if installation of a later callback fails. Enter/exit the
        // scope unconditionally so a settings update during the original call cannot leak it.
        val ready = AtomicBoolean(false)
        val displayDepth = ThreadLocal<Int>()
        val recentsDepth = ThreadLocal<Int>()
        taskCheck.trackCall("freeform_task_scope", displayDepth)
        activityCheck.trackCall("freeform_activity_scope", displayDepth)
        recentsCheck.trackCall("freeform_recents_scope", recentsDepth)
        nonResizable.overrideWhen("freeform_display_resize", true) {
            ready.get() && forceAllow && displayDepth.get() != null
        }
        recentsResize.overrideWhen("freeform_recents_resize", true) {
            ready.get() && forceAllow && recentsDepth.get() != null
        }
        forceResize.overrideWhen("freeform_force_resize", true) { ready.get() && forceAllow }
        blacklists.forEach { method ->
            deoptimize(method)
            method.hook("freeform_blacklist_${method.declaringClass.simpleName}_${method.name}") {
                before { param -> if (ready.get() && forceAllow) param.result = ArrayList<String>() }
            }
        }
        ready.set(true)
    }

    private fun Method.trackCall(id: String, depth: ThreadLocal<Int>) {
        deoptimize(this)
        hook(id) {
            before { depth.set((depth.get() ?: 0) + 1) }
            after {
                val current = depth.get() ?: return@after
                if (current == 1) depth.remove() else depth.set(current - 1)
            }
        }
    }
}

internal object SystemUiFreeformHooker : FreeformHooker() {
    override val hookerName = "SystemUiFreeform"

    private val jump by hookSetting(false) { Preferences.getBoolean(FreeformSettingsKeys.KEY_JUMP, false) }
    private val notificationTap by hookSetting(false) {
        Preferences.getBoolean(FreeformSettingsKeys.KEY_JUMP_NOTIFICATION, false)
    }

    override fun onHook() {
        if (!isMainProcess) return
        installFeature("notification tap") { installNotificationTap() }
        installFeature("force allow") {
            val decision = packageEligibility()
            val shell = "com.android.wm.shell.multitasking.common.MultiTaskingCommonUtils"
            listOf(String::class.java, ComponentName::class.java).forEach { type ->
                deoptimize(target(shell, "supportFreeform", BOOL, Context::class.java, type, static = true))
            }
            deoptimize(target(
                UTILS, "getActivityOptions", ActivityOptions::class.java,
                Context::class.java, String::class.java, BOOL, INT, INT, BOOL, static = true,
            ))
            deoptimize(decision)
            decision.hook("freeform_package_systemui") {
                before { param ->
                    if (forceAllow && param.args[0] is Context &&
                        !(param.args[1] as? String).isNullOrBlank()
                    ) param.result = true
                }
            }
        }
    }

    private fun installNotificationTap() {
        val notificationStarter = "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter"
        val notificationTap = target(
            notificationStarter, "startNotificationIntentPublic", VOID,
            PendingIntent::class.java, Intent::class.java,
            host("com.android.systemui.statusbar.notification.collection.NotificationEntry"),
            host("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"), BOOL, BOOL, BOOL,
        )
        val stockOptions = target(
            UTILS, "getActivityOptions", ActivityOptions::class.java, Context::class.java, String::class.java,
            static = true,
        )
        val requestFreeform = freeformOptionsFactory()
        deoptimize(notificationTap)
        deoptimize(stockOptions)

        // Only replace an empty factory result during the native notification-tap path. The
        // starter still owns keyguard dismissal, PendingIntent delivery and fallback handling.
        val taps = ThreadLocal<MutableList<PendingIntent?>>()
        stockOptions.hook("freeform_notification_tap_options") {
            after { param ->
                if (!isNotificationTapEnabled() || param.throwable != null || param.result != null) return@after
                val pending = taps.get()?.lastOrNull() ?: return@after
                HookFailurePolicy.open(hookerName, "notification tap options", Unit) {
                    val context = param.args[0] as? Context ?: return@open
                    val pkg = param.args[1] as? String ?: return@open
                    if (pkg != pending.creatorPackage || !isFreeformLaunchTarget(pkg)) return@open
                    if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return@open
                    val options = requestFreeform.invoke(null, context, pkg, true, false) as? ActivityOptions
                    if (options != null) param.result = options
                }
            }
        }
        notificationTap.hook("freeform_notification_tap_scope") {
            before { param ->
                val stack = taps.get() ?: run {
                    if (!isNotificationTapEnabled()) return@before
                    ArrayList<PendingIntent?>(1).also(taps::set)
                }
                stack.add(null)
                HookFailurePolicy.open(hookerName, "notification tap request", Unit) {
                    val pending = param.args[0] as? PendingIntent ?: return@open
                    if (isNotificationTapEnabled() && pending.isActivity) stack[stack.lastIndex] = pending
                }
            }
            after {
                val stack = taps.get() ?: return@after
                stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) taps.remove()
            }
        }
    }

    private fun isNotificationTapEnabled() = jump && notificationTap

}
