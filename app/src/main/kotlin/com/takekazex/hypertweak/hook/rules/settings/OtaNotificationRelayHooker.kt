package com.takekazex.hypertweak.hook.rules.settings

import android.content.ContentProvider
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.settings.contract.OtaNotificationReplay
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Method

/** Calls the signature-protected viewer provider using Settings' own identity. */
internal object OtaNotificationRelayHooker : StaticHooker() {
    private const val TAG = "OtaNotificationRelay"
    private const val CALL = "Lcom/android/settings/dashboard/suggestions/SuggestionStateProvider;" +
        "->call(Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;"

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(name = TAG, resolve = { resolve(apk) }) { call ->
            call.hook("settings_ota_notification_relay") {
                before { param ->
                    if (generationRetired || param.args[0] != OtaNotificationReplay.METHOD) return@before
                    val provider = param.thisObject as? ContentProvider ?: return@before
                    param.result = HookFailurePolicy.open(TAG, "forward OTA replay request", null as Bundle?) {
                        if (provider.callingPackage != BuildConfig.APPLICATION_ID) return@open null
                        val context = provider.context ?: return@open null
                        val identity = Binder.clearCallingIdentity()
                        try {
                            // The ID lets the stock provider ignore the unknown method when its hook
                            // is still preparing.
                            val extras = Bundle().apply { putString("id", "id_ota") }
                            context.contentResolver.call(
                                Uri.parse("content://${OtaNotificationReplay.VIEWER_AUTHORITY}"),
                                OtaNotificationReplay.METHOD, null, extras,
                            )
                        } finally {
                            Binder.restoreCallingIdentity(identity)
                        }
                    }
                }
            }
        }
    }

    private fun resolve(apk: String): Method? {
        val index = DexRuntime.operands(apk)
        val call = index.definition(CALL) ?: return null
        val body = index.code(call)
        if (!body.hasString("getSuggestionState") || !body.hasString("candidate_is_complete")) return null
        return index.method(call, classLoader)
    }
}
