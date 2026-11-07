package com.takekazex.hypertweak.hook.rules.barrage

import android.app.Notification
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object BarrageAnyAppHooker : DynamicHooker() {
    private const val TAG = "BarrageAnyApp"
    private const val SERVICE = "com.xiaomi.barrage.service.NotificationMonitorService"
    private const val PACKAGE_LIST = "mBarragePackageList"
    private const val SBN = "Landroid/service/notification/StatusBarNotification;"
    private const val WINDOW = "Lcom/xiaomi/barrage/utils/BarrageWindowUtils;"
    private const val MAX_RECENT = 128
    private const val REPEAT_WINDOW_MS = 5_000L
    private const val MAX_TEXT_LENGTH = 2048

    private object Feature : DexFeature {
        override val id = "barrage.any_app"
        override val schema = 2
    }

    private data class Targets(val filter: Method, val caller: Method, val packages: Field)
    private data class Recent(val title: String, val body: String, val timestamp: Long)

    override fun onHook() {
        val apkPath = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(Feature.id, resolve = { resolve(apkPath) }, install = ::install)
    }

    private fun resolve(apkPath: String): Targets? = DexRuntime.resolve(
        feature = Feature,
        apkPath = apkPath,
        cacheDir = dexCacheDirectory,
        compute = { session ->
            session.dexKit { bridge ->
                val service = bridge.findClass { matcher { className(SERVICE) } }.singleOrNull()
                    ?: return@dexKit emptyMap()
                val filter = service.methods.singleOrNull { method ->
                    method.descriptor.endsWith("->filterNotification(${SBN})Z") &&
                        method.usingFields.any {
                            it.usingType.isRead() && it.field.descriptor == "${service.descriptor}->${PACKAGE_LIST}:Ljava/util/List;"
                        } &&
                        method.invokes.any { it.descriptor == "Ljava/util/List;->contains(Ljava/lang/Object;)Z" } &&
                        method.usingStrings.contains("No barrage: not in barrage support package list")
                } ?: return@dexKit emptyMap()
                val caller = filter.callers.singleOrNull()?.takeIf { method ->
                    method.descriptor == "${service.descriptor}->handleNotification(${SBN})V" &&
                        method.invokes.any { it.descriptor == "${WINDOW}->addBarrageNotification(${SBN}Z)V" }
                } ?: return@dexKit emptyMap()
                mapOf("filter" to filter.descriptor, "caller" to caller.descriptor)
            } ?: emptyMap()
        },
        decode = { values, _ ->
            val filter = values["filter"]?.let { MethodDescriptorCodec.method(it, classLoader) }
                ?: return@resolve null
            val caller = values["caller"]?.let { MethodDescriptorCodec.method(it, classLoader) }
                ?: return@resolve null
            val service = filter.declaringClass
            if (service.name != SERVICE || service.superclass != NotificationListenerService::class.java ||
                caller.declaringClass != service
            ) return@resolve null
            val packages = service.getDeclaredField(PACKAGE_LIST)
            if (packages.type != List::class.java || !Modifier.isStatic(packages.modifiers)) return@resolve null
            packages.isAccessible = true
            Targets(filter, caller, packages)
        },
    )

    private fun install(targets: Targets) {
        val enabled by hookSetting(false) {
            Preferences.getBoolean(PlatformSettingsKeys.KEY_BARRAGE_ANY_APP, false)
        }
        val recent = LinkedHashMap<String, Recent>()
        if (!deoptimize(targets.caller) || !deoptimize(targets.filter)) {
            DebugLog.hookSkipped(TAG, "notification whitelist", "could not deoptimize host path")
            return
        }
        targets.filter.hook("barrage_any_app") {
            intercept { chain ->
                val sbn = chain.args.getOrNull(0) as? StatusBarNotification
                // The framework delivers notifications on the same looper that initializes the list.
                if (!enabled || sbn == null || Looper.myLooper() != Looper.getMainLooper()) {
                    return@intercept chain.proceed()
                }
                val packages = HookFailurePolicy.open(TAG, "read allowlist", null) {
                    @Suppress("UNCHECKED_CAST")
                    targets.packages.get(null) as? ArrayList<String>
                } ?: return@intercept chain.proceed()
                val packageName = sbn.packageName
                if (packageName in packages) return@intercept chain.proceed()
                if (!sbn.isClearable || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
                    return@intercept true
                }
                packages.add(packageName)
                val filtered = try {
                    chain.proceed()
                } finally {
                    packages.remove(packageName)
                }
                if (filtered != false) return@intercept filtered
                HookFailurePolicy.open(TAG, "filter repeated update", false) {
                    val extras = sbn.notification.extras
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE) ?: return@open false
                    val body = extras.getCharSequence(Notification.EXTRA_TEXT) ?: return@open false
                    if (title.length > MAX_TEXT_LENGTH || body.length > MAX_TEXT_LENGTH) return@open false
                    val now = SystemClock.elapsedRealtime()
                    val previous = recent.remove(sbn.key)
                    val current = Recent(title.toString(), body.toString(), now)
                    recent[sbn.key] = current
                    if (recent.size > MAX_RECENT) recent.remove(recent.keys.first())
                    previous != null && previous.title == current.title && previous.body == current.body &&
                        now - previous.timestamp < REPEAT_WINDOW_MS
                }
            }
        }
        DebugLog.i(TAG, "dynamic notification allowlist installed")
    }
}
