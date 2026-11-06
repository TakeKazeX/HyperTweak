package com.takekazex.hypertweak.hook.rules.market

import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Special store modes read their own page configuration and reject a tab list with four or fewer
 * entries (`WebResourceManager.checkSpecialModePageConfigValid` requires `size > 3`). Filtering
 * there could invalidate the configuration and force a fallback, so the tab policy stays inactive
 * in elder, child and overseas modes. Missing helpers count as "mode off" instead of disabling the
 * feature, because the classes are optional vendor code.
 */
internal object MarketTabGuard {
    private const val TAG = "Market"
    private val warned = linkedSetOf<String>()

    fun allowsFiltering(classLoader: ClassLoader): Boolean =
        !elderMode(classLoader) && !childMode(classLoader) && !overseasMode(classLoader)

    private fun elderMode(loader: ClassLoader) = readSingleton(loader, "com.xiaomi.market.util.ElderChecker", "isElderMode")

    private fun childMode(loader: ClassLoader) = readSingleton(loader, "com.xiaomi.market.common.child.ChildUtils", "isChildMode")

    private fun overseasMode(loader: ClassLoader): Boolean {
        val helper = runCatching {
            loader.loadClass("com.xiaomi.market.util.OverseasModeHelper").getMethod("getInstance").invoke(null)
        }.getOrNull()
        if (helper == null) {
            warnOnce("OverseasModeHelper missing")
            return false
        }
        return runCatching { helper.javaClass.getMethod("isOverseasMode").invoke(helper) as Boolean }
            .getOrElse { warnOnce("OverseasModeHelper.isOverseasMode failed: " + it.javaClass.simpleName); false }
    }

    private fun readSingleton(loader: ClassLoader, typeName: String, method: String): Boolean {
        val instance = runCatching {
            loader.loadClass(typeName).getField("INSTANCE").get(null)
        }.getOrNull()
        if (instance == null) {
            warnOnce(typeName + " missing")
            return false
        }
        return runCatching { instance.javaClass.getMethod(method).invoke(instance) as Boolean }
            .getOrElse { warnOnce(typeName + "." + method + " failed: " + it.javaClass.simpleName); false }
    }

    private fun warnOnce(message: String) {
        synchronized(warned) { if (!warned.add(message)) return }
        DebugLog.d(TAG, "tab policy " + message)
    }
}
