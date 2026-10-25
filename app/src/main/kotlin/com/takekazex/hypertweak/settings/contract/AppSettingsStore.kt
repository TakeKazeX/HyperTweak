package com.takekazex.hypertweak.settings.contract

/** App composition root: consumers select a feature capability instead of extending a flat writer. */
internal interface AppSettingsStore : SettingsValues {
    val backup: BackupSettings
    val fingerprint: FingerprintSettings
    val powerButton: PowerButtonSettings
    val backGesture: BackGestureSettings
    val camera: CameraAppSettings
    val launcher: LauncherAppSettings
    val systemUi: SystemUiAppSettings
    val platform: PlatformAppSettings
    val powerSave: PowerSaveSettings
}
