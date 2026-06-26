package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Remove HyperOS restrictions on Google Cloud Messaging (FCM/GCM).
 * Based on HyperOS_FCM_Live by howard20181.
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
                    var calleePkgName = param.args[3] as? String
                    val calleeUid = param.args[2] as? Int
                    val action = param.args[4] as? String

                    // Try to resolve callee package name from uid
                    if (calleeUid != null) {
                        runCatching {
                            calleePkgName = getPackageNameFromUidMethod.invoke(param.thisObject, calleeUid) as? String
                        }
                    }

                    // Allow FCM broadcasts
                    if (action != null && (
                        (callerPkgName == GMS_PACKAGE_NAME && action == ACTION_REMOTE_INTENT) ||
                        ((calleePkgName == GMS_PACKAGE_NAME || calleePkgName == GMS_PERSISTENT_PROCESS_NAME) &&
                            action in CN_DEFER_BROADCAST)
                    )) {
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
                    param.result = false
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
            val mSystemBlackListField = clazz.getDeclaredField("mSystemBlackList").apply { isAccessible = true }

            // Hook all constructors to remove GMS from blacklist
            clazz.declaredConstructors.forEach { constructor ->
                constructor.hook {
                    after { param ->
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val blackList = mSystemBlackListField.get(param.thisObject) as? MutableList<String>
                            blackList?.remove(GMS_PACKAGE_NAME)
                        }
                    }
                }
            }

            // boolean isInWhiteList(String packageName)
            runCatching {
                val isInWhiteListMethod = clazz.getDeclaredMethod("isInWhiteList", String::class.java)
                val mUseDataWhiteListField = clazz.getDeclaredField("mUseDataWhiteList").apply { isAccessible = true }

                isInWhiteListMethod.hook {
                    before { param ->
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val whiteList = mUseDataWhiteListField.get(param.thisObject) as? MutableSet<String>
                            whiteList?.add(GMS_PACKAGE_NAME)
                        }
                    }
                }
            }

            DebugLog.i(hookerName, "ListAppsManager hooks registered")
        }.onFailure { t ->
            DebugLog.e(hookerName, "Failed to hook ListAppsManager", t)
        }
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
                        val whiteList = param.result as? MutableList<String>
                        whiteList?.apply {
                            add(GMS_PACKAGE_NAME)
                            add(GMS_PERSISTENT_PROCESS_NAME)
                        }
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

    private fun hookActivityManagerService() {
        runCatching {
            val amsClass = "com.android.server.am.ActivityManagerService".toClassOrNull() ?: return@runCatching
            val mContextField = amsClass.getDeclaredField("mContext").apply { isAccessible = true }
            val appThreadClass = "android.app.IApplicationThread".toClassOrNull() ?: return@runCatching
            val receiverClass = "android.content.IIntentReceiver".toClassOrNull() ?: return@runCatching
            val processRecordClass = "com.android.server.am.ProcessRecord".toClassOrNull() ?: return@runCatching
            val infoField = processRecordClass.getDeclaredField("info").apply { isAccessible = true }

            // Use API 31+ method (minSdk is 35)
            val getRecordMethod = amsClass.getDeclaredMethod("getRecordForAppLOSP", appThreadClass)

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
                        handleBroadcastIntent(param, 2, getRecordMethod, infoField, mContextField)
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
        param: Any,
        intentArgIndex: Int,
        getRecordMethod: java.lang.reflect.Method,
        infoField: java.lang.reflect.Field,
        mContextField: java.lang.reflect.Field
    ) {
        // Access param properties through reflection since we're outside the hook DSL
        val paramClass = param.javaClass
        val argsField = paramClass.getDeclaredField("args").apply { isAccessible = true }
        val thisObjectField = paramClass.getDeclaredField("thisObject").apply { isAccessible = true }

        val args = argsField.get(param) as Array<*>
        val thisObject = thisObjectField.get(param)

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
