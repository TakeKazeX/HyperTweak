package com.takekazex.hypertweak.util

import android.annotation.SuppressLint
import android.os.Build

/** Prefer Xiaomi's commercial model name, as KernelSU's device information does. */
internal val deviceModel: String by lazy {
    readMarketModel() ?: buildList {
        add(Build.MANUFACTURER)
        if (!Build.BRAND.equals(Build.MANUFACTURER, ignoreCase = true)) add(Build.BRAND)
        add(Build.MODEL)
    }.mapNotNull { it.validModel() }.joinToString(" ")
        .ifEmpty { Build.MODEL }
}

@SuppressLint("PrivateApi")
private fun readMarketModel(): String? = try {
    val properties = Class.forName("android.os.SystemProperties")
    (properties.getMethod("get", String::class.java)
        .invoke(null, "ro.product.marketname") as? String).validModel()
} catch (_: Exception) {
    null
}

private fun String?.validModel(): String? = this?.trim()?.takeIf {
    it.isNotEmpty() && !it.equals(Build.UNKNOWN, ignoreCase = true) &&
        !it.equals("null", ignoreCase = true)
}
