package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.VolumeKeyStepPolicy

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Changes only AudioService adjustments marked as volume-key input. */
object VolumeKeyStepsHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingVolumeKeyStepCount by hookSetting(PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT) {
        Preferences.getInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT)
    }
    private val settingVolumeKeyStepScope by hookSetting(PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY) {
        Preferences.getInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE, PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY)
    }

    override val hookerName = "VolumeKeySteps"

    private const val TAG = "VolumeKeySteps"
    private const val AUDIO_SERVICE = "com.android.server.audio.AudioService"
    private const val VOLUME_STREAM_STATE = "com.android.server.audio.AudioService\$VolumeStreamState"
    private const val AUDIO_DEVICE_ATTRIBUTES = "android.media.AudioDeviceAttributes"
    private const val FLAG_FROM_KEY = 0x1000

    private data class HardwareStepRemainderKey(
        val stream: Int,
        val device: Int,
        val direction: Int
    )

    private class HardwareKeyContext(var adjustmentConsumed: Boolean = false)
    private data class HardwareKeyFrame(val context: HardwareKeyContext?)

    private val hardwareKeyFrames = ThreadLocal<ArrayDeque<HardwareKeyFrame>?>()
    private val hardwareStepRemainders = generationResource(HashMap<HardwareStepRemainderKey, Int>()) { it.clear() }
    private val loggedFirstAppliedStep = generationResource(AtomicBoolean(false)) { it.set(false) }

    private var hooksReady by generationState { false }

    private var activeConfiguration by generationState { VolumeKeyStepPolicy.configuration(
        PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT,
        PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY
    ) }

    private var streamTypeField: Field by generationRequiredState()
    private var indexMaxField: Field by generationRequiredState()
    private var indexMinField: Field by generationRequiredState()
    private var getIndexMethod: Method by generationRequiredState()

    override fun onHook() {
        val serviceClass = loadClass(AUDIO_SERVICE) ?: return
        val streamStateClass = loadClass(VOLUME_STREAM_STATE) ?: return
        val audioDeviceAttributesClass = loadClass(AUDIO_DEVICE_ATTRIBUTES) ?: return

        val stateFieldsReady = runCatching {
            streamTypeField = HostAccess.requireDeclaredField(streamStateClass, "mStreamType", accessible = true)
            indexMaxField = HostAccess.requireDeclaredField(streamStateClass, "mIndexMax", accessible = true)
            indexMinField = HostAccess.requireDeclaredField(streamStateClass, "mIndexMin", accessible = true)
            getIndexMethod = HostAccess.requireDeclaredMethod(streamStateClass,
                "getIndex",
                Int::class.javaPrimitiveType!!, accessible = true)
        }.isSuccess
        if (!stateFieldsReady) {
            DebugLog.hookSkipped(TAG, VOLUME_STREAM_STATE, "stream index fields were not found")
            return
        }

        val adjustStreamVolumeMethod = resolveAdjustStreamVolume(serviceClass, audioDeviceAttributesClass)
        val setIndexMethod = runCatching {
            HostAccess.requireDeclaredMethod(streamStateClass,
                "setIndex",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                Boolean::class.javaPrimitiveType!!, accessible = true)
        }.getOrNull()
        if (adjustStreamVolumeMethod == null || setIndexMethod == null) {
            DebugLog.hookSkipped(TAG, AUDIO_SERVICE, "OS4 adjustStreamVolume/VolumeStreamState.setIndex signatures were not recognized")
            return
        }

        refreshConfiguration()
        listOf(adjustStreamVolumeMethod, setIndexMethod).forEach { method ->
            if (!deoptimize(method)) {
                DebugLog.w(TAG, "could not deoptimize ${method.name}; a boot-image inline may bypass its hook")
            }
        }

        val installed = runCatching {
            // FLAG_FROM_KEY is present on the actual system_server volume-key path. Keep this
            // context around AudioService.adjustStreamVolume, where the stream and direction are
            // already resolved, rather than depending on the optional handleVolumeKey entrypoint.
            adjustStreamVolumeMethod.hook("volume_key_detect_flag_from_key") {
                before { param -> beginHardwareAdjustment(param.args) }
                after { endHardwareAdjustment() }
            }
            setIndexMethod.hook("volume_key_apply_configured_hardware_step") {
                before { param ->
                    runCatching { applyHardwareKeyStep(param.thisObject, param.args) }
                        .onFailure { t -> DebugLog.w(TAG, "could not apply hardware volume-key step", t) }
                }
            }
        }.isSuccess

        hooksReady = installed
        if (!installed) {
            DebugLog.w(TAG, "one or more hardware volume-key hooks failed")
            return
        }

        DebugLog.hookRegistered(
            TAG,
            "AudioService FLAG_FROM_KEY step override steps=${activeConfiguration.steps} scope=${activeConfiguration.scope}"
        )
    }

    @Synchronized
    fun refreshConfiguration() {
        val configuration = VolumeKeyStepPolicy.configuration(
            settingVolumeKeyStepCount,
            settingVolumeKeyStepScope
        )
        if (configuration != activeConfiguration) {
            synchronized(hardwareStepRemainders) {
                hardwareStepRemainders.clear()
                activeConfiguration = configuration
            }
            loggedFirstAppliedStep.set(false)
            DebugLog.i(TAG, "applied live configuration steps=${configuration.steps} scope=${configuration.scope}")
        }
    }

    @Synchronized
    override fun onPrepareHotReload() {
        loggedFirstAppliedStep.set(false)
    }

    private fun beginHardwareAdjustment(args: Array<Any?>) {
        val direction = args.getOrNull(1) as? Int
        val flags = args.getOrNull(2) as? Int ?: 0
        val isVolumeKeyAdjustment = (direction == -1 || direction == 1) &&
            (flags and FLAG_FROM_KEY) != 0
        val stack = hardwareKeyFrames.get()
            ?: ArrayDeque<HardwareKeyFrame>().also(hardwareKeyFrames::set)
        stack.addLast(HardwareKeyFrame(if (isVolumeKeyAdjustment) HardwareKeyContext() else null))
    }

    private fun endHardwareAdjustment() {
        val stack = hardwareKeyFrames.get() ?: return
        if (stack.isNotEmpty()) stack.removeLast()
        if (stack.isEmpty()) hardwareKeyFrames.remove()
    }

    @Synchronized
    private fun applyHardwareKeyStep(streamState: Any, args: Array<Any?>) {
        if (!hooksReady) return
        val context = hardwareKeyFrames.get()?.peekLast()?.context ?: return
        if (context.adjustmentConsumed) return

        val stream = streamTypeField.getInt(streamState)
        val configuration = activeConfiguration
        if (!VolumeKeyStepPolicy.appliesToStream(configuration.scope, stream)) return

        val requestedIndex = args.getOrNull(0) as? Int ?: return
        val device = args.getOrNull(1) as? Int ?: return
        val minimum = indexMinField.getInt(streamState)
        val maximum = indexMaxField.getInt(streamState)
        val current = getIndexMethod.invoke(streamState, device) as? Int ?: return
        val direction = (requestedIndex - current).compareTo(0)
        val logicalRange = (maximum - minimum) / 10
        if (direction == 0 || logicalRange <= 0 || current !in minimum..maximum) return

        val key = HardwareStepRemainderKey(stream, device, direction)
        val boundedStep = synchronized(hardwareStepRemainders) {
            val result = VolumeKeyStepPolicy.nextDelta(
                logicalRange,
                configuration.steps,
                hardwareStepRemainders[key] ?: 0
            )
            val requestedStep = result.delta * 10
            val bounded = if (direction > 0) {
                requestedStep.coerceAtMost(maximum - current)
            } else {
                -requestedStep.coerceAtMost(current - minimum)
            }
            hardwareStepRemainders[key] = result.remainder
            bounded
        }
        context.adjustmentConsumed = true
        args[0] = current + boundedStep
        if (loggedFirstAppliedStep.compareAndSet(false, true)) {
            DebugLog.i(
                TAG,
                "applied FLAG_FROM_KEY step stream=$stream delta=${boundedStep / 10} range=$logicalRange steps=${configuration.steps}"
            )
        }
    }

    private fun resolveAdjustStreamVolume(
        serviceClass: Class<*>,
        audioDeviceAttributes: Class<*>
    ): Method? = serviceClass.declaredMethods.singleOrNull { method ->
        val parameters = method.parameterTypes
        method.name == "adjustStreamVolume" &&
            parameters.size == 11 &&
            parameters[0] == Int::class.javaPrimitiveType!! &&
            parameters[1] == Int::class.javaPrimitiveType!! &&
            parameters[2] == Int::class.javaPrimitiveType!! &&
            parameters[3] == audioDeviceAttributes &&
            parameters[4] == String::class.java &&
            parameters[5] == String::class.java &&
            parameters[6] == Int::class.javaPrimitiveType!! &&
            parameters[7] == Int::class.javaPrimitiveType!! &&
            parameters[8] == String::class.java &&
            parameters[9] == Boolean::class.javaPrimitiveType!! &&
            parameters[10] == Int::class.javaPrimitiveType!!
    }?.accessible()

    private fun loadClass(name: String): Class<*>? = runCatching {
        classLoader.loadClass(name)
    }.onFailure { t ->
        DebugLog.w(TAG, "could not load $name", t)
    }.getOrNull()

    private fun Method.accessible(): Method = apply { isAccessible = true }
}
