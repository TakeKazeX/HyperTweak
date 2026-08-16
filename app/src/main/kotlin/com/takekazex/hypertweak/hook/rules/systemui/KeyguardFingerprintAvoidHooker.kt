package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog

/**
 * Lockscreen notification fingerprint avoidance (锁屏通知指纹避让), OS4 SystemUI.
 *
 * SystemUI anchors the lockscreen notification stack against the in-display (GXZW) fingerprint
 * icon through `KeyguardPanelViewController.nsslLockYPosition`, a
 * `StateFlow<Triple<Int,Int,Int>>` computed from seven combined flows. When
 * `MiuiConfigs.GXZW_SENSOR && fingerApplyForKeyguard && hasEnrolledTemplates` the stack bottom
 * bound becomes `GXZW_ICON_Y + offset - 20dp` (just above the icon); otherwise it falls back to
 * the bottom indication area. The triple feeds `MiuiKeyguardRepositoryImpl`
 * `.notificationBottomOnKeyguard` and every stack/list/number positioning strategy.
 *
 * The combine lambda (`KeyguardPanelViewController$nsslLockYPosition_delegate$lambda*$
 * $$inlined$combine$1$3`) receives the seven combined values as an `Object[]`, where index 5 is
 * the fingerprint-apply setting and index 6 the enrolled-templates flag. The hook forces those
 * two booleans for this single computation only, so:
 * - mode 1 (不避让): both false -> the GXZW branch is skipped, notifications end at the
 *   indication-area bound and ignore the fingerprint icon entirely;
 * - mode 2 (避让): both true -> the GXZW branch runs regardless of the fingerprint-unlock
 *   setting or enrollment, so the stack always stops above the icon.
 *
 * `fingerApplyForKeyguard` is deliberately **not** replaced as a flow: it also drives the
 * fingerprint icon visibility (`MiuiGxzwStateProviderImpl`) and the low-position indication
 * area (`KeyguardBottomAreaInjector$gxzwLowPositionShow`), which must keep following the user's
 * setting.
 *
 * Class resolution: the Kotlin lambda classes are NOT reachable through
 * `KeyguardPanelViewController.getDeclaredClasses()` on the release SystemUI — R8 folds the
 * lazy lambda into the synthesized `$$ExternalSyntheticLambda6`, so the `$lambda*$` chain that
 * would nest the combine classes no longer exists as loadable enclosing classes (observed on
 * OS4.0.0.15.XPMCNXM: the nested-class walk produced nothing while the dex still carries the
 * full class name). The resolver therefore loads the class by its exact dex name, falling back
 * to enumerating the class loader's dex entries and finally to probing the lambda ordinal.
 */
object KeyguardFingerprintAvoidHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "KeyguardFingerprintAvoid"
    private const val KEYGUARD_PANEL_VC = "com.android.keyguard.panel.KeyguardPanelViewController"
    private const val COMBINE_CLASS_PREFIX = "com.android.keyguard.panel.KeyguardPanelViewController\$nsslLockYPosition_delegate\$lambda"
    private const val COMBINE_CLASS_MIDDLE = "\$\$inlined\$combine\$1\$3"

    @Volatile
    private var mode = Preferences.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT

    override fun onPrepareHotReload() {
        mode = Preferences.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
    }

    override fun onHook() {
        mode = Preferences.getInt(
            Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID,
            Preferences.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
        )
        if (mode == Preferences.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT) {
            DebugLog.hookSkipped(TAG, "nsslLockYPosition combine", "mode is system default")
            return
        }

        // Sanity check that the keyguard panel class exists at all before searching for the lambda.
        runCatching { classLoader.loadClass(KEYGUARD_PANEL_VC) }.getOrElse {
            DebugLog.hookSkipped(TAG, KEYGUARD_PANEL_VC, "class not found")
            return
        }

        val combineLambda = findCombineLambdaClass()
        val invoke = combineLambda?.declaredMethods?.firstOrNull {
            it.name == "invoke" && it.parameterCount == 3
        }
        if (invoke == null) {
            DebugLog.hookSkipped(
                TAG,
                "nsslLockYPosition combine lambda$3",
                "class or invoke(FlowCollector, Object[], Continuation) not found"
            )
            return
        }

        invoke.hook {
            before { param ->
                val values = param.args.getOrNull(1) as? Array<*> ?: return@before
                if (values.size != 7) return@before
                // Index 5 = fingerApplyForKeyguard, 6 = hasEnrolledTemplates (see the flow
                // array built in KeyguardPanelViewController$$ExternalSyntheticLambda6).
                val forced = mode == Preferences.LOCKSCREEN_FINGERPRINT_AVOID_ALWAYS
                @Suppress("UNCHECKED_CAST")
                val anyValues = values as Array<Any?>
                anyValues[5] = forced
                anyValues[6] = forced
            }
        }
        DebugLog.d(TAG, "hooked nsslLockYPosition combine$3 (mode=$mode)")
    }

    /**
     * Loads the `Function3.invoke(FlowCollector, Object[], Continuation)` bridge class of the
     * `nsslLockYPosition` combine. Three strategies in order:
     * 1. the exact known dex name (baseline ordinal 106);
     * 2. enumerate every dex class under the class loader and match the `nsslLockYPosition`
     *    combine name shape, so the ordinal does not matter;
     * 3. probe a range of lambda ordinals directly.
     */
    private fun findCombineLambdaClass(): Class<*>? {
        // 1. Exact name first — verified on the OS4.0.0.15.XPMCNXM baseline dex.
        runCatching {
            val fixed = "$COMBINE_CLASS_PREFIX\$106$COMBINE_CLASS_MIDDLE"
            val cls = classLoader.loadClass(fixed)
            if (hasInvoke3(cls)) return cls
        }.onFailure { DebugLog.d(TAG, "fixed combine class name lookup failed: ${it.message}") }

        // 2. Enumerate dex entries under BaseDexClassLoader.
        val enumerated = enumerateCombineClassNames()
        for (name in enumerated) {
            runCatching {
                val cls = classLoader.loadClass(name)
                if (hasInvoke3(cls)) return cls
            }.onFailure { }
        }
        if (enumerated.isNotEmpty()) {
            DebugLog.d(TAG, "enumerated combine candidates: $enumerated")
        }

        // 3. Probe lambda ordinals (rarely needed, kept as a last resort).
        for (ordinal in 1..400) {
            runCatching {
                val cls = classLoader.loadClass("$COMBINE_CLASS_PREFIX\$$ordinal$COMBINE_CLASS_MIDDLE")
                if (hasInvoke3(cls)) return cls
            }.onFailure { }
        }
        return null
    }

    /** Returns the dex class names matching the `nsslLockYPosition` combine lambda shape. */
    private fun enumerateCombineClassNames(): List<String> {
        val found = mutableListOf<String>()
        runCatching {
            val baseDexClassLoader = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseDexClassLoader.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListField.get(classLoader) ?: return@runCatching
            val dexElementsField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
            val elements = dexElementsField.get(pathList) as? Array<*> ?: return@runCatching
            for (element in elements) {
                val dexFileField = runCatching {
                    element?.javaClass?.getDeclaredField("dexFile")?.apply { isAccessible = true }
                }.getOrNull() ?: continue
                val dexFile = dexFileField.get(element) ?: continue
                val entries = runCatching {
                    dexFile.javaClass.getMethod("entries").invoke(dexFile) as? java.util.Enumeration<*>
                }.getOrNull() ?: continue
                while (entries.hasMoreElements()) {
                    val dexName = entries.nextElement() as? String ?: continue
                    if (dexName.startsWith("com/android/keyguard/panel/KeyguardPanelViewController\$nsslLockYPosition") &&
                        dexName.endsWith("combine\$1\$3")
                    ) {
                        found.add(dexName.replace('/', '.'))
                    }
                }
            }
        }.onFailure { DebugLog.d(TAG, "dex enumeration failed: ${it.message}") }
        return found.distinct()
    }

    private fun hasInvoke3(clazz: Class<*>): Boolean =
        clazz.declaredMethods.any { it.name == "invoke" && it.parameterCount == 3 }
}