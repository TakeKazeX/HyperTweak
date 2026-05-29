package com.takekazex.hypertweak.hook.rules

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import android.widget.ImageView
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File

object SettingsHooker : StaticHooker() {
    private const val HEADER_ID = 10777L

    private fun String.resolveClass(initialize: Boolean = false): Class<Any>? {
        val resolvedClass = resolveViaDexKit(this)
        if (resolvedClass != null) {
            @Suppress("UNCHECKED_CAST")
            return resolvedClass as Class<Any>
        }
        return this.toClassOrNull(initialize = initialize)
    }

    private fun resolveViaDexKit(className: String): Class<*>? {
        val appInfo = hookParam.appInfo ?: return null
        val baseDir = appInfo.deviceProtectedDataDir ?: appInfo.dataDir ?: return null
        val cacheDir = File(baseDir, "cache")
        val apkPath = appInfo.sourceDir ?: return null

        return when (className) {
            "com.android.settings.MiuiSettings" -> {
                val resolved = DexKitManager.resolveClasses(
                    cacheDir = cacheDir,
                    apkPath = apkPath,
                    classLoader = classLoader,
                    queries = mapOf("MiuiSettings" to { bridge ->
                        bridge.findClass {
                            searchPackages("com.android.settings")
                            matcher { className("MiuiSettings", StringMatchType.EndsWith) }
                        }.singleOrNull()?.name
                    })
                )
                resolved["MiuiSettings"]
            }
            "com.android.settings.MiuiSettings\$HeaderAdapter" -> {
                val resolved = DexKitManager.resolveClasses(
                    cacheDir = cacheDir,
                    apkPath = apkPath,
                    classLoader = classLoader,
                    queries = mapOf("HeaderAdapter" to { bridge ->
                        bridge.findClass {
                            searchPackages("com.android.settings")
                            matcher { className("MiuiSettings\$HeaderAdapter", StringMatchType.EndsWith) }
                        }.singleOrNull()?.name
                    })
                )
                resolved["HeaderAdapter"]
            }
            "com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header" -> {
                val resolved = DexKitManager.resolveClasses(
                    cacheDir = cacheDir,
                    apkPath = apkPath,
                    classLoader = classLoader,
                    queries = mapOf("PreferenceActivityHeader" to { bridge ->
                        bridge.findClass {
                            searchPackages("com.android.settingslib.miuisettings.preference")
                            matcher { className("PreferenceActivity\$Header", StringMatchType.EndsWith) }
                        }.singleOrNull()?.name
                    })
                )
                resolved["PreferenceActivityHeader"]
            }
            else -> null
        }
    }

