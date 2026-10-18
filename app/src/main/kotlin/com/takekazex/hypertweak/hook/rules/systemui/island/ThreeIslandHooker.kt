package com.takekazex.hypertweak.hook.rules.systemui.island

import android.os.Handler
import android.os.Looper
import android.view.View
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.rules.systemui.island.hooks.IslandHookContext
import com.takekazex.hypertweak.hook.rules.systemui.island.hooks.IslandHookPlan
import com.takekazex.hypertweak.hook.rules.systemui.island.hooks.IslandHookRegistrar
import com.takekazex.hypertweak.hook.rules.systemui.island.hooks.MediaCoverBinder
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaCoverContract
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandAccess
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandDiscovery
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeIslandHost
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaRenderContract
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMusicSpectrum
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandTemplateCutout
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandCameraSeat
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Extra native summaries, lent back to the host queue during each scheduling transaction. */
class ThreeIslandHooker(
    private val pluginApkPath: String,
    private val systemUiClassLoader: ClassLoader,
) : DynamicHooker() {
    private lateinit var access: NativeIslandAccess
    private var runtime: IslandRuntime? = null
    private var musicSpectrum: NativeMusicSpectrum? = null
    private var mediaRender: NativeMediaRenderContract? = null
    private var mediaCover: MediaCoverBinder? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var retiring = false

    private val pendingOwners = instanceTracker()

    override fun onHook() {
        if (retiring) return
        observeInstances("miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator", pendingOwners)
        prepareDexTargets(
            resolve = { NativeIslandAccess(NativeIslandDiscovery.scan(pluginApkPath, classLoader, dexCacheDirectory)) },
            install = { prepared ->
                if (!retiring) {
                    installPrepared(prepared)
                    restoreOwners(snapshotOwners())
                }
            },
        )
    }

    private fun installPrepared(prepared: NativeIslandAccess) {
        access = prepared
        musicSpectrum = runCatching { NativeMusicSpectrum(access.content) }
            .onFailure { DebugLog.w(TAG, "native small-island spectrum contract unavailable", it) }
            .getOrNull()
        mediaRender = musicSpectrum?.let {
            runCatching { NativeMediaRenderContract(access) }
                .onFailure { DebugLog.w(TAG, "native media rendering contract unavailable", it) }
                .getOrNull()
        }
        val nativeHost = NativeIslandHost(access)
        val islandRuntime = IslandRuntime(
            host = nativeHost,
            preferences = IslandPreferences(),
            retiring = { retiring },
            musicSpectrum = musicSpectrum,
            mediaRender = mediaRender,
        )
        runtime = islandRuntime
        val hookContext = IslandHookContext(
            access = access,
            host = nativeHost,
            runtime = islandRuntime,
            registrar = IslandHookRegistrar(this),
            spectrum = musicSpectrum,
            media = mediaRender,
            refreshSettings = ::refreshSettings,
        )
        val plan = IslandHookPlan(hookContext)
        // A changed plugin must skip this feature before any deoptimization or mutation.
        plan.validate()
        plan.deoptimizeTargets()
        plan.install()
        runCatching { NativeMediaCoverContract(classLoader, systemUiClassLoader) }
            .onFailure { DebugLog.w(TAG, "native media cover animation unavailable", it) }
            .getOrNull()?.let { contract ->
                mediaCover = MediaCoverBinder(hookContext, contract).also { it.install() }
            }
        DebugLog.hookRegistered(TAG, "DexKit native third task, adaptive full templates and touch routing")
    }

    fun snapshotOwners(): List<Any> =
        (runtime?.sessions?.owners().orEmpty() + pendingOwners.snapshot()).distinct()

    fun restoreOwners(owners: List<Any>) {
        if (retiring) return
        if (!::access.isInitialized || runtime == null) {
            owners.forEach(pendingOwners::add)
            return
        }
        val runtime = runtime ?: return
        pendingOwners.clear()
        owners.filter(access.coordinator::isInstance).forEach(runtime.sessions::getOrCreate)
        refreshSettings()
    }

    fun refreshSettings() {
        if (retiring) return
        main.removeCallbacks(settingsRefresh)
        main.post(settingsRefresh)
    }

    private val settingsRefresh = Runnable {
        val runtime = runtime ?: return@Runnable
        if (retiring) return@Runnable
        mediaCover?.retire()
        runtime.sessions.snapshot().forEach { session ->
            runtime.boundary.core(session, IslandFeature.TEMPLATE, "request frame relayout") {
                runtime.relayout.request(session)
            }
        }
    }

    override fun saveHotReloadState(): Any = snapshotOwners()

    override fun restoreHotReloadState(state: Any?) =
        restoreOwners((state as? List<*>)?.filterNotNull().orEmpty())

    override fun onPrepareHotReload() {
        retiring = true
        pendingOwners.clear()
        val runtime = runtime
        runtime?.diagnostics?.retire()
        main.removeCallbacks(settingsRefresh)
        if (!::access.isInitialized || runtime == null) return
        val owners = runtime.sessions.snapshot()
        val cleanup = Runnable {
            mediaCover?.retire()
            mediaCover = null
            owners.forEach { session ->
                session.template.relayout?.retire()
                session.template.relayout = null
                runCatching { runtime.settlement.restore(session) }
                    .onFailure { DebugLog.e(TAG, "retire third slot", it) }
                session.presentation.fullOrigins.keys.toList().forEach { view ->
                    runCatching { (access.call(view, "getBigIslandView") as? View)?.translationX = 0f }
                        .onFailure { DebugLog.e(TAG, "retire full template position", it) }
                }
            }
            runtime.sessions.clear()
            IslandCameraSeat.clear()
            IslandTemplateCutout.clear()
        }
        if (Looper.myLooper() == main.looper) {
            cleanup.run()
        } else {
            val done = CountDownLatch(1)
            main.post {
                try {
                    cleanup.run()
                } finally {
                    done.countDown()
                }
            }
            check(done.await(5, TimeUnit.SECONDS)) { "island cleanup timed out" }
        }
    }

    private companion object { const val TAG = "ThreeIslands" }
}
