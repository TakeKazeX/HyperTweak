package com.takekazex.hypertweak.hook.rules.systemui.island

/** Physical seats belong to task keys, not to whichever handler currently owns their views. */
internal class SmallIslandSeats {
    private val history = LinkedHashMap<String, Boolean>()
    var primaryOnLeft = false
        private set
    var extraOnLeft = true
        private set

    fun assign(primary: String?, extra: String?) {
        primaryOnLeft = primary?.let(history::get)
            ?: extra?.let(history::get)?.not() ?: false
        extraOnLeft = !primaryOnLeft
        if (primary != null) remember(primary, primaryOnLeft)
        if (extra != null) remember(extra, extraOnLeft)
    }

    fun left(key: String?): Boolean? = key?.let(history::get)

    private fun remember(key: String, left: Boolean) {
        history.remove(key)
        history[key] = left
        while (history.size > 16) history.remove(history.keys.first())
    }
}
