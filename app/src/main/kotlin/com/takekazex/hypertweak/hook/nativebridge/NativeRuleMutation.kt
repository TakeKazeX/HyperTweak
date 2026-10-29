package com.takekazex.hypertweak.hook.nativebridge

import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/** A failed editor can leave a shadow map changed. Build the next transaction from the last ACK. */
internal object NativeRuleMutation {
    fun canMirror(snapshot: NativeRuleStateSnapshot, localEpoch: Long, localRevision: Long): Boolean =
        localEpoch == snapshot.epoch && localRevision == snapshot.revision

    fun apply(base: NativeRuleStateSnapshot, key: String, value: Any, revision: Long): NativeRuleStateSnapshot {
        val next = when (key) {
            LauncherDesktopKeys.KEY_GRID -> base.copy(desktopGrid = value as Int, revision = revision)
            LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT -> base.copy(iconPercent = value as Int, revision = revision)
            PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON -> base.copy(hidden = value as Boolean, revision = revision)
            PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS -> base.copy(columns = value as Int, revision = revision)
            PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS -> base.copy(contextualSearch = value as Boolean, revision = revision)
            PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT -> base.copy(assistantWidgets = value as Boolean, revision = revision)
            PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS -> base.copy(xiaoaiOptions = value as Boolean, revision = revision)
            PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK -> base.copy(widgetStack = value as Boolean, revision = revision)
            PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW -> base.copy(folderStack = value as Boolean, revision = revision)
            PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC -> base.copy(recentsHaptic = value as Boolean, revision = revision)
            else -> error("unsupported native setting")
        }
        require(LauncherDesktopKeys.validGrid(next.desktopGrid) && next.columns in 3..5 && next.revision >= 0 && LauncherDesktopKeys.validPercent(next.iconPercent))
        return next
    }
}
