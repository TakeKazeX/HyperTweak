package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import com.takekazex.hypertweak.presentation.icon.duo.DuoNetwork
import com.takekazex.hypertweak.presentation.icon.duo.DuoTransport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.rules.systemui.icon.HostFlowSubscriptions
import com.takekazex.hypertweak.util.DebugLog
import kotlin.math.roundToInt

/** Network callbacks and flow replay have one generation owner and one retirement path. */
internal class DuoNetworkSource(private val host: Host, private val main: Handler) {
    interface Host {
        val enabled: Boolean
        val epoch: Int
        fun refresh()
    }
    private val wifiSubscriptions = HostFlowSubscriptions()
    var network = DuoNetwork()
        private set
    private var connectivity: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var networkPending: Runnable? = null
    var wifiScope: Any? = null
        private set
    var wifiInteractor: Any? = null
        private set
    var wifiContext: Context? = null
        private set
    fun bindWifi(scope: Any, interactor: Any, context: Context) {
        if (wifiInteractor === interactor && wifiSubscriptions.isBound) return
        wifiSubscriptions.clear()
        wifiScope = scope; wifiInteractor = interactor; wifiContext = context.applicationContext
        network = network.copy(wifiLevel = null, wifiDefault = null)
        ensureConnectivity(context)
        val token = host.epoch
        wifiSubscriptions.collect(scope, read(interactor, "isDefault"), { host.enabled && token == host.epoch }) { value ->
            network = network.copy(wifiDefault = value as? Boolean)
            host.refresh()
        }
        val wifiMax = (context.getSystemService(WifiManager::class.java)?.maxSignalLevel ?: 4).coerceAtLeast(1)
        wifiSubscriptions.collect(scope, read(interactor, "wifiNetwork"), { host.enabled && token == host.epoch }) { value ->
            val level = if (value?.javaClass?.name?.endsWith("WifiNetworkModel\$Active") == true)
                (read(value, "level") as? Int)?.takeIf { it in 0..wifiMax }?.let {
                    (it * 4f / wifiMax).roundToInt().coerceIn(0, 4)
                } else null
            network = network.copy(wifiLevel = level)
            host.refresh()
        }
    }

    fun ensureConnectivity(context: Context) {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val token = host.epoch
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(value: Network) {
                if (!host.enabled || token != host.epoch) return
                currentNetwork = value
                // Capabilities follow onAvailable. Keep the previous representation for this
                // bounded handover instead of exposing the native cluster between callbacks.
                networkPending?.let(main::removeCallbacks)
                networkPending = Runnable {
                    if (host.enabled && token == host.epoch && currentNetwork == value) {
                        network = network.copy(transport = DuoTransport.UNKNOWN, validated = false)
                        host.refresh()
                    }
                }.also { main.postDelayed(it, 200L) }
            }
            override fun onCapabilitiesChanged(value: Network, caps: NetworkCapabilities) {
                if (!host.enabled || token != host.epoch || currentNetwork != value) return
                networkPending?.let(main::removeCallbacks)
                networkPending = null
                network = network.copy(transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DuoTransport.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DuoTransport.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> DuoTransport.VPN
                    else -> DuoTransport.OTHER
                }, validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                host.refresh()
            }
            override fun onLost(value: Network) {
                if (!host.enabled || token != host.epoch || currentNetwork != value) return
                networkPending?.let(main::removeCallbacks)
                networkPending = null
                currentNetwork = null
                network = network.copy(transport = DuoTransport.NONE, validated = false)
                host.refresh()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(listener, main) }.onSuccess {
            connectivity = manager; callback = listener
        }.onFailure { DebugLog.w(TAG, "default network callback unavailable", it) }
    }
    fun retire() {
        callback?.let { listener -> runCatching { connectivity?.unregisterNetworkCallback(listener) } }
        networkPending?.let(main::removeCallbacks)
        networkPending = null
        callback = null; connectivity = null; currentNetwork = null
        wifiSubscriptions.clear()
        wifiScope = null; wifiInteractor = null; wifiContext = null
        network = DuoNetwork()
    }
    private fun read(owner: Any, name: String): Any? = HostAccess.field(owner.javaClass, name)?.get(owner)
    private companion object { const val TAG = "DuoSignal" }
}
