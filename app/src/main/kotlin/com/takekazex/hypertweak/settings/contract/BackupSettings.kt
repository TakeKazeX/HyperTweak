package com.takekazex.hypertweak.settings.contract

internal interface BackupSettings {
    fun clearAllSettings(): Boolean
    fun exportSettings(): String
    fun restoreSettings(json: String): Int
    fun settingsEpochForUi(): Long?
}
