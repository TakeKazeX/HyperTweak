package com.takekazex.hypertweak.ui.effect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.util.UpstreamBackGesture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Refresh on resume and package changes; unknown remains disabled until the query completes. */
@Composable
internal fun rememberUpstreamBackGestureInstalled(): Boolean? {
    val context = LocalContext.current
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value == Lifecycle.State.RESUMED
    var installed by remember { mutableStateOf<Boolean?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == UpstreamBackGesture.PACKAGE) revision++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(context, resumed, revision) {
        if (resumed) installed = withContext(Dispatchers.IO) { UpstreamBackGesture.refresh(context) }
    }
    return installed
}
