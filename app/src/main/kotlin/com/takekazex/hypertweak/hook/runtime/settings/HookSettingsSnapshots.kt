package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.hook.base.HookSetting
import com.takekazex.hypertweak.hook.base.HookSettingRegistry
import com.takekazex.hypertweak.util.logging.DebugLog

/** Typed callback slots updated by the existing Preferences/runtime-settings publication path. */
internal object HookSettingsSnapshots {
    private val registry = HookSettingRegistry { DebugLog.w("RuntimeSettings", "callback snapshot read failed", it) }
    fun <T> capture(default: T, read: () -> T): HookSetting<T> = registry.capture(default, read)
    fun refresh() = registry.refresh { DebugLog.w("RuntimeSettings", "callback snapshot refresh failed", it) }
}

/** Declare only settings reads here; do not capture hosts, Views or mutable installation state. */
internal fun <T> hookSetting(default: T, read: () -> T): HookSetting<T> = HookSettingsSnapshots.capture(default, read)
