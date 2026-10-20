package com.takekazex.hypertweak.hook.rules.googleapp

/** The verified Lens/OMNI activities execute only in :googleapp, not voice/search services. */
internal object GoogleAppProcessPolicy {
    const val PACKAGE = "com.google.android.googlequicksearchbox"
    const val UI_PROCESS = "$PACKAGE:googleapp"

    fun shouldSkipProcess(process: String): Boolean =
        (process == PACKAGE || process.startsWith("$PACKAGE:")) && process != UI_PROCESS

    fun ownsUi(packageName: String, process: String): Boolean =
        packageName == PACKAGE && process == UI_PROCESS
}
