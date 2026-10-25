package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.rules.camera.CameraDeviceMismatchHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraImpersonationHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraLegendaryProfileHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraUltraQualityHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraWatermarkHooker
import com.takekazex.hypertweak.hook.rules.downloads.DownloadUiHooker
import com.takekazex.hypertweak.hook.rules.downloads.DownloadXlLogDirectoryHooker
import com.takekazex.hypertweak.hook.rules.intentresolver.SharePanelHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.phone.VideoRingbackHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.CarrierOfflineConfigHooker
import com.takekazex.hypertweak.hook.rules.settings.VisualPerceptionSettingsHooker
import com.takekazex.hypertweak.hook.rules.system.AonGestureFeatureHooker
import com.takekazex.hypertweak.hook.rules.system.AonRuntimeGateHooker
import com.takekazex.hypertweak.hook.rules.system.NativeFilePickerHooker

/** Host-owned installation order; factories run only when the caller installs the plan. */
internal object AndroidAppHookPlan {
    fun steps(packageName: String, processName: String): List<HookStep> = buildList {
        when (packageName) {
            "com.android.phone" -> {
                // The native HyperPhone page delegates the CRBT capability and setter to this
                // telephony service; keep both the display gate and modem-setting boundary here.
                hook { VideoRingbackHooker }
            }
            "com.xiaomi.phone" -> {
                // HyperPhone caches the CRBT capability it read from the telephony service above,
                // so disabling video ringback only takes effect once this UI process restarts.
                // There is deliberately no feature hook here — the package is scoped so the
                // restart receiver registered in onPackageReady can kill it without root.
            }
            "com.xiaomi.aon" -> {
                // AON service/attention process. No feature hook attaches here: the
                // visual-perception / air-gesture capability gates live in com.android.settings
                // (MiuiUtils, VisualPerceptionSettingsHooker) and in system_server
                // (AonRuntimeGateHooker, AonGestureFeatureHooker). The process is still scoped on
                // purpose: every scoped package gets the in-process restart receiver registered by
                // onPackageReady (see RestartBroadcastHooker.register), which lets the user restart
                // the attention service from the module's restart picker without root.
            }
            "com.android.camera" -> {
                // Runs before Camera.onCreate; the after-hook preserves model-config initialization
                // while suppressing only the APK/device mismatch exit guard when opted in.
                hook { CameraDeviceMismatchHooker }
                // Register profile selection after the camera config provider initializes it.
                hook { CameraLegendaryProfileHooker }
                hook { CameraWatermarkHooker }
                // Must attach after CameraWatermarkHooker so its device-logo hook and the
                // camera feature hooks share the structurally resolved config facade.
                hook { CameraImpersonationHooker }
                // Queue config-dependent hooks behind provider initialization and profile selection.
                hook { CameraUltraQualityHooker }
            }
            "com.android.providers.downloads" -> {
                // The provider's optional Xunlei log setup creates /storage/emulated/0/.xlDownload
                // even with debug logging off. The feature only blocks that log path; downloads
                // continue through the provider's normal engine.
                hook { DownloadXlLogDirectoryHooker }
            }
            "com.android.providers.downloads.ui" -> {
                // The Download Manager UI has its own package/process and must be explicitly
                // scoped; provider-side .xlDownload filtering does not reach this process.
                hook { DownloadUiHooker }
            }
            "com.android.photopicker" -> {
                hook { NativeFilePickerHooker }
            }
            "com.android.intentresolver" -> {
                hook { SharePanelHooker }
            }
            "com.android.contacts" -> {
                hook { RestartBroadcastHooker }
                hook { CarrierOfflineConfigHooker }
            }
        }
    }
}
