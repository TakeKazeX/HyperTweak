package com.takekazex.hypertweak.hook.rules.systemui

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.HookFactory
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 多任务过渡模糊 (SystemUI): restores the gaussian-blur cross-fade HyperOS uses when a freeform,
 * split-screen, sidebar or full-screen window is swapped, and removes the opaque bottom board that
 * otherwise hides it.
 *
 * This is a rewrite of the standalone `com.liu.freeformblur` module (legacy Xposed API 82). The
 * mechanism, verified against the device's `Miui-WindowManager-Shell.jar`
 * (`aae1827abff92240c405a77794506bebbb801b06a57c7f66e501ec181af42ded`), has three parts:
 *
 * 1. **Open the blur gates.** Every blur decision funnels through
 *    `MultiTaskingCommonUtils.isBlurAnimToggleEnable()` (8 call sites across the freeform and
 *    multiwin-switch paths) and `enableAdvancedMaterials()`. Both read `persist.sys.*` props that
 *    some regional/device builds ship disabled, so the props are forced as well — that layer also
 *    feeds `HyperMaterialUtils.mIsSupport` and the two recommendation-layout `SUPPORT_MIUI_BLUR`
 *    flags. `enableAdvancedMaterials()` additionally short-circuits on the baked `IS_Q18_CUSTOMIZE`
 *    static, which is why the method itself is hooked and not only the props.
 * 2. **Remove the opaque cover.** `MultiTaskingCoverLayerController` paints the mask shape view with
 *    the app's dominant colour (`setBackgroundColor`), and `MultiTaskingShapeView.onDraw` fills a
 *    bottom board whose colour comes from `R.color.drag_bottom_board_color` /
 *    `drag_bottom_board_stoke_color` in the `com.android.wm.shell` resource package (id 0x70).
 *    `SoScShapeView` paints its own unconditional board from `sosc_decor_bottom_board_color` /
 *    `sosc_decor_bottom_board_stoke_color`, so the resource hook — not `onDraw` — is what clears it.
 * 3. **Drive the mask blur down smoothly.** `setupCoverLayerStyle(transaction, snapshotAlpha,
 *    darkAlpha, iconAlpha, blurRadius)` applies the per-frame cover style. The snapshot layer is
 *    blanked (at full alpha it occludes the blur), and `buildLinkageTargetFolmeState`'s state gets
 *    explicit `100 -> 0` properties with the ROM's own hide easings so the mask fades out instead of
 *    hard-cutting.
 *
 * Deliberate differences from the original module, each backed by artifact checks:
 * - The original also forced SystemUI resource ids `0x7f060139` / `0x7f06013a` transparent, believing
 *   they were `multitasking_cover_bottom_board_color` / `_stroke`. On the current build they are
 *   `color/dim_foreground_material_dark` / `_light` — Material's dimmed disabled foreground, used
 *   across SystemUI — and the SystemUI resource table contains no `bottom_board` entry at all. Those
 *   ids are not touched here; the real bottom-board colours live in the WM Shell package and are
 *   matched by id plus resource name.
 * - The original registered an `onDraw` hook on `SoScShapeView` and then read `mTypeAnimInfo` from
 *   it. That field does not exist there, so every animation frame threw `NoSuchFieldError` into a
 *   swallowed `catch`. SoSc is covered by the colour hook instead.
 * - The original's `com.android.p073wm.shell.*` class names and its `MulWinSwitchCoverLayerController`
 *   fallback are decompiler / dead-code artifacts: neither exists in the WM Shell jar or in SystemUI.
 *   Both are dropped.
 *
 * Read once at hook-install time; a SystemUI restart applies a change.
 */
object FreeformBlurTransitionHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "FreeformBlur"

    // ─── WM Shell targets (verified on the OS4 device baseline) ────────────────────────────────

    private const val COMMON_UTILS =
        "com.android.wm.shell.multitasking.common.MultiTaskingCommonUtils"
    private const val SHAPE_VIEW =
        "com.android.wm.shell.multitasking.common.cover.MultiTaskingShapeView"
    private const val COVER_CONTROLLER =
        "com.android.wm.shell.multitasking.common.cover.MultiTaskingCoverLayerController"
    private const val LINKAGE_TRANSITION =
        "com.android.wm.shell.multitasking.common.linkage.MultiTaskingLinkageTransition"
    private const val LINKAGE_ANIMATION =
        "com.android.wm.shell.multitasking.common.linkage.MultiTaskingLinkageAnimation"
    private const val FOLME_CONTROL =
        "com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeControl"
    private const val EASE_MANAGER =
        "com.android.wm.shell.multitasking.common.animation.MultiTaskingEaseManager"

    private const val TYPE_ANIM_INFO_FIELD = "mTypeAnimInfo"
    private const val BOTTOM_BOARD_ALPHA_FIELD = "bottomBoardAlpha"
    private const val TRANSACTION_FIELD = "mTransaction"

    /**
     * `com.android.wm.shell` allocates resource ids in the 0x70 application range while SystemUI
     * allocates in 0x7f, so a bare-id cache cannot cross-contaminate the two. These two ids are the
     * fast path; [isBottomBoardColor]'s name check is the authoritative one — it also covers the
     * `sosc_decor_*` pair the original module missed, and survives a resource-id reassignment.
     */
    private const val DRAG_BOTTOM_BOARD_COLOR_ID = 0x700600a3
    private const val DRAG_BOTTOM_BOARD_STROKE_ID = 0x700600a4
    private const val BOTTOM_BOARD_MARKER = "bottom_board"

    // ─── SystemProperties keys ─────────────────────────────────────────────────────────────────

    private const val PROP_MASK_BLUR_ENABLE = "persist.sys.multi_task.mask_blur_enable"
    private const val PROP_BLUR_SUPPORT = "persist.sys.background_blur_supported"
    private const val PROP_BLUR_DEFAULT = "persist.sys.background_blur_status_default"
    private const val PROP_BLUR_SETTING = "persist.sys.background_blur_setting"

    private val BOOLEAN_PROPS = setOf(PROP_MASK_BLUR_ENABLE, PROP_BLUR_SUPPORT, PROP_BLUR_DEFAULT)
    private val STRING_PROPS = setOf(PROP_BLUR_SUPPORT, PROP_BLUR_DEFAULT)

    private const val PROP_BLUR_SETTING_ENABLED = 1

    // ─── Cached state ─────────────────────────────────────────────────────────────────────────

    @Volatile
    private var enabled = false

    private var targets: Targets? = null

    /** `getFc()` / `getCoverLayerContainer()` / `apply()` style lookups, resolved once per class. */
    private val dynamicMethods = ConcurrentHashMap<String, Method>()

    /** Resource-id verdicts, so the hot `Resources.getColor` path never resolves a name twice. */
    private val knownBottomBoardIds = ConcurrentHashMap.newKeySet<Int>()
    private val missedBottomBoardIds = ConcurrentHashMap.newKeySet<Int>()

    /** Everything group 3 needs, resolved once. Group 2 resolves its own shape-view members. */
    private class Targets(
        val setupCoverLayerStyle: Method,
        val transactionField: Field,
        val setMaskBlurRadius: Method,
        val setMaskIconAlpha: Method,
        val setMaskDarkAlpha: Method,
        val blurProp: Any,
        val iconAlphaProp: Any,
        val darkAlphaProp: Any,
        val snapshotAlphaProp: Any,
        val blurHideEase: Any,
        val coverHideEase: Any
    )

    override fun onPrepareHotReload() {
        enabled = false
        targets = null
        dynamicMethods.clear()
        knownBottomBoardIds.clear()
        missedBottomBoardIds.clear()
    }

    override fun onHook() {
        enabled = Preferences.freeformBlurTransition()
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "freeform blur transition", "disabled")
            return
        }

        // The three groups are independent: a host build that moved one of them must not disable the
        // rest, so each installs behind its own boundary and reports its own count.
        val gates = installBlurGates()
        val cover = installCoverLayerRemoval()
        val smooth = installSmoothBlur()

        if (gates + cover + smooth == 0) {
            DebugLog.hookSkipped(TAG, "freeform blur transition", "no target resolved")
            return
        }
        DebugLog.i(
            TAG,
            "freeform blur transition armed: gates=$gates cover=$cover smooth=$smooth"
        )
    }

    // ─── Group 1: blur gates ───────────────────────────────────────────────────────────────────

    /**
     * Opens the property layer and the two `MultiTaskingCommonUtils` gates. The property hooks only
     * rewrite a value the ROM reported as "off", so a build that already ships blur enabled keeps its
     * own value, and every unrelated property read in SystemUI is untouched.
     */
    private fun installBlurGates(): Int {
        var installed = 0
        val systemProperties = "android.os.SystemProperties".toClassOrNull()
        if (systemProperties == null) {
            DebugLog.hookSkipped(TAG, "android.os.SystemProperties", "class not found")
        } else {
            val getBoolean = systemProperties.singleMethodOrNull(
                "getBoolean",
                arrayOf<Class<*>>(String::class.java, Boolean::class.javaPrimitiveType!!),
                Boolean::class.javaPrimitiveType
            )
            if (getBoolean == null) {
                DebugLog.hookSkipped(TAG, "SystemProperties#getBoolean(String,boolean)", "method not found")
            } else if (prepare(getBoolean, "SystemProperties#getBoolean")) {
                getBoolean.hook("freeform_blur_prop_boolean") {
                    after { param ->
                        if (!enabled) return@after
                        val key = param.args.getOrNull(0) as? String ?: return@after
                        if (key !in BOOLEAN_PROPS) return@after
                        if (param.result == true) return@after
                        HookFailurePolicy.open(TAG, "force $key", Unit) { param.result = true }
                    }
                }
                installed++
            }

            val getInt = systemProperties.singleMethodOrNull(
                "getInt",
                arrayOf<Class<*>>(String::class.java, Int::class.javaPrimitiveType!!),
                Int::class.javaPrimitiveType
            )
            if (getInt == null) {
                DebugLog.hookSkipped(TAG, "SystemProperties#getInt(String,int)", "method not found")
            } else if (prepare(getInt, "SystemProperties#getInt")) {
                getInt.hook("freeform_blur_prop_int") {
                    after { param ->
                        if (!enabled) return@after
                        if (param.args.getOrNull(0) != PROP_BLUR_SETTING) return@after
                        if (param.result == PROP_BLUR_SETTING_ENABLED) return@after
                        HookFailurePolicy.open(TAG, "force $PROP_BLUR_SETTING", Unit) {
                            param.result = PROP_BLUR_SETTING_ENABLED
                        }
                    }
                }
                installed++
            }

            // The String overload feeds the baked `HyperMaterialUtils.mIsSupport` and the two
            // `SUPPORT_MIUI_BLUR` statics, which the original module never reached because it only
            // hooked getBoolean/getInt.
            val getString = systemProperties.singleMethodOrNull(
                "get",
                arrayOf<Class<*>>(String::class.java, String::class.java),
                String::class.java
            )
            if (getString == null) {
                DebugLog.hookSkipped(TAG, "SystemProperties#get(String,String)", "method not found")
            } else if (prepare(getString, "SystemProperties#get")) {
                getString.hook("freeform_blur_prop_string") {
                    after { param ->
                        if (!enabled) return@after
                        val key = param.args.getOrNull(0) as? String ?: return@after
                        if (key !in STRING_PROPS) return@after
                        if (param.result == "true") return@after
                        HookFailurePolicy.open(TAG, "force $key", Unit) { param.result = "true" }
                    }
                }
                installed++
            }
        }

        val utils = COMMON_UTILS.toClassOrNull()
        if (utils == null) {
            DebugLog.hookSkipped(TAG, COMMON_UTILS, "class not found")
        } else {
            listOf("isBlurAnimToggleEnable", "enableAdvancedMaterials").forEach { name ->
                val method = utils.declaredMethods.firstOrNull {
                    it.name == name && it.parameterCount == 0 &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }
                if (method == null) {
                    DebugLog.hookSkipped(TAG, "$COMMON_UTILS#$name", "method not found")
                    return@forEach
                }
                if (!prepare(method, "$COMMON_UTILS#$name")) return@forEach
                method.hook("freeform_blur_$name") { returnConstant(true) }
                installed++
            }
        }

        return installed
    }

    // ─── Group 2: opaque cover removal ─────────────────────────────────────────────────────────

    /**
     * Blanks the mask geometry the ROM fills before the blur is visible: the resource colours (both
     * the `drag_*` and `sosc_decor_*` boards), the shape view's solid dominant-colour background, and
     * the shape view's own bottom-board alpha.
     */
    private fun installCoverLayerRemoval(): Int {
        var installed = 0
        val shapeViewClass = SHAPE_VIEW.toClassOrNull()
        if (shapeViewClass == null) DebugLog.hookSkipped(TAG, SHAPE_VIEW, "class not found")

        // 2a. Resources.getColor — the two overloads the original module hooked. getColor(int)
        // delegates to getColor(int, Theme) on this platform; both are covered so a deoptimized
        // direct call site cannot slip through.
        runCatching {
            listOf(
                Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType),
                Resources::class.java.getMethod(
                    "getColor", Int::class.javaPrimitiveType, Resources.Theme::class.java
                )
            ).forEach { method ->
                deoptimize(method)
                method.hook("freeform_blur_bottom_board_${method.parameterCount}") {
                    before { param ->
                        if (!enabled) return@before
                        val resourcesInstance = param.thisObject as? Resources ?: return@before
                        val id = param.args.getOrNull(0) as? Int ?: return@before
                        if (!isBottomBoardColor(resourcesInstance, id)) return@before
                        param.result = Color.TRANSPARENT
                    }
                }
                installed++
            }
        }.onFailure { DebugLog.hookFailed(TAG, "Resources#getColor", it) }

        // 2b. View.setBackgroundColor — MultiTaskingCoverLayerController paints the shape view with
        // the app's dominant colour, which is the solid rectangle that hides the blur. Matched by
        // cached Class identity rather than by class name, because this method is called for every
        // background set anywhere in SystemUI.
        runCatching {
            val method = View::class.java.getMethod(
                "setBackgroundColor", Int::class.javaPrimitiveType
            )
            deoptimize(method)
            method.hook("freeform_blur_shape_view_background") {
                before { param ->
                    if (!enabled) return@before
                    val viewClass = shapeViewClass ?: return@before
                    if (!viewClass.isInstance(param.thisObject)) return@before
                    if (param.args.getOrNull(0) as? Int == Color.TRANSPARENT) return@before
                    HookFailurePolicy.open(TAG, "clear shape view background", Unit) {
                        param.args[0] = Color.TRANSPARENT
                    }
                }
            }
            installed++
        }.onFailure { DebugLog.hookFailed(TAG, "View#setBackgroundColor", it) }

        // 2c. MultiTaskingShapeView.onDraw — the board is drawn only while bottomBoardAlpha > 0, so
        // zeroing it every frame skips the fill and the stroke while keeping the app icon and label.
        // The two Fields are resolved once here; the original module re-resolved them per frame.
        if (shapeViewClass != null) {
            val typeAnimInfoField = shapeViewClass.fieldOrNull(TYPE_ANIM_INFO_FIELD)
            val alphaField = typeAnimInfoField?.type?.fieldOrNull(BOTTOM_BOARD_ALPHA_FIELD)
            if (typeAnimInfoField == null || alphaField == null) {
                DebugLog.hookSkipped(
                    TAG,
                    "$SHAPE_VIEW#$TYPE_ANIM_INFO_FIELD.$BOTTOM_BOARD_ALPHA_FIELD",
                    "field not found"
                )
            } else {
                val methods = shapeViewClass.declaredMethods.filter {
                    it.name == "onDraw" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == Canvas::class.java
                }
                if (methods.isEmpty()) {
                    DebugLog.hookSkipped(TAG, "$SHAPE_VIEW#onDraw(Canvas)", "method not found")
                }
                methods.forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        method.hook("freeform_blur_shape_view_on_draw_${method.parameterCount}") {
                            before { param ->
                                if (!enabled) return@before
                                HookFailurePolicy.open(TAG, "blank bottom board alpha", Unit) {
                                    val shapeView = param.thisObjectOrNull ?: return@open
                                    val animInfo = typeAnimInfoField.get(shapeView) ?: return@open
                                    alphaField.setFloat(animInfo, 0f)
                                }
                            }
                        }
                        installed++
                    }.onFailure { DebugLog.hookFailed(TAG, "$SHAPE_VIEW#onDraw(Canvas)", it) }
                }
            }
        }

        return installed
    }

    /**
     * True when [id] is one of the WM Shell bottom-board colours. The fast path is the two compiled
     * ids; the authoritative path is the resource name. Verdicts are cached because this runs on
     * every `Resources.getColor` call in SystemUI.
     */
    private fun isBottomBoardColor(resources: Resources, id: Int): Boolean {
        if (id == 0) return false
        if (id == DRAG_BOTTOM_BOARD_COLOR_ID || id == DRAG_BOTTOM_BOARD_STROKE_ID) return true
        if (id in knownBottomBoardIds) return true
        if (id in missedBottomBoardIds) return false
        val name = runCatching { resources.getResourceEntryName(id) }.getOrNull()
        val match = name != null && name.contains(BOTTOM_BOARD_MARKER)
        if (match) knownBottomBoardIds += id else missedBottomBoardIds += id
        return match
    }

    // ─── Group 3: smooth blur transition ───────────────────────────────────────────────────────

    /**
     * Rebuilds the cover-layer style and the Folme mask so the blur ramps `100 -> 0` with the ROM's
     * own hide easings. Every callback seeds the session's Folme control first, because the linkage
     * entry points run before the animated state is built and would otherwise start from whatever the
     * previous transition left behind.
     */
    private fun installSmoothBlur(): Int {
        val resolved = resolveTargets()
        if (resolved == null) {
            DebugLog.hookSkipped(TAG, "cover layer / linkage targets", "resolution failed")
            return 0
        }
        targets = resolved
        var installed = 0

        // 3a. setupCoverLayerStyle(transaction, snapshotAlpha, darkAlpha, iconAlpha, blurRadius)
        if (prepare(resolved.setupCoverLayerStyle, "$COVER_CONTROLLER#setupCoverLayerStyle")) {
            runCatching {
                resolved.setupCoverLayerStyle.hook("freeform_blur_cover_layer_style") {
                    before { param ->
                        if (!enabled) return@before
                        HookFailurePolicy.open(TAG, "setupCoverLayerStyle", Unit) {
                            // The snapshot layer is a flat copy of the task; at full alpha it hides
                            // the blur behind it, and the Folme state already fades it out.
                            param.args[1] = 0.0f

                            val darkAlpha = param.args.getOrNull(2) as? Float ?: return@open
                            val iconAlpha = param.args.getOrNull(3) as? Float ?: return@open
                            val blurRadius = param.args.getOrNull(4) as? Float ?: return@open

                            param.args[4] = freeformBlurRadius(blurRadius, iconAlpha)
                            param.args[2] = freeformDarkAlpha(darkAlpha, blurRadius, iconAlpha)
                        }
                    }
                }
                installed++
            }.onFailure { DebugLog.hookFailed(TAG, "$COVER_CONTROLLER#setupCoverLayerStyle", it) }
        }

        val seedFolme: (Any) -> Unit = { session ->
            HookFailurePolicy.open(TAG, "seed folme control", Unit) {
                seedFolmeControl(session, resolved)
            }
        }
        val seedCoverStyle: (Any, Any) -> Unit = { session, transaction ->
            HookFailurePolicy.open(TAG, "seed cover layer style", Unit) {
                val container = invokeMethod(session, "getCoverLayerContainer") ?: return@open
                val controller = invokeMethod(container, "getCoverLayerController") ?: return@open
                applyInitialCoverStyle(resolved, controller, transaction)
            }
        }

        // 3b. MultiTaskingLinkageTransition.initCoverLayerCommon (all overloads — the parameter list
        // grew from 4 to 5 between host builds, so only the name and args[0] are relied upon).
        installed += hookAllByName(LINKAGE_TRANSITION, "initCoverLayerCommon") {
            after { param ->
                if (!enabled) return@after
                HookFailurePolicy.open(TAG, "initCoverLayerCommon", Unit) {
                    val session = param.args.getOrNull(0) ?: return@open
                    seedFolme(session)

                    val container = invokeMethod(session, "getCoverLayerContainer") ?: return@open
                    val controller = invokeMethod(container, "getCoverLayerController")
                        ?: return@open
                    val transaction = resolved.transactionField.get(controller) ?: return@open
                    applyInitialCoverStyle(resolved, controller, transaction)
                    invokeMethod(transaction, "apply")
                }
            }
        }

        // 3c. The pre-animation seed, the two window-animation starts, and the Folme state that
        // carries the 100 -> 0 ramp.
        installed += hookAllByName(LINKAGE_ANIMATION, "startLinkageAnimInternal") {
            before { param ->
                if (!enabled) return@before
                param.args.getOrNull(0)?.let(seedFolme)
            }
        }

        listOf("startLinkageFreeFormAnimation", "startLinkageSplitScreenAnimation").forEach { name ->
            installed += hookAllByName(LINKAGE_ANIMATION, name) {
                before { param ->
                    if (!enabled) return@before
                    val session = param.args.getOrNull(0) ?: return@before
                    seedFolme(session)
                    val startTransaction = param.args.getOrNull(1) ?: return@before
                    seedCoverStyle(session, startTransaction)
                }
            }
        }

        installed += hookAllByName(LINKAGE_ANIMATION, "buildLinkageTargetFolmeState") {
            before { param ->
                if (!enabled) return@before
                param.args.getOrNull(0)?.let(seedFolme)
            }
            after { param ->
                if (!enabled) return@after
                HookFailurePolicy.open(TAG, "buildLinkageTargetFolmeState state", Unit) {
                    val state = param.result ?: return@open
                    addFolmeProperty(state, resolved.blurProp, DEFAULT_BLUR_RADIUS, 0.0f, resolved.blurHideEase, resolved)
                    addFolmeProperty(state, resolved.iconAlphaProp, DEFAULT_ICON_ALPHA, 0.0f, resolved.coverHideEase, resolved)
                    addFolmeProperty(state, resolved.darkAlphaProp, DEFAULT_DARK_ALPHA, 0.0f, resolved.coverHideEase, resolved)
                    // Re-assert the blanked snapshot layer so Folme cannot fade it back in.
                    addFolmeProperty(state, resolved.snapshotAlphaProp, 0.0f, 0.0f, resolved.coverHideEase, resolved)
                }
            }
        }

        return installed
    }

    /**
     * Hooks every declared method named [name] on [className], regardless of signature. The host
     * build changed `initCoverLayerCommon`'s parameter list between releases, so matching by name and
     * validating inside the callback is the only stable option.
     */
    private fun hookAllByName(
        className: String,
        name: String,
        block: HookFactory.() -> Unit
    ): Int {
        val clazz = className.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, className, "class not found")
            return 0
        }
        val methods = clazz.declaredMethods.filter { it.name == name }
        if (methods.isEmpty()) {
            DebugLog.hookSkipped(TAG, "$className#$name", "method not found")
            return 0
        }
        var installed = 0
        methods.forEach { method ->
            val hookId = "freeform_blur_${name}_" +
                method.parameterTypes.joinToString("_") { it.simpleName }
            runCatching {
                method.isAccessible = true
                deoptimize(method)
                method.hook(hookId, block = block)
                installed++
            }.onFailure { DebugLog.hookFailed(TAG, "$className#$name", it) }
        }
        return installed
    }

    /** Seeds the session's Folme mask at the values the linkage entry points expect. */
    private fun seedFolmeControl(session: Any, targets: Targets) {
        val fc = invokeMethod(session, "getFc") ?: return
        targets.setMaskBlurRadius.invoke(fc, DEFAULT_BLUR_RADIUS)
        targets.setMaskIconAlpha.invoke(fc, DEFAULT_ICON_ALPHA)
        targets.setMaskDarkAlpha.invoke(fc, DEFAULT_DARK_ALPHA)
    }

    /** Re-asserts the cover style the transition starts from: no snapshot, full icon, blur at 100. */
    private fun applyInitialCoverStyle(targets: Targets, controller: Any, transaction: Any) {
        targets.setupCoverLayerStyle.invoke(
            controller,
            transaction,
            0.0f,
            DEFAULT_DARK_ALPHA,
            DEFAULT_ICON_ALPHA,
            DEFAULT_BLUR_RADIUS
        )
    }

    /**
     * Adds `from -> to` on [state] with the ROM's own easing. Only the 4-argument overload sets an
     * explicit start value, and it is resolved from the state's own class because it is the one
     * `addProperty` whose first and last parameters accept our property and easing objects.
     */
    private fun addFolmeProperty(
        state: Any,
        property: Any,
        from: Float,
        to: Float,
        ease: Any,
        targets: Targets
    ) {
        val method = addPropertyMethod(state.javaClass, targets) ?: return
        method.invoke(state, property, from, to, ease)
    }

    private fun addPropertyMethod(stateClass: Class<*>, targets: Targets): Method? {
        val key = "${stateClass.name}#addProperty4"
        dynamicMethods[key]?.let { return it }
        val found = stateClass.methods.firstOrNull {
            it.name == "addProperty" && it.parameterCount == 4 &&
                it.parameterTypes[1] == Float::class.javaPrimitiveType &&
                it.parameterTypes[2] == Float::class.javaPrimitiveType &&
                it.parameterTypes[0].isInstance(targets.blurProp) &&
                it.parameterTypes[3].isInstance(targets.blurHideEase)
        } ?: return null
        dynamicMethods[key] = found
        return found
    }

    /** Invokes a public no-argument method, resolving and caching it per concrete class. */
    private fun invokeMethod(target: Any, name: String): Any? {
        val key = "${target.javaClass.name}#$name"
        val method = dynamicMethods[key] ?: runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
        }.getOrNull() ?: return null
        dynamicMethods[key] = method
        return runCatching { method.invoke(target) }.getOrNull()
    }

    // ─── Target resolution ─────────────────────────────────────────────────────────────────────

    private fun resolveTargets(): Targets? {
        val coverControllerClass = COVER_CONTROLLER.toClassOrNull()
        val folmeControlClass = FOLME_CONTROL.toClassOrNull()
        val easeManagerClass = EASE_MANAGER.toClassOrNull()
        if (coverControllerClass == null || folmeControlClass == null || easeManagerClass == null) {
            return null
        }

        val setupCoverLayerStyle = coverControllerClass.declaredMethods.firstOrNull {
            it.name == "setupCoverLayerStyle" && it.parameterCount == 5
        } ?: return null
        val transactionField = coverControllerClass.fieldOrNull(TRANSACTION_FIELD) ?: return null

        val setMaskBlurRadius = folmeControlClass.singleArgMethodOrNull("setMaskBlurRadius")
            ?: return null
        val setMaskIconAlpha = folmeControlClass.singleArgMethodOrNull("setMaskIconAlpha")
            ?: return null
        val setMaskDarkAlpha = folmeControlClass.singleArgMethodOrNull("setMaskDarkAlpha")
            ?: return null

        val blurProp = folmeControlClass.staticFieldOrNull("FOLME_MASK_BLUR_RADIUS") ?: return null
        val iconAlphaProp = folmeControlClass.staticFieldOrNull("FOLME_MASK_ICON_ALPHA") ?: return null
        val darkAlphaProp = folmeControlClass.staticFieldOrNull("FOLME_MASK_DARK_ALPHA") ?: return null
        val snapshotAlphaProp = folmeControlClass.staticFieldOrNull("FOLME_MASK_SNAPSHOT_ALPHA")
            ?: return null

        val blurHideEase = easeManagerClass.staticFieldOrNull("BLUR_HIDE_EASE") ?: return null
        val coverHideEase = easeManagerClass.staticFieldOrNull("COVER_LAYER_HIDE_EASE") ?: return null

        return Targets(
            setupCoverLayerStyle = setupCoverLayerStyle,
            transactionField = transactionField,
            setMaskBlurRadius = setMaskBlurRadius,
            setMaskIconAlpha = setMaskIconAlpha,
            setMaskDarkAlpha = setMaskDarkAlpha,
            blurProp = blurProp,
            iconAlphaProp = iconAlphaProp,
            darkAlphaProp = darkAlphaProp,
            snapshotAlphaProp = snapshotAlphaProp,
            blurHideEase = blurHideEase,
            coverHideEase = coverHideEase
        )
    }

    // ─── Reflection helpers ────────────────────────────────────────────────────────────────────

    /**
     * Marks [method] accessible and undoes AOT inlining. The blur gates and the shape-view `onDraw`
     * are small, hot methods whose call sites ART may have inlined before the hook installed.
     */
    private fun prepare(method: Method, label: String): Boolean = runCatching {
        method.isAccessible = true
        deoptimize(method)
        true
    }.onFailure { DebugLog.hookFailed(TAG, label, it) }.getOrDefault(false)

    private fun Class<*>.fieldOrNull(name: String): Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    /** The declared method with an exact [parameterTypes] list; [returnType] null means any. */
    private fun Class<*>.singleMethodOrNull(
        name: String,
        parameterTypes: Array<Class<*>>,
        returnType: Class<*>? = null
    ): Method? = runCatching {
        declaredMethods.firstOrNull {
            it.name == name &&
                it.parameterTypes.contentEquals(parameterTypes) &&
                (returnType == null || it.returnType == returnType)
        }?.apply { isAccessible = true }
    }.getOrNull()

    private fun Class<*>.singleArgMethodOrNull(name: String): Method? = runCatching {
        declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == 1 &&
                it.parameterTypes[0] == Float::class.javaPrimitiveType
        }?.apply { isAccessible = true }
    }.getOrNull()

    private fun Class<*>.staticFieldOrNull(name: String): Any? =
        runCatching { getDeclaredField(name).apply { isAccessible = true }.get(null) }.getOrNull()
}
