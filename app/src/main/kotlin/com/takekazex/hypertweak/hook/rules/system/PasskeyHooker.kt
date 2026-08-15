package com.takekazex.hypertweak.hook.rules.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.ResourceLookup
import com.takekazex.hypertweak.util.StaticFieldWriter
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock

object PasskeyHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "HyperPasskey"
    private var fIsInternationalBuildBoolean: Field? = null

    private val INTL_LOCK = ReentrantLock(true)
    private val DEPTH = ThreadLocal.withInitial { 0 }
    private val PREV_VALUE = ThreadLocal<Boolean>()

    override fun onPrepareHotReload() {
        fIsInternationalBuildBoolean = null
        DEPTH.remove()
        PREV_VALUE.remove()
    }

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
                HookFailurePolicy.open(TAG, "RequestSession#hybridService", Unit) {
                    if (Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) {
                        fHybridService.set(param.thisObject, "com.google.android.gms/.auth.api.credentials.credman.service.RemoteService")
                    }
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

        hookSettingsActivityLaunchFallback()
        hookCombiPreferenceSwitchCommit()
    }

    /**
     * The 密码和账号 rows launch the provider's declared settings activity through
     * `CombinedProviderInfo.launchSettingsActivityIntent`. On this baseline almost every
     * provider declares none (GMS's credential XML has no `settingsActivity` and its
     * autofill fallback points at a manifest-disabled page; Edge/Authenticator declare
     * nothing), so the intent is null and the tap silently does nothing. This hook keeps
     * the original behaviour but falls back to a reachable target when it fails:
     * GMS goes to its Password Manager activity, everything else to the app-details page.
     */
    private fun hookSettingsActivityLaunchFallback() {
        val combinedProviderInfoClass = "com.android.settings.applications.credentials.CombinedProviderInfo".toClassOrNull() ?: return
        combinedProviderInfoClass.findMethodOrNull {
            name("launchSettingsActivityIntent")
            parameterTypes(Context::class.java, CharSequence::class.java, CharSequence::class.java, Int::class.javaPrimitiveType!!)
        }?.hook {
            intercept { chain ->
                val args = chain.args
                val context = args.getOrNull(0) as? Context
                val packageName = args.getOrNull(1)?.toString()
                val userId = (args.getOrNull(3) as? Int) ?: -1
                if (context == null || packageName.isNullOrEmpty() || userId < 0) {
                    return@intercept chain.proceed()
                }
                // GMS's declared settings activity is the disabled autofill page on
                // domestic builds, so redirect straight to the Password Manager.
                if (packageName == "com.google.android.gms") {
                    val passwordManager = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_DEFAULT)
                        setComponent(
                            ComponentName(
                                packageName,
                                "com.google.android.gms.credential.manager.PasswordManagerActivity"
                            )
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (tryStartActivityAsUser(context, passwordManager, userId)) {
                        return@intercept true
                    }
                }
                val original = try {
                    chain.proceed() as? Boolean ?: false
                } catch (_: Throwable) {
                    false
                }
                if (!original) {
                    // Provider declared no settings activity (or it cannot be started).
                    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (tryStartActivityAsUser(context, details, userId)) {
                        return@intercept true
                    }
                }
                original
            }
        }
    }

    private fun tryStartActivityAsUser(context: Context, intent: Intent, userId: Int): Boolean {
        return runCatching {
            // Context#startActivityAsUser and UserHandle#of are @hide; resolve by name.
            val userHandle = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType!!).invoke(null, userId)
            context.javaClass.getMethod("startActivityAsUser", Intent::class.java, UserHandle::class.java)
                .invoke(context, intent, userHandle)
        }.isSuccess
    }

    /**
     * `CombiPreference` only attaches its commit listener (`onCheckChanged` ->
     * `setEnabledProviders`) when the row widget is a `miuix.slidingwidget.widget.SlidingSwitch`.
     * On OS4 the expressive settings theme uses a `MaterialSwitch` instead, so toggling the
     * switch only flips the local `mChecked` state (the super class's own listener) and the
     * enable/disable never reaches the CredentialManager — the switch reverts on rebind.
     * This hook wires the real listener onto any non-SlidingSwitch widget after the bind.
     */
    private fun hookCombiPreferenceSwitchCommit() {
        val combiPreferenceClass =
            "com.android.settings.applications.credentials.CredentialManagerPreferenceController\$CombiPreference".toClassOrNull() ?: return
        val preferenceViewHolderClass = "androidx.preference.PreferenceViewHolder".toClassOrNull() ?: return
        combiPreferenceClass.findMethodOrNull {
            name("onBindViewHolder")
            parameterTypes(preferenceViewHolderClass)
        }?.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "CombiPreference#onBindViewHolder switch wiring", Unit) {
                    wireCombiPreferenceSwitch(param.thisObject, param.args.getOrNull(0))
                }
            }
        }
    }

    private fun wireCombiPreferenceSwitch(preference: Any?, viewHolder: Any?) {
        if (preference == null || viewHolder == null) {
            DebugLog.i(TAG, "DIAG wire skipped pref=${preference != null} vh=${viewHolder != null}")
            return
        }
        val itemView = runCatching {
            viewHolder.javaClass.getField("itemView").get(viewHolder) as? View
        }.getOrNull()
        if (itemView == null) {
            DebugLog.i(TAG, "DIAG wire skipped: no itemView")
            return
        }
        val context = runCatching {
            preference.javaClass.getMethod("getContext").invoke(preference) as? Context
        }.getOrNull()
        if (context == null) {
            DebugLog.i(TAG, "DIAG wire skipped: no context")
            return
        }
        var switchId = ResourceLookup.identifier(context, "switchWidget", "id", "com.android.settings")
        if (switchId == 0) {
            switchId = ResourceLookup.identifier(context, "switchWidget", "id", "com.android.settingslib")
        }
        if (switchId == 0) {
            DebugLog.i(TAG, "DIAG wire skipped: switchWidget id not found")
            return
        }
        val switch = itemView.findViewById<View>(switchId) as? CompoundButton
        if (switch == null) {
            DebugLog.i(TAG, "DIAG wire skipped: widget not a CompoundButton id=$switchId")
            return
        }
        // The native path already wires the commit listener for SlidingSwitch.
        if (switch.javaClass.name == "miuix.slidingwidget.widget.SlidingSwitch") return

        val listenerField = runCatching {
            preference.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
        }.getOrNull()
        if (listenerField == null) {
            DebugLog.i(TAG, "DIAG wire skipped: no mOnClickListener field")
            return
        }
        val onCheckChanged = runCatching {
            listenerField.type.getDeclaredMethod(
                "onCheckChanged",
                preference.javaClass,
                Boolean::class.javaPrimitiveType!!
            )
        }.getOrNull()
        if (onCheckChanged == null) {
            DebugLog.i(TAG, "DIAG wire skipped: no onCheckChanged method in ${listenerField.type.name}")
            return
        }
        val checkedField = runCatching {
            preference.javaClass.getDeclaredField("mChecked").apply { isAccessible = true }
        }.getOrNull()

        switch.setOnCheckedChangeListener { _, checked ->
            val listener = listenerField.get(preference) ?: return@setOnCheckedChangeListener
            val accepted = runCatching {
                onCheckChanged.invoke(listener, preference, checked) as? Boolean ?: true
            }.getOrDefault(true)
            if (!accepted) {
                // Provider limit reached: revert exactly like the native SlidingSwitch path.
                checkedField?.setBoolean(preference, false)
                switch.isChecked = false
            }
        }

        // AOSP builds the rows before the controller refreshes mEnabledPackageNames, and the
        // controller's own sync (`setAvailableServices` -> `mPrefs.setChecked`) only reaches
        // rows already registered in `mPrefs`; on this build that left the rows stuck at their
        // initial (off) state even though the provider is enabled. Force the switch to the
        // controller's authoritative state at every bind so the rows always reflect reality.
        val listener = listenerField.get(preference) ?: return
        val controller = runCatching {
            listener.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }.get(listener)
        }.getOrNull()
        val packageName = runCatching {
            listener.javaClass.getDeclaredField("val\$packageName").apply { isAccessible = true }.get(listener) as? String
        }.getOrNull()
        if (controller != null && packageName != null && checkedField != null) {
            val enabledNamesField = runCatching {
                controller.javaClass.getDeclaredField("mEnabledPackageNames").apply { isAccessible = true }
            }.getOrNull()
            if (enabledNamesField != null) {
                val enabled = runCatching {
                    (enabledNamesField.get(controller) as? Set<*>)?.contains(packageName) == true
                }.getOrDefault(false)
                if (checkedField.getBoolean(preference) != enabled || switch.isChecked != enabled) {
                    checkedField.setBoolean(preference, enabled)
                    switch.isChecked = enabled
                }
            }
        }
    }

    private fun hookSecurityCenter(apkPath: String) {
        // The default-credential writers moved between SecurityCenter builds (from
        // `com.miui.securitycenter.Application` to `com.miui.securitycenter.service.CacheService`
        // on the current OS4 device build, with R8-renamed `(String,int)` helpers), so resolve
        // them by shape instead of by class: a helper that reads a resource and writes
        // Settings.Secure, plus the wrapper that calls it with the setting names.
        DexKitManager.withBridge(apkPath) bridgeBlock@ { bridge ->
                val mSetStringResourceConfigIfNeed = runCatching {
                    bridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                        .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .addInvoke("Landroid/content/res/Resources;->getString(I)Ljava/lang/String;")
                            .addInvoke("Landroid/provider/Settings\$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
                        )).singleOrNull()
                }.getOrNull()

                val mConfigForAutofillService = if (mSetStringResourceConfigIfNeed != null) {
                    runCatching {
                        bridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                            .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                .addEqString("autofill_service")
                                .addInvoke(mSetStringResourceConfigIfNeed.descriptor)
                            )).singleOrNull()
                    }.getOrNull()
                } else null

                val mSetStringArrayResourceConfigIfNeed = runCatching {
                    bridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                        .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .addInvoke("Landroid/content/res/Resources;->getStringArray(I)[Ljava/lang/String;")
                            .addInvoke("Landroid/provider/Settings\$Secure;->putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z")
                        )).singleOrNull()
                }.getOrNull()

                val mSetDefaultConfigForAutofillAndCredentialManager = if (mSetStringArrayResourceConfigIfNeed != null) {
                    runCatching {
                        bridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                            .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
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
                        DebugLog.i(TAG, "securitycenter autofill-service default write blocked")
                    }.onFailure { t ->
                        DebugLog.e(TAG, "securitycenter autofill hook failed", t)
                    }
                } else {
                    DebugLog.w(TAG, "securitycenter autofill-service default write not found")
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
                        DebugLog.i(TAG, "securitycenter credential default write blocked")
                    }.onFailure { t ->
                        DebugLog.e(TAG, "securitycenter credential hook failed", t)
                    }
                } else {
                    DebugLog.w(TAG, "securitycenter credential default write not found")
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
                    fIsInternationalBuildBoolean?.let { StaticFieldWriter.setBoolean(it, true) }
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
                        fIsInternationalBuildBoolean?.let { StaticFieldWriter.setBoolean(it, prev) }
                    }
                } else {
                    DEPTH.set(d)
                }
            }
        } finally {
            INTL_LOCK.unlock()
        }
    }

}
