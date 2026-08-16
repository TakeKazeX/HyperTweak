package com.takekazex.hypertweak.hook.rules.module

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.createBitmap
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.ResourceLookup
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import android.graphics.Bitmap
import android.graphics.Path

object SettingsHooker : StaticHooker() {
    private const val HEADER_ID = 10777L
    private val headerFieldCache = ConcurrentHashMap<Class<*>, Map<String, Field>>()
    private val headerConstructorCache = ConcurrentHashMap<Class<*>, Constructor<*>?>()
    private val iconBitmapCache = ConcurrentHashMap<Long, Bitmap>()

    /** `header_icon_size` dimen id, resolved once per process instead of per header bind. */
    @Volatile
    private var headerIconSizeResId = 0

    override fun onPrepareHotReload() {
        headerFieldCache.clear()
        headerConstructorCache.clear()
        iconBitmapCache.clear()
        headerIconSizeResId = 0
    }

    override fun onHook() {
        val clzMiuiSettings = resolveAppClass(
            "com.android.settings.MiuiSettings",
            mapOf("MiuiSettings" to { bridge ->
                bridge.findClass {
                    searchPackages("com.android.settings")
                    matcher { className("MiuiSettings", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        ) ?: return
        val clzHeaderAdapter = resolveAppClass(
            "com.android.settings.MiuiSettings\$HeaderAdapter",
            mapOf("HeaderAdapter" to { bridge ->
                bridge.findClass {
                    searchPackages("com.android.settings")
                    matcher { className("MiuiSettings\$HeaderAdapter", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        )
        val clzHeader = resolveAppClass(
            "com.android.settingslib.miuisettings.preference.PreferenceActivity\$Header",
            mapOf("PreferenceActivityHeader" to { bridge ->
                bridge.findClass {
                    searchPackages("com.android.settingslib.miuisettings.preference")
                    matcher { className("PreferenceActivity\$Header", StringMatchType.EndsWith) }
                }.singleOrNull()?.name
            })
        )

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
                    runCatching {
                        val iterator = list.iterator()
                        while (iterator.hasNext()) {
                            val head = iterator.next() ?: continue
                            val idField = headerField(head.javaClass, "id")
                            if (idField?.get(head) == HEADER_ID) {
                                iterator.remove()
                            }
                        }
                    }
                    return@after
                }

                try {
                    // Check if already injected
                    val alreadyInjected = list.any { head ->
                        head?.let { headerField(it.javaClass, "id")?.get(it) } == HEADER_ID
                    }
                    if (alreadyInjected) return@after

                    // Instantiate new Header object
                    val headerCtor = clzHeader?.let { type ->
                        headerConstructorCache.computeIfAbsent(type) {
                            runCatching { type.getDeclaredConstructor().apply { isAccessible = true } }.getOrNull()
                        }
                    }
                    val header = headerCtor?.newInstance()

                    if (header != null) {
                        headerField(header.javaClass, "id")?.set(header, HEADER_ID)

                        val intent = Intent().apply {
                            putExtra("isDisplayHomeAsUpEnabled", true)
                            setClassName("com.takekazex.hypertweak", "com.takekazex.hypertweak.MainActivity")
                        }
                        headerField(header.javaClass, "intent")?.set(header, intent)
                        headerField(header.javaClass, "title")?.set(header, "HyperTweak")
                        headerField(header.javaClass, "iconRes")?.set(header, 0)

                        val bundle = Bundle().apply {
                            val ctorUserHandle = UserHandle::class.java.getDeclaredConstructor(Int::class.java).apply { isAccessible = true }
                            val users = arrayListOf(ctorUserHandle.newInstance(0))
                            putParcelableArrayList("header_user", users)
                        }
                        header.javaClass.getDeclaredField("extras").apply { isAccessible = true }.set(header, bundle)

                        // Find "wifi_settings" and keep the module entry immediately before it.
                        var targetIndex = -1
                        val wifiSettingsId = try {
                            ResourceLookup.identifier(activity.resources, "wifi_settings", "id", "com.android.settings").toLong()
                        } catch (t: Throwable) {
                            0L
                        }

                        for (i in list.indices) {
                            val head = list[i] ?: continue
                            try {
                                val idField = headerField(head.javaClass, "id")
                                val id = (idField?.get(head) as? Number)?.toLong() ?: -1L
                                if (wifiSettingsId != 0L && id == wifiSettingsId) {
                                    targetIndex = i
                                    break
                                }

                                val intentField = headerField(head.javaClass, "intent")
                                val headIntent = intentField?.get(head) as? Intent
                                if (headIntent?.action == "android.settings.WIFI_SETTINGS" ||
                                    headIntent?.component?.className?.contains("WifiSettings", ignoreCase = true) == true) {
                                    targetIndex = i
                                    break
                                }
                            } catch (t: Throwable) {
                                // Ignore
                            }
                        }

                        list.add(SettingsHeaderPlacement.before(targetIndex, list.size), header)
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
                    val identifier = header?.let { headerField(it.javaClass, "id")?.get(it) } as? Long
                    if (identifier == HEADER_ID) {
                        val iconField = headerViewHolder?.let { headerField(it.javaClass, "icon") }
                        val iconView = iconField?.get(headerViewHolder) as? ImageView
                        if (iconView != null) {
                            iconView.visibility = View.VISIBLE
                            val moduleIcon = Icon.createWithResource("com.takekazex.hypertweak", R.mipmap.ic_launcher).loadDrawable(iconView.context)
                            if (moduleIcon != null) {
                                val headerIconSizeResId = resolveHeaderIconSizeResId(iconView.context)
                                val size = if (headerIconSizeResId != 0) {
                                    iconView.context.resources.getDimensionPixelSize(headerIconSizeResId)
                                } else {
                                    val density = iconView.context.resources.displayMetrics.density
                                    (24 * density).toInt()
                                }

                                // Keep the system icon slot width so this title aligns with
                                // every other header; only scale the bitmap inside that slot.
                                iconView.scaleType = ImageView.ScaleType.FIT_CENTER

                                // Render the drawable onto a bitmap of exact size for a clean look
                                val density = iconView.resources.displayMetrics.densityDpi
                                val cacheKey = (density.toLong() shl 32) or size.toLong()
                                val bitmap = iconBitmapCache.computeIfAbsent(cacheKey) {
                                    createMaskedIconBitmap(moduleIcon, size)
                                }
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

    private fun headerField(type: Class<*>, name: String): Field? {
        val fields = headerFieldCache.computeIfAbsent(type) {
            type.declaredFields.associateBy { field ->
                field.isAccessible = true
                field.name
            }
        }
        return fields[name]
    }

    private fun resolveHeaderIconSizeResId(context: android.content.Context): Int {
        var cached = headerIconSizeResId
        if (cached != 0) return cached
        cached = try {
            ResourceLookup.identifier(context.resources, "header_icon_size", "dimen", "com.android.settings")
        } catch (t: Throwable) {
            0
        }
        headerIconSizeResId = cached
        return cached
    }

    private fun createMaskedIconBitmap(drawable: android.graphics.drawable.Drawable, size: Int): Bitmap {
        return createBitmap(size, size).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap)
            val mask = Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(mask)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            canvas.restore()
        }
    }
}
