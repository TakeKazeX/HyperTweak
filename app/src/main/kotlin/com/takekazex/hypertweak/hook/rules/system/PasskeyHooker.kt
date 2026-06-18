package com.takekazex.hypertweak.hook.rules.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.StaticHooker
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock

object PasskeyHooker : StaticHooker() {
    private const val TAG = "HyperPasskey"
    private var fIsInternationalBuildBoolean: Field? = null

    private val INTL_LOCK = ReentrantLock(true)
    private val DEPTH = ThreadLocal.withInitial { 0 }
    private val PREV_VALUE = ThreadLocal<Boolean>()

    override fun onHook() {
        val packageName = hookParam.packageName

        if (packageName == "system") {
            try {
                hookSystemServer()
            } catch (t: Throwable) {
                Log.e(TAG, "Error hooking system service", t)
            }
            return
        }

        // Initialize IS_INTERNATIONAL_BUILD field if available
        try {
            val buildClass = classLoader.loadClass("miui.os.Build")
            fIsInternationalBuildBoolean = buildClass.getDeclaredField("IS_INTERNATIONAL_BUILD").apply {
                isAccessible = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "find IS_INTERNATIONAL_BUILD failed", e)
        }

        val appInfo = hookParam.appInfo ?: return
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return
        val cacheDir = File(baseDir, "cache")
        val apkPath = appInfo.sourceDir ?: return

        when (packageName) {
            "com.android.settings" -> {
                try {
                    hookSettings(cacheDir, apkPath)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error hooking Settings", t)
                }
            }
            "com.miui.securitycenter" -> {
                try {
                    hookSecurityCenter(apkPath)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error hooking SecurityCenter", t)
                }
            }
            "com.xiaomi.scanner" -> {
                try {
                    hookScanner()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error hooking Scanner", t)
                }
            }
        }
    }

