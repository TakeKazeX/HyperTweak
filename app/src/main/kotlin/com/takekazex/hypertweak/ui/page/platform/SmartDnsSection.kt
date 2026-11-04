package com.takekazex.hypertweak.ui.page.platform

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.util.platform.CloudDnsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Three tri-state cloud overrides with live details in each dropdown summary. The cloud
 * Settings.System values are edited here; MiuiNetworkPolicyManagerService applies them to the
 * resolver properties.
 */
@Composable
internal fun SmartDnsSection() {
    val context = LocalContext.current
    val settingsStore = LocalAppSettingsStore.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CloudDnsSettings.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value == Lifecycle.State.RESUMED

    suspend fun readLiveState(): CloudDnsSettings.State? = withContext(Dispatchers.IO) {
        runCatching { CloudDnsSettings.read(context, settingsStore) }.getOrNull()
    }

    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            val next = readLiveState()
            if (next != null) {
                state = next
                if (!busy) {
                    withContext(Dispatchers.IO) {
                        CloudDnsSettings.reconcileSmartDns(context, settingsStore)
                        CloudDnsSettings.reconcileHappyEyeballs(context, settingsStore)
                    }
                }
            }
            delay(1_000)
        }
    }

    fun update(previous: CloudDnsSettings.State?, write: suspend () -> Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val success = runCatching { write() }.getOrDefault(false)
            if (success) {
                val next = readLiveState()
                if (next != null) state = next
            } else {
                state = previous
                Toast.makeText(context, R.string.smartdns_write_failed, Toast.LENGTH_SHORT).show()
            }
            busy = false
        }
    }

    fun updateSmartDnsMode(index: Int) {
        val current = state ?: return
        // MiuiNetworkPolicyManagerService treats any "on" in the whole CSV as enabling
        // SmartDNS, so turning the master switch off must also turn IPv4-first off.
        val ipv4Mode = if (index == CloudDnsSettings.MODE_OFF) {
            CloudDnsSettings.MODE_OFF
        } else {
            current.ipv4FirstMode
        }
        state = current.copy(smartDnsMode = index, ipv4FirstMode = ipv4Mode)
        update(current) {
            CloudDnsSettings.setSmartDnsModes(context, settingsStore, index, ipv4Mode)
        }
    }

    fun updateIpv4FirstMode(index: Int) {
        val current = state ?: return
        state = current.copy(ipv4FirstMode = index)
        update(current) {
            CloudDnsSettings.setSmartDnsModes(
                context,
                settingsStore,
                current.smartDnsMode,
                index,
            )
        }
    }

    fun updateHappyEyeballsMode(index: Int) {
        val current = state ?: return
        state = current.copy(happyEyeballsMode = index)
        update(current) {
            CloudDnsSettings.setHappyEyeballsMode(context, settingsStore, index)
        }
    }

    val modeItems = listOf(
        stringResource(R.string.smartdns_mode_default),
        stringResource(R.string.smartdns_mode_on),
        stringResource(R.string.smartdns_mode_off),
    )

    SmallTitle(text = stringResource(R.string.smartdns_section_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.smartdns_enable_title),
                summary = smartDnsSummary(state),
                items = modeItems,
                selectedIndex = state?.smartDnsMode ?: CloudDnsSettings.MODE_DEFAULT,
                enabled = state != null && !busy,
                onSelectedIndexChange = ::updateSmartDnsMode,
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.smartdns_ipv4_first_title),
                summary = ipv4FirstSummary(state),
                items = modeItems,
                selectedIndex = state?.ipv4FirstMode ?: CloudDnsSettings.MODE_DEFAULT,
                enabled = !busy && state?.let { it.smartDnsMode != CloudDnsSettings.MODE_OFF } == true,
                onSelectedIndexChange = ::updateIpv4FirstMode,
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.smartdns_happy_eyeballs_title),
                summary = happyEyeballsSummary(state),
                items = modeItems,
                selectedIndex = state?.happyEyeballsMode ?: CloudDnsSettings.MODE_DEFAULT,
                enabled = state != null && !busy,
                onSelectedIndexChange = ::updateHappyEyeballsMode,
            )
        }
    }
    Text(
        text = stringResource(R.string.smartdns_footer),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
    )
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun smartDnsSummary(state: CloudDnsSettings.State?): String {
    val cloud = state?.smartDnsV2 ?: state?.smartDnsV1 ?: stringResource(R.string.smartdns_cloud_unset)
    return stringResource(
        R.string.smartdns_summary_smartdns,
        effectiveLabel(state?.effectiveSmartDns),
        cloud,
    )
}

@Composable
private fun ipv4FirstSummary(state: CloudDnsSettings.State?): String {
    val cloud = state?.ipv4CloudValue ?: stringResource(R.string.smartdns_cloud_unset)
    return stringResource(
        R.string.smartdns_summary_ipv4,
        effectiveLabel(state?.effectiveIpv4First),
        cloud,
    )
}

@Composable
private fun happyEyeballsSummary(state: CloudDnsSettings.State?): String {
    val cloud = state?.happyEyeballsCloud ?: stringResource(R.string.smartdns_cloud_unset)
    return stringResource(
        R.string.smartdns_summary_happy,
        effectiveLabel(state?.effectiveHappyEyeballs),
        cloud,
    )
}

@Composable
private fun effectiveLabel(value: Boolean?): String = when (value) {
    true -> stringResource(R.string.smartdns_status_on)
    false -> stringResource(R.string.smartdns_status_off)
    null -> stringResource(R.string.smartdns_status_unknown)
}
