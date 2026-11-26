package com.takekazex.hypertweak.hook.rules.systemui.media

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.hook.base.HostContext
import java.util.WeakHashMap

/** Opt-in handoff from the stock single publisher when multiple cards are playing. */
internal object MultiSessionMediaIslandHooker : StaticHooker() {
    private const val TAG = "MultiSessionMediaIsland"
    private const val OWNER = "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl"

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_MULTI_SESSION, false)
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var contract: NativeMediaIslandContract? by generationState { null }
    private var session: Session? by generationState { null }
    private var failedContent: Any? by generationState { null }
    private var restoreAfterPluginSwap by generationState { false }
    private var buildingForPlugin: Any? by generationState { null }
    private val pluginLoaders = generationMainResource(WeakHashMap<ClassLoader, Boolean>()) { it.clear() }
    private val refresh = Runnable { reconcile() }

    private class Session(val owner: Any, val content: Any) {
        val cards = LinkedHashMap<String, NativeMediaIslandContract.Card>()
    }

    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) { scope ->
            reconcile(scope.component)
        }
    }

    override fun onHook() {
        val owner = OWNER.toClassOrNull() ?: return
        val host = HookFailurePolicy.open(TAG, "resolve SystemUI contract", null) {
            NativeMediaIslandContract(owner)
        } ?: return
        host.setTop.hook {
            after { if (enabled) requestRefresh() }
        }
        host.pluginLoaded.hook {
            after { if (enabled) requestRefresh() }
        }
        host.pluginUnloaded.hook {
            after { if (enabled) requestRefresh() }
        }
        host.publish.hook {
            before { param ->
                if (session?.owner === param.thisObject && buildingForPlugin == null) param.result = null
            }
        }
        host.remove.hook {
            before { param ->
                if (session?.owner === param.thisObject) param.result = null
            }
        }
        host.getContent.hook {
            before { param ->
                if (Looper.myLooper() == main.looper && buildingForPlugin === param.thisObject) param.result = null
            }
        }
        for (resize in listOf(host.refreshDensity, host.reinflate)) {
            resize.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "refresh media island dimensions", Unit) {
                        val state = session?.takeIf { it.owner === param.thisObject } ?: return@open
                        state.cards.values.forEach(host::resizeCard)
                    }
                }
            }
        }
        contract = host
        DebugLog.i(TAG, "HOOK_OK SystemUI media island publisher ready")
    }

    private fun requestRefresh() {
        HookFailurePolicy.open(TAG, "schedule media island refresh", Unit) {
            main.removeCallbacks(refresh)
            main.post(refresh)
        }
    }

    fun refreshSettings() = HostAccess.onMain {
        failedContent = null
        reconcile()
    }

    private fun reconcile(recoveredComponent: Any? = null) {
        val host = contract ?: return
        if (!enabled) {
            stop(host, restore = true)
            return
        }
        HookFailurePolicy.open(TAG, "refresh playing cards", Unit) {
            val context = hookParam.appContext ?: HostContext.appContextOrNull
            val app = context?.applicationContext ?: context
            val component = host.component(recoveredComponent ?: app?.let {
                HostAccess.read(it, "mSysUIComponent")
                    ?: HostAccess.read(it, "mInitializer")?.let { initializer ->
                        HostAccess.invoke(initializer, "getSysUIComponent")
                    }
            }) ?: return@open
            val owner = host.owner(component) ?: return@open
            val content = host.content(owner)
            if (session?.let { it.owner !== owner || it.content !== content } == true) {
                stop(host, restore = false)
                restoreAfterPluginSwap = true
            }
            if (content == null || content === failedContent) return@open
            try {
                installPluginGuard(host, checkNotNull(content.javaClass.classLoader))
                val desired = host.playingCards(component)
                if (session == null && restoreAfterPluginSwap) {
                    restoreAfterPluginSwap = false
                    if (desired.size < 2) host.restoreStock(owner)
                }
                if (desired.isEmpty()) {
                    stop(host, restore = true)
                    return@open
                }
                if (session == null && desired.size < 2) return@open
                val state = session ?: Session(owner, content)
                val previousCount = state.cards.size
                val incoming = LinkedHashMap<String, NativeMediaIslandContract.Card>()
                try {
                    for ((key, data) in desired) {
                        if (key in state.cards) continue
                        val card = host.createCard(component, owner, key)
                        incoming[key] = card
                        host.bindCard(card, data)
                    }
                    if (session == null) {
                        host.removeStock(owner)
                        session = state
                    }
                    state.cards.putAll(incoming)
                    incoming.clear()
                } finally {
                    incoming.values.forEach { close(host, it) }
                }

                val obsolete = state.cards.keys.filter { it !in desired }
                obsolete.forEach { key -> retireCard(host, state, key) }
                for ((key, data) in desired) {
                    val card = state.cards.getValue(key)
                    if (card.data === data) continue
                    if (card.submitted) host.bindCard(card, data)
                    val payload = buildPayload(host, owner, card, data)
                    val update = card.submitted
                    card.submitted = true
                    host.submit(state.content, payload, update)
                    card.data = data
                }
                if (previousCount != state.cards.size || obsolete.isNotEmpty()) {
                    DebugLog.i(TAG, "published independent media islands=${state.cards.size}")
                }
            } catch (error: Throwable) {
                failedContent = content
                stop(host, restore = true)
                DebugLog.e(TAG, "independent media publishing fell back to SystemUI", error)
            }
        }
    }

    private fun installPluginGuard(host: NativeMediaIslandContract, loader: ClassLoader) {
        if (pluginLoaders.containsKey(loader)) return
        host.pendingMedia(loader).hook {
            before { param ->
                val state = session ?: return@before
                val data = param.args.firstOrNull() ?: return@before
                HookFailurePolicy.open(TAG, "isolate media island animation views", Unit) {
                    if (host.key(data) in state.cards) param.result = false
                }
            }
        }
        pluginLoaders[loader] = true
        DebugLog.i(TAG, "HOOK_OK plugin media island reuse guard ready")
    }

    private fun buildPayload(
        host: NativeMediaIslandContract,
        owner: Any,
        card: NativeMediaIslandContract.Card,
        data: Any,
    ): Any {
        buildingForPlugin = host.plugin(owner)
        return try {
            host.buildPayload(owner, card, data)
        } finally {
            buildingForPlugin = null
        }
    }

    private fun retireCard(host: NativeMediaIslandContract, state: Session, key: String) {
        val card = state.cards.remove(key) ?: return
        try {
            // Immediate native deletion prevents a retired expanded view being rebound to a new card.
            HookFailurePolicy.open(TAG, "remove media island", Unit) {
                if (card.submitted) host.withdraw(state.content, key)
            }
        } finally {
            close(host, card)
        }
    }

    private fun close(host: NativeMediaIslandContract, card: NativeMediaIslandContract.Card) {
        HookFailurePolicy.open(TAG, "release media island controls", Unit) { host.close(card) }
    }

    private fun stop(host: NativeMediaIslandContract, restore: Boolean) {
        val state = session ?: return
        state.cards.keys.toList().forEach { retireCard(host, state, it) }
        session = null
        if (restore) HookFailurePolicy.open(TAG, "restore native media island", Unit) { host.restoreStock(state.owner) }
    }

    override fun onPrepareHotReload() = HostAccess.onMain {
        main.removeCallbacks(refresh)
        contract?.let { stop(it, restore = true) }
        failedContent = null
        restoreAfterPluginSwap = false
    }
}
