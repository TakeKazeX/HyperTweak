package com.takekazex.hypertweak.hook.rules.systemui.island.policy

import java.util.WeakHashMap

/** Only bindings expected from a configuration rebuild need deferred geometry publication. */
internal class IslandTemplateRebinds {
    private val pending = WeakHashMap<Any, Boolean>()
    fun expect(template: Any) { pending[template] = true }
    fun bound(template: Any): Boolean = pending.remove(template) == true
}
