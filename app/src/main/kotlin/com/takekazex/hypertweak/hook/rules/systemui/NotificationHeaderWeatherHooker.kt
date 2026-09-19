package com.takekazex.hypertweak.hook.rules.systemui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Appends Xiaomi Weather provider data to the date in the OS4 notification header. */
object NotificationHeaderWeatherHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "NotificationHeaderWeather"
    private const val HEADER_CLASS = "com.android.systemui.qs.MiuiNotificationHeaderView"
    private const val CLOCK_CLASS = "com.android.systemui.statusbar.views.MiuiClock"
    private const val FETCHER_CLASS = "com.miui.clock.utils.WeatherFetcherController"
    private const val CALLBACK_CLASS = "com.miui.clock.utils.WeatherFetcherController\$Callback"
    private const val SUCCESS_REFRESH_MS = 30 * 60 * 1000L
    private const val FAILURE_RETRY_MS = 5 * 60 * 1000L

    private data class DateLayoutState(
        val maxWidth: Int,
        val maxLines: Int,
        val ellipsize: TextUtils.TruncateAt?
    )

    private data class HeaderState(
        val date: TextView,
        val carrier: View?,
        val listener: View.OnLayoutChangeListener
    )

    private val main = Handler(Looper.getMainLooper())
    private val fields = HashMap<Pair<Class<*>, String>, Field?>()
    private val headers = WeakHashMap<ViewGroup, HeaderState>()
    private val baseTexts = WeakHashMap<TextView, String>()
    private val dateLayouts = WeakHashMap<TextView, DateLayoutState>()
    private val stateLock = Any()

    @Volatile private var showRegion = false
    @Volatile private var weatherType = NotificationHeaderModel.WEATHER_CONDITION
    @Volatile private var weatherSnapshot: NotificationHeaderModel.WeatherSnapshot? = null
    @Volatile private var lastSuccessElapsed = Long.MIN_VALUE
    @Volatile private var lastAttemptElapsed = Long.MIN_VALUE
    @Volatile private var fetchInFlight = false

    private var fetcher: Any? = null
    private var fetchMethod: Method? = null
    private var callbackProxy: Any? = null
    private var updateTimeMethod: Method? = null

    override fun onPrepareHotReload() {
        val restore = {
            runCatching { fetcher?.javaClass?.methods?.firstOrNull {
                it.name == "reset" && it.parameterCount == 0
            }?.invoke(fetcher) }
            headers.forEach { (root, state) -> root.removeOnLayoutChangeListener(state.listener) }
            baseTexts.forEach { (view, base) -> if (view.isAttachedToWindow) view.text = base }
            dateLayouts.forEach { (view, state) ->
                view.maxWidth = state.maxWidth
                view.maxLines = state.maxLines
                view.ellipsize = state.ellipsize
            }
            headers.clear()
            baseTexts.clear()
            dateLayouts.clear()
            fields.clear()
            fetcher = null
            fetchMethod = null
            callbackProxy = null
            updateTimeMethod = null
            weatherSnapshot = null
            fetchInFlight = false
            lastSuccessElapsed = Long.MIN_VALUE
            lastAttemptElapsed = Long.MIN_VALUE
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            restore()
        } else {
            val latch = CountDownLatch(1)
            main.post { try { restore() } finally { latch.countDown() } }
            check(latch.await(5, TimeUnit.SECONDS)) { "weather header cleanup timed out" }
        }
        showRegion = false
        weatherType = NotificationHeaderModel.WEATHER_CONDITION
    }

    override fun onHook() {
        if (!Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_WEATHER_ENABLED, false)) return
        showRegion = Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_WEATHER_REGION, false)
        weatherType = NotificationHeaderModel.normalizeWeatherType(
            Preferences.getInt(
                Preferences.KEY_NOTIFICATION_HEADER_WEATHER_TYPE,
                NotificationHeaderModel.WEATHER_CONDITION
            )
        )

        val headerClass = HEADER_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, HEADER_CLASS, "class not found")
            return
        }
        val clockClass = CLOCK_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CLOCK_CLASS, "class not found")
            return
        }
        updateTimeMethod = clockClass.findMethodOrNull { name("updateTime"); noParams() }

        var hookCount = 0
        listOf("onFinishInflate", "onAttachedToWindow").forEach { name ->
            headerClass.declaredMethods.filter { it.name == name && it.parameterCount == 0 }
                .forEach { method ->
                    deoptimize(method)
                    method.hook {
                        after { param ->
                            HookFailurePolicy.open(TAG, name, Unit) {
                                (param.thisObject as? ViewGroup)?.let(::registerHeader)
                            }
                        }
                    }
                    hookCount++
                }
        }
        updateTimeMethod?.let { method ->
            deoptimize(method)
            method.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "updateTime", Unit) {
                        val date = param.thisObject as? TextView ?: return@open
                        synchronized(stateLock) {
                            if (!baseTexts.containsKey(date)) return@open
                            baseTexts[date] = date.text?.toString().orEmpty()
                        }
                        render(date)
                        requestWeather(date)
                    }
                }
            }
            hookCount++
        }

        if (hookCount == 0) {
            DebugLog.hookSkipped(TAG, "notification header weather hooks", "no hook methods found")
            return
        }
        prepareFetcher()
        DebugLog.hookRegistered(
            TAG,
            "date weather enabled region=$showRegion type=$weatherType refresh=${SUCCESS_REFRESH_MS / 60_000}min"
        )
    }

    private fun registerHeader(root: ViewGroup) {
        if (headers.containsKey(root)) return
        val date = (read(root, "mDateView") as? TextView)
            ?: root.findViewById(id(root, "date_time"))
            ?: return
        val carrier = (read(root, "mCarrierContainer") as? View)
            ?: root.findViewById(id(root, "carrier_container"))
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            HookFailurePolicy.open(TAG, "date bounds", Unit) {
                updateDateBounds(root, date, carrier)
            }
        }
        headers[root] = HeaderState(date, carrier, listener)
        synchronized(stateLock) { baseTexts.putIfAbsent(date, date.text?.toString().orEmpty()) }
        dateLayouts.putIfAbsent(
            date,
            DateLayoutState(date.maxWidth, date.maxLines, date.ellipsize)
        )
        date.maxLines = 1
        date.ellipsize = TextUtils.TruncateAt.END
        root.addOnLayoutChangeListener(listener)
        root.post {
            HookFailurePolicy.open(TAG, "initial weather", Unit) {
                updateDateBounds(root, date, carrier)
                render(date)
                requestWeather(date)
            }
        }
    }

    private fun updateDateBounds(root: ViewGroup, date: TextView, carrier: View?) {
        if (root.width <= 0) return
        val spacing = (8f * root.resources.displayMetrics.density).toInt()
        val carrierWidth = if (carrier?.visibility == View.VISIBLE) carrier.measuredWidth + spacing else 0
        val available = root.width - root.paddingStart - root.paddingEnd - carrierWidth
        if (available > 0 && date.maxWidth != available) date.maxWidth = available
    }

    private fun render(date: TextView) {
        val base = synchronized(stateLock) { baseTexts[date] } ?: return
        val locale = date.resources.configuration.locales[0] ?: Locale.getDefault()
        val rainLabel = if (locale.language == Locale.CHINESE.language) "降雨" else "Rain "
        date.text = NotificationHeaderModel.formatDateWeather(
            baseDate = base,
            weather = weatherSnapshot,
            showRegion = showRegion,
            weatherType = weatherType,
            rainLabel = rainLabel
        )
    }

    private fun prepareFetcher(): Boolean {
        if (fetcher != null && fetchMethod != null && callbackProxy != null) return true
        return runCatching {
            val fetcherClass = FETCHER_CLASS.toClassOrNull() ?: return false
            val callbackClass = CALLBACK_CLASS.toClassOrNull() ?: return false
            val controller = fetcherClass.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance()
            val method = fetcherClass.declaredMethods.firstOrNull {
                it.name == "fetchWeatherData" && it.parameterCount == 3 &&
                    it.parameterTypes[2] == callbackClass
            }?.apply { isAccessible = true } ?: return false
            val callback = Proxy.newProxyInstance(
                callbackClass.classLoader ?: classLoader,
                arrayOf(callbackClass)
            ) { proxy, invoked, args ->
                when (invoked.name) {
                    "updateWeatherInfo" -> {
                        onWeatherResult(args?.getOrNull(0))
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "HyperTweakNotificationHeaderWeatherCallback"
                    else -> null
                }
            }
            fetcher = controller
            fetchMethod = method
            callbackProxy = callback
            true
        }.onFailure {
            DebugLog.w(TAG, "failed to prepare Xiaomi weather fetcher", it)
        }.getOrDefault(false)
    }

    private fun requestWeather(date: TextView) {
        val now = SystemClock.elapsedRealtime()
        synchronized(stateLock) {
            if (fetchInFlight) return
            val freshness = if (weatherSnapshot == null) FAILURE_RETRY_MS else SUCCESS_REFRESH_MS
            val anchor = if (weatherSnapshot == null) lastAttemptElapsed else lastSuccessElapsed
            if (anchor != Long.MIN_VALUE && now - anchor < freshness) return
            fetchInFlight = true
            lastAttemptElapsed = now
        }
        if (!prepareFetcher()) {
            synchronized(stateLock) { fetchInFlight = false }
            return
        }
        runCatching {
            fetchMethod?.invoke(fetcher, date.context, false, callbackProxy)
        }.onFailure {
            synchronized(stateLock) { fetchInFlight = false }
            DebugLog.w(TAG, "weather request failed", it)
        }
    }

    private fun onWeatherResult(bean: Any?) {
        HookFailurePolicy.open(TAG, "weather callback", Unit) {
            val snapshot = bean?.let(::extractSnapshot)
            synchronized(stateLock) {
                fetchInFlight = false
                if (snapshot != null) {
                    weatherSnapshot = snapshot
                    lastSuccessElapsed = SystemClock.elapsedRealtime()
                }
            }
            if (snapshot == null) return@open
            val liveDates = synchronized(stateLock) { baseTexts.keys.toList() }
            liveDates.filter(View::isAttachedToWindow).forEach(::render)
        }
    }

    private fun extractSnapshot(bean: Any): NotificationHeaderModel.WeatherSnapshot? {
        val region = call(bean, "getCityName") as? String
        val condition = call(bean, "getDescription") as? String
        val validTemperature = call(bean, "getTemperatureValid") as? Boolean ?: false
        val temperature = if (validTemperature) (call(bean, "getTemperature") as? Number)?.toInt() else null
        val rain = call(bean, "getRainProbability") as? String
        if (region.isNullOrBlank() && condition.isNullOrBlank() && temperature == null &&
            (rain.isNullOrBlank() || rain == "--")
        ) return null
        return NotificationHeaderModel.WeatherSnapshot(region, condition, temperature, rain)
    }

    private fun call(owner: Any, name: String): Any? = runCatching {
        owner.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.invoke(owner)
    }.getOrNull()

    @Suppress("DiscouragedApi")
    private fun id(view: View, name: String): Int =
        view.resources.getIdentifier(name, "id", "com.android.systemui")

    private fun read(owner: Any, name: String): Any? = field(owner.javaClass, name)?.get(owner)

    private fun field(type: Class<*>, name: String): Field? = fields.getOrPut(type to name) {
        var current: Class<*>? = type
        var result: Field? = null
        while (current != null && result == null) {
            result = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()
            current = current.superclass
        }
        result
    }
}
