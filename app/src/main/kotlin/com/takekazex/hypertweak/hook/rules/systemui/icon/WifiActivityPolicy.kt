package com.takekazex.hypertweak.hook.rules.systemui.icon

/** OS4 native WifiViewModelInject.wifiInoutLeft: a visible standard or metered badge leads. */
internal object WifiActivityPolicy {
    fun nativeInoutLeft(metered: Boolean, standard: Int): Boolean = standard > 0 || metered
}
