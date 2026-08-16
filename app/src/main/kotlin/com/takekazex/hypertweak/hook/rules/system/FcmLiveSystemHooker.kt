package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.CollectionOverrides
import com.takekazex.hypertweak.util.DebugLog

/**
 * Remove HyperOS restrictions on Google Cloud Messaging (FCM/GCM).
 * Based on HyperOS_FCM_Live by howard20181.
 * https://github.com/howard20181/HyperOS_FCM_Live
 */
object FcmLiveSystemHooker : StaticHooker() {
    override val hookerName = "FcmLiveSystem"

    private val CN_DEFER_BROADCAST = listOf(
        "com.google.android.intent.action.GCM_RECONNECT",
        "com.google.android.gcm.DISCONNECTED",
        "com.google.android.gcm.CONNECTED",
        "com.google.android.gms.gcm.HEARTBEAT_ALARM"
    )
    private const val ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE"
    private const val GMS_PACKAGE_NAME = "com.google.android.gms"
    private const val GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent"

    @Volatile
    private var loggedImmutableWhiteList = false

    override fun onInit() {
        if (!Preferences.getBoolean(Preferences.KEY_FCM_LIVE_ENABLED, false)) {
            DebugLog.d(hookerName, "FCM Live disabled by user preference")
            return
        }

        hookGreezeManagerService()
        hookDomesticPolicyManager()
        hookListAppsManager()
        hookBroadcastQueueModernStubImpl()
        hookProcessPolicy()
        hookAwareResourceControl()
        hookActivityManagerService()
        hookBroadcastSkipPolicy()
    }

