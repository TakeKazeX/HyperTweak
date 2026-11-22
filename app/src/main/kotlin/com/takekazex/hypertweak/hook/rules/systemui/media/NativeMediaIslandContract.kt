package com.takekazex.hypertweak.hook.rules.systemui.media

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Bundle
import android.view.View
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import java.lang.reflect.Modifier
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/** SystemUI owns media discovery and the native island template. Each card owns its mutable binder state. */
internal class NativeMediaIslandContract(private val owner: Class<*>) {
    private val loader = owner.classLoader
    private val bool = Boolean::class.javaPrimitiveType!!
    private val media = field(owner, "topMediaData").type
    private val binder = field(owner, "dynamicMediaViewBinder")
    private val playerHolder = field(owner, "miuiPlayerHolder")
    private val fakeHolder = field(owner, "miuiDummyPlayerHolder")
    private val holder = playerHolder.type
    private val player = field(holder, "player")
    private val playerLayout = type(owner.name.substringBeforeLast('.') + ".PlayerIslandConstraintLayout")
    private val measurePlayer = method(playerLayout, "calSizeByDensity")
    private val layout = field(owner, "normalLayoutIsland")
    private val layoutConstructor = layout.type.getConstructor()
    private val key = field(owner, "currentKey")
    private val payload = field(owner, "dynamicIslandData")
    private val top = field(owner, "topMediaData")
    private val castType = field(owner, "castDeviceType")
    private val castIcon = field(owner, "icon")
    private val context = field(owner, "context")
    private val resetHolders = method(owner, "resetPlayerHolder")
    val refreshDensity = method(owner, "refreshSizeByDensity")
    val reinflate = method(owner, "reInflateView\$2")
    val publish = method(owner, "addDynamicIslandView", holder, holder, media)
    val remove = method(owner, "removeDynamicIslandView\$1", String::class.java, bool)

    private val plugin = field(owner, "dynamicIslandPluginController")
    private val contentType = type("com.android.systemui.plugins.miui.dynamicisland.DynamicIslandContent")
    val getContent = plugin.type.declaredMethods.single {
        it.parameterCount == 0 && it.returnType == contentType
    }.apply { isAccessible = true }
    private val pluginType = type("com.android.systemui.plugins.Plugin")
    private val pluginLifecycle = type("com.android.systemui.plugins.PluginLifecycleManager")
    val pluginLoaded = method(plugin.type, "onPluginLoaded", pluginType, Context::class.java, pluginLifecycle)
    val pluginUnloaded = method(plugin.type, "onPluginUnloaded", pluginType, pluginLifecycle)
    private val getDataKey = method(payload.type, "getKey")
    private val setDataKey = method(payload.type, "setKey", String::class.java)
    private val ticker = method(payload.type, "getTickerData")
    private val realView = method(payload.type, "getView")
    private val fakeView = method(payload.type, "getFakeView")
    private val addData = method(contentType, "addDynamicIslandView", payload.type, bool)
    private val updateData = method(contentType, "updateDynamicIslandView", payload.type, bool)
    private val handleData = method(contentType, "handleDynamicIsland", Bundle::class.java)

    private val notifications = type("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl")
    val setTop = method(notifications, "access\$setTopMediaData", notifications, media)
    private val sort = field(notifications, "mediaSorUtils")
    private val cards = field(sort.type, "mediaDataList")
    private val cardData = field(type(sort.type.name + "\$MediaSortKey"), "data")
    private val notificationKey = field(media, "notificationKey")
    private val playing = field(media, "isPlaying")
    private val cardIdentity = field(media, "instanceId")
    private val token = field(media, "token")

    private val component = type(
        "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl",
    )
    private val islandProvider = field(component, "miuiIslandMediaControllerImplProvider")
    private val notificationProvider = field(component, "miuiMediaNotificationControllerImplProvider")
    private val powerProvider = field(component, "powerInteractorProvider")
    private val providerGet = method(type("javax.inject.Provider"), "get")
    private val power = type("com.android.systemui.power.domain.interactor.PowerInteractor")
    private val newSeek = method(component, "seekBarViewModel")
    private val newActions = method(component, "miuiMediaActionButtonUtils")

