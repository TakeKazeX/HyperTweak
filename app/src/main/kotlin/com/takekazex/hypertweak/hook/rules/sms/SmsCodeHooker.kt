package com.takekazex.hypertweak.hook.rules.sms

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PersistableBundle
import android.os.SystemClock
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostApplicationContext
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.SmsSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import com.takekazex.hypertweak.hook.base.HostContext

private object SmsCodeFeature : DexFeature {
    override val id = "sms.auto_copy_code"
    override val schema = 1
}

/** Observe the existing recognition result only during ordinary SMS delivery. */
object SmsCodeHooker : StaticHooker() {
    private const val TAG = "SmsCode"
    private const val SMS_DELIVER = "android.provider.Telephony.SMS_DELIVER"
    private val enabled by hookSetting(false) { Preferences.getBoolean(SmsSettingsKeys.KEY_SMS_AUTO_COPY_CODE, false) }
    private var retired by generationState { true }
    private var queue by generationState { SmsCopyQueue() }
    private var context by generationState<Context?> { null }
    private var receiver by generationState<BroadcastReceiver?> { null }
    private val receiving = ThreadLocal<Boolean>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val publish = Runnable {
        HookFailurePolicy.open(TAG, "publish pending code", Unit) { reconcile() }
    }

    init { generationCleanup { retire() } }

    override fun onHook() {
        if (!PlatformLevel.isOs4 || hookParam.processName != SmsSettingsKeys.PACKAGE) return
        retired = false
        queue.enable(enabled)
        val apk = hookParam.appInfo?.sourceDir ?: return
        val cached = resolve(apk, true)
        if (cached != null) installDexContract { install(cached) }
        else prepareDexTargets(SmsCodeFeature.id, { resolve(apk, false) }, ::install)
        recoverHotReloadHosts()
    }

    private fun resolve(apk: String, cacheOnly: Boolean): NativeSmsCodeAccess? = DexRuntime.resolve(
        feature = SmsCodeFeature, apkPath = apk, cacheDir = dexCacheDirectory, cacheOnly = cacheOnly,
        compute = { SmsCodeResolver(DexRuntime.operands(apk)).resolve() },
        decode = { values, _ -> NativeSmsCodeAccess.decode(values, classLoader) })

    private fun install(access: NativeSmsCodeAccess) {
        check(deoptimize(access.receive) && deoptimize(access.recognize)) { "Cannot prepare SMS recognition path" }
        access.receive.hook("sms_receive_scope") {
            intercept { chain ->
                val previous = receiving.get()
                val intent = (chain.args.getOrNull(0) as? Message)?.obj as? Intent
                receiving.set(!retired && enabled && intent?.action == SMS_DELIVER)
                try { chain.proceed() } finally {
                    if (previous == null) receiving.remove() else receiving.set(previous)
                }
            }
        }
        access.recognize.hook("sms_recognized_code") {
            intercept { chain ->
                val ticket = if (!retired && enabled && receiving.get() == true) {
                    HookFailurePolicy.open(TAG, "admit received message", null) {
                        val info = chain.args.getOrNull(1) ?: return@open null
                        val id = (access.id.invoke(info) as? Number)?.toLong() ?: return@open null
                        queue.begin(id, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                    }
                } else null
                // Keep host exceptions and return values unchanged.
                val result = chain.proceed()
                if (ticket != null && result != null && !retired && enabled) {
                    HookFailurePolicy.open(TAG, "consume native recognition", Unit) {
                        val code = access.otp.invoke(result) as? String ?: return@open
                        if (queue.offer(ticket, code, SystemClock.elapsedRealtime())) {
                            onPackageReady(chain.args.getOrNull(0) as? Context ?: return@open)
                            schedule()
                            DebugLog.d(TAG, "new native OTP queued")
                        }
                    }
                }
                result
            }
        }
        DebugLog.hookRegistered(TAG, "native SMS receive and recognition path ready")
    }

    fun onPackageReady(host: Context) {
        if (retired || host.packageName != SmsSettingsKeys.PACKAGE) return
        HostApplicationContext.whenPublished(host) { application ->
            // Recognition may run on the host's service Handler. Never wait for main here.
            main.post {
                if (!retired) {
                    context = application
                    refreshSettings()
                }
            }
        }
    }

    fun refreshSettings() {
        if (retired) return
        queue.enable(enabled)
        HostAccess.onMain {
            if (retired) return@onMain
            main.removeCallbacks(publish)
            if (!enabled) {
                closeReceiver()
                return@onMain
            }
            val application = context ?: return@onMain
            if (receiver == null) {
                val next = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (!retired && enabled && intent?.action == Intent.ACTION_USER_PRESENT) schedule()
                    }
                }
                HookFailurePolicy.open(TAG, "register unlock receiver", Unit) {
                    application.registerReceiver(next, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
                    receiver = next
                }
            }
            schedule()
        }
    }

    private fun schedule() {
        main.removeCallbacks(publish)
        if (!retired && enabled) main.post(publish)
    }

    private fun reconcile() {
        if (retired || !enabled) return
        val candidate = queue.peek(SystemClock.elapsedRealtime()) ?: return
        val application = context ?: return
        val keyguard = application.getSystemService(KeyguardManager::class.java) ?: return
        if (keyguard.isKeyguardLocked) {
            main.removeCallbacks(publish)
            main.postDelayed(publish, (SmsCopyQueue.MAX_AGE_MS -
                (SystemClock.elapsedRealtime() - candidate.elapsed)).coerceAtLeast(1L))
            return
        }
        val clipboard = application.getSystemService(ClipboardManager::class.java) ?: return
        // Metadata only. Some hosts deny background metadata reads; do not read clipboard text.
        val clipboardTime = runCatching { clipboard.primaryClipDescription?.timestamp }
            .onFailure { DebugLog.w(TAG, "clipboard metadata unavailable (${it.javaClass.simpleName})") }.getOrNull()
        val code = queue.take(candidate, SystemClock.elapsedRealtime(), clipboardTime) ?: return
        if (retired || !enabled || keyguard.isKeyguardLocked) return
        val clip = ClipData.newPlainText(null, code).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("mms_is_ververification_code", true)
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        try {
            clipboard.setPrimaryClip(clip)
            DebugLog.i(TAG, "native SMS OTP copied")
        } catch (error: Throwable) {
            // Never include ClipData or a service exception message that might contain the code.
            DebugLog.w(TAG, "clipboard publication failed (${error.javaClass.simpleName})")
        }
    }

    private fun closeReceiver() {
        val old = receiver ?: return
        receiver = null
        context?.let { application ->
            HookFailurePolicy.open(TAG, "unregister unlock receiver", Unit) { application.unregisterReceiver(old) }
        }
    }

    private fun retire() {
        retired = true
        HostAccess.onMain { main.removeCallbacks(publish); closeReceiver() }
        queue.clear()
    }

    override fun onPrepareHotReload() = retire()
    override fun saveHotReloadState(): Any = queue.save()
    override fun restoreHotReloadState(state: Any?) {
        queue.restore(state, SystemClock.elapsedRealtime())
        schedule()
    }
    override fun recoverHotReloadHosts() {
        if (retired) return
        (hookParam.appContext ?: HostContext.appContextOrNull)?.let(::onPackageReady)
    }
}
