package com.takekazex.hypertweak.hook.rules.htmlviewer

import android.app.NotificationManager
import android.content.ContentProvider
import android.content.Context
import android.os.Binder
import android.os.Bundle
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.settings.contract.OtaNotificationReplay
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Replays the stock focus notification from the local OTA response in HTML Viewer's process. */
internal object OtaNotificationReplayHooker : StaticHooker() {
    private const val TAG = "OtaNotificationReplay"
    private const val PROVIDER = "Lcom/android/htmlviewer/ota/notification/BannerProvider;"
    private const val SERVICE = "Lcom/android/htmlviewer/ota/OtaService;"
    private const val DATA = "Lcom/android/htmlviewer/ota/net/result/BaseResult;"
    private const val RESPONSE = "Lcom/android/htmlviewer/ota/request/OtaResponse;"
    private const val CHANNEL = "com_miui_htmlViewer_channel_id"
    private const val NOTIFICATION_ID = 1

    private data class Target(
        val call: Method, val read: Method, val data: Field, val expired: Method, val post: Method,
    )

    override fun onHook() {
        if (!PlatformLevel.isOs4) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(name = TAG, resolve = { resolve(apk) }) { target ->
            target.call.hook("html_viewer_replay_ota_notification") {
                before { param ->
                    if (generationRetired || param.args[0] != OtaNotificationReplay.VIEWER_AUTHORITY ||
                        param.args[1] != OtaNotificationReplay.METHOD
                    ) return@before
                    val provider = param.thisObject as? ContentProvider ?: return@before
                    param.result = HookFailurePolicy.open(TAG, "handle Settings replay request", null as Bundle?) {
                        if (provider.callingPackage != OtaNotificationReplay.SETTINGS) return@open null
                        val context = provider.context ?: return@open null
                        val identity = Binder.clearCallingIdentity()
                        try {
                            Bundle().apply { putInt(OtaNotificationReplay.RESULT, replay(target, context)) }
                        } finally {
                            Binder.restoreCallingIdentity(identity)
                        }
                    }
                }
            }
            DebugLog.hookRegistered(TAG, "native OTA focus notification replay")
        }
    }

    @Synchronized
    private fun replay(target: Target, context: Context): Int {
        val notifications = context.getSystemService(NotificationManager::class.java)
            ?: return OtaNotificationReplay.UNAVAILABLE
        if (!notifications.areNotificationsEnabled() ||
            notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return OtaNotificationReplay.BLOCKED
        if (target.expired.invoke(null, context) != false) return OtaNotificationReplay.UNAVAILABLE
        val cached = target.read.invoke(null, context) ?: return OtaNotificationReplay.UNAVAILABLE
        val response = target.data.get(cached) ?: return OtaNotificationReplay.UNAVAILABLE
        if (notifications.activeNotifications.any { it.id == NOTIFICATION_ID && it.tag == null && it.notification.channelId == CHANNEL }) {
            notifications.cancel(NOTIFICATION_ID)
        }
        if (target.post.invoke(null, context, response, true) != true) return OtaNotificationReplay.UNAVAILABLE
        DebugLog.i(TAG, "posted the native OTA focus notification")
        return OtaNotificationReplay.POSTED
    }

    private fun resolve(apk: String): Target? {
        val index = DexRuntime.operands(apk)
        val call = index.definition(PROVIDER +
            "->call(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;")
            ?: return null
        if (index.code(call).calls.none { it.method.name == "onCall" }) return null
        val start = index.code(SERVICE + "->onStartJob(Landroid/app/job/JobParameters;)Z")
        val read = start.calls.map { it.method }.distinctBy(MethodDescriptorCodec::descriptor).singleOrNull {
            it.returnType == DATA && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
                index.code(it).hasString("/ota/otaResponse.json")
        } ?: return null
        val data = start.reads.distinct().singleOrNull { it.definingClass == DATA && it.type == RESPONSE } ?: return null
        val expired = start.calls.map { it.method }.distinctBy(MethodDescriptorCodec::descriptor).singleOrNull {
            it.definingClass == read.definingClass && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
                index.code(it).hasString("ota_data_os_version")
        } ?: return null
        val post = index.code(SERVICE + "->showOtaNewFeaturesNotificationIfNeed(Z)V").calls
            .map { it.method }.singleOrNull {
                it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) ==
                    listOf("Landroid/content/Context;", RESPONSE, "Z") &&
                    index.code(it).let { body ->
                        body.hasString("miui.focus.param") && body.hasString("ota_provision") && body.hasString(CHANNEL) &&
                            body.calls.any { candidate -> MethodDescriptorCodec.descriptor(candidate.method) ==
                                "Landroid/app/NotificationManager;->notify(ILandroid/app/Notification;)V" }
                    }
            } ?: return null
        return Target(
            index.method(call, classLoader) ?: return null,
            index.method(read, classLoader) ?: return null,
            index.field(data, classLoader) ?: return null,
            index.method(expired, classLoader) ?: return null,
            index.method(post, classLoader) ?: return null,
        )
    }
}
