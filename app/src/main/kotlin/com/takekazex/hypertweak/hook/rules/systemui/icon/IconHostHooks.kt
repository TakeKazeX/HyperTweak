package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookComponent
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/** The callbacks one consumer asks for on one host executable. */
internal class IconHostCallbacks {
    internal val before = ArrayList<(HookParam) -> Unit>()
    internal val after = ArrayList<(HookParam) -> Unit>()
    fun before(action: (HookParam) -> Unit) { before += action }
    fun after(action: (HookParam) -> Unit) { after += action }
}

/**
 * The one hook owner for every host executable an icon feature observes.
 *
 * Each executable gets a single host hook here, created on first use and kept until this hooker
 * itself retires. Consumers only subscribe callbacks; their subscriptions close with their own
 * generation. Reinstalling one renderer therefore cannot reorder or duplicate the host hooks the
 * others rely on, and callback order is [IconRenderer.hostCallbackOrder], not install order.
 */
internal object IconHostHooks : StaticHooker() {
    private const val TAG = "IconHostHooks"
    private const val HOOK_PRIORITY = 10_000

    private class Callbacks {
        val before = HostCallbackRegistry<HookParam> { DebugLog.w(TAG, "before callback failed", it) }
        val after = HostCallbackRegistry<HookParam> { DebugLog.w(TAG, "after callback failed", it) }
        fun clear() {
            before.clear()
            after.clear()
        }
    }

    private val hooks = generationResource(LinkedHashMap<Executable, Callbacks>()) {
        synchronized(it) {
            it.values.forEach(Callbacks::clear)
            it.clear()
        }
    }
    private var accepting by generationState { false }

    // Hooks are installed on demand, when the first consumer observes an executable.
    override val expectsHooks = false

    override fun onHook() {
        accepting = isMainProcess
    }

    fun observe(consumer: BaseHooker, target: Executable, block: IconHostCallbacks.() -> Unit) {
        // Install order is fixed in SystemUiSettingsHost.iconHookers(); reaching this is a wiring bug.
        check(accepting) { "IconHostHooks must install before ${consumer.javaClass.simpleName}" }
        val callbacks = synchronized(hooks) { hooks.getOrPut(target) { install(target) } }
        val requested = IconHostCallbacks().apply(block)
        val key = consumer.javaClass.simpleName
        val order = (consumer as? IconRenderer)?.hostCallbackOrder ?: 0
        requested.before.forEach { consumer.ownSubscription(callbacks.before.subscribe(order, key, it)) }
        requested.after.forEach { consumer.ownSubscription(callbacks.after.subscribe(order, key, it)) }
    }

    private fun install(target: Executable): Callbacks {
        val callbacks = Callbacks()
        // API-102 replaceHook preserves the old priority. A priority-qualified ID makes the
        // upgrade register a fresh handle and retire the old one; later reloads reuse this ID.
        val hookId = "HyperTweak.IconHostHooks.p$HOOK_PRIORITY.${target.declaringClass.name}#${target.name}" +
            target.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        when (target) {
            is Method -> target.hook(hookId) {
                priority(HOOK_PRIORITY)
                before { callbacks.before.dispatch(it) }
                after { callbacks.after.dispatch(it) }
            }
            is Constructor<*> -> target.hook(hookId) {
                priority(HOOK_PRIORITY)
                after { callbacks.after.dispatch(it) }
            }
            else -> error("Unsupported icon executable ${target.javaClass.name}")
        }
        return callbacks
    }
}

/** A renderer lifecycle with no host hook handles of its own; it observes through [IconHostHooks]. */
internal abstract class IconRenderer : StaticHooker() {
    internal open val hostCallbackOrder: Int = 0

    protected fun Method.observe(block: IconHostCallbacks.() -> Unit) =
        IconHostHooks.observe(this@IconRenderer, this, block)

    protected fun Constructor<*>.observe(block: IconHostCallbacks.() -> Unit) =
        IconHostHooks.observe(this@IconRenderer, this, block)

    protected fun Class<*>.observeConstructors(block: IconHostCallbacks.() -> Unit) =
        declaredConstructors.forEach { IconHostHooks.observe(this@IconRenderer, it, block) }
}

/** The [IconRenderer] helpers for a component; subscriptions belong to the owning hooker. */
internal abstract class IconRenderComponent(owner: BaseHooker) : HookComponent(owner) {
    protected fun Method.observe(block: IconHostCallbacks.() -> Unit) = IconHostHooks.observe(owner, this, block)

    protected fun Constructor<*>.observe(block: IconHostCallbacks.() -> Unit) =
        IconHostHooks.observe(owner, this, block)

    protected fun Class<*>.observeConstructors(block: IconHostCallbacks.() -> Unit) =
        declaredConstructors.forEach { IconHostHooks.observe(owner, it, block) }
}
