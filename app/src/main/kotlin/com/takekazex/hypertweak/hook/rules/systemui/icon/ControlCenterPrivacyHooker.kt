package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.animation.DecelerateInterpolator
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Restyles native privacy children. Native expansion/Folme remain the sole animation owner. */
object ControlCenterPrivacyHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED
    private const val TAG = "CcPrivacy"
    private const val VIEW = "com.android.systemui.controlcenter.shade.MiuiPrivacyContainerView"
    private const val HEADER = "com.android.systemui.controlcenter.shade.ControlCenterHeaderController"
    private const val CONTROLLER = "com.android.systemui.statusbar.privacy.MiuiPrivacyControllerImpl"
    private val main = Handler(Looper.getMainLooper())
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val methods = HashMap<Pair<Class<*>, String>, Method?>()
    private val constructors = HashMap<Class<*>, Constructor<*>>()
    private val bindings = WeakHashMap<ViewGroup, Binding>()
    private val headers = WeakHashMap<Any, Boolean>()
    private val layoutStates = WeakHashMap<View, ViewGroup.LayoutParams>()
    private val labels = LruCache<String, String>(64)
    private var privacyController: WeakReference<Any>? = null
    @Volatile private var enabled = false
    @Volatile private var retiring = false
    private var center = true
    private var background = false
    private var colored = true
    private var names = true
    private var allApps = true
    private var arrow = true
    private var dot = false
    private var iconSize = ControlCenterPrivacyPolicy.DEFAULT_ICON_SIZE
    private var textSize = ControlCenterPrivacyPolicy.DEFAULT_TEXT_SIZE
    private var offset = ControlCenterPrivacyPolicy.DEFAULT_OFFSET
    private var dependencyGet: Method? = null
    private var dependency: Any? = null
    private var dependencyClass: Class<*>? = null
    private var controllerClass: Class<*>? = null
    private var expiryAt = 0L
    private var primaryPackage: String? = null
    private val projectionHistory = LinkedHashMap<Pair<Int, String>, ControlCenterPrivacyPolicy.App>()
    private val expiryTask = Runnable {
        expiryAt = 0
        if (enabled && !retiring) guarded { bindings.values.toList().forEach(::updatePresentation) }
    }

    private class IconState(val view: ImageView) {
        val drawable = view.drawable
        val tint = view.imageTintList
        val background = view.background
        val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        val params = view.layoutParams
    }
    private class Binding(root: ViewGroup, val text: TextView, val icons: List<IconState>,
                          val chip: View, val dotView: View, val end: View) {
        val root = WeakReference(root)
        var textSize = text.textSize
        val maxWidth = text.maxWidth
        val ellipsize = text.ellipsize
        val singleLine = text.isSingleLine
        val compound = text.compoundDrawablesRelative
        val compoundPadding = text.compoundDrawablePadding
        val textParams = text.layoutParams
        val chipVisibility = chip.visibility
        val dotVisibility = dotView.visibility
        val endVisibility = end.visibility
        var originalText: CharSequence = text.text
        var nativePermissions: Set<ControlCenterPrivacyPolicy.Permission> = emptySet()
        var styledText: String? = null
        var lastColor: Int? = null
        var fake: WeakReference<View>? = null
        var fakeGravity: Int? = null
        var expansion: WeakReference<Any>? = null
        var refreshPending = false
        var renderedPermissions: Set<ControlCenterPrivacyPolicy.Permission>? = null
        var renderedText: String? = null
        var renderedVisible = false
        var animating = false
        lateinit var listener: View.OnLayoutChangeListener
        lateinit var refresh: Runnable
    }

    override fun onHook() {
        enabled = isMainProcess && Preferences.getBoolean(Preferences.KEY_CC_PRIVACY_ENABLED, false)
        retiring = false
        if (!enabled) return
        center = Preferences.getBoolean(Preferences.KEY_CC_PRIVACY_CENTER, true)
        background = Preferences.getBoolean(Preferences.KEY_CC_PRIVACY_BACKGROUND, false)
        colored = Preferences.getBoolean(Preferences.KEY_CC_PRIVACY_COLORED, true)
        names = Preferences.getBoolean(Preferences.KEY_CC_PRIVACY_NAMES, true)
        allApps = Preferences.getBoolean(Preferences.KEY_CC_PRIVACY_ALL_APPS, true)
        arrow = Preferences.getBoolean(Preferences.KEY_CC_PRIVACY_ARROW, true)
        dot = Preferences.getBoolean(Preferences.KEY_CC_PRIVACY_DOT, false)
        iconSize = Preferences.getInt(Preferences.KEY_CC_PRIVACY_ICON_SIZE, ControlCenterPrivacyPolicy.DEFAULT_ICON_SIZE).coerceIn(16, 32)
        textSize = Preferences.getInt(Preferences.KEY_CC_PRIVACY_TEXT_SIZE, ControlCenterPrivacyPolicy.DEFAULT_TEXT_SIZE).coerceIn(9, 20)
        offset = Preferences.getInt(Preferences.KEY_CC_PRIVACY_OFFSET, ControlCenterPrivacyPolicy.DEFAULT_OFFSET).coerceIn(-16, 24)
        val viewClass = VIEW.toClassOrNull() ?: return
        controllerClass = CONTROLLER.toClassOrNull() ?: return
        val headerClass = HEADER.toClassOrNull() ?: return
        // Reject incomplete host contracts before changing any views.
        if (listOf("mPrivacyText", "mIconCamera", "mIconMic", "mIconLocation", "mIconScreen",
                "mPrivacyBackground", "mIconDot", "mEndSpace").any { field(viewClass, it) == null }) {
            DebugLog.hookSkipped(TAG, "privacy header", "missing native children")
            return
        }
        val dependencyType = "com.android.systemui.Dependency".toClassOrNull() ?: return
        dependencyClass = dependencyType
        dependencyGet = dependencyType.declaredMethods.singleOrNull {
            it.name == "getDependencyInner" && it.parameterTypes.contentEquals(arrayOf(Class::class.java))
        }?.apply { isAccessible = true }
        // Restore before native configuration rebuilds so portrait snapshots cannot overwrite
        // the newly calculated landscape geometry or font-scale defaults afterward.
        before(viewClass, listOf("onConfigChanged", "onDensityOrFontScaleChanged", "updateDimens\$2")) { owner ->
            (owner as? ViewGroup)?.let { bindings[it] }?.let(::restore)
        }
        before(headerClass, listOf("updateConstraint")) { owner ->
            (read(owner, "privacyContainer") as? ViewGroup)?.let { bindings[it] }?.let(::restore)
        }
        hook(viewClass, listOf("onFinishInflate", "onAttachedToWindow",
            "onConfigChanged", "onDensityOrFontScaleChanged", "updateDimens\$2")) { owner, name ->
            val root = owner as? ViewGroup ?: return@hook
            if (name == "onDensityOrFontScaleChanged") bindings[root]?.let { it.textSize = it.text.textSize }
            bind(root)?.let(::style)
        }
        viewClass.findMethodOrNull { name("onPromptInfoChanged"); paramCount(2) }?.let { method ->
            deoptimize(method)
            method.hook { after { param ->
                if (!enabled || retiring) return@after
                guarded {
                    val root = param.thisObject as? ViewGroup ?: return@guarded
                    val binding = bind(root) ?: return@guarded
                    binding.originalText = binding.text.text
                    binding.styledText = null
                    binding.nativePermissions = promptPermissions(param.args.getOrNull(0), param.args.getOrNull(1))
                    style(binding)
                }
            } }
        }
        hook(viewClass, listOf("onDetachedFromWindow")) { owner, _ ->
            val root = owner as? ViewGroup ?: return@hook
            bindings.remove(root)?.let { binding ->
                root.removeOnLayoutChangeListener(binding.listener)
                binding.fake?.get()?.removeOnLayoutChangeListener(binding.listener)
                main.removeCallbacks(binding.refresh)
                restore(binding)
            }
            if (bindings.isEmpty()) {
                main.removeCallbacks(expiryTask)
                expiryAt = 0
                projectionHistory.clear()
            }
        }
        hook(headerClass, listOf("onInit", "updateConstraint", "updateDimens", "onConfigChanged",
            "onScreenLayoutSizeChanged", "updateCarrierAndPrivacyVisible")) { owner, _ ->
            headers[owner] = true
            val root = read(owner, "privacyContainer") as? ViewGroup ?: return@hook
            bind(root)?.let {
                setFake(it, read(owner, "fakePrivacyContainer") as? View)
                read(owner, "cchec")?.let { lazy -> invoke(lazy, "get") }
                    ?.let { expansion -> it.expansion = WeakReference(expansion) }
                place(it)
                style(it)
            }
        }
        hook(controllerClass!!, listOf("updatePrompt", "onPrivacyItemsChanged")) { owner, _ ->
            privacyController = WeakReference(owner)
            bindings.values.toList().forEach(::updatePresentation)
        }
        "com.android.systemui.statusbar.privacy.MiuiPrivacyHistoryTracker".toClassOrNull()?.let { tracker ->
            // The native active=false path ends with removeExpiredItems after mutating state.
            hook(tracker, listOf("upsertActiveItem", "removeExpiredItems")) { _, _ ->
                bindings.values.toList().forEach(::updatePresentation)
            }
        }
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController".toClassOrNull()?.let { expansion ->
            hook(expansion, listOf("updateLocation")) { owner, _ ->
                val combined = read(owner, "headerController")?.let { invoke(it, "get") }
                val root = combined?.let { read(it, "controlCenterPrivacyContainer") } as? ViewGroup
                root?.let { bindings[it] }?.expansion = WeakReference(owner)
            }
        }
        "com.android.systemui.controlcenter.shade.ControlCenterHeaderExpandController\$controlCenterCallback\$1"
            .toClassOrNull()?.let { callback ->
                // Re-evaluate elapsed-time expiry on opening, including after device sleep.
                hook(callback, listOf("onVisibleChanged", "onAppearanceChanged")) { _, _ ->
                    bindings.values.toList().forEach(::updatePresentation)
                }
                callback.declaredMethods.singleOrNull {
                it.name == "onExpansionChanged" && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
                }
            }?.let { method ->
                deoptimize(method)
                method.hook { before { param ->
                    if ((param.args.firstOrNull() as? Float) != 1f) guarded {
                        bindings.values.toList().filter { it.animating }.forEach(::stopTransition)
                    }
                } }
            }
        DebugLog.hookRegistered(TAG, "native privacy header and expansion anchors center=$center")
    }

    private fun hook(type: Class<*>, names: List<String>, action: (Any, String) -> Unit) {
        type.declaredMethods.filter { it.name in names }.forEach { method ->
            deoptimize(method)
            method.hook { after { param ->
                if (enabled && !retiring) guarded { action(param.thisObject, method.name) }
            } }
        }
    }

    private fun before(type: Class<*>, names: List<String>, action: (Any) -> Unit) {
        type.declaredMethods.filter { it.name in names }.forEach { method ->
            deoptimize(method)
            method.hook(hookId = "$hookerName:privacy-restore:${method.toGenericString()}") { before { param ->
                if (enabled && !retiring) guarded { action(param.thisObject) }
            } }
        }
    }

    internal fun recoverExistingViews(views: List<View>, component: Any? = null) {
        if (!enabled || retiring) return
        guarded {
            views.filterIsInstance<ViewGroup>().filter { it.javaClass.name == VIEW }.forEach { root ->
                bind(root)?.let { binding ->
                    val parent = root.parent as? ViewGroup
                    setFake(binding, parent?.findViewById<View>(id(root, "normal_fake_privacy_container")))
                    place(binding)
                    style(binding)
                }
            }
            if (bindings.isNotEmpty() && component != null) {
                val owner = read(component, "controlCenterHeaderControllerProvider")?.let { invoke(it, "get") }
                val expansion = read(component, "controlCenterHeaderExpandControllerProvider")?.let { invoke(it, "get") }
                if (owner != null) {
                    headers[owner] = true
                    val root = read(owner, "privacyContainer") as? ViewGroup
                    root?.let { bindings[it] }?.let { binding ->
                        setFake(binding, read(owner, "fakePrivacyContainer") as? View)
                        expansion?.let { binding.expansion = WeakReference(it) }
                    }
                    invoke(owner, "updateConstraint")
                }
            }
        }
    }

    private fun bind(root: ViewGroup): Binding? {
        bindings[root]?.let { return it }
        // Notification-shade and alternate surfaces keep their own native geometry.
        if (root.id != id(root, "normal_privacy_container") ||
            !ControlCenterHeaderHooker.supportsCompactLayout(root)) return null
        val text = read(root, "mPrivacyText") as? TextView ?: return null
        val icons = listOf("mIconCamera", "mIconMic", "mIconLocation", "mIconScreen")
            .map { read(root, it) as? ImageView ?: return null }.map(::IconState)
        val binding = Binding(root, text, icons,
            read(root, "mPrivacyBackground") as? View ?: return null,
            read(root, "mIconDot") as? View ?: return null,
            read(root, "mEndSpace") as? View ?: return null)
        binding.listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            guarded { if (enabled && !retiring) {
                place(binding)
                updateWidth(binding)
                if (!binding.refreshPending) {
                    binding.refreshPending = true
                    main.post(binding.refresh)
                }
            } }
        }
        binding.refresh = Runnable {
            binding.refreshPending = false
            if (enabled && !retiring) guarded {
                binding.expansion?.get()?.let { expansion ->
                    invoke(expansion, "updateLocation")
                    read(expansion, "controlCenterCallback")?.let { invoke(it, "updateProgress") }
                }
            }
        }
        bindings[root] = binding
        root.addOnLayoutChangeListener(binding.listener)
        return binding
    }

    private fun style(binding: Binding) {
        val root = binding.root.get() ?: return
        if (!enabled || retiring) return
        if (!ControlCenterHeaderHooker.supportsCompactLayout(root)) {
            restore(binding)
            return
        }
        binding.chip.visibility = if (background) binding.chipVisibility else View.INVISIBLE
        // Native expansion reads the separate fake dot. Remove the real trailing slot so the
        // visible icon/name/arrow group, rather than invisible whitespace, is centered.
        binding.dotView.visibility = if (dot) binding.dotVisibility else View.GONE
        val textParams = binding.text.layoutParams as? ViewGroup.MarginLayoutParams
        if (!dot && textParams != null && textParams.marginEnd != 0) {
            binding.text.layoutParams = copyParams(textParams).also {
                (it as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
            }
        }
        binding.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())
        binding.text.isSingleLine = true
        binding.text.ellipsize = TextUtils.TruncateAt.END
        val height = maxOf(dp(root, 26), dp(root, iconSize + 6),
            (binding.text.paint.fontMetrics.descent - binding.text.paint.fontMetrics.ascent).roundToInt() + dp(root, 8))
        if (root.layoutParams.height != height) {
            layoutStates.putIfAbsent(root, copyParams(root.layoutParams))
            root.layoutParams = copyParams(root.layoutParams).also { it.height = height }
        }
        val size = dp(root, iconSize)
        binding.icons.forEachIndexed { index, state ->
            val icon = state.view
            if (colored && icon.drawable !is PrivacyCircleDrawable) {
                val glyph = state.drawable?.constantState?.newDrawable(root.resources)?.mutate()
                if (glyph != null) {
                    glyph.setTint(Color.WHITE)
                    icon.imageTintList = null
                    icon.setImageDrawable(PrivacyCircleDrawable(glyph, COLORS[index]))
                }
            }
            val params = icon.layoutParams
            if (params.width != size || params.height != size) {
                val copy = copyParams(params)
                copy.width = size
                copy.height = size
                icon.layoutParams = copy
            }
        }
        updatePresentation(binding)
        place(binding)
        updateWidth(binding)
    }

    private fun updatePresentation(binding: Binding) {
        val root = binding.root.get() ?: return
        if (!enabled || retiring || !ControlCenterHeaderHooker.supportsCompactLayout(root)) return
        if (binding.styledText != binding.text.text.toString()) binding.originalText = binding.text.text
        val fallback = binding.originalText.toString()
        val presentation = appPresentation(root, fallback)
        val permissions = ControlCenterPrivacyPolicy.displayPermissions(
            presentation?.permissions, nativePermissions(binding))
        scheduleExpiry(presentation?.nextExpiryMs)
        val value = if (!names) "" else presentation?.names ?: fallback
        val nativeVisible = read(root, "mVisibility") == true
        val visible = (nativeVisible || permissions.isNotEmpty()) && read(root, "mNeedShowing") != false
        animateChange(binding, permissions, value, visible)
        root.visibility = if (visible) View.VISIBLE else View.GONE
        binding.fake?.get()?.let { fake ->
            val show = read(fake, "mVisibility") == true || permissions.isNotEmpty()
            fake.visibility = if (show && read(fake, "mNeedShowing") != false) View.VISIBLE else View.GONE
        }
        binding.icons.forEachIndexed { index, state ->
            state.view.visibility = if (ControlCenterPrivacyPolicy.Permission.entries[index] in permissions)
                View.VISIBLE else View.GONE
        }
        if (binding.text.text.toString() != value) binding.text.text = value
        binding.styledText = value
        val color = binding.text.currentTextColor
        if (binding.lastColor != color) {
            val chevron = if (arrow) PrivacyChevronDrawable(color).apply {
                setBounds(0, 0, dp(root, 7), dp(root, 12))
            } else null
            binding.text.setCompoundDrawablesRelative(null, null, chevron, null)
            binding.text.compoundDrawablePadding = dp(root, 5)
            binding.lastColor = color
        }
        val available = (root.parent as? View)?.width?.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val visibleIcons = binding.icons.count { it.view.visibility != View.GONE }
        binding.text.maxWidth = (available - dp(root, 64 + visibleIcons * (iconSize + 6)))
            .coerceAtLeast(dp(root, 24))
    }

    private fun resolvePrivacyController(): Any? {
        // Dependency can be unavailable during cold inflation; never infer permissions from Views.
        if (dependency == null) dependency = dependencyClass?.let { field(it, "sDependency")?.get(null) }
        return privacyController?.get() ?: runCatching {
            dependency?.let { dependencyGet?.invoke(it, controllerClass) }
        }.getOrNull()?.also { privacyController = WeakReference(it) }
    }

    private fun promptPermissions(miui: Any?, android: Any?): Set<ControlCenterPrivacyPolicy.Permission> =
        ControlCenterPrivacyPolicy.nativePermissions(miui?.let { invoke(it, "getMiuiType") } as? IntArray,
            android?.let { invoke(it, "getAndroidType") } as? IntArray, android != null)

    private fun nativePermissions(binding: Binding): Set<ControlCenterPrivacyPolicy.Permission> {
        val root = binding.root.get() ?: return emptySet()
        val controller = resolvePrivacyController()
            ?: return if (read(root, "mVisibility") == true) binding.nativePermissions else emptySet()
        val miui = read(controller, "mMiuiOptimization") as? Boolean ?: return binding.nativePermissions
        return promptPermissions(if (miui) read(controller, "mPromptInfo") else null,
            if (miui) null else read(controller, "mAndroidPromptInfo"))
            .also { binding.nativePermissions = it }
    }

    private fun appPresentation(root: View, fallback: String): ControlCenterPrivacyPolicy.Presentation? {
        val controller = resolvePrivacyController() ?: return null
        val tracker = read(controller, "mHistoryTracker")
        (read(controller, "mPromptInfo")?.let { read(it, "mPackageName") } as? String)
            ?.takeIf { it.isNotBlank() }?.let { primaryPackage = it }
        val history = tracker?.let { read(it, "privacyHistory") as? List<*> }
        val nativeItems = (read(controller, "mPrivacyItems") as? List<*>).orEmpty()
        val now = SystemClock.elapsedRealtime()
        val projections = nativeItems.mapNotNull { item ->
            item ?: return@mapNotNull null
            if ((read(item, "privacyType") as? Enum<*>)?.name != "TYPE_MEDIA_PROJECTION") return@mapNotNull null
            privacyApp(root, item, read(item, "paused") != true)
        }
        val liveKeys = projections.filter { it.active }.map { it.uid to it.packageName }.toSet()
        projectionHistory.replaceAll { key, app ->
            if (app.active && key !in liveKeys) app.copy(active = false, inactiveSinceMs = now) else app
        }
        projections.filter { it.active }.forEach { projectionHistory[it.uid to it.packageName] = it }
        projectionHistory.entries.removeAll { (_, app) -> !app.active && now - app.inactiveSinceMs >= ControlCenterPrivacyPolicy.RETENTION_MS }
        val apps = if (history != null) history.mapNotNull { state ->
            state ?: return@mapNotNull null
            read(state, "item")?.let { privacyApp(root, it, read(state, "isBeingCalled") == true,
                read(state, "inactiveSinceElapsed") as? Long ?: 0) }
        } + projectionHistory.values else nativeItems.mapNotNull {
            it?.let { item -> privacyApp(root, item, read(item, "paused") != true) }
        }
        val selected = if (allApps) apps else {
            val pkg = primaryPackage ?: return null
            apps.filter { it.packageName == pkg }
        }
        return ControlCenterPrivacyPolicy.presentation(selected, fallback, now)
    }

    private fun privacyApp(root: View, item: Any, active: Boolean, inactiveSinceMs: Long = 0): ControlCenterPrivacyPolicy.App? {
        val permission = when ((read(item, "privacyType") as? Enum<*>)?.name) {
            "TYPE_CAMERA" -> ControlCenterPrivacyPolicy.Permission.CAMERA
            "TYPE_MICROPHONE" -> ControlCenterPrivacyPolicy.Permission.MICROPHONE
            "TYPE_LOCATION" -> ControlCenterPrivacyPolicy.Permission.LOCATION
            "TYPE_MEDIA_PROJECTION" -> ControlCenterPrivacyPolicy.Permission.SCREEN
            else -> return null
        }
        val application = read(item, "application") ?: return null
        val uid = read(application, "uid") as? Int ?: return null
        val pkg = read(application, "packageName") as? String ?: return null
        val key = "$uid:$pkg"
        val label = labels[key] ?: runCatching {
            val pm = root.context.packageManager
            val resolver = methods.getOrPut(pm.javaClass to "getApplicationInfoAsUser") {
                pm.javaClass.methods.firstOrNull { it.name == "getApplicationInfoAsUser" &&
                    it.parameterTypes.contentEquals(arrayOf(String::class.java, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType)) }?.apply { isAccessible = true }
            }
            val info = resolver?.invoke(pm, pkg, 0, uid / 100000) as? ApplicationInfo
                ?: pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg).also { labels.put(key, it) }
        return ControlCenterPrivacyPolicy.App(uid, pkg, label, active, permission, inactiveSinceMs)
    }

    private fun scheduleExpiry(deadline: Long?) {
        val next = deadline ?: 0
        if (next == expiryAt) return
        main.removeCallbacks(expiryTask)
        expiryAt = next
        if (next > 0) main.postDelayed(expiryTask, (next - SystemClock.elapsedRealtime()).coerceAtLeast(1))
    }

    private fun animateChange(binding: Binding, permissions: Set<ControlCenterPrivacyPolicy.Permission>,
                              text: String, visible: Boolean) {
        val root = binding.root.get() ?: return
        val old = binding.renderedPermissions
        val changed = old != permissions || binding.renderedText != text || binding.renderedVisible != visible
        val callback = binding.expansion?.get()?.let { read(it, "controlCenterCallback") }
        val expanded = callback?.let { read(it, "progress") } as? Float == 1f
        if (old != null && changed && expanded && root.isLaidOut && root.isAttachedToWindow) {
            stopTransition(binding)
            // Native prompt callbacks may already have reset the children; recapture the last
            // displayed state before transitioning to the new shared snapshot.
            binding.icons.forEachIndexed { index, icon ->
                icon.view.visibility = if (ControlCenterPrivacyPolicy.Permission.entries[index] in old) View.VISIBLE else View.GONE
            }
            binding.text.text = binding.renderedText
            root.visibility = if (binding.renderedVisible) View.VISIBLE else View.GONE
            val parent = root.parent as? ViewGroup
            if (parent != null) {
                val transition = TransitionSet().addTransition(ChangeBounds()).addTransition(Fade())
                    .setDuration(220).setInterpolator(DecelerateInterpolator())
                transition.addTarget(root)
                transition.addTarget(binding.text)
                binding.icons.forEach { transition.addTarget(it.view) }
                transition.addListener(object : Transition.TransitionListener {
                    override fun onTransitionStart(t: Transition) { binding.animating = true }
                    override fun onTransitionEnd(t: Transition) { binding.animating = false }
                    override fun onTransitionCancel(t: Transition) { binding.animating = false }
                    override fun onTransitionPause(t: Transition) = Unit
                    override fun onTransitionResume(t: Transition) = Unit
                })
                binding.animating = true
                TransitionManager.beginDelayedTransition(parent, transition)
                if (binding.renderedText != text) {
                    binding.text.animate().cancel()
                    binding.text.alpha = 0f
                    binding.text.animate().alpha(1f).setDuration(180).start()
                }
            }
        }
        binding.renderedPermissions = permissions
        binding.renderedText = text
        binding.renderedVisible = visible
    }

    private fun stopTransition(binding: Binding) {
        binding.root.get()?.parent?.let { it as? ViewGroup }?.let {
            if (binding.animating) TransitionManager.endTransitions(it)
        }
        binding.animating = false
        binding.text.animate().cancel()
        binding.text.alpha = 1f
    }

    /** Match both containers before native updateLocation calculates the expansion deltas. */
    private fun place(binding: Binding) {
        val root = binding.root.get() ?: return
        if (!ControlCenterHeaderHooker.supportsCompactLayout(root)) return
        adjustAnchor(root)
        binding.fake?.get()?.let { fake ->
            adjustAnchor(fake)
            if ((center || isLandscape(root)) && fake is LinearLayout) {
                if (binding.fakeGravity == null) binding.fakeGravity = fake.gravity
                fake.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
    }

    private fun setFake(binding: Binding, fake: View?) {
        if (binding.fake?.get() === fake) return
        binding.fake?.get()?.removeOnLayoutChangeListener(binding.listener)
        binding.fake = fake?.let(::WeakReference)
        fake?.addOnLayoutChangeListener(binding.listener)
    }

    private fun adjustAnchor(view: View) {
        val original = view.layoutParams ?: return
        val params = copyParams(original)
        val placement = ControlCenterPrivacyPolicy.placement(enabled, isLandscape(view), center, offset)
        if (center || placement == ControlCenterPrivacyPolicy.Placement.CENTER_HEADER) {
            setInt(params, "startToStart", 0)
            setInt(params, "startToEnd", -1)
            setInt(params, "endToStart", -1)
            setInt(params, "endToEnd", 0)
        }
        val margin = dp(view, offset)
        if (placement == ControlCenterPrivacyPolicy.Placement.CENTER_HEADER) {
            setInt(params, "topToTop", 0)
            setInt(params, "topToBottom", -1)
            setInt(params, "bottomToBottom", 0)
            setInt(params, "bottomToTop", -1)
            setInt(params, "topMargin", 0)
            setInt(params, "bottomMargin", 0)
        } else if (placement == ControlCenterPrivacyPolicy.Placement.ABOVE_STATUS) {
            setInt(params, "topToTop", -1)
            setInt(params, "topToBottom", -1)
            setInt(params, "bottomToBottom", -1)
            setInt(params, "bottomToTop", id(view, "normal_control_center_status_bar"))
            setInt(params, "bottomMargin", -margin)
        }
        val changed = ANCHOR_FIELDS.any { name ->
            field(params.javaClass, name)?.let { it.get(params) != it.get(original) } == true
        }
        if (changed) {
            layoutStates.putIfAbsent(view, copyParams(original))
            view.layoutParams = params
        }
    }

    private fun updateWidth(binding: Binding) {
        val root = binding.root.get() ?: return
        val fake = binding.fake?.get() ?: return
        if (root.width <= 0 || !ControlCenterHeaderHooker.supportsCompactLayout(root)) return
        val width = if (center || isLandscape(root)) root.width else fake.layoutParams.width
        val height = root.layoutParams.height
        if (fake.layoutParams.width != width || fake.layoutParams.height != height) {
            layoutStates.putIfAbsent(fake, copyParams(fake.layoutParams))
            fake.layoutParams = copyParams(fake.layoutParams).also { it.width = width; it.height = height }
        }
    }

    private fun isLandscape(view: View): Boolean =
        view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun restore(binding: Binding) {
        val root = binding.root.get() ?: return
        stopTransition(binding)
        binding.renderedPermissions = null
        binding.renderedText = null
        root.visibility = if (read(root, "mVisibility") == true && read(root, "mNeedShowing") != false) View.VISIBLE else View.GONE
        binding.fake?.get()?.let { fake ->
            fake.visibility = if (read(fake, "mVisibility") == true && read(fake, "mNeedShowing") != false) View.VISIBLE else View.GONE
        }
        val native = nativePermissions(binding)
        binding.icons.forEachIndexed { index, state ->
            state.view.visibility = if (ControlCenterPrivacyPolicy.Permission.entries[index] in native) View.VISIBLE else View.GONE
            state.view.setImageDrawable(state.drawable)
            state.view.imageTintList = state.tint
            state.view.background = state.background
            state.view.setPadding(state.padding[0], state.padding[1], state.padding[2], state.padding[3])
            state.view.layoutParams = state.params
        }
        binding.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, binding.textSize)
        binding.text.maxWidth = binding.maxWidth
        binding.text.isSingleLine = binding.singleLine
        binding.text.ellipsize = binding.ellipsize
        binding.text.text = binding.originalText
        binding.text.setCompoundDrawablesRelative(binding.compound[0], binding.compound[1], binding.compound[2], binding.compound[3])
        binding.text.compoundDrawablePadding = binding.compoundPadding
        binding.text.layoutParams = binding.textParams
        binding.chip.visibility = binding.chipVisibility
        binding.dotView.visibility = binding.dotVisibility
        binding.end.visibility = binding.endVisibility
        binding.fake?.get()?.let { fake ->
            binding.fakeGravity?.let { (fake as? LinearLayout)?.gravity = it }
            layoutStates.remove(fake)?.let { fake.layoutParams = it }
        }
        layoutStates.remove(root)?.let { root.layoutParams = it }
        binding.lastColor = null
        binding.styledText = null
    }

    override fun onPrepareHotReload() {
        retiring = true
        enabled = false
        StatusIconHostAccess.onMain {
            bindings.values.toList().forEach { binding ->
                binding.root.get()?.removeOnLayoutChangeListener(binding.listener)
                binding.fake?.get()?.removeOnLayoutChangeListener(binding.listener)
                guarded { restore(binding) }
            }
            headers.keys.toList().forEach { owner -> guarded { invoke(owner, "updateConstraint") } }
            privacyController?.get()?.let { guarded { invoke(it, "updatePrompt") } }
            bindings.clear()
            headers.clear()
            layoutStates.clear()
            fields.clear()
            methods.clear()
            constructors.clear()
            labels.evictAll()
            projectionHistory.clear()
            expiryAt = 0
            primaryPackage = null
            privacyController = null
            dependency = null
            dependencyClass = null
            dependencyGet = null
            controllerClass = null
            main.removeCallbacksAndMessages(null)
        }
    }

    private fun copyParams(params: ViewGroup.LayoutParams): ViewGroup.LayoutParams =
        constructors.getOrPut(params.javaClass) {
            params.javaClass.getConstructor(ViewGroup.LayoutParams::class.java)
        }.newInstance(params) as ViewGroup.LayoutParams
    private fun id(view: View, name: String) = view.resources.getIdentifier(name, "id", view.context.packageName)
    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).roundToInt()
    private fun setInt(owner: Any, name: String, value: Int) { field(owner.javaClass, name)?.setInt(owner, value) }
    private fun field(type: Class<*>, name: String): Field? = fields.getOrPut(type to name) {
        generateSequence(type) { it.superclass }.mapNotNull {
            runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull()
    }
    private fun read(owner: Any, name: String) = field(owner.javaClass, name)?.get(owner)
    private fun invoke(owner: Any, name: String) = methods.getOrPut(owner.javaClass to name) {
        generateSequence(owner.javaClass) { it.superclass }.mapNotNull {
            runCatching { it.getDeclaredMethod(name).apply { isAccessible = true } }.getOrNull()
        }.firstOrNull()
    }?.invoke(owner)
    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { DebugLog.w(TAG, "native privacy boundary", it) }
    }
    private val COLORS = intArrayOf(0xff34c759.toInt(), 0xffff9f0a.toInt(), 0xff0a84ff.toInt(), 0xffaf52de.toInt())
    private val ANCHOR_FIELDS = listOf("startToStart", "startToEnd", "endToStart", "endToEnd",
        "topToTop", "topToBottom", "bottomToBottom", "bottomToTop", "topMargin", "bottomMargin")

    private class PrivacyCircleDrawable(private val glyph: Drawable, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) / 2f, paint)
            val inset = (minOf(b.width(), b.height()) * .23f).roundToInt()
            glyph.setBounds(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            glyph.draw(canvas)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; glyph.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { glyph.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private class PrivacyChevronDrawable(color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        override fun draw(canvas: Canvas) {
            val b = bounds
            paint.strokeWidth = b.width() * .2f
            canvas.drawLine(b.left + b.width() * .2f, b.top + b.height() * .15f,
                b.left + b.width() * .75f, b.exactCenterY(), paint)
            canvas.drawLine(b.left + b.width() * .75f, b.exactCenterY(),
                b.left + b.width() * .2f, b.bottom - b.height() * .15f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
