package com.takekazex.hypertweak.hook.rules.voiceassist

import com.takekazex.hypertweak.hook.base.HostAccess
import android.app.Service
import android.content.Intent
import android.provider.Settings
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.reflect.Method

private object XiaoaiGestureFeature : DexFeature {
    override val id = "xiaoai.gesture_input"
    override val schema = 1
}

/** Let XiaoAI's normal keyboard/recording flows handle the three added native choices. */
object XiaoaiGestureHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingXiaoaiGestureOptions by hookSetting(false) { Preferences.xiaoaiGestureOptions() }

    private const val TAG = "XiaoaiGesture"
    private const val FUNCTION = "voice_assist_function_key"
    private const val START_FROM = "voice_assist_start_from_key"
    @Volatile
    private var retired = false
    init { generationCleanup { retired = true } }
    private data class Access(val keyboard: Method, val consumer: Method)

    override fun onHook() {
        if (hookParam.processName != "com.miui.voiceassist") return
        retired = false
        val apk = hookParam.appInfo?.sourceDir ?: return
        resolve(apk, true)?.let(::install) ?: prepareDexTargets(XiaoaiGestureFeature.id,
            resolve = { resolve(apk, false) }, install = ::install)
    }

    private fun resolve(apk: String, cacheOnly: Boolean): Access? = DexRuntime.resolve(
        feature = XiaoaiGestureFeature, apkPath = apk, cacheDir = dexCacheDirectory, cacheOnly = cacheOnly,
        compute = { session -> session.dexKit(XiaoaiGestureResolver::resolve) ?: emptyMap() },
        decode = { values, _ ->
            val gate = values["keyboard"]?.let { MethodDescriptorCodec.method(it, classLoader) }
            val consumer = values["consumer"]?.let { MethodDescriptorCodec.method(it, classLoader) }
            if (gate != null && gate.returnType == Boolean::class.javaPrimitiveType &&
                gate.parameterTypes.toList() == listOf(Intent::class.java) && consumer != null &&
                consumer.declaringClass.name == "com.xiaomi.voiceassistant.VoiceService") Access(gate, consumer) else null
        }
    )

    private fun install(access: Access) {
        val service = access.consumer.declaringClass
        val start = HostAccess.requireDeclaredMethod(service, "onStartCommand", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, accessible = false)
        check(deoptimize(access.keyboard) && deoptimize(access.consumer) && deoptimize(start))
        start.hook("xiaoai_long_press_text") {
            before { param ->
                HookFailurePolicy.open(TAG, "route native text input", Unit) {
                    if (retired || !settingXiaoaiGestureOptions) return@open
                    val intent = param.args.getOrNull(0) as? Intent ?: return@open
                    val host = param.thisObject as? Service ?: return@open
                    if (intent.action != Intent.ACTION_ASSIST) return@open
                    val trigger = intent.getStringExtra("triggerType")
                    if (trigger != XiaoaiGesturePolicy.LONG_PRESS) return@open
                    val selected = Settings.Secure.getString(host.contentResolver, trigger)
                    if (XiaoaiGesturePolicy.textRoute(true, trigger, selected, intent.getStringExtra(FUNCTION))) {
                        // A native long-press origin enters PTT before keyboard selection. The
                        // explicit keyboard function and existing search origin bypass that PTT
                        // branch while retaining the physical trigger and timestamp in the Intent.
                        param.args[0] = Intent(intent).putExtra(FUNCTION, "pull_up_keyboard")
                            .putExtra(START_FROM, "home_search")
                    }
                }
            }
        }
        access.keyboard.hook("xiaoai_double_click_voice") {
            after { param ->
                HookFailurePolicy.open(TAG, "route native voice input", Unit) {
                    if (retired || !settingXiaoaiGestureOptions) return@open
                    val intent = param.args.getOrNull(0) as? Intent ?: return@open
                    if (intent.action != Intent.ACTION_ASSIST) return@open
                    val trigger = intent.getStringExtra("triggerType")
                    if (trigger != XiaoaiGesturePolicy.DOUBLE_CLICK) return@open
                    val context = io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.appContextOrNull ?: return@open
                    val selected = Settings.Secure.getString(context.contentResolver, trigger)
                    if (XiaoaiGesturePolicy.voiceRoute(true, trigger, selected, intent.getStringExtra(FUNCTION))) param.result = false
                }
            }
        }
        DebugLog.hookRegistered(TAG, "native text/voice input routes")
    }
    override fun onPrepareHotReload() { retired = true }
}
