package com.takekazex.hypertweak.settings.contract

internal interface PowerButtonSettings {
    fun powerButtonAction(): Int
    fun setPowerButtonAction(action: Int): Unit
}
