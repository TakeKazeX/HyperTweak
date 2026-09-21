package com.takekazex.hypertweak.hook.rules.systemui

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File

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
 * The combine lambda receives the seven combined values as an `Object[]`; the hook identifies the
 * two Boolean values by type rather than relying on their positions. It forces those values for
 * this computation only, so:
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
 * DexKit resolves the generated transform by its owner-name marker and its `invoke` and
 * `invokeSuspend` method shapes. At runtime, the two Boolean inputs are selected by type and
 * uniqueness, so a changed Flow input order does not silently rewrite unrelated values.
 */
object KeyguardFingerprintAvoidHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "KeyguardFingerprintAvoid"
    private const val DEX_CACHE_KEY = "keyguardNsslLockYTransform"
    private const val DEX_OWNER_MARKER =
        "com.android.keyguard.panel.KeyguardPanelViewController\$nsslLockYPosition_delegate\$lambda"

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

        val combineLambda = resolveCombineLambdaClass()
        val invoke = combineLambda?.declaredMethods?.firstOrNull {
            it.name == "invoke" && it.parameterCount == 3
        }
        if (invoke == null) {
            DebugLog.hookSkipped(
                TAG,
                "nsslLockYPosition combine transform",
                "class or invoke(FlowCollector, Object[], Continuation) not found"
            )
            return
        }

        invoke.hook {
            before { param ->
                val values = param.args.getOrNull(1) as? Array<*> ?: return@before
                if (values.size != 7) return@before
                val booleanIndices = values.indices.filter { values[it] is Boolean }
                if (booleanIndices.size != 2) {
                    DebugLog.w(TAG, "expected exactly two Boolean inputs, found=${booleanIndices.size}")
                    return@before
                }
                val forced = mode == Preferences.LOCKSCREEN_FINGERPRINT_AVOID_ALWAYS
                @Suppress("UNCHECKED_CAST")
                val anyValues = values as Array<Any?>
                booleanIndices.forEach { anyValues[it] = forced }
            }
        }
        DebugLog.d(TAG, "hooked DexKit nsslLockYPosition transform (mode=$mode)")
    }

    private fun resolveCombineLambdaClass(): Class<*>? {
        val appInfo = hookParam.appInfo ?: return null
        val apkPath = appInfo.sourceDir ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        return DexKitManager.resolveClasses(
            cacheDir = File(baseDir, "cache"),
            apkPath = apkPath,
            classLoader = classLoader,
            queries = mapOf(
                DEX_CACHE_KEY to { bridge ->
                    val candidates = bridge.findClass {
                        matcher {
                            className(DEX_OWNER_MARKER, StringMatchType.Contains)
                            methods {
                                matchType(MatchType.Contains)
                                countMin(1)
                                add {
                                    name("invoke")
                                    paramCount(3)
                                    returnType(Any::class.java)
                                }
                            }
                        }
                    }.toList()
                    val matchingNames = candidates.mapNotNull { data ->
                        val type = runCatching { data.getInstance(classLoader) }
                            .onFailure {
                                DebugLog.w(TAG, "failed to load DexKit candidate ${data.name}", it)
                            }
                            .getOrNull()
                        type?.takeIf(::isCombineLambdaClass)?.name
                    }
                        .distinct()
                    DebugLog.d(
                        TAG,
                        "DexKit transform lookup: ownerCandidates=${candidates.size}, shapeMatches=${matchingNames.size}"
                    )
                    if (matchingNames.size != 1) {
                        DebugLog.w(
                            TAG,
                            "DexKit transform candidates=${candidates.map { it.name }}, shapeMatches=$matchingNames"
                        )
                    }
                    matchingNames.singleOrNull()
                }
            ),
            validators = mapOf(DEX_CACHE_KEY to ::isCombineLambdaClass)
        )[DEX_CACHE_KEY]
    }

    private fun isCombineLambdaClass(type: Class<*>): Boolean =
        type.name.contains(DEX_OWNER_MARKER) &&
            type.declaredMethods.count {
                it.name == "invoke" && it.parameterCount == 3 && it.returnType == Any::class.java
            } == 1 &&
            type.declaredMethods.count {
                it.name == "invokeSuspend" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Any::class.java && it.returnType == Any::class.java
            } == 1
}
