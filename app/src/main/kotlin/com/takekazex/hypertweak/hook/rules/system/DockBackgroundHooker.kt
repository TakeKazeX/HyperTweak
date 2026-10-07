package com.takekazex.hypertweak.hook.rules.system

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.SurfaceControlViewHost
import android.view.Choreographer
import android.view.WindowManager
import com.takekazex.hypertweak.dock.DockConfig
import com.takekazex.hypertweak.dock.DockGeometry
import com.takekazex.hypertweak.dock.DockReflection
import com.takekazex.hypertweak.dock.DockRecoveryPolicy
import com.takekazex.hypertweak.dock.DockPresentationPolicy
import com.takekazex.hypertweak.dock.DockFrameChannel
import com.takekazex.hypertweak.hook.NativeRuleProtocol
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import androidx.core.net.toUri

/** WMS owns only our child layer. The wallpaper/material renderer belongs to the module app. */
@SuppressLint("StaticFieldLeak") // Holds only system_server's process context, cleared on retirement.
object DockBackgroundHooker : StaticHooker() {
    private const val TAG = "DockBackground"
    private val uri = "content://com.takekazex.hypertweak.dock".toUri()
    private val epoch = AtomicLong()
    @Volatile private var stopped = true
    private var worker = Executors.newSingleThreadExecutor { Thread(it, "HT-DockIPC").apply { isDaemon = true } }
    private var service: Any? = null
    private var context: Context? = null
    private var handler: Handler? = null
    private var wmLock: Any = Any()
    private val entries = WeakHashMap<Any, Entry>() // WMS global lock only.
    private val observed = WeakHashMap<Any, DockRecoveryPolicy>()
    private var preferenceSource: SharedPreferences? = null
    private var receiver: BroadcastReceiver? = null
    private var displays: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var settingsObserver: ContentObserver? = null
    private val settingsRequest = AtomicLong()
    private var configSnapshot = DockConfig()
    private var frameChannel: DockFrameChannel? = null
    private val channelEpoch = AtomicLong()
    private val offerVersion = AtomicLong()
    private var restoredMotion: Any? = null
    private var providerLease = 0L
    private var frameScheduled = false
    private var frameClock: Choreographer? = null
    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        if (!stopped) synchronized(wmLock) {
            val transaction = newTransaction()
            try {
                entries.values.forEach { entry -> runCatching { applyPose(transaction, entry) }
                    .onFailure { DebugLog.w(TAG, "pose transaction failed", it) } }
                DockReflection.call(transaction, "apply")
            } finally { DockReflection.call(transaction, "close") }
        }
    }
    private fun scheduleFrame() {
        val generation = epoch.get()
        handler?.post {
            if (stopped || epoch.get() != generation || frameScheduled) return@post
            val clock = frameClock ?: Choreographer.getInstance().also { frameClock = it }
            frameScheduled = true
            clock.postFrameCallback(frameCallback)
        }
    }

    private fun offerChannel() {
        val ctx = context ?: return
        val channel = frameChannel
        val version = offerVersion.updateAndGet { maxOf(it + 1L, SystemClock.elapsedRealtimeNanos()) }
        val intent = Intent(DockFrameChannel.OFFER).setPackage(NativeRuleProtocol.SYSTEM_UI)
            .putExtra("port", channel?.port ?: 0).putExtra("token0", channel?.token0 ?: 0L).putExtra("token1", channel?.token1 ?: 0L)
            .putExtra("version", version)
        // ActivityManager broadcast work must not run under WMS's global lock.
        runCatching { worker.execute { runCatching { NativeRuleProtocol.send(ctx, intent) }
            .onFailure { DebugLog.w(TAG, "native channel broadcast failed", it) } } }
            .onFailure { DebugLog.w(TAG, "native channel setup unavailable", it) }
    }

    private fun setChannel(enabled: Boolean) {
        if (enabled == (frameChannel != null)) return
        val channelGeneration = channelEpoch.incrementAndGet()
        frameChannel?.close()
        frameChannel = null
        if (enabled) {
            val generation = epoch.get()
            val reported = java.util.concurrent.atomic.AtomicBoolean()
            val carriedMotion = restoredMotion
            restoredMotion = null
            frameChannel = DockFrameChannel(carriedMotion) {
                if (stopped || channelEpoch.get() != channelGeneration) return@DockFrameChannel
                if (reported.compareAndSet(false, true)) {
                    val ipcContext = context
                    val lease = providerLease
                    runCatching { worker.execute {
                        if (!stopped && channelEpoch.get() == channelGeneration)
                            ipc("motion", "state", Bundle().apply { putBoolean("ready", true); putLong("lease", lease) }, ipcContext)
                    } }
                }
                if (!stopped && generation == epoch.get()) scheduleFrame()
            }
        }
        val ipcContext = context
        val lease = providerLease
        worker.execute { ipc("motion", "state", Bundle().apply { putBoolean("ready", false); putLong("lease", lease) }, ipcContext) }
        offerChannel()
    }
    private data class Entry(val id: String, val parent: Any, val effect: Any, val key: String,
        var materialKey: String,
        val owner: IBinder = Binder(), var parcel: SurfaceControlViewHost.SurfacePackage? = null,
        var renderer: IBinder? = null, var death: IBinder.DeathRecipient? = null,
        var ready: Boolean = false, var probePending: Boolean = false, var attempts: Int = 0,
        var probeAllowed: Boolean = false, var applied: String? = null,
        var bounds: com.takekazex.hypertweak.dock.DockBounds? = null, var frame: Rect = Rect(), var density: Float = 1f,
        var surfaceVisible: Boolean = false, val lease: Long = 0L)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == DockConfig.KEY || key == "prefs_epoch") {
            Preferences.invalidateRuntimeReadCache()
            requestSettings()
        }
    }

    fun bindPreferences(source: SharedPreferences) {
        if (preferenceSource === source) return
        runCatching {
            preferenceSource?.unregisterOnSharedPreferenceChangeListener(prefListener)
            preferenceSource = null
            source.registerOnSharedPreferenceChangeListener(prefListener)
            preferenceSource = source
        }.onFailure { DebugLog.w(TAG, "Dock preference listener unavailable", it) }
        requestSettings()
    }

    override fun onHook() {
        if (!com.takekazex.hypertweak.util.PlatformLevel.isOs4) return
        val clazz = "com.android.server.wm.WindowState".toClassOrNull() ?: return
        val prepareMethod = clazz.declaredMethods.singleOrNull { it.name == "prepareSurfaces" && it.parameterCount == 0 && it.returnType == Void.TYPE }
            ?: error("WMS prepare boundary unavailable")
        val remove = clazz.declaredMethods.singleOrNull { it.name == "removeImmediately" && it.parameterCount == 0 && it.returnType == Void.TYPE }
            ?: error("WMS remove boundary unavailable")
        stopped = false
        configSnapshot = DockConfig.decode(Preferences.getString(DockConfig.KEY, DockConfig().encode())) ?: DockConfig()
        providerLease = SystemClock.elapsedRealtimeNanos()
        epoch.incrementAndGet()
        if (preferenceSource == null) preferenceSource = runCatching { Preferences.observeDockBackground(prefListener) }
            .onFailure { DebugLog.w(TAG, "Dock preference listener unavailable", it) }.getOrNull()
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor { Thread(it, "HT-DockIPC").apply { isDaemon = true } }
        prepareMethod.hook("dock_wms_prepare") {
            after { param ->
                val window = param.thisObject
                runCatching { DockBackgroundHooker.prepareWindow(window) }.onFailure { DebugLog.w(TAG, "window update failed", it) }
            }
        }
        remove.hook("dock_wms_remove") {
            before { param -> synchronized(wmLock) {
                retire(param.thisObject); observed.remove(param.thisObject)
                if (observed.isEmpty()) setChannel(false)
            } }
        }
        recoverExistingWindows()
    }

    private fun bootstrap(window: Any) {
        if (service != null) return
        bootstrapService(requireNotNull(DockReflection.field(window, "mWmService").get(window)))
    }
    private fun bootstrapService(target: Any) {
        if (service != null) return
        val ctx = DockReflection.field(target, "mContext").get(target) as? Context ?: return
        wmLock = requireNotNull(DockReflection.field(target, "mGlobalLock").get(target))
        handler = DockReflection.field(target, "mH").get(target) as? Handler ?: Handler(Looper.getMainLooper())
        service = target
        context = ctx
        val generation = epoch.get()
        handler?.post {
            if (stopped || epoch.get() != generation) return@post
            runCatching { installObservers(ctx) }.onFailure { DebugLog.w(TAG, "display lifecycle observer unavailable", it) }
        }
    }

    /** Also works when the previous APK did not carry a Dock window snapshot. */
    fun recoverExistingWindows() {
        val generation = epoch.get()
        fun attempt(remaining: Int) {
            if (stopped || epoch.get() != generation) return
            val target = service ?: runCatching {
                val type = requireNotNull("com.android.server.wm.WindowManagerService".toClassOrNull())
                val manager = Class.forName("android.os.ServiceManager")
                val binder = DockReflection.callStatic(manager, "getService", "window")
                if (type.isInstance(binder)) binder else {
                    val localServices = requireNotNull("com.android.server.LocalServices".toClassOrNull())
                    val internal = requireNotNull("com.android.server.wm.WindowManagerInternal".toClassOrNull())
                    val local = requireNotNull(DockReflection.callStatic(localServices, "getService", internal))
                    val owner = local.javaClass.declaredFields.single { type.isAssignableFrom(it.type) }
                    owner.isAccessible = true
                    owner.get(local)
                }
            }.getOrNull()
            if (target == null) {
                if (remaining > 0) Handler(Looper.getMainLooper()).postDelayed({ attempt(remaining - 1) }, 500)
                else DebugLog.w(TAG, "existing window discovery unavailable")
                return
            }
            runCatching {
                bootstrapService(target)
                handler?.post { if (!stopped && epoch.get() == generation) synchronized(wmLock) {
                    runCatching {
                        val windows = com.takekazex.hypertweak.dock.DockWindowDiscovery.collect(target)
                        windows.forEach(::prepareWindow)
                        requestRefresh(recover = true)
                        DebugLog.i(TAG, "existing home window recovery observed=${observed.size}")
                    }.onFailure { DebugLog.w(TAG, "existing window recovery failed", it) }
                } }
            }.onFailure { DebugLog.w(TAG, "existing WMS bind failed", it) }
        }
        Handler(Looper.getMainLooper()).post { attempt(20) }
    }

    private fun installObservers(ctx: Context) {
        settingsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = requestSettings()
        }.also { ctx.contentResolver.registerContentObserver(com.takekazex.hypertweak.dock.DockSettingsSignal.uri, false, it) }
        requestSettings()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DockFrameChannel.REQUEST) {
                    if (NativeRuleProtocol.trusted(context, sentFromPackage, sentFromUid, NativeRuleProtocol.SYSTEM_UI)) offerChannel()
                } else { requestSettings(); requestRefresh(recover = true) }
            }
        }.also { ctx.registerReceiver(it, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(DockFrameChannel.REQUEST)
        }, Context.RECEIVER_EXPORTED) }
        displays = ctx.getSystemService(DisplayManager::class.java)
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = requestRefresh(recover = true)
            override fun onDisplayRemoved(displayId: Int) = requestRefresh(recover = true)
            override fun onDisplayChanged(displayId: Int) = requestRefresh(recover = true)
        }.also { displays?.registerDisplayListener(it, handler) }
    }

    private fun prepareWindow(window: Any) {
        if (stopped) return
        val attrs = DockReflection.field(window, "mAttrs").get(window) as? WindowManager.LayoutParams ?: return
        if (attrs.packageName != "com.miui.home") return
        val displayId = DockReflection.call(window, "getDisplayId") as? Int ?: -1
        if (!DockGeometry.isHomeWindow(attrs.packageName, attrs.title?.toString().orEmpty(), attrs.type,
                displayId)) return
        bootstrap(window)
        synchronized(wmLock) {
            val newWindow = !observed.containsKey(window)
            if (newWindow && frameChannel != null) setChannel(false)
            observed.getOrPut(window) { DockRecoveryPolicy() }
            update(window)
            if (newWindow) offerChannel()
        }
    }

    private fun update(window: Any) {
        if (stopped) return
        val config = configSnapshot
        setChannel(config.enabled)
        if (!config.enabled) { observed[window]?.reset(); retire(window); return }
        val ctx = context ?: return
        val interactive = ctx.getSystemService(PowerManager::class.java).isInteractive
        val locked = ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        // The native Hotseat state owns visibility while sliding toward the assistant. Its overlay
        // can coexist with a still-visible home row; it is not a Dock visibility boundary.
        val motion = frameChannel?.latest
        val nativeHidden = motion == null || motion.alpha <= 0
        val parent = DockReflection.call(window, "getSurfaceControl")
        val presentation = DockPresentationPolicy.resolve(config.enabled,
            parent != null && DockReflection.call(parent, "isValid") == true, interactive, locked, nativeHidden,
            DockReflection.call(window, "isOnScreen") == true)
        if (!presentation.retainHost || parent == null) { retire(window); return }
        val frame = DockReflection.call(window, "getFrame") as? Rect ?: return
        val configuration = DockReflection.call(window, "getConfiguration") as? Configuration ?: return
        val bounds = DockGeometry.resolve(frame.width(), frame.height(), configuration.densityDpi / 160f, config) ?: run { retire(window); return }
        val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val rotation = displays?.getDisplay(0)?.rotation ?: 0
        val windowRotation = runCatching {
            val windowConfig = requireNotNull(DockReflection.field(configuration, "windowConfiguration").get(configuration))
            DockReflection.call(windowConfig, "getRotation") as? Int
        }.getOrNull()?.takeIf { it in 0..3 } ?: rotation
        val key = "$bounds/$rotation/$windowRotation"
        val materialKey = "${config.style}/$dark"
        var entry = entries[window]
        if (entry != null && (entry.key != key || entry.parent !== parent || DockReflection.call(entry.effect, "isValid") != true)) {
            retire(window); entry = null
        }
        if (entry == null) {
            if (observed.getOrPut(window) { DockRecoveryPolicy() }.permit(key, SystemClock.uptimeMillis()).not()) return
            val builder = Class.forName("android.view.SurfaceControl\$Builder").getDeclaredConstructor().newInstance()
            DockReflection.call(builder, "setName", "HyperTweak Dock")
            DockReflection.call(builder, "setContainerLayer")
            DockReflection.call(builder, "setParent", parent)
            val effect = requireNotNull(DockReflection.call(builder, "build"))
            entry = Entry(UUID.randomUUID().toString(), parent, effect, key, materialKey, lease = providerLease)
            entries[window] = entry
            val args = Bundle().apply {
                putLong("lease", entry.lease); putString("config", config.encode()); putInt("width", bounds.width); putInt("height", bounds.height)
                putFloat("radius", bounds.radius); putBoolean("dark", dark); putBinder("owner", entry.owner)
                putInt("rotation", windowRotation)
                putBoolean("motionReady", motion != null)
            }
            create(window, entry, args)
        }
        if (entry.parcel != null && entry.materialKey != materialKey) {
            entry.materialKey = materialKey
            val targetEntry = entry
            val args = Bundle().apply { putLong("lease", targetEntry.lease); putString("config", config.encode()); putBoolean("dark", dark); putFloat("radius", bounds.radius) }
            val reference = WeakReference(window)
            val generation = epoch.get()
            val ipcContext = context
            val resultHandler = handler
            worker.execute {
                val result = ipc("update", targetEntry.id, args, ipcContext)
                resultHandler?.post { synchronized(wmLock) {
                    reference.get()?.takeIf { !stopped && generation == epoch.get() && entries[it] === targetEntry }?.let {
                        if (result?.getBoolean("updated") != true) fail(it, targetEntry)
                    }
                } }
            }
        }
        entry.probeAllowed = presentation.probeTexture
        if (!entry.probeAllowed) entry.attempts = 0
        if (entry.parcel != null && entry.probeAllowed) probe(WeakReference(window), entry, epoch.get())
        entry.bounds = bounds
        entry.frame = Rect(frame)
        entry.density = configuration.densityDpi / 160f
        entry.surfaceVisible = interactive && !locked && DockReflection.call(window, "isOnScreen") == true
        scheduleFrame()
    }

    /** The vsync transaction alone owns pose; WMS traversal never queues stale animation values. */
    private fun applyPose(transaction: Any, entry: Entry) {
        val bounds = entry.bounds ?: return
        val frame = entry.frame
        val motion = frameChannel?.latest
        val visible = entry.surfaceVisible && motion != null && motion.alpha > 0
        val appearance = "${entry.key}/${entry.ready}/$visible/${motion?.sequence}"
        if (entry.applied == appearance) return
        DockReflection.call(transaction, "setLayer", entry.effect, -1)
        val density = entry.density
        val scale = motion?.scale?.toFloat() ?: 1f
        val pivotX = frame.width() / 2f
        val pivotY = frame.height() - (49 + 68) * density + (motion?.originY?.toFloat() ?: 0f) * density
        val screenScale = motion?.screenScale?.toFloat() ?: 1f
        val totalScale = scale * screenScale
        val localX = pivotX + (bounds.x - pivotX) * scale + (motion?.x?.toFloat() ?: 0f) * density
        val localY = pivotY + (bounds.y - pivotY) * scale + (motion?.y?.toFloat() ?: 0f) * density
        DockReflection.call(transaction, "setMatrix", entry.effect, totalScale, 0f, 0f, totalScale)
        DockReflection.call(transaction, "setPosition", entry.effect,
            frame.width() / 2f + (localX - frame.width() / 2f) * screenScale,
            frame.height() / 2f + (localY - frame.height() / 2f) * screenScale)
        DockReflection.call(transaction, "setWindowCrop", entry.effect, bounds.width, bounds.height)
        DockReflection.call(transaction, "setCornerRadius", entry.effect, bounds.radius)
        DockReflection.call(transaction, "setAlpha", entry.effect, if (entry.ready) motion?.alpha?.toFloat() ?: 0f else 0.001f)
        DockReflection.call(transaction, if (visible) "show" else "hide", entry.effect)
        entry.applied = appearance
    }

    private fun create(window: Any, entry: Entry, args: Bundle) {
        val windowRef = WeakReference(window)
        val generation = epoch.get()
        val ipcContext = context
        val resultHandler = handler
        worker.execute {
            val reply = ipc("create", entry.id, args, ipcContext)
            val parcel = reply?.getParcelable("surface", SurfaceControlViewHost.SurfacePackage::class.java)
            val renderer = reply?.getBinder("renderer")
            resultHandler?.post {
                synchronized(wmLock) {
                    val current = windowRef.get()
                    if (current == null || stopped || generation != epoch.get() || entries[current] !== entry) {
                        parcel?.release(); releaseRemote(entry.id, ipcContext, entry.lease); return@synchronized
                    }
                    if (parcel == null || renderer == null) {
                        DebugLog.w(TAG, "material host unavailable: ${reply?.getString("error")}")
                        fail(current, entry); return@synchronized
                    }
                    runCatching {
                        entry.parcel = parcel
                        entry.renderer = renderer
                        val death = IBinder.DeathRecipient { handler?.post {
                            synchronized(wmLock) { if (entries[current] === entry) fail(current, entry) }
                        } }
                        entry.death = death
                        renderer.linkToDeath(death, 0)
                        val surface = requireNotNull(DockReflection.call(parcel, "getSurfaceControl"))
                        val transaction = newTransaction()
                        DockReflection.call(transaction, "reparent", surface, entry.effect)
                        DockReflection.call(transaction, "setLayer", surface, 0)
                        DockReflection.call(transaction, "show", surface)
                        DockReflection.call(transaction, "apply")
                        DockReflection.call(transaction, "close")
                        probe(windowRef, entry, generation)
                    }.onFailure { DebugLog.w(TAG, "surface attach failed", it); fail(current, entry) }
                }
                requestRefresh()
            } ?: run { parcel?.release(); releaseRemote(entry.id, ipcContext, entry.lease) }
        }
    }

    private fun probe(window: WeakReference<Any>, entry: Entry, generation: Long) {
        if (entry.probePending || entry.ready || stopped || !entry.probeAllowed) return
        entry.probePending = true
        val ipcContext = context
        val resultHandler = handler
        worker.execute {
            val result = ipc("probe", entry.id, Bundle().apply { putLong("lease", entry.lease) }, ipcContext)
            resultHandler?.post { synchronized(wmLock) {
                val target = window.get() ?: return@synchronized
                if (stopped || epoch.get() != generation || entries[target] !== entry) return@synchronized
                entry.probePending = false
                entry.ready = result?.getBoolean("committed") == true && result.getLong("texture") > 0
                if (entry.ready) { update(target); requestTraversal() }
                else if (!entry.probeAllowed) entry.attempts = 0 // Resume from WMS, without polling a hidden producer.
                else if (++entry.attempts < 100) {
                    entry.probePending = true
                    resultHandler.postDelayed({ synchronized(wmLock) {
                        val resumed = window.get()
                        if (!stopped && epoch.get() == generation && resumed != null && entries[resumed] === entry) {
                            entry.probePending = false
                            probe(window, entry, generation)
                        }
                    } }, 50)
                }
                else { DebugLog.w(TAG, "native wallpaper texture unavailable; renderer stopped"); fail(target, entry) }
            } }
        }
    }

    private fun ipc(operation: String, id: String, args: Bundle, ipcContext: Context? = context): Bundle? = runCatching {
        val resolver = ipcContext?.contentResolver ?: return null
        resolver.acquireUnstableContentProviderClient(uri)?.use { it.call(operation, id, Bundle(args).apply { if (!containsKey("lease")) putLong("lease", providerLease) }) }
    }.onFailure { DebugLog.w(TAG, "material IPC $operation failed", it) }.getOrNull()

    private fun newTransaction(): Any = Class.forName("android.view.SurfaceControl\$Transaction").getDeclaredConstructor().newInstance()
    private fun fail(window: Any, entry: Entry) {
        val delay = observed.getOrPut(window) { DockRecoveryPolicy() }.failed(entry.key, SystemClock.uptimeMillis())
        retire(window)
        if (delay != null) {
            val reference = WeakReference(window)
            val generation = epoch.get()
            handler?.postDelayed({ if (!stopped && generation == epoch.get()) synchronized(wmLock) {
                reference.get()?.takeIf { observed.containsKey(it) }?.let { target ->
                    runCatching { update(target); requestTraversal() }.onFailure { DebugLog.w(TAG, "renderer recovery failed", it) }
                }
            } }, delay)
        }
    }
    private fun retire(window: Any) {
        val entry = entries.remove(window) ?: return
        entry.death?.let { death -> runCatching { entry.renderer?.unlinkToDeath(death, 0) } }
        runCatching {
            val transaction = newTransaction()
            try { DockReflection.call(transaction, "remove", entry.effect); DockReflection.call(transaction, "apply") }
            finally { DockReflection.call(transaction, "close") }
        }.onFailure { DebugLog.w(TAG, "owned surface cleanup failed", it) }
        entry.parcel?.release()
        runCatching { DockReflection.call(entry.effect, "release") }
        releaseRemote(entry.id, lease = entry.lease)
    }
    private fun releaseRemote(id: String, ipcContext: Context? = context, lease: Long = providerLease) {
        val cleanup = Runnable { ipc("release", id, Bundle().apply { putLong("lease", lease) }, ipcContext) }
        if (worker.isShutdown) Thread(cleanup, "HT-DockRetire").apply { isDaemon = true }.start()
        else worker.execute(cleanup)
    }
    private fun requestTraversal() { service?.let { runCatching { DockReflection.call(it, "requestTraversal") }.onFailure { DebugLog.w(TAG, "traversal unavailable", it) } } }
    /** Read the module's Preferences after acknowledgement, outside WMS's global lock. */
    private fun requestSettings(attempt: Int = 0) {
        val target = context ?: return
        val resultHandler = handler ?: return
        if (stopped || worker.isShutdown) return
        val generation = epoch.get()
        val request = settingsRequest.incrementAndGet()
        runCatching { worker.execute {
            if (stopped || epoch.get() != generation || settingsRequest.get() != request) return@execute
            val result = ipc("settings", "configuration", Bundle.EMPTY, target)
            val config = DockConfig.decode(result?.getString("config").orEmpty())
            resultHandler.post {
                if (stopped || epoch.get() != generation || settingsRequest.get() != request) return@post
                if (config == null) {
                    if (attempt < 3) resultHandler.postDelayed({
                        if (!stopped && epoch.get() == generation && settingsRequest.get() == request) requestSettings(attempt + 1)
                    }, 250)
                    else DebugLog.w(TAG, "saved configuration unavailable; retaining current Dock")
                    return@post
                }
                synchronized(wmLock) {
                    if (configSnapshot != config) DebugLog.i(TAG, "settings applied ${config.encode()}")
                    configSnapshot = config
                }
                requestRefresh(recover = true)
            }
        } }.onFailure { DebugLog.w(TAG, "settings refresh unavailable", it) }
    }
    private fun requestRefresh(recover: Boolean = false) {
        val generation = epoch.get()
        handler?.post { if (!stopped && generation == epoch.get()) synchronized(wmLock) {
            if (recover) observed.values.forEach { it.reset() }
            observed.keys.toList().forEach { window -> runCatching { update(window) }.onFailure { DebugLog.w(TAG, "refresh failed", it) } }
            requestTraversal()
        } }
    }
    override fun saveHotReloadState(): Any? = synchronized(wmLock) {
        arrayOf(service, observed.keys.toTypedArray(), frameChannel?.snapshot())
    }
    override fun restoreHotReloadState(state: Any?) {
        val saved = state as? Array<*> ?: return
        restoredMotion = saved.getOrNull(2)
        val windows = saved.getOrNull(1) as? Array<*> ?: return
        // Carried objects belong to WMS's platform ClassLoader, never the retired module.
        windows.filterNotNull().firstOrNull()?.let(::bootstrap)
        val generation = epoch.get()
        handler?.post { if (!stopped && epoch.get() == generation) synchronized(wmLock) {
            windows.filterNotNull().forEach { window -> runCatching { prepareWindow(window) }
                .onFailure { DebugLog.w(TAG, "hot reload window rebind failed", it) } }
            requestTraversal()
        } }
    }
    override fun onPrepareHotReload() {
        stopped = true
        epoch.incrementAndGet()
        setChannel(false)
        frameClock?.removeFrameCallback(frameCallback)
        frameScheduled = false; frameClock = null
        runCatching { preferenceSource?.unregisterOnSharedPreferenceChangeListener(prefListener) }
            .onFailure { DebugLog.w(TAG, "preference observer cleanup failed", it) }
        preferenceSource = null
        settingsObserver?.let { observer -> runCatching { context?.contentResolver?.unregisterContentObserver(observer) }
            .onFailure { DebugLog.w(TAG, "settings observer cleanup failed", it) } }
        settingsObserver = null
        settingsRequest.incrementAndGet()
        receiver?.let { callback -> context?.let { runCatching { it.unregisterReceiver(callback) } } }
        displayListener?.let { listener -> runCatching { displays?.unregisterDisplayListener(listener) }
            .onFailure { DebugLog.w(TAG, "display observer cleanup failed", it) } }
        synchronized(wmLock) { entries.keys.toList().forEach(::retire) }
        synchronized(wmLock) { observed.clear() }
        receiver = null; displayListener = null; displays = null
        val retiredLease = providerLease
        val retiredContext = context
        worker.execute { ipc("retire", "generation", Bundle().apply { putLong("lease", retiredLease) }, retiredContext) }
        worker.shutdown()
        service = null; handler = null; context = null
        configSnapshot = DockConfig()
    }
}