    override fun onHook() {
        val clzMiuiSettings = "com.android.settings.MiuiSettings".resolveClass() ?: return
        val clzHeaderAdapter = "com.android.settings.MiuiSettings\$HeaderAdapter".resolveClass()
        val clzHeader = "com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header".resolveClass()

        // 1. Hook updateHeaderList to inject our custom entry in MiuiSettings
        clzMiuiSettings.declaredMethods.firstOrNull { m ->
            m.name == "updateHeaderList" && m.parameterTypes.size == 1 &&
                m.parameterTypes[0].name == "java.util.List"
        }?.hook {
            after { param ->
                @Suppress("UNCHECKED_CAST")
                val list = param.args[0] as? MutableList<Any?> ?: return@after
                val activity = param.thisObject as? Activity ?: return@after

                val showInSettings = Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)
                if (!showInSettings) {
                    val iterator = list.iterator()
                    while (iterator.hasNext()) {
                        val head = iterator.next()
                        val idField = try {
                            head?.javaClass?.getDeclaredField("id")?.apply { isAccessible = true }
                        } catch (t: Throwable) {
                            null
                        }
                        if (idField?.get(head) == HEADER_ID) {
                            iterator.remove()
                        }
                    }
                    return@after
                }

                try {
                    // Check if already injected
                    val alreadyInjected = list.any { head ->
                        val idField = head?.javaClass?.getDeclaredField("id")?.apply { isAccessible = true }
                        idField?.get(head) == HEADER_ID
                    }
                    if (alreadyInjected) return@after

                    // Instantiate new Header object
                    val headerCtor = clzHeader?.getDeclaredConstructor()?.apply { isAccessible = true }
                    val header = headerCtor?.newInstance()

                    if (header != null) {
                        header.javaClass.getDeclaredField("id").apply { isAccessible = true }.set(header, HEADER_ID)

                        val intent = Intent().apply {
                            putExtra("isDisplayHomeAsUpEnabled", true)
                            setClassName("com.takekazex.hypertweak", "com.takekazex.hypertweak.MainActivity")
                        }
                        header.javaClass.getDeclaredField("intent").apply { isAccessible = true }.set(header, intent)
                        header.javaClass.getDeclaredField("title").apply { isAccessible = true }.set(header, "HyperTweak")
                        header.javaClass.getDeclaredField("iconRes").apply { isAccessible = true }.set(header, 0)

                        val bundle = Bundle().apply {
                            val ctorUserHandle = UserHandle::class.java.getDeclaredConstructor(Int::class.java).apply { isAccessible = true }
                            val users = arrayListOf(ctorUserHandle.newInstance(0))
                            putParcelableArrayList("header_user", users)
                        }
                        header.javaClass.getDeclaredField("extras").apply { isAccessible = true }.set(header, bundle)

                        // Find index of "wifi_settings" to insert right after it
                        var targetIndex = -1
                        val wifiSettingsId = try {
                            activity.resources.getIdentifier("wifi_settings", "id", "com.android.settings").toLong()
                        } catch (t: Throwable) {
                            0L
                        }

                        for (i in list.indices) {
                            val head = list[i] ?: continue
                            try {
                                val idField = head.javaClass.getDeclaredField("id").apply { isAccessible = true }
                                val id = (idField.get(head) as? Number)?.toLong() ?: -1L
                                if (wifiSettingsId != 0L && id == wifiSettingsId) {
                                    targetIndex = i
                                    break
                                }

                                val intentField = head.javaClass.getDeclaredField("intent").apply { isAccessible = true }
                                val headIntent = intentField.get(head) as? Intent
                                if (headIntent?.action == "android.settings.WIFI_SETTINGS" ||
                                    headIntent?.component?.className?.contains("WifiSettings", ignoreCase = true) == true) {
                                    targetIndex = i
                                    break
                                }
                            } catch (t: Throwable) {
                                // Ignore
                            }
                        }

                        if (targetIndex != -1) {
                            list.add(targetIndex, header)
                        } else {
                            if (list.size > 2) list.add(2, header) else list.add(header)
                        }
                    }
                } catch (t: Throwable) {
                    // Ignore
                }
            }
        }

        // 2. Hook setIcon in HeaderAdapter to set our custom icon from module resources
        clzHeaderAdapter?.declaredMethods?.firstOrNull { it.name == "setIcon" }?.hook {
            intercept { chain ->
                val param = chain.args
                val headerViewHolder = param[0]
                val header = param[1]

                try {
                    val idField = header?.javaClass?.getDeclaredField("id")?.apply { isAccessible = true }
                    val identifier = idField?.get(header) as? Long
                    if (identifier == HEADER_ID) {
                        val iconField = headerViewHolder?.javaClass?.getDeclaredField("icon")?.apply { isAccessible = true }
                        val iconView = iconField?.get(headerViewHolder) as? ImageView
                        if (iconView != null) {
                            iconView.visibility = View.VISIBLE
                            val moduleIcon = Icon.createWithResource("com.takekazex.hypertweak", R.mipmap.ic_launcher).loadDrawable(iconView.context)
                            if (moduleIcon != null) {
                                val headerIconSizeResId = try {
                                    iconView.context.resources.getIdentifier("header_icon_size", "dimen", "com.android.settings")
                                } catch (t: Throwable) {
                                    0
                                }
                                val size = if (headerIconSizeResId != 0) {
                                    iconView.context.resources.getDimensionPixelSize(headerIconSizeResId)
                                } else {
                                    val density = iconView.context.resources.displayMetrics.density
                                    (24 * density).toInt()
                                }

                                // Enforce exact dimension limits on ImageView to prevent overflow
                                iconView.layoutParams = iconView.layoutParams?.apply {
                                    width = size
                                    height = size
                                }
                                iconView.scaleType = ImageView.ScaleType.FIT_CENTER

                                // Render the drawable onto a bitmap of exact size for a clean look
                                val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                moduleIcon.setBounds(0, 0, size, size)
                                moduleIcon.draw(canvas)
                                iconView.setImageBitmap(bitmap)
                            }
                        }
                        return@intercept null
                    }
                } catch (t: Throwable) {
                    // Ignore
                }
                chain.proceed()
            }
        }
    }
}
