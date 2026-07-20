package com.takekazex.hypertweak.ui.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takekazex.hypertweak.getSystemAccentColor

@Composable
fun rememberDeviceAccentColor(): Int {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var accent by remember(context) { mutableIntStateOf(getSystemAccentColor(context)) }
    DisposableEffect(context, owner) {
        val manager = context.getSystemService(Context.WALLPAPER_SERVICE) as WallpaperManager
        val listener = WallpaperManager.OnColorsChangedListener { _: WallpaperColors?, _: Int ->
            accent = getSystemAccentColor(context)
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accent = getSystemAccentColor(context)
        }
        owner.lifecycle.addObserver(lifecycleObserver)
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose {
            owner.lifecycle.removeObserver(lifecycleObserver)
            manager.removeOnColorsChangedListener(listener)
        }
    }
    return accent
}
