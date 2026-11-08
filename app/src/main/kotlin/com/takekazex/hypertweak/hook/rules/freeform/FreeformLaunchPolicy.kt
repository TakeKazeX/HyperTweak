package com.takekazex.hypertweak.hook.rules.freeform

import android.content.Intent
import com.takekazex.hypertweak.hook.rules.aicr.AicrCopyDirectBrowserPolicy

private const val CONTENT_EXTENSION = "com.miui.contentextension"
private const val WECHAT = "com.tencent.mm"

private val excludedTargets = setOf(
    "android", "com.android.intentresolver", "com.android.systemui", "com.miui.home",
    "com.android.camera", "com.android.settings", "com.miui.securitycenter", "com.miui.securitycore",
    "com.android.packageinstaller", "com.miui.packageinstaller", "com.android.certinstaller",
    "com.android.permissioncontroller", "com.google.android.permissioncontroller",
)

internal enum class FreeformLaunchScenario {
    SHARE,
    CONTENT_LINK,
    COPY_DIRECT_LINK;

    fun isEnabled(share: Boolean, contentLinks: Boolean): Boolean = when (this) {
        SHARE -> share
        CONTENT_LINK, COPY_DIRECT_LINK -> contentLinks
    }
}

internal fun isFreeformLaunchTarget(pkg: String) = pkg.isNotBlank() && pkg !in excludedTargets

internal fun freeformLaunchScenario(
    caller: String?,
    target: String?,
    activity: String?,
    action: String?,
    scheme: String?,
    openSource: String? = null,
): FreeformLaunchScenario? {
    if (caller.isNullOrBlank() || target == null || !isFreeformLaunchTarget(target) || activity.isNullOrBlank() ||
        caller == target || caller == "com.miui.screenshot"
    ) return null
    val webLink = action == Intent.ACTION_VIEW && (scheme == "https" || scheme == "http")
    return when {
        action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE ||
            (target == WECHAT && activity == "$WECHAT.plugin.base.stub.WXEntryActivity") ->
            FreeformLaunchScenario.SHARE
        caller == CONTENT_EXTENSION && webLink ->
            FreeformLaunchScenario.CONTENT_LINK
        caller == AicrCopyDirectBrowserPolicy.AICR_PACKAGE && webLink &&
            openSource == AicrCopyDirectBrowserPolicy.CLIPBOARD_OPEN ->
            FreeformLaunchScenario.COPY_DIRECT_LINK
        else -> null
    }
}
