package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.presentation.icon.NetworkTransport
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.roundToInt

/** Owned by the Wi-Fi adapter; all callbacks and flow emissions are generation gated. */
internal class WifiPipelineSource {
    private val main = Handler(Looper.getMainLooper())
    private val subscriptions = HostFlowSubscriptions()
    private var interactor: Any? = null
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var current: Network? = null
    private var pending: Runnable? = null
    private var epoch = 0L
    private var flowGeneration = 0L
    var onNetwork: ((Any?) -> Unit)? = null

    fun isBoundTo(source: Any): Boolean = interactor === source && NetworkHub.wifiBound

    fun bind(scope: Any, source: Any, context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        ensureConnectivity(context)
        if (interactor === source && NetworkHub.wifiBound) return
        flowGeneration++
        subscriptions.clear()
        interactor = source
        NetworkHub.wifiBound = false
        NetworkHub.publish { it.copy(wifiDefault = null, wifiLevel = null) }
        val token = flowGeneration
        val default = HostAccess.read(source, "isDefault")
        val network = HostAccess.read(source, "wifiNetwork")
        val max = runCatching { context.getSystemService(WifiManager::class.java)?.maxSignalLevel ?: 4 }
            .getOrDefault(4)
            .coerceAtLeast(1)
        val a = subscriptions.collect(scope, default, { token == flowGeneration }, replay = true) { value ->
            NetworkHub.publish { it.copy(wifiDefault = value as? Boolean) }
        }
        val b = subscriptions.collect(scope, network, { token == flowGeneration }, replay = true) { value ->
            runCatching { onNetwork?.invoke(value) }
                .onFailure { DebugLog.w("IconTuner", "Wi-Fi override update failed", it) }
            val level = if (value?.javaClass?.name?.endsWith("WifiNetworkModel\$Active") == true)
                (HostAccess.read(value, "level") as? Int)?.takeIf { it in 0..max }
                    ?.let { (it * 4f / max).roundToInt().coerceIn(0, 4) } else null
            NetworkHub.publish { it.copy(wifiLevel = level) }
        }
        NetworkHub.wifiBound = a && b
        DebugLog.i("IconTuner", "shared Wi-Fi flows bound=${NetworkHub.wifiBound} default=${default != null} network=${network != null}")
        if (!NetworkHub.wifiBound) {
            subscriptions.clear()
            NetworkHub.publish { it.copy(wifiDefault = null, wifiLevel = null) }
            runCatching { onNetwork?.invoke(null) }
                .onFailure { DebugLog.w("IconTuner", "Wi-Fi override reset failed", it) }
            DebugLog.w("IconTuner", "shared Wi-Fi flows unavailable")
        }
    }

    private fun ensureConnectivity(context: Context) {
        if (callback != null) return
        val service = context.getSystemService(ConnectivityManager::class.java) ?: return
        val token = epoch
        if (runCatching { service.activeNetwork == null }.getOrDefault(false))
            NetworkHub.publish { it.copy(transport = NetworkTransport.NONE, validated = false) }
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (token != epoch) return
                current = network
                pending?.let(main::removeCallbacks)
                pending = Runnable {
                    if (token == epoch && current == network)
                        NetworkHub.publish { it.copy(transport = NetworkTransport.UNKNOWN, validated = false) }
                }.also { main.postDelayed(it, 200L) }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (token != epoch || current != network) return
                pending?.let(main::removeCallbacks); pending = null
                val transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
                    else -> NetworkTransport.OTHER
                }
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                NetworkHub.publish { it.copy(transport = transport, validated = validated) }
            }
            override fun onLost(network: Network) {
                if (token != epoch || current != network) return
                pending?.let(main::removeCallbacks); pending = null; current = null
                NetworkHub.publish { it.copy(transport = NetworkTransport.NONE, validated = false) }
            }
        }
        runCatching { service.registerDefaultNetworkCallback(listener, main) }.onSuccess {
            manager = service; callback = listener
        }.onFailure { DebugLog.w("IconTuner", "default network callback unavailable", it) }
    }

    fun retire() {
        epoch++
        flowGeneration++
        subscriptions.clear()
        interactor = null
        onNetwork = null
        NetworkHub.wifiBound = false
        pending?.let(main::removeCallbacks)
        pending = null
        current = null
        callback?.let { listener ->
            runCatching { manager?.unregisterNetworkCallback(listener) }
                .onFailure { DebugLog.w("IconTuner", "network callback cleanup failed", it) }
        }
        callback = null
        manager = null
    }
}
