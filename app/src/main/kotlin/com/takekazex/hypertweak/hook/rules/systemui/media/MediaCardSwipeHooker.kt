package com.takekazex.hypertweak.hook.rules.systemui.media

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isGone
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** Hosts AOSP and HyperOS media pagers in the unlocked notification shade. */
internal object MediaCardSwipeHooker : StaticHooker() {
    private const val TAG = "MediaCardCarousel"
    private const val BASE = "com.android.systemui."
    private const val STACK = BASE + "statusbar.notification.stack.NotificationStackScrollLayout"
    private const val SHADE = 0

    private val legacyAosp by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_SWIPE, false)
    }
    private val style by hookSetting(-1) {
        Preferences.getInt(SystemUiSettingsKeys.KEY_MEDIA_CARD_CAROUSEL_STYLE, -1)
    }
    private val mode get() = style.takeIf { it in 0..2 } ?: if (legacyAosp) 1 else 0
    private val enabled get() = mode == 1 || mode == 2

    private var host: CarouselHost? by generationState { null }
    private val stacks = generationMainResource(WeakHashMap<ViewGroup, WeakReference<ShadeMediaCarouselLayer>>()) { it.clear() }
    private val savedPages = generationMainResource(WeakHashMap<ViewGroup, Array<*>>()) { it.clear() }
    private var carouselGesture: Long? by generationState { null }
    private var dispatchingCarousel by generationState { false }

    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) { recovery ->
            val contract = host ?: return@hostRecovery
            recovery.views.filterIsInstance<ViewGroup>().filter(contract.stack::isInstance)
                .forEach(::refresh)
        }
    }

    override fun onHook() {
        if (host != null) return
        val stack = STACK.toClassOrNull() ?: return
        val contract = CarouselHost.resolve(stack) ?: run {
            DebugLog.hookSkipped(TAG, STACK, "AOSP carousel contract unavailable")
            return
        }
        contract.reinflateViews.hook {
            after { param ->
                val sections = param.thisObject
                HookFailurePolicy.open(TAG, "attach after reinflate", Unit) {
                    contract.stackForSections(sections)?.let(::refresh)
                }
            }
        }
        contract.setTopMedia.hook {
            after { param ->
                if (!enabled) return@after
                val controller = param.args[0] ?: return@after
                HookFailurePolicy.open(TAG, "refresh shade media", Unit) {
                    contract.header(controller)?.parent?.let { it as? ViewGroup }
                        ?.takeIf(stack::isInstance)?.let(::refresh)
                }
            }
        }
        contract.headerAttached.hook {
            after { param ->
                val header = param.thisObject as? View ?: return@after
                (header.parent as? ViewGroup)?.takeIf(stack::isInstance)?.let(::refresh)
            }
        }
        contract.setStatusBarState.hook {
            after { param -> (param.thisObject as? ViewGroup)?.let(::refresh) }
        }
        contract.dispatchTouchEvent.hook {
            intercept { chain ->
                val view = chain.thisObject as? ViewGroup ?: return@intercept chain.proceed()
                val motion = chain.args.firstOrNull() as? MotionEvent ?: return@intercept chain.proceed()
                if (enabled && motion.actionMasked == MotionEvent.ACTION_DOWN && stacks[view]?.get() == null) {
                    refresh(view)
                }
                val layer = stacks[view]?.get()
                val previous = dispatchingCarousel
                dispatchingCarousel = HookFailurePolicy.open(TAG, "route carousel touches", false) {
                    if (motion.actionMasked == MotionEvent.ACTION_DOWN) {
                        carouselGesture = motion.downTime.takeIf { layer?.contains(motion) == true }
                    }
                    layer?.showing == true && carouselGesture == motion.downTime
                }
                try {
                    chain.proceed()
                } finally {
                    dispatchingCarousel = previous
                    if (motion.actionMasked == MotionEvent.ACTION_UP || motion.actionMasked == MotionEvent.ACTION_CANCEL) {
                        carouselGesture = null
                    }
                }
            }
        }
        // The notification dismissal recognizer competes with the Compose pager for horizontal
        // input. Vertical stack scrolling still runs through its own recognizer.
        contract.swipeIntercept.hook { before { if (dispatchingCarousel) it.result = false } }
        contract.swipeTouch.hook {
            before { if (dispatchingCarousel && contract.swipeHelper.isInstance(it.thisObject)) it.result = false }
        }
        host = contract
        DebugLog.i(TAG, "AOSP carousel contract ready")
    }

    fun refreshSettings() = HostAccess.onMain {
        if (mode != 2) savedPages.clear()
        stacks.keys.toList().forEach(::refresh)
    }

    override fun saveHotReloadState(): Any = HostAccess.onMain {
        stacks.mapNotNull { (stack, reference) ->

            val state = (reference.get() as? HyperOsMediaCarousel)?.saveState() ?: savedPages[stack]
            state?.let { arrayOf(stack, it) }
        }
    }

    override fun restoreHotReloadState(state: Any?) {
        HostAccess.onMain {
            (state as? List<*>)?.forEach { item ->
                val pair = item as? Array<*> ?: return@forEach
                val stack = pair.getOrNull(0) as? ViewGroup ?: return@forEach
                val saved = pair.getOrNull(1) as? Array<*> ?: return@forEach
                val layer = stacks[stack]?.get() as? HyperOsMediaCarousel
                if (layer == null) savedPages[stack] = saved else layer.restoreState(saved)
            }
        }
    }

    override fun onPrepareHotReload() = HostAccess.onMain {
        stacks.values.mapNotNull(WeakReference<ShadeMediaCarouselLayer>::get).forEach(ShadeMediaCarouselLayer::remove)
        stacks.clear()
        host?.releaseLegacySessions()
        carouselGesture = null
        dispatchingCarousel = false
    }

    private fun refresh(stack: ViewGroup) {
        if (stack !in stacks) stacks[stack] = WeakReference(null)
        HookFailurePolicy.open(TAG, "mount AOSP carousel", Unit) {
            val contract = host ?: return@open
            val old = stacks[stack]?.get()
            val hostCard = if (enabled) contract.card(stack) else null
            if (hostCard == null) {
                if (mode == 2 && old is HyperOsMediaCarousel) savedPages[stack] = old.saveState()
                old?.remove()
                stacks[stack] = WeakReference(null)
                if (stacks.values.none { it.get() != null }) contract.releaseLegacySessions()
                return@open
            }
            val hyperOs = mode == 2
            if (hyperOs) {
                contract.releaseLegacySessions()
            } else {
                contract.syncLegacySessions(hostCard)
            }
            if (old?.parent === hostCard.header && old.player === hostCard.player &&
                (old is HyperOsMediaCarousel) == hyperOs
            ) {
                if (old is HyperOsMediaCarousel) old.refreshPages()
                return@open
            }
            if (hyperOs && old is HyperOsMediaCarousel) savedPages[stack] = old.saveState()
            old?.remove()
            stacks[stack] = WeakReference(null)
            val layer = if (hyperOs) {
                val native = contract.hyperOs ?: return@open
                HyperOsMediaCarousel(hostCard.player, hostCard.controller, hostCard.factory, native).also {
                    savedPages.remove(stack)?.let(it::restoreState)
                }
            } else {
                CarouselLayer(hostCard, contract)
            }
            stacks[stack] = WeakReference(layer)
            hostCard.header.addView(layer, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            if (!hyperOs) DebugLog.i(TAG, "AOSP media carousel mounted")
        }
    }

    private class CarouselLayer(card: CarouselHost.Card, private val host: CarouselHost) :
        ShadeMediaCarouselLayer(card.player) {
        private val factory = card.factory
        private var compose: View? = null
        private var lifecycleRoot: FrameLayout? = null
        private var ownedLifecycle: Any? = null
        private var failed = false

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (failed) return
            try {
                val root = FrameLayout(context)
                lifecycleRoot = root
                addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                if (host.lifecycleOwner(root) == null) {
                    // ComposeInitializer accepts a window content child. Use a private child
                    // and supply the required parent ID only for the synchronous initialization.
                    val previousId = id
                    id = android.R.id.content
                    try {
                        host.initializeComposeRoot(root)
                        ownedLifecycle = host.lifecycleOwner(root)
                    } finally {
                        id = previousId
                    }
                }
                val lifecycle = checkNotNull(host.lifecycleOwner(root))
                checkNotNull(host.savedStateOwner(root))
                host.initializeBackDispatcher(this, lifecycle)
                val view = host.createComposeView(context, factory, ::fallback)
                compose = view
                root.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                    if (!failed && right > left && bottom > top) {
                        if (showCarousel()) DebugLog.i(TAG, "AOSP media carousel measured")
                    } else {
                        restorePlayer()
                    }
                }
            } catch (error: Throwable) {
                fallback(error)
            }
        }

        private fun fallback(error: Throwable) {
            if (failed) return
            failed = true
            DebugLog.w(TAG, "AOSP carousel fell back to the host card", error)
            // An exception can come from the composition itself. Dispose after its callback.
            post {
                restorePlayer()
                visibility = View.GONE
                HookFailurePolicy.open(TAG, "dispose failed carousel", Unit) { disposeContent() }
            }
        }

        private fun disposeContent() {
            val view = compose
            val root = lifecycleRoot
            val lifecycle = ownedLifecycle
            compose = null
            lifecycleRoot = null
            ownedLifecycle = null
            try {
                if (view != null) host.disposeCompose(view)
            } finally {
                try {
                    if (root?.parent === this) removeView(root)
                } finally {
                    if (lifecycle != null) host.destroyLifecycle(lifecycle)
                }
            }
        }

        override fun onDetachedFromWindow() {
            try {
                restorePlayer()
                HookFailurePolicy.open(TAG, "detach AOSP carousel", Unit) { disposeContent() }
            } finally {
                super.onDetachedFromWindow()
            }
        }

    }

    private class CarouselHost(val stack: Class<*>) {
        private val loader = stack.classLoader
        private val int = Int::class.javaPrimitiveType!!
        private val bool = Boolean::class.javaPrimitiveType!!
        private val sections = field(stack, "mSectionsManager")
        private val sectionsType = sections.type
        private val sectionsInjector = field(sectionsType, "notificationSectionsManagerInjector")
        private val parentStack = field(sectionsInjector.type, "parent")
        private val mediaController = field(sectionsInjector.type, "mediaController")
        private val owner = mediaController.type
        private val header = field(owner, "mediaContainerView")
        private val holder = field(owner, "mediaViewHolder")
        private val player = field(holder.type, "player")
        private val top = field(owner, "topMediaData")
        private val keyguardController = field(sectionsType, "keyguardMediaController")
        private val factory = field(keyguardController.type, "mediaViewModelFactory")
        private val carouselInteractor = field(keyguardController.type, "mediaCarouselInteractor")
        private val repository = field(carouselInteractor.type, "mediaRepository")
        private val legacyManager = type(BASE + "media.controls.domain.pipeline.LegacyMediaDataManagerImpl")
        private val manager = field(owner, "mediaDataManager")
        private val legacyFilter = field(legacyManager, "mediaDataFilter")
        private val legacyUserEntries = field(legacyFilter.type, "userEntries")
        private val userEntries = field(repository.type, "currentUserEntries")
        private val getUserEntries = method(userEntries.type, "getValue")
        private val instanceId = field(top.type, "instanceId")
        private val addEntry = method(repository.type, "addCurrentUserMediaEntry", top.type)
        private val removeEntry = method(repository.type, "removeCurrentUserMediaEntry", instanceId.type, top.type)
        private val published = WeakHashMap<Any, MutableMap<Any, Any>>()
        private val shadeState = field(stack, "mStatusBarState")
        private val compose = type("androidx.compose.ui.platform.ComposeView")
        private val function0 = type("kotlin.jvm.functions.Function0")
        private val function2 = type("kotlin.jvm.functions.Function2")
        private val modifier = type("androidx.compose.ui.Modifier")
        private val composer = type("androidx.compose.runtime.Composer")
        private val visibility = type(BASE + "media.remedia.ui.viewmodel.MediaCarouselVisibility")
        private val behavior = type(BASE + "media.remedia.ui.compose.MediaUiBehavior")
        private val composeConstructor = compose.getConstructor(Context::class.java)
        private val createBehavior = behavior.getConstructor(bool, visibility, function0, int)
        private val activeOnly = field(visibility, "WhenAnyCardIsActive").get(null)
        private val noModifier = field(modifier, "Companion").get(null)
        private val unit = field(type("kotlin.Unit"), "INSTANCE").get(null)
        private val setContent = method(compose, "setContent", function2)
        private val dispose = method(compose, "disposeComposition")
        private val initialize = method(type(BASE + "compose.ComposeInitializer"),
            "onAttachedToWindow", View::class.java, bool)
        private val getLifecycle = method(type("androidx.lifecycle.ViewTreeLifecycleOwner"), "get", View::class.java)
        private val getSavedState = method(type("androidx.savedstate.ViewTreeSavedStateRegistryOwner"),
            "get", View::class.java)
        private val destroy = method(type(BASE + "lifecycle.ViewLifecycleOwner"), "onDestroy")
        private val lifecycle = method(type("androidx.lifecycle.LifecycleOwner"), "getLifecycle")
        private val initBack = method(type(BASE + "InitOnBackPressedDispatcherOwnerKt"),
            "initOnBackPressedDispatcherOwner\$default", View::class.java, lifecycle.returnType)
        private val media = method(type(BASE + "media.remedia.ui.compose.MediaKt"), "Media",
            factory.type, behavior, function0, modifier, function0, function0, function0, composer, int, int)
        private val wrapContent = method(type("androidx.compose.runtime.internal.ComposableLambdaKt"),
            "composableLambdaInstance", int, bool, Any::class.java)
        val hyperOs = HyperOsMediaContract.resolve(owner, holder.type, top.type, factory.type)
        val reinflateViews = method(sectionsType, "reinflateViews")
        val headerAttached = method(header.type, "onAttachedToWindow")
        val setTopMedia = method(owner, "access\$setTopMediaData", owner, top.type)
        val setStatusBarState = method(stack, "setStatusBarState", int)
        val dispatchTouchEvent = method(stack, "dispatchTouchEvent", MotionEvent::class.java)
        val swipeHelper = type(BASE + "statusbar.notification.stack.NotificationSwipeHelper")
        val swipeIntercept = method(swipeHelper, "onInterceptTouchEvent", MotionEvent::class.java)
        val swipeTouch = method(swipeHelper, "onTouchEvent", MotionEvent::class.java)

        init {
            check(parentStack.type == stack && shadeState.type == int &&
                ViewGroup::class.java.isAssignableFrom(header.type) && View::class.java.isAssignableFrom(player.type))
            check(dispatchTouchEvent.declaringClass == stack && dispatchTouchEvent.returnType == bool &&
                swipeIntercept.declaringClass == swipeHelper && swipeIntercept.returnType == bool &&
                swipeTouch.returnType == bool && media.returnType == Void.TYPE &&
                headerAttached.declaringClass == header.type && removeEntry.returnType == bool &&
                Map::class.java.isAssignableFrom(legacyUserEntries.type))
        }

        data class Card(val header: ViewGroup, val player: View, val factory: Any, val controller: Any, val keyguard: Any)

        fun stackForSections(value: Any) = sectionsInjector.get(value)?.let(parentStack::get) as? ViewGroup
        fun header(controller: Any) = header.get(controller) as? ViewGroup

        fun card(stack: ViewGroup): Card? {
            if (shadeState.getInt(stack) != SHADE) return null
            val sections = sections.get(stack) ?: return null
            val owner = sectionsInjector.get(sections)?.let(mediaController::get) ?: return null
            val header = header(owner) ?: return null
            val player = holder.get(owner)?.let(player::get) as? View ?: return null
            if (HostAccess.ancestor(player, header.javaClass.name) !== header || top.get(owner) == null) return null
            val keyguard = keyguardController.get(sections) ?: return null
            val factory = factory.get(keyguard) ?: return null
            return Card(header, player, factory, owner, keyguard)
        }

        // MIUI still runs the legacy manager. Its filter has already applied user/profile policy
        // and associated device data. Feed those same instances into the dormant Compose repository.
        fun syncLegacySessions(card: Card) {
            val manager = manager.get(card.controller)?.takeIf(legacyManager::isInstance) ?: return
            val input = legacyFilter.get(manager)?.let(legacyUserEntries::get) as? Map<*, *> ?: return
            val repo = carouselInteractor.get(card.keyguard)?.let(repository::get) ?: return
            val current = getUserEntries.invoke(userEntries.get(repo)) as? Map<*, *> ?: return
            val owned = published.getOrPut(repo) { mutableMapOf() }
            val desired = input.values.filter(top.type::isInstance).associateBy(instanceId::get)
            val previousCount = owned.size
            for (id in owned.keys.toList()) {
                if (id !in desired) {
                    removeEntry.invoke(repo, id, owned.getValue(id))
                    owned.remove(id)
                }
            }
            for ((id, data) in desired) {
                if (id == null || data == null) continue
                val existing = current[id]
                if (existing != null && existing !== owned[id]) continue
                if (existing === data) continue
                addEntry.invoke(repo, data)
                owned[id] = data
            }
            if (owned.size != previousCount) DebugLog.i(TAG, "AOSP carousel legacy sessions=${owned.size}")
        }

        fun releaseLegacySessions() {
            for ((repo, entries) in published) {
                for ((id, data) in entries) {
                    HookFailurePolicy.open(TAG, "release AOSP media session", Unit) {
                        removeEntry.invoke(repo, id, data)
                    }
                }
            }
            published.clear()
        }

        fun lifecycleOwner(view: View): Any? = getLifecycle.invoke(null, view)
        fun savedStateOwner(view: View): Any? = getSavedState.invoke(null, view)
        fun initializeComposeRoot(view: View) { initialize.invoke(null, view, false) }
        fun initializeBackDispatcher(view: View, owner: Any) { initBack.invoke(null, view, lifecycle.invoke(owner)) }
        fun destroyLifecycle(owner: Any) { destroy.invoke(owner) }
        fun disposeCompose(view: View) { dispose.invoke(view) }

        fun createComposeView(context: Context, factory: Any, fail: (Throwable) -> Unit): View {
            val view = composeConstructor.newInstance(context) as View
            val configuration = createBehavior.newInstance(false, activeOnly, null, 0)
            val visible = callback(function0) { true }
            val content = callback(function2) { args ->
                try {
                    // Media's default exposure callback, with its standard card presentation.
                    media.invoke(null, factory, configuration, visible, noModifier,
                        null, null, null, args[0], 0, 0x20)
                } catch (error: Throwable) {
                    fail(error)
                }
                unit
            }
            setContent.invoke(view, wrapContent.invoke(null, TAG.hashCode(), true, content))
            return view
        }

        private fun callback(type: Class<*>, invoke: (Array<out Any?>) -> Any?) =
            Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "invoke" -> invoke(args.orEmpty())
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> TAG
                    else -> error("Unexpected Compose callback: ${method.name}")
                }
            }

        private fun type(name: String): Class<*> = Class.forName(name, false, loader)
        private fun field(owner: Class<*>, name: String) = checkNotNull(HostAccess.field(owner, name))
        private fun method(owner: Class<*>, name: String, vararg args: Class<*>): Method =
            checkNotNull(HostAccess.method(owner, name, *args))

        companion object {
            fun resolve(stack: Class<*>): CarouselHost? =
                HookFailurePolicy.open(TAG, "resolve AOSP carousel", null) { CarouselHost(stack) }
        }
    }
}


internal abstract class ShadeMediaCarouselLayer(val player: View) : FrameLayout(player.context) {
    private var restoreVisibility: Int? = null
    val showing get() = restoreVisibility != null && isShown

    protected fun showCarousel(): Boolean {
        if (restoreVisibility != null) return false
        restoreVisibility = player.visibility
        player.visibility = View.GONE
        return true
    }

    protected fun restorePlayer() {
        restoreVisibility?.let { if (player.isGone) player.visibility = it }
        restoreVisibility = null
    }

    fun remove() {
        restorePlayer()
        (parent as? ViewGroup)?.removeView(this)
    }

    fun contains(event: MotionEvent): Boolean {
        if (!showing) return false
        val rect = Rect()
        if (!getLocalVisibleRect(rect)) return false
        val location = IntArray(2)
        getLocationOnScreen(location)
        return rect.contains((event.rawX - location[0]).toInt(), (event.rawY - location[1]).toInt())
    }
}