    private fun hookGreezeManagerService() {
        runCatching {
            val clazz = "com.miui.server.greeze.GreezeManagerService".toClassOrNull() ?: return@runCatching

            // boolean isAllowBroadcast(int callerUid, String callerPkgName, int calleeUid, String calleePkgName, String action)
            val isAllowBroadcastMethod = clazz.getDeclaredMethod(
                "isAllowBroadcast",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
            val getPackageNameFromUidMethod = clazz.getDeclaredMethod(
                "getPackageNameFromUid",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }

            isAllowBroadcastMethod.hook {
                before { param ->
                    val callerPkgName = param.args[1] as? String
                    val calleeUid = param.args[2] as? Int
                    val action = param.args[4] as? String

                    // Fast path: a c2dm delivery owned by the GMS caller needs no callee
                    // resolution at all.
                    if (action != null && callerPkgName == GMS_PACKAGE_NAME &&
                        action == ACTION_REMOTE_INTENT
                    ) {
                        param.result = true
                        return@before
                    }

                    // Deferral actions: only now resolve the callee package name from its uid.
                    // The previous code did that for every broadcast that passed through greeze,
                    // even ones completely unrelated to GMS.
                    if (action == null || action !in CN_DEFER_BROADCAST) return@before
                    var calleePkgName = param.args[3] as? String
                    if (calleePkgName == null && calleeUid != null) {
                        runCatching {
                            calleePkgName = getPackageNameFromUidMethod.invoke(param.thisObject, calleeUid) as? String
                        }
                    }
                    if (calleePkgName == GMS_PACKAGE_NAME || calleePkgName == GMS_PERSISTENT_PROCESS_NAME) {
                        param.result = true
                    }
                }
            }

            // boolean deferBroadcastForMiui(String action)
            val deferBroadcastForMiuiMethod = clazz.getDeclaredMethod(
                "deferBroadcastForMiui",
                String::class.java
            )
            deferBroadcastForMiuiMethod.hook {
                before { param ->
                    val action = param.args[0] as? String
                    if (action in CN_DEFER_BROADCAST) {
                        param.result = false
                    }
                }
            }

            // void triggerGMSLimitAction(boolean enable) or void triggerGMSLimitAction()
            val triggerGMSLimitActionMethod = runCatching {
                clazz.getDeclaredMethod("triggerGMSLimitAction", Boolean::class.javaPrimitiveType)
            }.getOrElse {
                clazz.getDeclaredMethod("triggerGMSLimitAction")
            }

            triggerGMSLimitActionMethod.hook {
                before { param ->
                    runCatching {
                        if (param.args.isNotEmpty()) {
                            param.args[0] = false
                        } else {
                            val mGmsLimitEnabledField = clazz.getDeclaredField("mGmsLimitEnabled").apply { isAccessible = true }
                            mGmsLimitEnabledField.setBoolean(param.thisObject, false)
                        }
                    }.onFailure { t ->
                        DebugLog.w(hookerName, "Failed to disable GMS limit action", t)
                    }
                }
            }

            DebugLog.i(hookerName, "GreezeManagerService hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook GreezeManagerService", t)
        }
    }

    private fun hookDomesticPolicyManager() {
        runCatching {
            val clazz = "com.miui.server.greeze.DomesticPolicyManager".toClassOrNull() ?: return@runCatching
            val deferBroadcastMethod = clazz.getDeclaredMethod("deferBroadcast", String::class.java)

            deferBroadcastMethod.hook {
                before { param ->
                    val action = param.args.firstOrNull() as? String
                    if (action in CN_DEFER_BROADCAST) param.result = false
                }
            }

            DebugLog.i(hookerName, "DomesticPolicyManager hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook DomesticPolicyManager", t)
        }
    }

    private fun hookListAppsManager() {
        runCatching {
            val clazz = "com.miui.server.greeze.power.ListAppsManager".toClassOrNull() ?: return@runCatching
            // OS4 renamed both fields and made them static: mSystemBlackList -> SYSTEM_BLACK_LIST,
            // mUseDataWhiteList -> USE_DATA_WHITE_LIST (verified on OS4.0.0.15.XPMCNXM,
            // miui-services.jar classes2.dex). Older builds use the m-prefixed instance fields;
            // try both spellings.
            val systemBlackListField = runCatching {
                clazz.getDeclaredField("mSystemBlackList").apply { isAccessible = true }
            }.getOrElse {
                clazz.getDeclaredField("SYSTEM_BLACK_LIST").apply { isAccessible = true }
            }
            val useDataWhiteListField = runCatching {
                clazz.getDeclaredField("mUseDataWhiteList").apply { isAccessible = true }
            }.getOrElse {
                runCatching { clazz.getDeclaredField("USE_DATA_WHITE_LIST").apply { isAccessible = true } }
                    .onFailure {
                        DebugLog.w(hookerName, "ListAppsManager use-data whitelist field is unavailable", it)
                    }.getOrNull()
            }

            // Hook all constructors to remove GMS from the blacklist and seed it into the whitelist.
            clazz.declaredConstructors.forEach { constructor ->
                constructor.hook {
                    after { param ->
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val blackList = systemBlackListField.get(param.thisObject) as? MutableList<String>
                            blackList?.remove(GMS_PACKAGE_NAME)
                        }
                        // Seed GMS into the static whitelist once, in place, so the platform's own
                        // add/removeAll mutations are preserved.
                        useDataWhiteListField?.let { field ->
                            runCatching { addGmsToWhiteListInPlace(field.get(param.thisObject)) }
                        }
                    }
                }
            }

            // boolean isInWhiteList(String packageName)
            runCatching {
                val isInWhiteListMethod = clazz.getDeclaredMethod("isInWhiteList", String::class.java)
                isInWhiteListMethod.hook {
                    before { param ->
                        // USE_DATA_WHITE_LIST is a process-wide static Set. Mutate it in place instead
                        // of allocating a replacement and reassigning the field: the old reference
                        // swap discarded the platform's concurrent add/removeAll updates and
                        // republished the static unsafely across binder threads.
                        useDataWhiteListField?.let { field ->
                            runCatching {
                                addGmsToWhiteListInPlace(field.get(param.thisObject))
                            }
                        }
                        // Repeat the blacklist removal here so it holds even when the constructor
                        // ran before this hook was installed.
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val blackList = systemBlackListField.get(param.thisObject) as? MutableList<String>
                            blackList?.remove(GMS_PACKAGE_NAME)
                        }
                    }
                }
            }

            DebugLog.i(hookerName, "ListAppsManager hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook ListAppsManager", t)
        }
    }

    /**
     * Adds [GMS_PACKAGE_NAME] to the existing whitelist collection in place, never replacing the
     * static reference. No-op when GMS is already present; leaves the collection untouched and logs
     * once when it cannot be mutated.
     */
    @Suppress("UNCHECKED_CAST")
    private fun addGmsToWhiteListInPlace(current: Any?) {
        val whiteList = current as? MutableCollection<Any?>
        if (whiteList == null) {
            logImmutableWhiteListOnce(null)
            return
        }
        if (whiteList.contains(GMS_PACKAGE_NAME)) return
        try {
            whiteList.add(GMS_PACKAGE_NAME)
        } catch (t: UnsupportedOperationException) {
            // The static is an unmodifiable view; leave it as-is rather than swapping the reference.
            logImmutableWhiteListOnce(t)
        }
    }

    private fun logImmutableWhiteListOnce(t: Throwable?) {
        if (loggedImmutableWhiteList) return
        loggedImmutableWhiteList = true
        DebugLog.w(hookerName, "use-data whitelist is not mutable; left GMS unadded", t)
    }

    private fun hookBroadcastQueueModernStubImpl() {
        runCatching {
            val stubClass = "com.android.server.am.BroadcastQueueModernStubImpl".toClassOrNull() ?: return@runCatching
            val queueClass = "com.android.server.am.BroadcastQueue".toClassOrNull() ?: return@runCatching
            val recordClass = "com.android.server.am.BroadcastRecord".toClassOrNull() ?: return@runCatching

            val callerPackageField = recordClass.getDeclaredField("callerPackage").apply { isAccessible = true }
            val intentField = recordClass.getDeclaredField("intent").apply { isAccessible = true }

            val checkMethod = stubClass.getDeclaredMethod(
                "checkApplicationAutoStart",
                queueClass,
                recordClass,
                ResolveInfo::class.java
            )

            checkMethod.hook {
                before { param ->
                    val broadcastRecord = param.args[1]
                    val callerPackage = callerPackageField.get(broadcastRecord) as? String
                    val intent = intentField.get(broadcastRecord) as? Intent

                    if (callerPackage == GMS_PACKAGE_NAME && intent?.action == ACTION_REMOTE_INTENT) {
                        param.result = true
                    }
                }
            }

            DebugLog.i(hookerName, "BroadcastQueueModernStubImpl hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook BroadcastQueueModernStubImpl", t)
        }
    }

    private fun hookProcessPolicy() {
        runCatching {
            val clazz = "com.android.server.am.ProcessPolicy".toClassOrNull() ?: return@runCatching
            val getWhiteListMethod = clazz.getDeclaredMethod("getWhiteList", Int::class.javaPrimitiveType)

            getWhiteListMethod.hook {
                after { param ->
                    val flags = param.args[0] as? Int
                    if (flags != null && (flags and 1) != 0) {
                        @Suppress("UNCHECKED_CAST")
                        val current = param.result as? Collection<*>
                        param.result = CollectionOverrides.stringList(
                            current, GMS_PACKAGE_NAME, GMS_PERSISTENT_PROCESS_NAME
                        )
                    }
                }
            }

            DebugLog.i(hookerName, "ProcessPolicy hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook ProcessPolicy", t)
        }
    }

    private fun hookAwareResourceControl() {
        runCatching {
            val clazz = "com.miui.server.greeze.power.AwareResourceControl".toClassOrNull() ?: return@runCatching
            val mNoNetworkBlackUidsField = clazz.getDeclaredField("mNoNetworkBlackUids").apply { isAccessible = true }

            clazz.declaredConstructors.forEach { constructor ->
                constructor.hook {
                    after { param ->
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val blackUids = mNoNetworkBlackUidsField.get(param.thisObject) as? MutableList<String>
                            blackUids?.remove(GMS_PACKAGE_NAME)
                        }
                    }
                }
            }

            DebugLog.i(hookerName, "AwareResourceControl hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook AwareResourceControl", t)
        }
    }

    private fun hookBroadcastSkipPolicy() {
        runCatching {
            val policyClass = "com.android.server.am.BroadcastSkipPolicy".toClassOrNull() ?: return@runCatching
            val recordClass = "com.android.server.am.BroadcastRecord".toClassOrNull() ?: return@runCatching
            val callerPackageField = recordClass.getDeclaredField("callerPackage").apply { isAccessible = true }
            val intentField = recordClass.getDeclaredField("intent").apply { isAccessible = true }

            fun isGmsC2dm(record: Any?): Boolean {
                if (record == null) return false
                val caller = callerPackageField.get(record) as? String
                if (caller != GMS_PACKAGE_NAME) return false
                val intent = intentField.get(record) as? Intent
                return intent?.action == ACTION_REMOTE_INTENT
            }

            // Tombstone-freezer modules (observed: cn.myflv.noactive) hook the private
            // shouldSkipMessage variants and skip broadcasts to frozen processes ("Skip broadcast
            // to frozen process"), which defeats FCM delivery even with the greeze bypasses above.
            // Short-circuit at the public entry points instead — the enqueue loop calls
            // shouldSkipAtEnqueueMessage(BroadcastRecord, Object) and the delivery path calls
            // shouldSkipMessage(BroadcastRecord, Object), both before the private variants, so the
            // bypass holds regardless of module hook order.
            listOf(
                "shouldSkipAtEnqueueMessage",
                "shouldSkipMessage"
            ).forEach { methodName ->
                val method = policyClass.getDeclaredMethod(methodName, recordClass, Any::class.java)
                method.hook {
                    before { param ->
                        if (isGmsC2dm(param.args.firstOrNull())) {
                            param.result = null
                        }
                    }
                }
            }

            DebugLog.i(hookerName, "BroadcastSkipPolicy hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook BroadcastSkipPolicy", t)
        }
    }

    private fun hookActivityManagerService() {
        runCatching {
            val amsClass = "com.android.server.am.ActivityManagerService".toClassOrNull() ?: return@runCatching
            val mContextField = amsClass.getDeclaredField("mContext").apply { isAccessible = true }
            val appThreadClass = "android.app.IApplicationThread".toClassOrNull() ?: return@runCatching
            val receiverClass = "android.content.IIntentReceiver".toClassOrNull() ?: return@runCatching
            val processRecordClass = "com.android.server.am.ProcessRecord".toClassOrNull() ?: return@runCatching
            val infoField = processRecordClass.getDeclaredField("info").apply { isAccessible = true }

            // Use API 31+ method (minSdk is 35)
            val getRecordMethod = amsClass.getDeclaredMethod("getRecordForAppLOSP", appThreadClass).apply {
                isAccessible = true
            }

            val stringArrayClass = Array<String>::class.java

            // Use API 33+ method signature (minSdk is 35)
            val method = amsClass.getDeclaredMethod(
                "broadcastIntentWithFeature",
                appThreadClass, String::class.java,
                Intent::class.java, String::class.java, receiverClass,
                Int::class.javaPrimitiveType, String::class.java, android.os.Bundle::class.java,
                stringArrayClass, stringArrayClass,
                stringArrayClass, Int::class.javaPrimitiveType, android.os.Bundle::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            method.hook {
                before { param ->
                    runCatching {
                        handleBroadcastIntent(param.args, param.thisObject, 2, getRecordMethod, infoField, mContextField)
                    }.onFailure { t ->
                        DebugLog.w(hookerName, "Failed to handle FCM broadcast intent", t)
                    }
                }
            }

            DebugLog.i(hookerName, "ActivityManagerService hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook ActivityManagerService", t)
        }
    }

    private fun handleBroadcastIntent(
        args: Array<Any?>,
        thisObject: Any,
        intentArgIndex: Int,
        getRecordMethod: java.lang.reflect.Method,
        infoField: java.lang.reflect.Field,
        mContextField: java.lang.reflect.Field
    ) {
        val intent = args[intentArgIndex] as? Intent
        if (intent?.action == ACTION_REMOTE_INTENT) {
            val app = getRecordMethod.invoke(thisObject, args[0])
            val info = app?.let { infoField.get(it) as? ApplicationInfo }

            if (info?.packageName == GMS_PACKAGE_NAME) {
                // Add to temporary allow list for push messaging (API 31+, minSdk is 35)
                val packageName = intent.`package`
                if (packageName != null) {
                    runCatching {
                        val context = mContextField.get(thisObject) as? Context ?: return@runCatching
                        val powerExemptionManager = context.getSystemService("power_exemption") ?: return@runCatching
                        val pemClass = Class.forName("android.os.PowerExemptionManager")
                        val addMethod = pemClass.getMethod(
                            "addToTemporaryAllowList",
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            String::class.java,
                            Long::class.javaPrimitiveType
                        )
                        addMethod.invoke(powerExemptionManager, packageName, 102, "GOOGLE_C2DM", 2000L)
                    }.onFailure { t ->
                        DebugLog.w(hookerName, "Failed to add FCM receiver to temporary allow list", t)
                    }
                }

                // Add FLAG_INCLUDE_STOPPED_PACKAGES
                if ((intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
            }
        }
    }
}
