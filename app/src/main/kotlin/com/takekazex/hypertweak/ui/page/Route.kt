package com.takekazex.hypertweak.ui.page

import androidx.navigation3.runtime.NavKey

sealed interface Route : NavKey {
    data object Main : Route
    data object Appearance : Route
    data object About : Route
    data object Credits : Route
    data object HiddenFeatures : Route
    data object AppShortcuts : Route
    data object PredictiveBackApps : Route
    data object AospRestore : Route
    data object AospIme : Route
    data object SystemUi : Route
    data object IconTuner : Route
    data object GlassTuner : Route
    data object Watermark : Route
    data object CameraUnlock : Route
    data object ChargingDetail : Route
    data object ControlCenterCorner : Route
    data object ControlCenterResize : Route
    data object Debug : Route
    data object DebugLogs : Route
    data object BatteryInfo : Route
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
        Route.About -> "About"
        Route.Credits -> "Credits"
        Route.HiddenFeatures -> "HiddenFeatures"
        Route.AppShortcuts -> "AppShortcuts"
        Route.PredictiveBackApps -> "PredictiveBackApps"
        Route.AospRestore -> "AospRestore"
        Route.AospIme -> "AospIme"
        Route.SystemUi -> "SystemUi"
        Route.IconTuner -> "IconTuner"
        Route.GlassTuner -> "GlassTuner"
        Route.Watermark -> "Watermark"
        Route.CameraUnlock -> "CameraUnlock"
        Route.ChargingDetail -> "ChargingDetail"
        Route.ControlCenterCorner -> "ControlCenterCorner"
        Route.ControlCenterResize -> "ControlCenterResize"
        Route.Debug -> "Debug"
        Route.DebugLogs -> "DebugLogs"
        Route.BatteryInfo -> "BatteryInfo"
    }

/** Inverse of [saveKey]; returns null for an unknown key so a stale save cannot crash restore. */
fun routeFromSaveKey(key: String): Route? = when (key) {
    "Main" -> Route.Main
    "Appearance" -> Route.Appearance
    "About" -> Route.About
    "Credits" -> Route.Credits
    "HiddenFeatures" -> Route.HiddenFeatures
    "AppShortcuts" -> Route.AppShortcuts
    "PredictiveBackApps" -> Route.PredictiveBackApps
    "AospRestore" -> Route.AospRestore
    "AospIme" -> Route.AospIme
    "SystemUi" -> Route.SystemUi
    "IconTuner" -> Route.IconTuner
    "GlassTuner" -> Route.GlassTuner
    "Watermark" -> Route.Watermark
    "CameraUnlock" -> Route.CameraUnlock
    "ChargingDetail" -> Route.ChargingDetail
    "ControlCenterCorner" -> Route.ControlCenterCorner
    "ControlCenterResize" -> Route.ControlCenterResize
    "Debug" -> Route.Debug
    "DebugLogs" -> Route.DebugLogs
    "BatteryInfo" -> Route.BatteryInfo
    else -> null
}
