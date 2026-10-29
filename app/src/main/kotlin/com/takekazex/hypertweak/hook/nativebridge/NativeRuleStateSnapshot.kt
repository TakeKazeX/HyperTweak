package com.takekazex.hypertweak.hook.nativebridge

import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

internal data class NativeRuleStateSnapshot(val hidden: Boolean, val columns: Int, val contextualSearch: Boolean,
    val revision: Long = 0, val epoch: Long = 0, val assistantWidgets: Boolean = false, val recentsHaptic: Boolean = false, val folderStack: Boolean = false, val widgetStack: Boolean = false, val xiaoaiOptions: Boolean = false, val iconPercent: Int = -1) {
    fun settingsValues(): Map<String, Any> = mapOf(
        PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON to hidden, PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS to columns,
        PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS to contextualSearch,
        PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT to assistantWidgets,
        PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC to recentsHaptic,
        PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW to folderStack,
        PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK to widgetStack,
        PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS to xiaoaiOptions,
        LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT to iconPercent,
        PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION to revision, "prefs_epoch" to epoch)
    companion object {
        val REPLAY_DELAYS_MS = longArrayOf(0, 250, 1_000, 3_000, 10_000)

        /** A complete authoritative read, or no publication; outages are never defaults. */
        fun read(readValues: () -> Map<String, *>): NativeRuleStateSnapshot? = runCatching {
            val values = readValues()
            val hidden = values[PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON]
            val columns = values[PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS]
            val contextual = values[PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS]
            val widgets = values[PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT]
            val xiaoaiOptions = values[PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS]
            val widgetStack = values[PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK]
            val stack = values[PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW]
            val haptic = values[PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC]
            val iconPercent = values[LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT]
            val revision = values[PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION]
            val epoch = values["prefs_epoch"]
            // Reject the whole snapshot on any type mismatch; a partial read is never a default.
            if ((iconPercent != null && iconPercent !is Int) || (xiaoaiOptions != null && xiaoaiOptions !is Boolean) || (widgetStack != null && widgetStack !is Boolean) || (stack != null && stack !is Boolean) || (hidden != null && hidden !is Boolean) || (columns != null && columns !is Int) ||
                (haptic != null && haptic !is Boolean) || (contextual != null && contextual !is Boolean) ||
                (widgets != null && widgets !is Boolean) || (revision != null && revision !is Long) ||
                (epoch != null && epoch !is Long)) return null
            // The guards above smart-cast each value to its checked type for the rest of the block.
            val resolvedColumns = columns ?: 3
            val resolvedRevision = revision ?: 0L
            val resolvedEpoch = epoch ?: 0L
            if (resolvedRevision < 0L || resolvedEpoch < 0L || resolvedColumns !in 3..5 || !LauncherDesktopKeys.validPercent(iconPercent ?: -1)) return null
            NativeRuleStateSnapshot(hidden ?: false, resolvedColumns, contextual ?: false,
                resolvedRevision, resolvedEpoch, widgets ?: false, haptic ?: false, stack ?: false, widgetStack ?: false, xiaoaiOptions ?: false, iconPercent ?: -1)
        }.getOrNull()
    }
}
