package com.takekazex.hypertweak.hook.rules.system

import android.content.Context
import android.os.Handler
import android.provider.Settings
import com.takekazex.hypertweak.hook.base.HostAccess

/** Current-image contracts and framework roots; no host or observer is retained by the module. */
internal object AonLiveHosts {
    fun refresh(loader: ClassLoader, enabled: Boolean, nativeBoolean: (Context, Int) -> Boolean) {
        val power = SystemServiceHosts.singleton(loader, "com.android.server.power.PowerManagerServiceImpl")
        val attention = power?.let { HostAccess.read(it, "mAttentionDetector") }
        if (attention != null) {
            val context = HostAccess.read(attention, "mContext") as? Context ?: error("attention Context unavailable")
            val handler = HostAccess.read(attention, "mAttentionHandler") as? Handler ?: error("attention Handler unavailable")
            val parser = Class.forName("miui.util.FeatureParser", false, loader)
            val proximity = HostAccess.requireDeclaredMethod(parser, "getBoolean", String::class.java,
                Boolean::class.javaPrimitiveType, accessible = true).invoke(null, "config_aon_proximity_available", false) as? Boolean == true
            val screenOff = enabled || proximity || nativeBoolean(context, 285540515)
            val screenOn = enabled || proximity || nativeBoolean(context, 285540516)
            HostAccess.onHandler(handler) {
                HostAccess.requireDeclaredField(attention.javaClass, "mAonScreenOffSupported", accessible = true).setBoolean(attention, screenOff)
                HostAccess.requireDeclaredField(attention.javaClass, "mAonScreenOnSupported", accessible = true).setBoolean(attention, screenOn)
                if (!screenOff && !screenOn) HostAccess.invoke(attention, "cancelAndUnbind")
                HostAccess.requireDeclaredMethod(attention.javaClass, "updateConfigState", accessible = true).invoke(attention)
            }
        }
        val sensors = SystemServiceHosts.singleton(loader, "com.android.server.sensors.SensorServiceImpl") ?: return
        val context = HostAccess.read(sensors, "mContext") as? Context ?: return // Boot has not initialized sensors yet.
        val field = HostAccess.requireDeclaredField(sensors.javaClass, "mAntiburnScreenController", accessible = true)
        var controller = field.get(sensors) ?: return // Native boot callback still owns first creation.
        val supported = nativeBoolean(context, 285540512)
        if (enabled && HostAccess.read(controller, "mHandler") == null) {
            // The native disabled constructor has no observers, sensors or bound service to retire.
            // Replace it exactly once; keep the initialized native controller for subsequent toggles.
            controller = controller.javaClass.getConstructor(Context::class.java).newInstance(context)
            field.set(sensors, controller)
        }
        val host = controller
        val handler = HostAccess.read(host, "mHandler") as? Handler ?: return
        HostAccess.onHandler(handler) {
            if (!enabled && !supported) {
                val open = HostAccess.read(host, "isOpen") == true
                HostAccess.requireDeclaredField(host.javaClass, "isOpen", accessible = true).setBoolean(host, false)
                if (open) {
                    listOf("unregisterActivityChangeListener", "unregisterBroadCastListener", "unregisterSensorManager")
                        .forEach { HostAccess.requireDeclaredMethod(host.javaClass, it, accessible = true).invoke(host) }
                    if (HostAccess.read(host, "mIsFoldDevice") == true) HostAccess.invoke(host, "unregisterFoldStateListener")
                }
                HostAccess.requireDeclaredMethod(host.javaClass, "stopAntiBurnForAppExit", accessible = true).invoke(host)
            } else if (HostAccess.read(host, "isOpen") != true) {
                val desired = HostAccess.requireDeclaredMethod(Settings.System::class.java, "getIntForUser",
                    android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, accessible = true)
                    .invoke(null, context.contentResolver, "miui_aon_anti_burn", 1, -2) == 1
                if (desired) {
                    HostAccess.requireDeclaredField(host.javaClass, "isOpen", accessible = true).setBoolean(host, true)
                    HostAccess.requireDeclaredMethod(host.javaClass, "registerBroadCastListener", accessible = true).invoke(host)
                    if (HostAccess.read(host, "isPowerSaveMode") != true) HostAccess.invoke(host, "registerActivityChangeListener")
                    if (HostAccess.read(host, "mIsFoldDevice") == true) HostAccess.invoke(host, "registerFoldStateListener")
                }
            }
        }
    }
}
