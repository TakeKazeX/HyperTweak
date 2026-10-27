package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostFlowSubscriptions
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import java.util.IdentityHashMap

/**
 * Own only the final notification-bound publication, never the panel's seven-way combine.
 * Explicit modes project current native geometry; default passes through the native input.
 * The existing synchronous flow notifies every native layout consumer.
 */
object KeyguardFingerprintAvoidHooker : StaticHooker() {
    private const val TAG = "KeyguardFingerprintAvoid"
    private val settingMode by hookSetting(0) {
        Preferences.getInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID, 0)
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var retired = false
    private var installed by generationState { false }
    private var mode by generationState { 0 }
    private var host: FingerprintAvoidanceHost? by generationState { null }
    private var publish: ((Any, Int) -> Unit)? by generationState { null }
    private val bindings = generationMainResource(IdentityHashMap<Any, Binding>()) { map ->
        map.values.forEach { it.subscriptions.clear() }
        map.clear()
    }

    private class Binding(val repository: Any, val flow: Any, initial: Int) {
        val projection = FingerprintBoundProjection(initial)
        val subscriptions = HostFlowSubscriptions()
        var inputs: FingerprintAvoidanceHost.Inputs? = null
    }

    init {
        generationCleanup { retired = true; main.removeCallbacksAndMessages(null) }
        hostRecovery(HostRecoveryPhase.CONTENT) { scope ->
            val provider = scope.component?.let { HostAccess.read(it, "miuiKeyguardRepositoryImplProvider") }
            if (provider != null) boundary("recover native repository") {
                val repository = FingerprintAvoidanceHost.lazy(provider)
                host?.getter?.invoke(repository) // The getter hook enrolls the already-living flow.
            }
        }
    }

    override fun onHook() {
        if (!isMainProcess) return
        retired = false
        mode = settingMode
        boundary("resolve notification-bound contract") {
            val contract = FingerprintAvoidanceHost(classLoader)
            host = contract
            val original = module.getInvoker(contract.setter).setType(XposedInterface.Invoker.Type.ORIGIN)
            // Origin dispatch keeps module writes out of the upstream ledger and retains the
            // OEM's main-thread affinity assertion, equality check and synchronous notifications.
            publish = { flow, value -> original.invoke(flow, value) }
            deoptimize(contract.getter)
            deoptimize(contract.bridgeWriter)
            contract.setter.hook {
                before { param ->
                    if (retired || Looper.myLooper() != Looper.getMainLooper()) return@before
                    val binding = bindings[param.thisObject] ?: return@before
                    val upstream = param.args.getOrNull(0) as? Int ?: return@before
                    // Capture the real incoming value even when geometry resolution fails.
                    binding.projection.observe(upstream, null)
                    boundary("project native publication") {
                        param.args[0] = binding.projection.effective(override(binding))
                    }
                }
            }
            contract.getter.hook {
                after { param ->
                    if (retired) return@after
                    val repository = param.thisObject
                    val flow = param.result ?: return@after
                    if (Looper.myLooper() == Looper.getMainLooper()) boundary("bind native publication") {
                        enroll(repository, flow)
                    } else main.post { if (!retired) boundary("bind native publication") { enroll(repository, flow) } }
                }
            }
            installed = true
            DebugLog.i(TAG, "HOOK_OK final native notification-bound projection mode=$mode")
        }
    }

    private fun enroll(repository: Any, readonly: Any) {
        val contract = host ?: return
        val flow = contract.unwrap(readonly)
        if (bindings.containsKey(flow)) return
        val binding = Binding(repository, flow, FingerprintAvoidanceHost.intValue(flow))
        bindings[flow] = binding
        ownSubscription(AutoCloseable { binding.subscriptions.clear() })
        // Do not mutate a flow from inside its lazy getter: native getKeyguardStackStatusInfo
        // can be running. Publish once the call stack has returned.
        main.post { if (!retired && bindings[flow] === binding) boundary("initial publication") { refresh(binding) } }
    }

    private fun override(binding: Binding): Int? {
        if (mode !in 1..2) return null
        val inputs = binding.inputs ?: checkNotNull(host).inputs(binding.repository).also { binding.inputs = it }
        return FingerprintAvoidancePolicy.bottom(mode, inputs.geometry(mode))
    }

    private fun refresh(binding: Binding) {
        val value = binding.projection.effective(override(binding))
        if (mode in 1..2 && !binding.subscriptions.isBound) {
            val inputs = checkNotNull(binding.inputs)
            try {
                inputs.flows.forEach { flow ->
                    check(binding.subscriptions.collect(inputs.scope, flow,
                        isCurrent = { !retired && bindings[binding.flow] === binding }, replay = false) {
                        // Default relies exclusively on genuine upstream publications.
                        if (mode in 1..2) boundary("native geometry changed") {
                            publish?.invoke(binding.flow, binding.projection.effective(override(binding)))
                        }
                    }) { "Native geometry subscription failed" }
                }
            } catch (error: Throwable) {
                binding.subscriptions.clear()
                throw error
            }
        } else if (mode !in 1..2) binding.subscriptions.clear()
        publish?.invoke(binding.flow, value)
        DebugLog.i(TAG, "bound applied mode=$mode upstream=${binding.projection.upstream} effective=$value")
    }

    fun refreshSettings(): Boolean = HostAccess.onMain {
        if (retired) return@onMain true
        mode = settingMode
        var applied = mode !in 1..2 || installed
        bindings.values.toList().forEach { binding ->
            runCatching { refresh(binding) }.onFailure {
                applied = false
                binding.subscriptions.clear()
                runCatching { publish?.invoke(binding.flow, binding.projection.upstream) }
                DebugLog.w(TAG, "bound refresh failed; native baseline restored", it)
            }
        }
        applied
    }

    override fun saveHotReloadState(): Any = HostAccess.onMain { bindings.values.map { it.repository } }

    override fun restoreHotReloadState(state: Any?) {
        HostAccess.onMain {
            (state as? List<*>)?.filterNotNull()?.forEach { repository ->
                boundary("restore native repository") { host?.getter?.invoke(repository) }
            }
        }
    }

    override fun onPrepareHotReload() {
        HostAccess.onMain {
            retired = true
            main.removeCallbacksAndMessages(null)
            bindings.values.toList().forEach { binding ->
                // Restore latest real upstream, never a stale install-time or module value.
                boundary("restore upstream bound") { publish?.invoke(binding.flow, binding.projection.upstream) }
                binding.subscriptions.clear()
            }
        }
    }

    private inline fun boundary(operation: String, action: () -> Unit) {
        runCatching(action).onFailure { DebugLog.w(TAG, operation, it) }
    }
}
