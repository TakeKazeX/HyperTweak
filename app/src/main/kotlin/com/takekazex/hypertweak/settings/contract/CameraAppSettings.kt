package com.takekazex.hypertweak.settings.contract

internal interface CameraAppSettings {
    fun setCameraLegendaryMomentMode(mode: String): Unit
    fun cameraLegendaryMomentMode(): String
}
