package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.presentation.icon.Claim
import com.takekazex.hypertweak.presentation.icon.IconLayoutPlan
import com.takekazex.hypertweak.presentation.icon.NetworkDisplayModel
import com.takekazex.hypertweak.presentation.icon.Ownership
import com.takekazex.hypertweak.presentation.icon.PlanSurface
import com.takekazex.hypertweak.presentation.icon.RendererId
import com.takekazex.hypertweak.presentation.icon.SlotPart
import com.takekazex.hypertweak.settings.contract.IconConfig
import java.util.WeakHashMap

/**
 * Container-local slot claims, resolved by the same pure [IconLayoutPlan] the preview uses.
 *
 * [slots] is read from host visibility callbacks on every icon state change, so the resolved
 * list is kept per container and surface until its claims or the icon config change. Slot ownership does not
 * depend on the mobile state, so a signal update never invalidates it.
 */
internal class SlotClaimRegistry(private val config: () -> IconConfig) {
    private class Resolved(val config: IconConfig, val surface: PlanSurface, val slots: List<String>)

    private val claims = WeakHashMap<Any, MutableMap<RendererId, Set<String>>>()
    private val resolved = WeakHashMap<Any, Resolved>()

    @Synchronized
    fun owned(container: Any, owner: RendererId): Set<String> = claims[container]?.get(owner).orEmpty()

    @Synchronized
    fun set(container: Any, owner: RendererId, slots: Set<String>) {
        resolved.remove(container)
        if (slots.isNotEmpty()) {
            claims.getOrPut(container) { linkedMapOf() }[owner] = slots.toSet()
            return
        }
        val owners = claims[container] ?: return
        owners.remove(owner)
        if (owners.isEmpty()) claims.remove(container)
    }

    /** The slots a ready replacement owns on [container], after user visibility and priority. */
    @Synchronized
    fun slots(container: Any, surface: PlanSurface): List<String> {
        val owners = claims[container] ?: return emptyList()
        val config = config()
        resolved[container]?.takeIf { it.config === config && it.surface == surface }?.let { return it.slots }
        val requests = owners.map { (owner, slots) ->
            Claim(
                owner = owner,
                surface = surface,
                replaces = slots.mapTo(linkedSetOf()) { SlotPart.Slot(it) },
                ready = true,
                priority = if (owner == RendererId.CARRIER) CARRIER_PRIORITY else DEFAULT_PRIORITY
            )
        }
        val layout = IconLayoutPlan.plan(config, NetworkDisplayModel(), requests)[surface]
        val slots = layout.ownership.mapNotNull { (part, ownership) ->
            (part as? SlotPart.Slot)?.name?.takeIf { ownership is Ownership.Replaced }
        }
        resolved[container] = Resolved(config, surface, slots)
        return slots
    }

    fun merged(container: Any, surface: PlanSurface, baseline: List<String>): List<String> =
        (baseline + slots(container, surface)).distinct()

    @Synchronized
    fun clear() {
        claims.clear()
        resolved.clear()
    }

    private companion object {
        /** The carrier rows replace the whole network group, so they win over a Duo claim. */
        const val CARRIER_PRIORITY = 200
        const val DEFAULT_PRIORITY = 100
    }
}