    private val progress = field(binder.type, "seekBarProgressOwner")
    private val seek = field(progress.type, "seekBarViewModel")
    private val executor = field(seek.type, "bgExecutor")
    private val progressConstructor = progress.type.getConstructor(Context::class.java, seek.type, Executor::class.java)
    private val progressExecutor = field(progress.type, "bgExecutor")
    private val seekToken = field(progress.type, "currentToken")
    private val seekController = field(progress.type, "currentController")
    private val seekState = field(progress.type, "_seekBarRenderState")
    private val updateSeek = method(seek.type, "updateController", MediaController::class.java)
    private val scope = field(binder.type, "scope")
    private val coroutineContext = method(scope.type, "getCoroutineContext")
    private val plus = method(coroutineContext.returnType, "plus", coroutineContext.returnType)
    private val supervisorJob = method(type("kotlinx.coroutines.SupervisorKt"), "SupervisorJob\$default")
    private val scopeFactory = type("kotlinx.coroutines.CoroutineScopeKt")
    private val createScope = method(scopeFactory, "CoroutineScope", coroutineContext.returnType)
    private val cancelScope = method(scopeFactory, "cancel", scope.type, CancellationException::class.java)
    private val animator = field(binder.type, "miuiMediaAlbumAnimationUtils").type.getConstructor()
    private val binderArguments = listOf(
        "scope", "context", "activityStarter", "seekBarProgressOwner", "mediaTransferManager",
        "miPlayPluginManager", "mediaOutputDialogManager", "miuiMediaAlbumAnimationUtils",
        "miuiMediaActionButtonUtils", "powerInteractor", "dynamicIslandController",
    ).map { name -> name to if (name == "powerInteractor") null else field(binder.type, name) }
    private val binderConstructor = binder.type.getConstructor(
        *binderArguments.map { (_, member) -> member?.type ?: power }.toTypedArray(),
    )
    private val attach = method(binder.type, "attach", holder, holder)
    private val detach = method(binder.type, "detach")
    private val bind = method(binder.type, "bindMediaData", media)

    init {
        check(Modifier.isStatic(setTop.modifiers) && setTop.returnType == Void.TYPE)
        check(fakeHolder.type == holder && View::class.java.isAssignableFrom(player.type))
        check(context.type == Context::class.java && key.type == String::class.java)
        check(getDataKey.returnType == key.type && notificationKey.type == key.type && ticker.returnType == key.type)
        check(realView.returnType == View::class.java && fakeView.returnType == View::class.java)
        check(handleData.returnType == Bundle::class.java && View::class.java.isAssignableFrom(playerLayout))
        check(cardData.type == media && Iterable::class.java.isAssignableFrom(cards.type))
        check(playing.type == Boolean::class.javaObjectType && token.type == MediaSession.Token::class.java)
        check(newSeek.returnType == seek.type && newActions.returnType == field(binder.type, "miuiMediaActionButtonUtils").type)
        check(listOf(publish, remove, resetHolders, refreshDensity, reinflate, attach, detach, bind, updateSeek,
            setDataKey, addData, updateData)
            .all { it.returnType == Void.TYPE })
    }

    class Card(
        val key: String,
        val player: Any,
        val fake: Any,
        val binder: Any,
        val scope: Any,
        val progress: Any,
        val seek: Any,
        val context: Context,
    ) {
        var data: Any? = null
        var submitted = false
    }

    fun component(value: Any?) = value?.takeIf(component::isInstance)
    fun owner(component: Any): Any? = provided(islandProvider.get(component))?.takeIf(owner::isInstance)
    fun plugin(owner: Any): Any = checkNotNull(plugin.get(owner))
    fun content(owner: Any): Any? = getContent.invoke(plugin(owner))
    fun key(data: Any): String? = getDataKey.invoke(data) as? String

    fun pendingMedia(loader: ClassLoader): java.lang.reflect.Method {
        val window = Class.forName("miui.systemui.dynamicisland.window.DynamicIslandWindowView", false, loader)
        val pending = field(window, "pendingMediaView")
        val viewData = method(pending.type, "getCurrentIslandData")
        check(View::class.java.isAssignableFrom(window) && viewData.returnType == payload.type)
        val reuse = method(window, "hasPendingMedia", payload.type)
        check(reuse.declaringClass == window && Modifier.isPrivate(reuse.modifiers) && reuse.returnType == bool)
        return reuse
    }

    fun playingCards(component: Any): Map<String, Any> {
        val source = checkNotNull(provided(notificationProvider.get(component)))
        val entries = cards.get(sort.get(source)) as Iterable<*>
        val identities = HashSet<Any>()
        return buildMap {
            for (entry in entries) {
                val data = cardData.get(entry) ?: continue
                if (playing.get(data) != true) continue
                val notification = (notificationKey.get(data) as? String)?.takeIf(String::isNotBlank) ?: continue
                val id = "hypertweak:media:$notification"
                val card = cardIdentity.get(data) ?: continue
                if (token.get(data) is MediaSession.Token && id !in this && identities.add(card)) put(id, data)
            }
        }
    }

