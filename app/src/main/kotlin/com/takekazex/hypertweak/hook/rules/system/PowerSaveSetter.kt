package com.takekazex.hypertweak.hook.rules.system

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.Keep
import androidx.core.net.toUri
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Use the native tile protocol for MIUI policy; clear the independent framework request on exit. */
@Keep
internal object PowerSaveSetter {
    private val setter: Method? by lazy {
        PowerManager::class.java.declaredMethods.singleOrNull {
            it.name == "setPowerSaveModeEnabled" &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
        }?.apply { isAccessible = true }
    }

    private val currentUserMethod by lazy {
        ActivityManager::class.java.getDeclaredMethod("getCurrentUser").apply { isAccessible = true }
    }
    private val nativeStateReader by lazy {
        Settings.System::class.java.getDeclaredMethod(
            "getIntForUser", ContentResolver::class.java, String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
    }

    val available: Boolean get() = setter != null
    fun currentUser(): Int = invoke(currentUserMethod, null) as Int

    fun nativeEnabled(context: Context, userId: Int): Boolean =
        (invoke(nativeStateReader, null, context.contentResolver, MODE_KEY, 0, userId) as Int) != 0

    /** ContentProvider.call returns no acknowledgement: completion is observed through Settings. */
    fun requestNative(context: Context, userId: Int, enabled: Boolean) {
        val uri = "content://$userId@com.miui.powercenter.powersaver".toUri()
        context.contentResolver.call(uri, "changePowerMode", null, Bundle().apply {
            putBoolean(MODE_KEY, enabled)
        })
    }

    const val MODE_KEY = "POWER_SAVE_MODE_OPEN"

    fun set(powerManager: PowerManager, enabled: Boolean): Boolean {
        val method = checkNotNull(setter) { "PowerManager.setPowerSaveModeEnabled unavailable" }
        return invoke(method, powerManager, enabled) as Boolean
    }

    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}
