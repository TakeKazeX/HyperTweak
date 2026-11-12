package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.view.View
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.presentation.icon.PlanSurface
import com.takekazex.hypertweak.presentation.icon.RendererId
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

internal sealed interface EndpointKind {
    data class Signal(val subId: Int) : EndpointKind
    data class Type(val subId: Int?) : EndpointKind
    data object Wifi : EndpointKind
    data object Airplane : EndpointKind
    data object Battery : EndpointKind
    data class Slot(val name: String) : EndpointKind
}

internal interface HandoverEndpoint {
    val owner: RendererId
    val surface: PlanSurface
    val kind: EndpointKind
    fun view(): View?
}

/**
 * Process-wide endpoint arbitration for the control-center handover. Main thread only.
 *
 * Providers expose their current endpoints instead of renderers reaching into each other. A
 * borrowed endpoint belongs to exactly one borrower, and a provider's retirement releases every
 * outstanding borrow before the provider disappears.
 */
internal object HandoverCoordinator {
    private class Provider(
        val owner: RendererId,
        val target: (Any, EndpointKind) -> View?,
        val claims: (Any, EndpointKind) -> Boolean,
        val acquire: (View, Any) -> Boolean,
        val release: (View, Any) -> Unit
    )

    private class Borrow(val provider: Provider, val borrower: Any)

    private val providers = LinkedHashMap<RendererId, Provider>()
    private val borrowed = IdentityHashMap<View, Borrow>()
    private var stacked: ((View) -> StackedSignalTarget?)? = null

    val statusIcons = EndpointOwnership<View>()
    val shade = HandoverPresence()

    fun register(
        owner: BaseHooker,
        renderer: RendererId,
        target: (Any, EndpointKind) -> View?,
        claims: (Any, EndpointKind) -> Boolean,
        acquire: (View, Any) -> Boolean,
        release: (View, Any) -> Unit
    ) {
        val provider = Provider(renderer, target, claims, acquire, release)
        providers[renderer] = provider
        owner.ownSubscription(AutoCloseable {
            val iterator = borrowed.entries.iterator()
            while (iterator.hasNext()) {
                val (view, borrow) = iterator.next()
                if (borrow.provider !== provider) continue
                iterator.remove()
                // One failed restore must not leave the remaining borrowed endpoints faded out.
                runCatching { provider.release(view, borrow.borrower) }
                    .onFailure { DebugLog.w("IconHandover", "endpoint release failed", it) }
            }
            if (providers[renderer] === provider) providers.remove(renderer)
        })
    }

    fun registerStacked(owner: BaseHooker, target: (View) -> StackedSignalTarget?) {
        stacked = target
        owner.ownSubscription(AutoCloseable { if (stacked === target) stacked = null })
    }

    /** A weak endpoint handle; per-frame callers should use [signalTarget] or [networkTarget]. */
    fun endpoint(container: Any, kind: EndpointKind, renderer: RendererId = RendererId.CARRIER): HandoverEndpoint? {
        val target = target(container, kind, renderer) ?: return null
        val weak = WeakReference(target)
        return object : HandoverEndpoint {
            override val owner = renderer
            override val surface = PlanSurface.CONTROL_CENTER_EXPANDED
            override val kind = kind
            override fun view(): View? = weak.get()?.takeIf { it.isAttachedToWindow }
        }
    }

    fun signalTarget(container: Any, subId: Int): View? = target(container, EndpointKind.Signal(subId))

    fun networkTarget(container: Any, wifi: Boolean, subId: Int?): View? =
        target(container, if (wifi) EndpointKind.Wifi else EndpointKind.Type(subId))

    fun ownsNetwork(container: Any, wifi: Boolean): Boolean {
        val kind = if (wifi) EndpointKind.Wifi else EndpointKind.Type(null)
        return providers[RendererId.CARRIER]?.claims?.invoke(container, kind) == true
    }

    fun stackedTarget(container: View): StackedSignalTarget? = stacked?.invoke(container)

    fun acquire(target: View, borrower: Any): Boolean {
        borrowed[target]?.let { return it.borrower === borrower }
        val provider = providers.values.firstOrNull { it.acquire(target, borrower) } ?: return false
        borrowed[target] = Borrow(provider, borrower)
        return true
    }

    fun release(target: View, borrower: Any) {
        val borrow = borrowed[target] ?: return
        if (borrow.borrower !== borrower) return
        borrowed.remove(target)
        borrow.provider.release(target, borrower)
    }

    /** The single alpha writer for handover motion; out-of-range progress is clamped. */
    fun alpha(view: View, value: Float) {
        view.alpha = value.coerceIn(0f, 1f)
    }

    private fun target(container: Any, kind: EndpointKind, renderer: RendererId = RendererId.CARRIER): View? =
        providers[renderer]?.target?.invoke(container, kind)?.takeIf { it.isAttachedToWindow }
}