    fun createCard(component: Any, owner: Any, key: String): Card {
        val originalPlayer = playerHolder.get(owner)
        val originalFake = fakeHolder.get(owner)
        val originalLayout = layout.get(owner)
        val (real, fake) = try {
            layout.set(owner, layoutConstructor.newInstance())
            resetHolders.invoke(owner)
            checkNotNull(playerHolder.get(owner)) to checkNotNull(fakeHolder.get(owner))
        } finally {
            playerHolder.set(owner, originalPlayer)
            fakeHolder.set(owner, originalFake)
            layout.set(owner, originalLayout)
        }
        check(playerLayout.isInstance(player.get(real)) && playerLayout.isInstance(player.get(fake)))
        val source = binder.get(owner)
        val hostContext = context.get(owner) as Context
        val seekModel = checkNotNull(newSeek.invoke(component))
        val progressOwner = progressConstructor.newInstance(hostContext, seekModel, progressExecutor.get(progress.get(source)))
        val actions = newActions.invoke(component)
        val powerInteractor = checkNotNull(provided(powerProvider.get(component)))
        val inherited = coroutineContext.invoke(scope.get(source))
        val scopedContext = plus.invoke(inherited, supervisorJob.invoke(null))
        val cardScope = checkNotNull(createScope.invoke(null, scopedContext))
        var cardBinder: Any? = null
        try {
            val args = binderArguments.map { (name, member) ->
                when (name) {
                    "scope" -> cardScope
                    "seekBarProgressOwner" -> progressOwner
                    "miuiMediaAlbumAnimationUtils" -> animator.newInstance()
                    "miuiMediaActionButtonUtils" -> actions
                    "powerInteractor" -> powerInteractor
                    else -> checkNotNull(member).get(source)
                }
            }
            cardBinder = binderConstructor.newInstance(*args.toTypedArray())
            attach.invoke(cardBinder, real, fake)
            return Card(key, real, fake, cardBinder, cardScope, progressOwner, seekModel, hostContext)
        } catch (failure: Throwable) {
            try {
                cardBinder?.let { detach.invoke(it) }
            } finally {
                cancelScope.invoke(null, cardScope, null)
            }
            throw failure
        }
    }

    fun bindCard(card: Card, data: Any) {
        val session = token.get(data) as MediaSession.Token
        if (session != seekToken.get(card.progress)) {
            val controller = MediaController(card.context, session)
            seekToken.set(card.progress, session)
            seekController.set(card.progress, controller)
            seekState.set(card.progress, null)
            updateProgress(card, controller)
        }
        bind.invoke(card.binder, data)
    }

    fun close(card: Card) {
        try {
            detach.invoke(card.binder)
        } finally {
            try {
                cancelScope.invoke(null, card.scope, null)
            } finally {
                updateProgress(card, null)
            }
        }
    }

    private fun updateProgress(card: Card, controller: MediaController?) {
        (executor.get(card.seek) as Executor).execute {
            HookFailurePolicy.open("MultiSessionMediaIsland", "update seek controller", Unit) {
                updateSeek.invoke(card.seek, controller)
            }
        }
    }

    fun removeStock(owner: Any) {
        (key.get(owner) as? String)?.let { remove.invoke(owner, it, false) }
    }

    fun restoreStock(owner: Any) {
        val data = top.get(owner) ?: return
        publish.invoke(owner, playerHolder.get(owner), fakeHolder.get(owner), data)
    }

    fun buildPayload(owner: Any, card: Card, data: Any): Any {
        // The native publisher builds the template before looking up the plugin. The hook
        // suppresses that lookup during this call, so only the session-owned payload is sent.
        val fields = listOf(key, payload, castType, castIcon)
        val original = fields.map { it.get(owner) }
        return try {
            key.set(owner, null)
            payload.set(owner, null)
            castType.set(owner, "local")
            castIcon.set(owner, null)
            publish.invoke(owner, card.player, card.fake, data)
            val result = checkNotNull(payload.get(owner))
            check(getDataKey.invoke(result) == notificationKey.get(data))
            check(!(ticker.invoke(result) as? String).isNullOrBlank())
            check(realView.invoke(result) === player.get(card.player) && fakeView.invoke(result) === player.get(card.fake))
            setDataKey.invoke(result, card.key)
            result
        } finally {
            fields.zip(original).forEach { (field, value) -> field.set(owner, value) }
        }
    }

    fun submit(content: Any, data: Any, update: Boolean) {
        (if (update) updateData else addData).invoke(content, data, false)
    }

    fun withdraw(content: Any, key: String) {
        val request = Bundle().apply {
            putString("action_key", "action_remove_island")
            putString("extra_island_deleted_key", key)
            putBoolean("extra_island_deleted_has_animation", false)
        }
        handleData.invoke(content, request)
    }

    fun resizeCard(card: Card) {
        for (holder in listOf(card.player, card.fake)) {
            val view = player.get(holder) as View
            measurePlayer.invoke(view)
            view.requestLayout()
            view.invalidate()
        }
    }

    private fun provided(provider: Any?): Any? = provider?.let { providerGet.invoke(it) }
    private fun type(name: String) = Class.forName(name, false, loader)
    private fun field(type: Class<*>, name: String) =
        HostAccess.requireInheritedField(type, name).apply { isAccessible = true }
    private fun method(type: Class<*>, name: String, vararg parameters: Class<*>) =
        checkNotNull(HostAccess.method(type, name, *parameters)) { "${type.name}.$name" }.apply { isAccessible = true }
}