    private fun hookSystemServer() {
        // RequestSession constructor hook
        val cRequestSession = "com.android.server.credentials.RequestSession".toClassOrNull() ?: return
        val fHybridService = runCatching {
            cRequestSession.getDeclaredField("mHybridService").apply { isAccessible = true }
        }.getOrNull() ?: return

        val aClass = "com.android.server.credentials.RequestSession\$SessionLifetime".toClassOrNull() ?: return
        val callingAppInfoClass = "android.service.credentials.CallingAppInfo".toClassOrNull() ?: return
        
        val constructorRequestSession = runCatching {
            cRequestSession.getDeclaredConstructor(
                Context::class.java, aClass, Any::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Any::class.java, Any::class.java, String::class.java,
                callingAppInfoClass,
                Set::class.java, android.os.CancellationSignal::class.java, Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!
            )
        }.getOrNull()

        constructorRequestSession?.hook {
            after { param ->
                if (Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) {
                    fHybridService.set(param.thisObject, "com.google.android.gms/.auth.api.credentials.credman.service.RemoteService")
                }
            }
        }

        val classIntentFactory = "android.credentials.selection.IntentFactory".toClassOrNull() ?: return
        val classIntentCreationResultBuilder = "android.credentials.selection.IntentCreationResult\$Builder".toClassOrNull() ?: return
        val mGetOemOverrideComponentName = runCatching {
            classIntentFactory.getDeclaredMethod(
                "getOemOverrideComponentName",
                Context::class.java, classIntentCreationResultBuilder, Int::class.javaPrimitiveType!!
            )
        }.getOrNull() ?: runCatching {
            classIntentFactory.getDeclaredMethod(
                "getOemOverrideComponentName",
                Context::class.java, classIntentCreationResultBuilder
            )
        }.getOrNull()

        mGetOemOverrideComponentName?.hook {
            intercept { chain ->
                if (!Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) {
                    return@intercept chain.proceed()
                }
                val args = chain.args
                if (args.size >= 2 && args[0] is Context && args[1] != null) {
                    val context = args[0] as Context
                    val intentResultBuilder = args[1]
                    val oemComponentString = "com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity"
                    runCatching {
                        val oemComponentName = ComponentName.unflattenFromString(oemComponentString)
                        if (oemComponentName != null) {
                            val info = context.packageManager.getActivityInfo(
                                oemComponentName,
                                PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong())
                            )
                            var oemComponentEnabled = info.enabled
                            val runtimeComponentEnabledState = context.packageManager.getComponentEnabledSetting(oemComponentName)
                            if (runtimeComponentEnabledState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                                oemComponentEnabled = true
                            } else if (runtimeComponentEnabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                                oemComponentEnabled = false
                            }
                            if (oemComponentEnabled && info.exported) {
                                runCatching {
                                    val setOemUiPackageNameMethod = intentResultBuilder.javaClass.getMethod("setOemUiPackageName", String::class.java)
                                    setOemUiPackageNameMethod.invoke(intentResultBuilder, oemComponentName.packageName)

                                    val oemUiUsageStatusClass = "android.credentials.selection.IntentCreationResult\$OemUiUsageStatus".toClass()
                                    val successValue = oemUiUsageStatusClass.getField("SUCCESS").get(null)
                                    val setOemUiUsageStatusMethod = intentResultBuilder.javaClass.getMethod("setOemUiUsageStatus", oemUiUsageStatusClass)
                                    setOemUiUsageStatusMethod.invoke(intentResultBuilder, successValue)
                                }
                                return@intercept oemComponentName
                            }
                        }
                    }.onFailure { t ->
                        Log.e(TAG, "Failed to override oem CredMan UI component", t)
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun hookSettings(cacheDir: File, apkPath: String) {
        val defaultCombinedPickerClass = "com.android.settings.applications.credentials.DefaultCombinedPicker".toClassOrNull()
        defaultCombinedPickerClass?.findMethodOrNull {
            name("setDefaultKey")
            parameterTypes(String::class.java)
        }?.hook {
            intercept { chain ->
                handleIsInternationalBuild(chain)
            }
        }

        val defaultCombinedPreferenceControllerClass = "com.android.settings.applications.credentials.DefaultCombinedPreferenceController".toClassOrNull()
        val credentialManagerClass = "android.credentials.CredentialManager".toClassOrNull()
        if (defaultCombinedPreferenceControllerClass != null && credentialManagerClass != null) {
            defaultCombinedPreferenceControllerClass.findMethodOrNull {
                name("getCombinedProviderInfos")
                parameterTypes(credentialManagerClass, Int::class.javaPrimitiveType!!)
            }?.hook {
                intercept { chain ->
                    handleIsInternationalBuild(chain)
                }
            }
        }

        val resolved = DexKitManager.resolveClasses(
            cacheDir = cacheDir,
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf("OnCombiPreferenceClickListener" to { bridge ->
                val onLeftSideClickedMatcher = org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                    .name("onLeftSideClicked")
                    .paramCount(0)
                    .addInvoke("Lcom/android/settings/applications/credentials/CombinedProviderInfo;->launchSettingsActivityIntent(Landroid/content/Context;Ljava/lang/CharSequence;Ljava/lang/CharSequence;I)V")
                
                bridge.findClass(org.luckypray.dexkit.query.FindClass.create()
                    .searchPackages("com.android.settings.applications.credentials")
                    .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create().methods(
                        org.luckypray.dexkit.query.matchers.MethodsMatcher.create().add(onLeftSideClickedMatcher)
                    ))
                ).getOrNull(0)?.name
            })
        )

        val listenerClass = resolved["OnCombiPreferenceClickListener"]
        if (listenerClass != null) {
            listenerClass.findMethodOrNull {
                name("onLeftSideClicked")
                parameterTypes()
            }?.hook {
                intercept { chain ->
                    handleIsInternationalBuild(chain)
                }
            }
        }
    }

    private fun hookSecurityCenter(apkPath: String) {
        val appClass = "com.miui.securitycenter.Application".toClassOrNull() ?: return
        DexKitManager.withBridge(apkPath) bridgeBlock@ { bridge ->
                val cApplication = bridge.getClassData("Lcom/miui/securitycenter/Application;") ?: return@bridgeBlock
                
                val mSetStringResourceConfigIfNeed = runCatching {
                    cApplication.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                        .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .paramTypes(Context::class.java.name, String::class.java.name, "int")
                            .addInvoke("Landroid/content/res/Resources;->getString(I)Ljava/lang/String;")
                            .addInvoke("Landroid/provider/Settings\$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
                        )).singleOrNull()
                }.getOrNull()

                val mConfigForAutofillService = if (mSetStringResourceConfigIfNeed != null) {
                    runCatching {
                        cApplication.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                            .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                .paramTypes(Context::class.java.name)
                                .addEqString("autofill_service")
                                .addInvoke(mSetStringResourceConfigIfNeed.descriptor)
                            )).singleOrNull()
                    }.getOrNull()
                } else null

                val mSetStringArrayResourceConfigIfNeed = runCatching {
                    cApplication.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                        .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .paramTypes(Context::class.java.name, String::class.java.name, "int")
                            .addInvoke("Landroid/content/res/Resources;->getStringArray(I)[Ljava/lang/String;")
                            .addInvoke("Landroid/provider/Settings\$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
                        )).singleOrNull()
                }.getOrNull()

                val mSetDefaultConfigForAutofillAndCredentialManager = if (mSetStringArrayResourceConfigIfNeed != null) {
                    runCatching {
                        cApplication.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                            .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                .paramTypes(Context::class.java.name)
                                .usingEqStrings("credential_service", "credential_service_primary")
                                .addInvoke(mSetStringArrayResourceConfigIfNeed.descriptor)
                            )).singleOrNull()
                    }.getOrNull()
                } else null

                if (mConfigForAutofillService != null) {
                    runCatching {
                        val method = mConfigForAutofillService.getMethodInstance(classLoader)
                        deoptimize(method)
                        method.hook {
                            intercept { chain ->
                                if (Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) {
                                    null
                                } else {
                                    chain.proceed()
                                }
                            }
                        }
                    }
                }

                if (mSetDefaultConfigForAutofillAndCredentialManager != null) {
                    runCatching {
                        val method = mSetDefaultConfigForAutofillAndCredentialManager.getMethodInstance(classLoader)
                        deoptimize(method)
                        method.hook {
                            intercept { chain ->
                                if (Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) {
                                    null
                                } else {
                                    chain.proceed()
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun hookScanner() {
        val iClass = "com.xiaomi.scanner.module.code.utils.bean.MiFiDoBean".toClassOrNull()
        if (iClass != null) {
            runCatching {
                val aMethod = iClass.getDeclaredMethod("getAppPackageName")
                aMethod.hook {
                    intercept { chain ->
                        if (Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) {
                            ""
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        }
    }

    private fun handleIsInternationalBuild(chain: Any): Any? {
        if (fIsInternationalBuildBoolean == null || !Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) {
            try {
                return chain.javaClass.getMethod("proceed").invoke(chain)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
        INTL_LOCK.lock()
        try {
            val depth = DEPTH.get() ?: 0
            if (depth == 0) {
                val prev = fIsInternationalBuildBoolean?.getBoolean(null) ?: false
                PREV_VALUE.set(prev)
                if (!prev) {
                    fIsInternationalBuildBoolean?.setBoolean(null, true)
                }
            }
            DEPTH.set(depth + 1)
            try {
                try {
                    return chain.javaClass.getMethod("proceed").invoke(chain)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException ?: e
                }
            } finally {
                val d = (DEPTH.get() ?: 0) - 1
                if (d == 0) {
                    val prev = PREV_VALUE.get()
                    PREV_VALUE.remove()
                    DEPTH.remove()
                    if (prev != null) {
                        fIsInternationalBuildBoolean?.setBoolean(null, prev)
                    }
                } else {
                    DEPTH.set(d)
                }
            }
        } finally {
            INTL_LOCK.unlock()
        }
    }

    private fun deoptimize(executable: java.lang.reflect.Executable) {
        runCatching {
            var clazz: Class<*>? = module.javaClass
            var deoptimizeMethod: java.lang.reflect.Method? = null
            while (clazz != null) {
                try {
                    deoptimizeMethod = clazz.getDeclaredMethod("deoptimize", java.lang.reflect.Executable::class.java)
                    break
                } catch (e: NoSuchMethodException) {
                    clazz = clazz.superclass
                }
            }
            deoptimizeMethod?.apply {
                isAccessible = true
                invoke(module, executable)
            }
        }
    }
}
