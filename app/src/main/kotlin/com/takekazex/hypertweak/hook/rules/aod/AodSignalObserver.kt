package com.takekazex.hypertweak.hook.rules.aod

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalEvent
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalModel
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalState
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoNetwork
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoTransport
import java.util.concurrent.Executor

/** AOD plugin adapter for the same mobile reducer used by the SystemUI Duo renderer. */
internal class AodSignalObserver(
    context: Context,
    private val onChanged: () -> Unit
) {
    data class Snapshot(val mobile: MobileSignalState, val network: DuoNetwork)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = Executor { command -> main.post(command) }
    private val subscriptionManager = appContext.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val callbacks = LinkedHashMap<Int, Pair<TelephonyManager, SignalCallback>>()
    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var receiverRegistered = false
    private var lastNetwork: Network? = null
    private var lastCapabilities: NetworkCapabilities? = null
    private var mobile = MobileSignalState()
    private var network = DuoNetwork()
    private var started = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    mobile = mobile.reduce(
                        MobileSignalEvent.AirplaneMode(airplaneModeEnabled())
                    )
                    publish()
                }
                SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED -> refreshDataSubscription()
                WifiManager.RSSI_CHANGED_ACTION -> refreshDefaultNetwork()
            }
        }
    }

    fun snapshot(): Snapshot = Snapshot(mobile, network)

    fun start() {
        if (started) return
        started = true
        mobile = mobile.reduce(MobileSignalEvent.AirplaneMode(airplaneModeEnabled()))
        registerStateReceiver()
        registerSubscriptions()
        registerNetwork()
        bindSubscriptions()
        refreshDefaultNetwork()
    }

    fun stop() {
        if (!started) return
        started = false
        callbacks.values.forEach { (manager, callback) ->
            runCatching { manager.unregisterTelephonyCallback(callback) }
        }
        callbacks.clear()
        subscriptionListener?.let { listener ->
            runCatching { subscriptionManager?.removeOnSubscriptionsChangedListener(listener) }
        }
        subscriptionListener = null
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        lastNetwork = null
        lastCapabilities = null
        mobile = MobileSignalState()
        network = DuoNetwork()
    }

    private fun registerStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
        }
        runCatching {
            appContext.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    private fun registerSubscriptions() {
        val manager = subscriptionManager ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                if (started) bindSubscriptions()
            }
        }
        subscriptionListener = listener
        runCatching { manager.addOnSubscriptionsChangedListener(mainExecutor, listener) }
    }

    @SuppressLint("MissingPermission")
    private fun bindSubscriptions() {
        if (!started) return
        val active: List<SubscriptionInfo> = if (
            appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { subscriptionManager?.activeSubscriptionInfoList }
                .getOrNull()
                .orEmpty()
                .filterNotNull()
                .sortedBy(SubscriptionInfo::getSimSlotIndex)
        } else emptyList()
        val activeIds = active.map(SubscriptionInfo::getSubscriptionId).distinct()
        mobile = mobile.reduce(MobileSignalEvent.Subscriptions(activeIds))
        refreshDataSubscription()

        callbacks.keys.filterNot(activeIds::contains).toList().forEach { subId ->
            callbacks.remove(subId)?.let { (manager, callback) ->
                runCatching { manager.unregisterTelephonyCallback(callback) }
            }
        }
        activeIds.forEach { subId ->
            if (callbacks.containsKey(subId)) return@forEach
            val manager = runCatching { telephonyManager?.createForSubscriptionId(subId) }
                .getOrNull() ?: return@forEach
            val callback = SignalCallback(subId, manager)
            runCatching { manager.registerTelephonyCallback(mainExecutor, callback) }
                .onSuccess { callbacks[subId] = manager to callback }
        }
        publish()
    }

    private fun refreshDataSubscription() {
        val subId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        mobile = mobile.reduce(MobileSignalEvent.ActiveDataSubId(subId))
        publish()
    }

    private fun registerNetwork() {
        val manager = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastNetwork = network
                lastCapabilities = manager.getNetworkCapabilities(network)
                refreshDefaultNetwork()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (lastNetwork == null || lastNetwork == network) {
                    lastNetwork = network
                    lastCapabilities = capabilities
                    refreshDefaultNetwork()
                }
            }

            override fun onLost(lostNetwork: Network) {
                if (lastNetwork == lostNetwork) {
                    lastNetwork = null
                    lastCapabilities = null
                    network = DuoNetwork(transport = DuoTransport.NONE)
                    publish()
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback, main) }
            .onFailure { networkCallback = null }
    }

    private fun refreshDefaultNetwork() {
        if (!started) return
        val capabilities = lastCapabilities
            ?: connectivityManager?.activeNetwork?.let { connectivityManager?.getNetworkCapabilities(it) }
        val wifiLevel = wifiLevel()
        val wifiIsUnderlying = wifiLevel != null
        network = when {
            capabilities == null -> DuoNetwork(transport = DuoTransport.NONE)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DuoNetwork(
                transport = DuoTransport.WIFI,
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                wifiLevel = wifiLevel
            )
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DuoNetwork(
                transport = DuoTransport.CELLULAR,
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> DuoNetwork(
                transport = DuoTransport.VPN,
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                wifiLevel = wifiLevel,
                wifiDefault = wifiIsUnderlying
            )
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> DuoNetwork(
                transport = DuoTransport.OTHER,
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
            else -> DuoNetwork(
                transport = DuoTransport.OTHER,
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }
        refreshDataSubscription()
        publish()
    }

    private fun wifiLevel(): Int? = runCatching {
        val info = wifiManager?.connectionInfo ?: return null
        if (info.networkId == -1 || info.rssi <= INVALID_WIFI_RSSI) return null
        WifiManager.calculateSignalLevel(info.rssi, 5).coerceIn(0, 4)
    }.getOrNull()

    private fun airplaneModeEnabled(): Boolean = runCatching {
        Settings.Global.getInt(
            appContext.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0
        ) == 1
    }.getOrDefault(false)

    private fun publish() {
        if (started) onChanged()
    }

    private inner class SignalCallback(
        private val subId: Int,
        private val manager: TelephonyManager
    ) : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DataConnectionStateListener,
        TelephonyCallback.DisplayInfoListener {

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            mobile = mobile.reduce(
                MobileSignalEvent.SignalModel(
                    subId,
                    MobileSignalModel.cellular(signalStrength.level.coerceIn(0, 4), 5)
                )
            )
            publish()
        }

        override fun onServiceStateChanged(serviceState: ServiceState) {
            mobile = mobile.reduce(
                MobileSignalEvent.InService(subId, serviceState.state == ServiceState.STATE_IN_SERVICE)
            ).reduce(MobileSignalEvent.Roaming(subId, manager.isNetworkRoaming))
            publish()
        }

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            mobile = mobile.reduce(
                MobileSignalEvent.DataConnected(
                    subId,
                    state == TelephonyManager.DATA_CONNECTED
                )
            ).reduce(MobileSignalEvent.NetworkType(subId, networkLabel(networkType, 0)))
            publish()
        }

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            val label = networkLabel(
                telephonyDisplayInfo.networkType,
                telephonyDisplayInfo.overrideNetworkType
            )
            mobile = mobile.reduce(MobileSignalEvent.NetworkType(subId, label))
                .reduce(
                    MobileSignalEvent.NonTerrestrial(
                        subId,
                        telephonyDisplayInfo.networkType == satelliteNetworkType()
                    )
                )
            publish()
        }
    }

    private fun networkLabel(networkType: Int, overrideType: Int): String? = when {
        networkType == TelephonyManager.NETWORK_TYPE_NR ||
            overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
        networkType == TelephonyManager.NETWORK_TYPE_LTE ||
            overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
            overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G"
        networkType == TelephonyManager.NETWORK_TYPE_HSPAP -> "4G"
        networkType in setOf(
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EHRPD
        ) -> "3G"
        networkType in setOf(
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN
        ) -> "2G"
        else -> null
    }

    private fun satelliteNetworkType(): Int? = runCatching {
        TelephonyManager::class.java.getField("NETWORK_TYPE_SATELLITE").getInt(null)
    }.getOrNull()

    private companion object {
        const val INVALID_WIFI_RSSI = -127
    }
}
