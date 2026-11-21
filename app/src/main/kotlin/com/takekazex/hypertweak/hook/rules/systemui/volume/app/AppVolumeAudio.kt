package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.takekazex.hypertweak.hook.base.GenerationWorker
import com.takekazex.hypertweak.hook.rules.systemui.volume.app.AppVolumeCandidates.Player
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SystemUI's view of per-app media gain, one per plugin generation.
 *
 * The gain path is the one MiSound's own slider uses: `AudioManager.setPlayerVolume(config, gain)`
 * reaches `PlaybackActivityMonitorStubImpl.setPlayerVolume`, which applies a 0–1 gain to every
 * media player of that UID and remembers it in system_server memory under
 * `getNameForUid(uid)`. The system ignores both calls while the Sound Assistant switch
 * (`Settings.Global.sound_assist_key`) is off, so the feature hides then instead of switching it on.
 * The value is not written to MiSound's provider: it lasts until the system restarts.
 *
 * Main thread owns [players], [gains] and [apps]. Binder and PackageManager work runs on the
 * single generation [worker], so playback reports, reads and writes keep their order.
 */
internal class AppVolumeAudio(
    context: Context,
    private val api: Api,
    private val worker: GenerationWorker,
    private val main: Handler,
    private val onChanged: () -> Unit,
) {
    class Api(private val clientUid: Method, private val playerState: Method, val getGain: Method, val setGain: Method) {
        fun player(config: AudioPlaybackConfiguration, key: (Int) -> String?): Player? = runCatching {
            val uid = clientUid.invoke(config) as Int
            val attributes = config.audioAttributes
            Player(uid, playerState.invoke(config) as Int, attributes.usage, attributes.volumeControlStream, key(uid), config)
        }.getOrNull()

        companion object {
            /** Xiaomi's AudioManager extension plus two @SystemApi getters; all are required. */
            fun resolve(): Api? = runCatching {
                val config = AudioPlaybackConfiguration::class.java
                fun method(owner: Class<*>, name: String, returns: Class<*>, vararg parameters: Class<*>) =
                    owner.getMethod(name, *parameters).also { require(it.returnType == returns) { "$name shape" } }
                Api(
                    method(config, "getClientUid", Int::class.javaPrimitiveType!!),
                    method(config, "getPlayerState", Int::class.javaPrimitiveType!!),
                    method(AudioManager::class.java, "getPlayerVolume", Float::class.javaPrimitiveType!!, String::class.java),
                    method(AudioManager::class.java, "setPlayerVolume", Void.TYPE, config, Float::class.javaPrimitiveType!!)
                )
            }.onFailure { DebugLog.hookSkipped(TAG, "AudioManager player volume", it.toString()) }.getOrNull()
        }
    }

    class App(val label: CharSequence, val icon: Drawable?, val iconColor: Int?)

    private val appContext = context.applicationContext ?: context
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val packages = appContext.packageManager
    private val keys = ConcurrentHashMap<Int, String>()
    private val appsLoaded = ConcurrentHashMap<String, Boolean>()
    private val pending = ConcurrentHashMap<String, Pair<List<Any>, Float>>()
    private val flushQueued = AtomicBoolean()
    @Volatile private var writeFailureLogged = false

    var players: List<Player> = emptyList()
        private set
    val gains = HashMap<String, Float>()
    val apps = HashMap<String, App>()

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val snapshot = ArrayList(configs)
            worker.execute { publish(snapshot) }
        }
    }

    /** Registers the playback callback and takes the first snapshot; returns the unregister action. */
    fun start(): () -> Unit {
        audio.registerAudioPlaybackCallback(callback, main)
        worker.execute { publish(audio.activePlaybackConfigurations) }
        return { audio.unregisterAudioPlaybackCallback(callback) }
    }

    fun soundAssistEnabled(): Boolean =
        runCatching { Settings.Global.getInt(appContext.contentResolver, SOUND_ASSIST_KEY, 0) == 1 }.getOrDefault(false)

    /** Reads the system's current gain for [rowKeys]; a failed read keeps the last known value. */
    fun refreshGains(rowKeys: Collection<String>) {
        val requested = rowKeys.toList()
        if (requested.isEmpty()) return
        worker.execute {
            val read = requested.mapNotNull { key ->
                runCatching { key to (api.getGain.invoke(audio, key) as Float).coerceIn(0f, 1f) }
                    .onFailure { DebugLog.w(TAG, "getPlayerVolume failed key=$key", it) }.getOrNull()
            }
            if (read.isNotEmpty()) main.post {
                read.forEach { (key, gain) -> if (!pending.containsKey(key)) gains[key] = gain }
                onChanged()
            }
        }
    }

    /**
     * Records [gain] for the row and queues one write of the latest value per key: a fast drag
     * coalesces into a few Binder calls instead of one per touch event.
     */
    fun write(row: AppVolumeCandidates.Row, gain: Float) {
        gains[row.key] = gain
        pending[row.key] = row.players.mapNotNull { it.token } to gain
        if (flushQueued.compareAndSet(false, true) && !worker.execute(::flush)) flushQueued.set(false)
    }

    private fun flush() {
        flushQueued.set(false)
        pending.keys.toList().forEach { key ->
            val (configs, gain) = pending.remove(key) ?: return@forEach
            val failed = configs.any { config ->
                runCatching { api.setGain.invoke(audio, config, gain) }.onFailure {
                    if (!writeFailureLogged) {
                        writeFailureLogged = true
                        DebugLog.w(TAG, "setPlayerVolume failed key=$key", it)
                    }
                }.isFailure
            }
            // Render what the system actually holds after a refused write.
            if (failed) refreshGains(listOf(key))
        }
    }

    private fun publish(configs: List<AudioPlaybackConfiguration>) {
        val next = configs.mapNotNull { config -> api.player(config, ::key) }
        val newKeys = next.filter(AppVolumeCandidates::adjustable).mapNotNull { it.key }.distinct()
        val loaded = newKeys.filterNot(appsLoaded::containsKey).associateWith { key ->
            next.first { it.key == key }.uid.let(::loadApp)
        }
        appsLoaded.putAll(loaded.mapValues { true })
        main.post {
            apps.putAll(loaded)
            players = next
            onChanged()
        }
    }

    private fun key(uid: Int): String? = keys[uid] ?: runCatching { packages.getNameForUid(uid) }.getOrNull()?.also { keys[uid] = it }

    private fun loadApp(uid: Int): App {
        val packageName = runCatching { packages.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
        val info = packageName?.let { runCatching { packages.getApplicationInfo(it, PackageManager.ApplicationInfoFlags.of(0)) }.getOrNull() }
        val label = info?.loadLabel(packages) ?: packageName ?: uid.toString()
        val original = info?.let { runCatching { it.loadIcon(packages) }.getOrNull() }
        val displayed = original?.let(::sampleIcon)
        // The host may supply a themed monochrome icon. Check the declared icon for a hue as well.
        val declared = if (displayed?.accent == null) info?.takeIf { it.icon != 0 }?.let {
            runCatching { packages.getDrawable(it.packageName, it.icon, it) }
                .onFailure { error -> DebugLog.d(TAG, "Cannot load declared app icon: ${error.javaClass.simpleName}") }
                .getOrNull()?.let(::sampleIcon)
        } else null
        val iconColor = displayed?.accent ?: declared?.accent ?: displayed?.tone ?: declared?.tone
        DebugLog.d(TAG) { "icon sample uid=$uid drawable=${original?.javaClass?.simpleName} color=${iconColor?.toUInt()?.toString(16)}" }
        // Sample before badging so the work/clone badge does not influence its accent.
        val icon = original?.let { drawable ->
            runCatching { packages.getUserBadgedIcon(drawable, UserHandle.getUserHandleForUid(uid)) }.getOrDefault(drawable)
        }
        return App(label, icon, iconColor)
    }

    private fun sampleIcon(icon: Drawable): AppVolumeIconColor.Sample? =
        runCatching { AppVolumeIconColor.sample(icon) }
            .onFailure { DebugLog.d(TAG, "Cannot sample ${icon.javaClass.simpleName}: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

    private companion object {
        const val TAG = "AppVolume"
        const val SOUND_ASSIST_KEY = "sound_assist_key"
    }
}
