package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.ExpiringReadCache

/** Shared cache identity: accepting a host snapshot never calls back into the storage facade. */
internal object RuntimeSettingsReadCache {
    val memo = ExpiringReadCache(100_000_000L)
}
