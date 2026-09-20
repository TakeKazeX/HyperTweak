package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.BatteryInfoChannel
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

/** Privileged, on-demand battery reads in Security Center's main application process. */
object BatteryInfoHooker : StaticHooker() {
    private const val TAG = "BatteryInfoHooker"
    const val PACKAGE = BatteryInfoChannel.PACKAGE
    override val hotReloadMode = HotReloadMode.RECREATE

    private var publisher: Publisher? = null

    override fun onHook() {
        // HookEntry calls onPackageReady once Application.attach supplies the context. No startup
        // sample or context-polling timer: merely loading Security Center must never hit the HAL.
        hookParam.appContext?.let(::onPackageReady)
    }

    @Synchronized
    fun onPackageReady(context: Context) {
        if (publisher != null) return
        // PackageReady can hand us a base Context during Application.attach, before its
        // applicationContext is available. This hook only needs process-scoped Context APIs, so
        // keep the app context when present and otherwise retain the supplied base Context.
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != PACKAGE) {
            DebugLog.hookSkipped(TAG, "battery-info receiver", "unexpected package=${appContext.packageName}")
            return
        }
        val next = Publisher(appContext)
        try {
            next.register()
            publisher = next
            DebugLog.i(TAG, "battery-info request receiver registered (on-demand; no startup reads) " +
                "process=${android.app.Application.getProcessName()} context=${appContext.javaClass.name}")
        } catch (failure: Throwable) {
            next.close()
            DebugLog.w(TAG, "battery-info receiver registration failed", failure)
        }
    }

    @Synchronized
    override fun onPrepareHotReload() {
        publisher?.close()
        publisher = null
    }

    private class Publisher(private val context: Context) {
        // The executor creates its one thread lazily, on the first real foreground request.
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "HyperTweak-BatteryInfo").apply { isDaemon = true }
        }
        private val source = AndroidSource(context)
        private val sampler = BatteryInfoSampler(source, SystemClock::elapsedRealtime)
        private var failureLogged = false
        private var firstRequestLogged = false
        private var firstSnapshotLogged = false
        private val requests = BatteryInfoRequests(
            clock = SystemClock::elapsedRealtime,
            executor = worker,
            collect = ::publish,
            onFailure = { failure ->
                if (!failureLogged) {
                    failureLogged = true
                    DebugLog.w(TAG, "battery-info request failed", failure)
                }
            }
        )
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    val session = intent.getStringExtra(BatteryInfoChannel.EXTRA_SESSION) ?: return
                    val issuedAt = intent.getLongExtra(BatteryInfoChannel.EXTRA_ISSUED_AT, -1)
                    when (intent.action) {
                        BatteryInfoChannel.ACTION_REQUEST -> {
                            if (!firstRequestLogged) {
                                firstRequestLogged = true
                                DebugLog.i(TAG, "received first on-demand battery request")
                            }
                            requests.request(session, issuedAt)
                        }
                        BatteryInfoChannel.ACTION_STOP -> requests.stop(session, issuedAt)
                    }
                }.onFailure { DebugLog.w(TAG, "invalid battery-info request", it) }
            }
        }
        private var registered = false

        fun register() {
            val filter = IntentFilter(BatteryInfoChannel.ACTION_REQUEST).apply {
                addAction(BatteryInfoChannel.ACTION_STOP)
            }
            ContextCompat.registerReceiver(
                context, receiver, filter, BatteryInfoChannel.PERMISSION, null,
                ContextCompat.RECEIVER_EXPORTED
            )
            registered = true
        }

        fun close() {
            requests.close()
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            registered = false
            // A synchronous vendor Binder call is not reliably interruptible. Let the current
            // call finish; isActive prevents any subsequent read or publish by this generation.
            worker.shutdown()
        }

        private fun publish(isActive: () -> Boolean) {
            check(Looper.myLooper() != Looper.getMainLooper()) { "battery sampling on main thread" }
            source.reads = 0
            source.halReads = 0
            val values = sampler.collect(isActive)
            if (!isActive()) return
            val sampledAt = Bundle()
            val validUntil = Bundle()
            val bundle = Bundle()
            val zh = when (Preferences.getInt(Preferences.KEY_LANGUAGE, 0)) {
                1 -> true
                2 -> false
                else -> Locale.getDefault().language == "zh"
            }
            values.forEach { (slot, value) ->
                format(slot, value.raw, zh)?.let { formatted ->
                    bundle.putString(slot, formatted)
                    sampledAt.putLong(slot, value.sampledAt)
                    validUntil.putLong(slot, value.validUntil)
                }
            }
            bundle.putLong(BatteryInfoChannel.KEY_UPDATED_AT, values.values.maxOfOrNull { it.sampledAt } ?: 0)
            bundle.putBundle(BatteryInfoChannel.KEY_SAMPLED_AT, sampledAt)
            bundle.putBundle(BatteryInfoChannel.KEY_VALID_UNTIL, validUntil)
            if (!isActive()) return
            val acknowledgement = context.contentResolver.call(
                BatteryInfoChannel.uri(), BatteryInfoChannel.METHOD_SET, null, bundle
            )
            check(acknowledgement?.containsKey(BatteryInfoChannel.KEY_UPDATED_AT) == true) {
                "battery snapshot was not acknowledged"
            }
            failureLogged = false
            val summary = "sampled slots=${sampledAt.size()} reads=${source.reads} " +
                "hal=${source.halReads} thread=${Thread.currentThread().name}"
            if (!firstSnapshotLogged) {
                firstSnapshotLogged = true
                DebugLog.i(TAG, "published first battery snapshot $summary")
            } else {
                DebugLog.d(TAG, summary)
            }
        }
    }

    private fun format(slot: String, raw: String, zh: Boolean): String? = when (slot) {
        BatteryInfoChannel.SLOT_DESIGN_CAPACITY, BatteryInfoChannel.SLOT_FG1_DESIGN,
        BatteryInfoChannel.SLOT_FG2_DESIGN, BatteryInfoChannel.SLOT_FG1_RM,
        BatteryInfoChannel.SLOT_FG2_RM, BatteryInfoChannel.SLOT_FCC -> BatteryInfoChannel.mah(raw.toDoubleOrNull())
        BatteryInfoChannel.SLOT_CYCLE_COUNT, BatteryInfoChannel.SLOT_BATTERY_CYCLE,
        BatteryInfoChannel.SLOT_FG1_CYCLE, BatteryInfoChannel.SLOT_FG2_CYCLE -> if (zh) "$raw 次" else "$raw cycles"
        BatteryInfoChannel.SLOT_FG1_SOH, BatteryInfoChannel.SLOT_FG2_SOH,
        BatteryInfoChannel.SLOT_BATTERY_SOH -> "$raw %"
        BatteryInfoChannel.SLOT_MANUFACTURING_DATE, BatteryInfoChannel.SLOT_FIRST_USAGE_DATE -> BatteryInfoChannel.date(raw)
        BatteryInfoChannel.SLOT_AUTHENTIC, BatteryInfoChannel.SLOT_SLAVE_AUTHENTIC ->
            if (zh) { if (raw == "1") "是" else "否" } else { if (raw == "1") "Yes" else "No" }
        BatteryInfoChannel.SLOT_PD_AUTH ->
            if (zh) { if (raw == "1") "已认证" else "未认证" } else { if (raw == "1") "Authenticated" else "Not authenticated" }
        BatteryInfoChannel.SLOT_CHARGE_POWER -> raw.toIntOrNull()?.takeIf { it > 0 }?.let { "$it W" }
        else -> raw
    }

    private class AndroidSource(private val context: Context) : BatteryInfoSampler.Source {
        var reads = 0
        var halReads = 0
        private var chargeClass: Class<*>? = null
        private var classResolved = false
        private var instance: Any? = null
        private val methods = mutableMapOf<String, Method?>()

        override fun plugged(): Int? = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra("plugged", -1)?.takeIf { it >= 0 }
        }.getOrNull()

        override fun read(query: BatteryInfoSampler.Query): BatteryInfoSampler.Result {
            reads++
            return when (query) {
                is BatteryInfoSampler.Query.File -> try {
                    val text = Files.newBufferedReader(Paths.get(query.path)).use { it.readLine() }
                    text?.let { BatteryInfoSampler.Result.Value(it) } ?: BatteryInfoSampler.Result.Unavailable
                } catch (_: NoSuchFileException) {
                    BatteryInfoSampler.Result.Unsupported
                } catch (_: Exception) {
                    // EACCES / SELinux denial is not proof that a vendor HAL field is unsupported.
                    BatteryInfoSampler.Result.Unavailable
                }
                is BatteryInfoSampler.Query.Path -> invoke("getMiChargePath", query.key)
                is BatteryInfoSampler.Query.Method -> invoke(query.name, null)
            }
        }

        private fun invoke(name: String, argument: String?): BatteryInfoSampler.Result {
            if (!classResolved) {
                chargeClass = runCatching { Class.forName("miui.util.IMiCharge", false, context.classLoader) }.getOrNull()
                classResolved = true
            }
            val cls = chargeClass ?: return BatteryInfoSampler.Result.Unsupported
            if (!methods.containsKey(name)) {
                methods[name] = runCatching {
                    if (argument == null) cls.getMethod(name) else cls.getMethod(name, String::class.java)
                }.getOrNull()
            }
            val method = methods[name] ?: return BatteryInfoSampler.Result.Unsupported
            val started = SystemClock.elapsedRealtime()
            return try {
                val target = instance ?: cls.getMethod("getInstance").invoke(null).also { instance = it }
                    ?: return BatteryInfoSampler.Result.Unavailable
                halReads++
                val raw = if (argument == null) method.invoke(target) else method.invoke(target, argument)
                // Current IMiCharge swallows its internal 2s timeout and returns null. Detect that
                // boundary too, so a dead service does not cost another 2s for every remaining field.
                if (raw == null && SystemClock.elapsedRealtime() - started >= 1_900) {
                    BatteryInfoSampler.Result.TimedOut
                } else {
                    raw?.toString()?.let { BatteryInfoSampler.Result.Value(it) } ?: BatteryInfoSampler.Result.Unavailable
                }
            } catch (failure: Exception) {
                val cause = (failure as? InvocationTargetException)?.targetException ?: failure
                if (cause is TimeoutException || SystemClock.elapsedRealtime() - started >= 1_900) {
                    BatteryInfoSampler.Result.TimedOut
                } else {
                    BatteryInfoSampler.Result.Unavailable
                }
            }
        }
    }
}
