package com.takekazex.hypertweak.hook.rules.system

import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.CollectionOverrides
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Remove HyperOS restrictions on Google Cloud Messaging (FCM/GCM).
 * Based on HyperOS_FCM_Live by howard20181.
 * https://github.com/howard20181/HyperOS_FCM_Live
 */
object FcmLiveSystemHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingFcmLiveEnabled by hookSetting(false) { Preferences.getBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, false) }

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

    @Volatile private var retired = false
    private var listBlack: java.lang.reflect.Field? by generationState { null }
    private var listWhite: java.lang.reflect.Field? by generationState { null }
    private val memberships = com.takekazex.hypertweak.hook.base.CollectionMembershipOverride(GMS_PACKAGE_NAME)
    init { generationCleanup { retired = true; memberships.restore() } }

    fun refreshSettings(): Boolean {
        if (retired || !settingFcmLiveEnabled) memberships.restore()
        else {
            @Suppress("UNCHECKED_CAST")
            listBlack?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }?.let {
                memberships.apply(it.get(null) as? MutableCollection<String>, false)
            }
            listWhite?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }?.let {
                addGmsToWhiteListInPlace(it.get(null))
            }
        }
        return true
    }

    override fun onPrepareHotReload() { retired = true; memberships.restore() }

    private var loggedImmutableWhiteList by generationState { false }

    override fun onHook() {
        retired = false
        hookGreezeManagerService()
        hookDomesticPolicyManager()
        hookListAppsManager()
        hookBroadcastQueueModernStubImpl()
        hookProcessPolicy()
        hookAwareResourceControl()
        hookActivityManagerService()
        hookBroadcastSkipPolicy()
        refreshSettings()
    }

    private fun hookGreezeManagerService() {
        runCatching {
            val clazz = "com.miui.server.greeze.GreezeManagerService".toClassOrNull() ?: return@runCatching

            // boolean isAllowBroadcast(int callerUid, String callerPkgName, int calleeUid, String calleePkgName, String action)
            val isAllowBroadcastMethod = HostAccess.requireDeclaredMethod(clazz,
                "isAllowBroadcast",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java, accessible = false)
            val getPackageNameFromUidMethod = HostAccess.requireDeclaredMethod(clazz,
                "getPackageNameFromUid",
                Int::class.javaPrimitiveType, accessible = true)

            isAllowBroadcastMethod.hook {
                before { param ->
                    if (retired || !settingFcmLiveEnabled) return@before
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
            val deferBroadcastForMiuiMethod = HostAccess.requireDeclaredMethod(clazz,
                "deferBroadcastForMiui",
                String::class.java, accessible = false)
            deferBroadcastForMiuiMethod.hook {
                before { param ->
                    if (retired || !settingFcmLiveEnabled) return@before
                    val action = param.args[0] as? String
                    if (action in CN_DEFER_BROADCAST) {
                        param.result = false
                    }
                }
            }

            // void triggerGMSLimitAction(boolean enable) or void triggerGMSLimitAction()
            val triggerGMSLimitActionMethod = runCatching {
                HostAccess.requireDeclaredMethod(clazz, "triggerGMSLimitAction", Boolean::class.javaPrimitiveType, accessible = false)
            }.getOrElse {
                HostAccess.requireDeclaredMethod(clazz, "triggerGMSLimitAction", accessible = false)
            }

            triggerGMSLimitActionMethod.hook {
                before { param ->
                    // This entry point performs only the GMS limit action. Suppress the action,
                    // preserving mGmsLimitEnabled for the next native update after disable.
                    if (!retired && settingFcmLiveEnabled) param.result = null
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
            val deferBroadcastMethod = HostAccess.requireDeclaredMethod(clazz, "deferBroadcast", String::class.java, accessible = false)

            deferBroadcastMethod.hook {
                before { param ->
                    if (retired || !settingFcmLiveEnabled) return@before
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
                HostAccess.requireDeclaredField(clazz, "mSystemBlackList", accessible = true)
            }.getOrElse {
                HostAccess.requireDeclaredField(clazz, "SYSTEM_BLACK_LIST", accessible = true)
            }
            val useDataWhiteListField = runCatching {
                HostAccess.requireDeclaredField(clazz, "mUseDataWhiteList", accessible = true)
            }.getOrElse {
                runCatching { HostAccess.requireDeclaredField(clazz, "USE_DATA_WHITE_LIST", accessible = true) }
                    .onFailure {
                        DebugLog.w(hookerName, "ListAppsManager use-data whitelist field is unavailable", it)
                    }.getOrNull()
            }

            listBlack = systemBlackListField
            listWhite = useDataWhiteListField
            // Hook all constructors to remove GMS from the blacklist and seed it into the whitelist.
            clazz.declaredConstructors.forEach { constructor ->
                constructor.hook {
                    after { param ->
                        if (retired || !settingFcmLiveEnabled) return@after
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val blackList = systemBlackListField.get(param.thisObject) as? MutableList<String>
                            memberships.apply(blackList, false)
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
                val isInWhiteListMethod = HostAccess.requireDeclaredMethod(clazz, "isInWhiteList", String::class.java, accessible = false)
                isInWhiteListMethod.hook {
                    before { param ->
                        if (retired || !settingFcmLiveEnabled) return@before
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
                            memberships.apply(blackList, false)
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
        val whiteList = current as? MutableCollection<String>
        if (whiteList == null) {
            logImmutableWhiteListOnce(null)
            return
        }
        if (whiteList.contains(GMS_PACKAGE_NAME)) return
        try {
            memberships.apply(whiteList, true)
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

            val callerPackageField = HostAccess.requireDeclaredField(recordClass, "callerPackage", accessible = true)
            val intentField = HostAccess.requireDeclaredField(recordClass, "intent", accessible = true)

            val checkMethod = HostAccess.requireDeclaredMethod(stubClass,
                "checkApplicationAutoStart",
                queueClass,
                recordClass,
                ResolveInfo::class.java, accessible = false)

            checkMethod.hook {
                before { param ->
                    if (retired || !settingFcmLiveEnabled) return@before
                    // Reflection on a system_server callback: isolate it so a field-shape change
                    // can never take down the broadcast queue.
                    val callerPackage = runCatching {
                        callerPackageField.get(param.args[1]) as? String
                    }.getOrNull()
                    val intent = runCatching {
                        intentField.get(param.args[1]) as? Intent
                    }.getOrNull()

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
            val getWhiteListMethod = HostAccess.requireDeclaredMethod(clazz, "getWhiteList", Int::class.javaPrimitiveType, accessible = false)

            getWhiteListMethod.hook {
                after { param ->
                    if (retired || !settingFcmLiveEnabled) return@after
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
            val method = HostAccess.requireDeclaredMethod(clazz, "isNetworkDisableAble", String::class.java, accessible = false)
            deoptimize(method)
            method.hook {
                before { param ->
                    if (!retired && settingFcmLiveEnabled && param.args.firstOrNull() == GMS_PACKAGE_NAME) param.result = false
                }
            }
        }.onFailure { DebugLog.e(hookerName, "Failed to hook AwareResourceControl", it) }
    }

    private fun hookBroadcastSkipPolicy() {
        runCatching {
            val policyClass = "com.android.server.am.BroadcastSkipPolicy".toClassOrNull() ?: return@runCatching
            val recordClass = "com.android.server.am.BroadcastRecord".toClassOrNull() ?: return@runCatching
            val callerPackageField = HostAccess.requireDeclaredField(recordClass, "callerPackage", accessible = true)
            val intentField = HostAccess.requireDeclaredField(recordClass, "intent", accessible = true)

            fun isGmsC2dm(record: Any?): Boolean {
                if (record == null) return false
                // Isolated: called from system_server broadcast paths where a field-shape change
                // would otherwise propagate out of the hook.
                val caller = runCatching {
                    callerPackageField.get(record) as? String
                }.getOrNull()
                if (caller != GMS_PACKAGE_NAME) return false
                val intent = runCatching {
                    intentField.get(record) as? Intent
                }.getOrNull()
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
                val method = HostAccess.requireDeclaredMethod(policyClass, methodName, recordClass, Any::class.java, accessible = false)
                method.hook {
                    before { param ->
                        if (retired || !settingFcmLiveEnabled) return@before
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
            val mContextField = HostAccess.requireDeclaredField(amsClass, "mContext", accessible = true)
            val appThreadClass = "android.app.IApplicationThread".toClassOrNull() ?: return@runCatching
            val receiverClass = "android.content.IIntentReceiver".toClassOrNull() ?: return@runCatching
            val processRecordClass = "com.android.server.am.ProcessRecord".toClassOrNull() ?: return@runCatching
            val infoField = HostAccess.requireDeclaredField(processRecordClass, "info", accessible = true)

            // Use API 31+ method (minSdk is 35)
            val getRecordMethod = HostAccess.requireDeclaredMethod(amsClass, "getRecordForAppLOSP", appThreadClass, accessible = true)

            val stringArrayClass = Array<String>::class.java

            // Use API 33+ method signature (minSdk is 35)
            val method = HostAccess.requireDeclaredMethod(amsClass,
                "broadcastIntentWithFeature",
                appThreadClass, String::class.java,
                Intent::class.java, String::class.java, receiverClass,
                Int::class.javaPrimitiveType, String::class.java, android.os.Bundle::class.java,
                stringArrayClass, stringArrayClass,
                stringArrayClass, Int::class.javaPrimitiveType, android.os.Bundle::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, accessible = false)
            method.hook {
                before { param ->
                    if (retired || !settingFcmLiveEnabled) return@before
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
                        val addMethod = HostAccess.requirePublicMethod(pemClass,
                            "addToTemporaryAllowList",
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            String::class.java,
                            Long::class.javaPrimitiveType, accessible = false)
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
