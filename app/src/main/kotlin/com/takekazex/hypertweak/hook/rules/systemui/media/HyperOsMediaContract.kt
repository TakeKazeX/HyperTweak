package com.takekazex.hypertweak.hook.rules.systemui.media

import android.animation.Animator
import android.content.Context
import android.graphics.Outline
import android.media.session.MediaController
import android.media.session.MediaSession
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.platform.ResourceLookup
import java.lang.reflect.Modifier
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/** Recreates the host holder setup and gives each page its own native binder, progress and scope. */
internal class HyperOsMediaContract private constructor(
    ownerType: Class<*>,
    private val holderType: Class<*>,
    mediaType: Class<*>,
    factoryType: Class<*>,
) {
    private val loader = ownerType.classLoader
    private val viewController = field(ownerType, "mediaViewController")
    private val binderType = viewController.type
    private val headerType = field(ownerType, "mediaContainerView").type
    private val materialState = field(ownerType, "notificationMaterialStateInteractor")
    private val setHeaderHolder = method(headerType, "setMediaViewHolder", holderType)
    private val setHeaderActualHeight = method(
        headerType, "setActualHeight", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
    )
    private val headerMedia = field(headerType, "topMediaData")
    private val bindMaterial = method(
        type("com.android.systemui.statusbar.notification.style.view.MediaViewBinder"),
        "bind", headerType, materialState.type,
    )
    private val binderFields = listOf(
        "context", "activityStarter", "seekBarProgressOwner", "mediaTransferManager", "fullAodController",
        "miPlayPluginManager", "mediaOutputDialogManager", "notificationStat", "miuiMediaAlbumAnimationUtils",
        "dynamicIslandController", "mainHandler", "miuiMediaActionButtonUtils", "miuiMediaWakeLockManager",
        "notificationMaterialStateInteractor", "scope", "powerInteractor", "panelExpandController",
        "controlCenterShadeWrapper",
    ).map { field(if (it == "powerInteractor") ownerType else binderType, it) }
    private val binderConstructor = binderType.getDeclaredConstructor(*binderFields.map { it.type }.toTypedArray())
    private val progressOwner = field(binderType, "seekBarProgressOwner")
    private val progressType = progressOwner.type
    private val seekViewModel = field(progressType, "seekBarViewModel")
    private val progressExecutor = field(progressType, "bgExecutor")
    private val seekExecutor = field(seekViewModel.type, "bgExecutor")
    private val progressConstructor = progressType.getConstructor(
        Context::class.java, seekViewModel.type, Executor::class.java,
    )
    private val controllerToken = field(progressType, "currentToken")
    private val controller = field(progressType, "currentController")
    private val lastProgress = field(progressType, "_seekBarRenderState")
    private val updateSeek = method(seekViewModel.type, "updateController", MediaController::class.java)
    private val attach = method(binderType, "attach", holderType)
    private val bind = method(binderType, "bindMediaData", mediaType)
    private val detach = method(binderType, "detach")
    private val token = field(mediaType, "token")
    private val instanceId = field(mediaType, "instanceId")
    private val packageName = field(mediaType, "packageName")
    private val userId = field(mediaType, "userId")
    private val isPlaying = field(mediaType, "isPlaying")
    private val sort = field(ownerType, "mediaSorUtils")
    private val sorted = field(sort.type, "mediaDataList")
    private val sortData = field(type(sort.type.name + "\$MediaSortKey"), "data")
    private val factoryParent = field(factoryType, "this\$0")
    private val component = field(factoryParent.type, "referenceSysUIComponentImpl")
    private val modalManager = method(component.type, "modalWindowManager")
    val chrome = HyperOsMediaChrome(loader, materialState.type, modalManager.returnType)
    private val createSeekModel = method(component.type, "seekBarViewModel")
    private val createActionButtons = method(component.type, "miuiMediaActionButtonUtils")
    private val albumAnimator = field(binderType, "miuiMediaAlbumAnimationUtils").type.getConstructor()
    private val nativeScope = field(binderType, "scope")
    private val context = method(nativeScope.type, "getCoroutineContext")
    private val plus = method(context.returnType, "plus", context.returnType)
    private val job = method(type("kotlinx.coroutines.SupervisorKt"), "SupervisorJob\$default")
    private val scopeFactory = type("kotlinx.coroutines.CoroutineScopeKt")
    private val createScope = method(scopeFactory, "CoroutineScope", context.returnType)
    private val cancelScope = method(scopeFactory, "cancel", nativeScope.type, CancellationException::class.java)
    // The vendor inflater constructs this zero-initialized data holder inline. ART exposes no
    // holder constructor on this build, so allocate it and populate every native holder field.
    private val unsafe = type("sun.misc.Unsafe")
    private val allocator = field(unsafe, "theUnsafe").also {
        check(it.type == unsafe && Modifier.isStatic(it.modifiers))
    }.get(null)
    private val allocateHolder = method(unsafe, "allocateInstance", Class::class.java)
    private val rootView = field(holderType, "player")
    private val childViews = mapOf(
        "mediaBg" to "media_bg",
        "elapsedTimeView" to "media_elapsed_time",
        "totalTimeView" to "media_total_time",
        "albumView" to "album_art",
        "albumImageView" to "album_art_image",
        "appIcon" to "icon",
        "titleText" to "header_title",
        "artistText" to "header_artist",
        "seamless" to "media_seamless",
        "seamlessIcon" to "media_seamless_image",
        "seamlessButton" to "media_seamless_button",
        "seekBar" to "media_progress_bar",
        "action0" to "action0",
        "action1" to "action1",
        "action2" to "action2",
        "action3" to "action3",
        "action4" to "action4",
    ).mapKeys { (name, _) -> field(holderType, name) }
    private val compact = method(type("com.miui.utils.configs.MiuiConfigs"), "isFlipTinyScreen", Context::class.java)
    private val normalLayout = field(ownerType, "normalLayout")
    private val normalAlbum = field(ownerType, "normalAlbumLayout")
    private val tinyLayout = field(ownerType, "tinyLayout")
    private val tinyAlbum = field(ownerType, "tinyAlbumLayout")
    private val applyLayout = method(normalLayout.type, "applyTo", type("androidx.constraintlayout.widget.ConstraintLayout"))

    init {
        check(createSeekModel.returnType == seekViewModel.type &&
            createActionButtons.returnType == field(binderType, "miuiMediaActionButtonUtils").type &&
            sortData.type == mediaType && View::class.java.isAssignableFrom(rootView.type) &&
            token.type == MediaSession.Token::class.java)
        binderConstructor.isAccessible = true
    }

    class Page(
        val view: ViewGroup,
        val player: View,
        val seekBar: View?,
        val binder: Any,
        val scope: Any,
        val progress: Any,
        val seekModel: Any,
    ) {
        var data: Any? = null
        var pressAnimator: Animator? = null
    }

    fun sessions(owner: Any): Map<Any, Any> {
        val entries = sort.get(owner)?.let(sorted::get) as? Iterable<*> ?: return emptyMap()
        val sessions = LinkedHashMap<Any, Any>()
        for (entry in entries) {
            val media = sortData.get(entry) ?: continue
            val key = instanceId.get(media) ?: continue
            sessions.putIfAbsent(key, media)
        }
        return sessions
    }

    fun app(media: Any): Pair<Int, String>? =
        (packageName.get(media) as? String)?.let { userId.getInt(media) to it }

    fun playing(media: Any): Boolean = isPlaying.get(media) == true

    fun notificationMaterial(owner: Any): Any = checkNotNull(materialState.get(owner))

    fun newModalManager(factory: Any): Any =
        checkNotNull(modalManager.invoke(component.get(factoryParent.get(factory))))

    fun pause(page: Page) {
        val media = page.data ?: return
        val controller = controller.get(page.progress) as? MediaController ?: return
        val currentlyPlaying = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        if (currentlyPlaying || playing(media)) controller.transportControls.pause()
    }

    fun createPage(owner: Any, factory: Any, original: View, parent: ViewGroup): Page {
        val deps = component.get(factoryParent.get(factory))
        val source = viewController.get(owner)
        val hostContext = original.context
        val inflater = LayoutInflater.from(hostContext)
        val header = inflater.inflate(resource(hostContext, "miui_media_header", "layout"), parent, false)
        check(headerType.isInstance(header))
        header as ViewGroup
        header.visibility = View.VISIBLE
        header.isClickable = false
        header.isFocusable = false
        // The stack sets this for its own rows. Nested pager headers are laid out by the
        // pager, but ExpandableView.pointInView still uses actualHeight for hit testing.
        header.addOnLayoutChangeListener { view, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val height = bottom - top
            if (height != oldBottom - oldTop) {
                HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "update media card touch bounds", Unit) {
                    setHeaderActualHeight.invoke(view, height, false)
                }
            }
        }
        val layout = resource(hostContext, "miui_media_session", "layout")
        val view = inflater.inflate(layout, header, false)
        view.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        val holder = allocateHolder.invoke(allocator, holderType) ?: error("No native holder")
        rootView.set(holder, view)
        childViews.forEach { (field, name) -> field.set(holder, view.requireViewById<View>(resource(hostContext, name, "id"))) }
        val seekBar = field(holderType, "seekBar").get(holder) as View
        seekBar.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val album = field(holderType, "albumView").get(holder) as View
        val compactCard = compact.invoke(null, hostContext) == true
        applyLayout.invoke((if (compactCard) tinyLayout else normalLayout).get(owner), view)
        applyLayout.invoke((if (compactCard) tinyAlbum else normalAlbum).get(owner), album)
        round(album, if (compactCard) "flip_album_art_bg_radius" else "album_art_bg_radius")
        round(field(holderType, "appIcon").get(holder) as View, "media_app_icon_bg_radius")
        round(field(holderType, "mediaBg").get(holder) as View,
            if (compactCard) "flip_notification_item_bg_radius" else "notification_item_bg_radius")

        val seekModel = createSeekModel.invoke(deps) ?: error("No native seek model")
        val executor = progressExecutor.get(progressOwner.get(source)) as Executor
        val progress = progressConstructor.newInstance(hostContext, seekModel, executor)
        val actionButtons = createActionButtons.invoke(deps)
        val animator = albumAnimator.newInstance()
        val coroutineContext = context.invoke(nativeScope.get(source))
        val scope = createScope.invoke(null, plus.invoke(coroutineContext, job.invoke(null))) ?: error("No page scope")
        val args = binderFields.map { field ->
            when (field.name) {
                "seekBarProgressOwner" -> progress
                "miuiMediaAlbumAnimationUtils" -> animator
                "miuiMediaActionButtonUtils" -> actionButtons
                "scope" -> scope
                "powerInteractor" -> field.get(owner)
                else -> field.get(source)
            }
        }.toTypedArray()
        var binder: Any? = null
        try {
            binder = binderConstructor.newInstance(*args)
            setHeaderHolder.invoke(header, holder)
            header.addView(view)
            attach.invoke(binder, holder)
            bindMaterial.invoke(null, header, materialState.get(owner))
            return Page(header, view, seekBar, binder, scope, progress, seekModel)
        } catch (error: Throwable) {
            if (binder != null) HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "detach failed card", Unit) { detach.invoke(binder) }
            cancelScope.invoke(null, scope, null)
            throw error
        }
    }

    fun bind(page: Page, data: Any) {
        if (page.data === data) return
        val token = token.get(data) as? MediaSession.Token
        if (controllerToken.get(page.progress) != token) {
            val nativeController = token?.let { MediaController(page.view.context, it) }
            controllerToken.set(page.progress, token)
            controller.set(page.progress, nativeController)
            lastProgress.set(page.progress, null)
            updateProgress(page, nativeController)
        }
        bind.invoke(page.binder, data)
        headerMedia.set(page.view, data)
        page.data = data
    }

    fun close(page: Page) {
        HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "close native media card", Unit) {
            try {
                page.player.setOnLongClickListener(null)
                page.player.setOnTouchListener(null)
                page.pressAnimator?.cancel()
                page.pressAnimator = null
                page.view.scaleX = 1f
                page.view.scaleY = 1f
                detach.invoke(page.binder)
            } finally {
                cancelScope.invoke(null, page.scope, null)
                updateProgress(page, null)
            }
        }
    }

    private fun updateProgress(page: Page, controller: MediaController?) {
        (seekExecutor.get(page.seekModel) as Executor).execute {
            HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "update page playback progress", Unit) {
                updateSeek.invoke(page.seekModel, controller)
            }
        }
    }

    private fun round(view: View, dimen: String) {
        val radius = view.resources.getDimension(resource(view.context, dimen, "dimen"))
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    private fun resource(context: Context, name: String, type: String): Int =
        ResourceLookup.identifier(context.resources, name, type, "com.android.systemui").also { check(it != 0) }
    private fun type(name: String): Class<*> = Class.forName(name, false, loader)
    private fun field(type: Class<*>, name: String) =
        checkNotNull(HostAccess.field(type, name)) { "${type.name}.$name" }
    private fun method(type: Class<*>, name: String, vararg args: Class<*>) =
        checkNotNull(HostAccess.method(type, name, *args)) { "${type.name}.$name" }

    companion object {
        fun resolve(owner: Class<*>, holder: Class<*>, media: Class<*>, factory: Class<*>): HyperOsMediaContract? =
            HookFailurePolicy.open(HYPER_OS_MEDIA_TAG, "resolve native HyperOS renderer", null) {
                HyperOsMediaContract(owner, holder, media, factory)
            }
    }
}
