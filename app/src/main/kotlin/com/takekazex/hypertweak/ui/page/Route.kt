package com.takekazex.hypertweak.ui.page

import androidx.navigation3.runtime.NavKey

sealed interface Route : NavKey {
    data object Main : Route
    data object Appearance : Route
    data object ScopePrompts : Route
    data object BackupRestore : Route
    data object About : Route
    data object Credits : Route
    data object HiddenFeatures : Route
    data object AppShortcuts : Route
    data object PredictiveBackApps : Route
    data object AospRestore : Route
    data object SecurityCenter : Route
    data object AospIme : Route
    data object SystemUi : Route
    data object DownloadManager : Route
    data object GoogleServices : Route
    data object IconTuner : Route
    data object GlassTuner : Route
    data object ExperimentalFeatures : Route
    data object CameraWatermark : Route
    data object ChargingDetail : Route
    data object LockscreenBottomText : Route
    data object ControlCenterCorner : Route
    data object ControlCenterResize : Route
    data object Debug : Route
    data object DeveloperSettings : Route
    data object DebugLogs : Route
    data object BatteryInfo : Route
    data object Update : Route
}

/**
 * Stable string key for each route, used to persist the navigation back stack across process death
 * (see the `rememberSaveable` back stack in `HyperTweakNavContainer`). The mapping is explicit
 * rather than derived from the class name so it survives R8/obfuscation and a route rename.
 */
val Route.saveKey: String
    get() = when (this) {
        Route.Main -> "Main"
        Route.Appearance -> "Appearance"
        Route.ScopePrompts -> "ScopePrompts"
        Route.BackupRestore -> "BackupRestore"
        Route.About -> "About"
        Route.Credits -> "Credits"
        Route.HiddenFeatures -> "HiddenFeatures"
        Route.AppShortcuts -> "AppShortcuts"
        Route.PredictiveBackApps -> "PredictiveBackApps"
        Route.AospRestore -> "AospRestore"
        Route.SecurityCenter -> "SecurityCenter"
        Route.AospIme -> "AospIme"
        Route.SystemUi -> "SystemUi"
        Route.DownloadManager -> "DownloadManager"
        Route.GoogleServices -> "GoogleServices"
        Route.IconTuner -> "IconTuner"
        Route.GlassTuner -> "GlassTuner"
        Route.CameraWatermark -> "CameraWatermark"
        Route.ExperimentalFeatures -> "ExperimentalFeatures"
        Route.ChargingDetail -> "ChargingDetail"
        Route.LockscreenBottomText -> "LockscreenBottomText"
        Route.ControlCenterCorner -> "ControlCenterCorner"
        Route.ControlCenterResize -> "ControlCenterResize"
        Route.Debug -> "Debug"
        Route.DeveloperSettings -> "DeveloperSettings"
        Route.DebugLogs -> "DebugLogs"
        Route.BatteryInfo -> "BatteryInfo"
        Route.Update -> "Update"
    }

/** Inverse of [saveKey]; returns null for an unknown key so a stale save cannot crash restore. */
fun routeFromSaveKey(key: String): Route? = when (key) {
    "Main" -> Route.Main
    "Appearance" -> Route.Appearance
    "ScopePrompts" -> Route.ScopePrompts
    "BackupRestore" -> Route.BackupRestore
    "About" -> Route.About
    "Credits" -> Route.Credits
    "HiddenFeatures" -> Route.HiddenFeatures
    "AppShortcuts" -> Route.AppShortcuts
    "PredictiveBackApps" -> Route.PredictiveBackApps
    "AospRestore" -> Route.AospRestore
    "SecurityCenter" -> Route.SecurityCenter
    "AospIme" -> Route.AospIme
    "SystemUi" -> Route.SystemUi
    "DownloadManager" -> Route.DownloadManager
    "GoogleServices" -> Route.GoogleServices
    "IconTuner" -> Route.IconTuner
    "GlassTuner" -> Route.GlassTuner
    "CameraWatermark" -> Route.CameraWatermark
    "ExperimentalFeatures" -> Route.ExperimentalFeatures
    "ChargingDetail" -> Route.ChargingDetail
    "LockscreenBottomText" -> Route.LockscreenBottomText
    "ControlCenterCorner" -> Route.ControlCenterCorner
    "ControlCenterResize" -> Route.ControlCenterResize
    "Debug" -> Route.Debug
    "DeveloperSettings" -> Route.DeveloperSettings
    "DebugLogs" -> Route.DebugLogs
    "BatteryInfo" -> Route.BatteryInfo
    "Update" -> Route.Update
    else -> null
}
