package com.takekazex.hypertweak.hook.rules.settings

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.net.LinkProperties
import android.net.StaticIpConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.runtime.settings.HookSettingsSnapshots
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.wifi.WifiIpAddressText
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.Inet6Address
import com.takekazex.hypertweak.util.platform.ResourceLookup

object WifiDetailsHooker : StaticHooker() {
    private const val TAG = "WifiDetailsHooker"

    private const val TAG_CUSTOM_ACTIONS = "hypertweak_saved_actions"
    private const val TAG_SHARE_BTN = "hypertweak_share_btn"
    private const val TAG_VIEW_PWD_BTN = "hypertweak_view_pwd_btn"
    private const val PREF_KEY_SAVED_WIFI_ENTRY = "hypertweak_saved_wifi_entry"

    private var savedNetworksEntry: SavedNetworksEntry? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val settingWifiDetailIpMultiline by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_WIFI_DETAIL_IP_MULTILINE, false)
    }

    private val settingWifiSavedNetworksEntryPosition by hookSetting(0) {
        Preferences.getInt(
            PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORKS_ENTRY_POSITION,
            PlatformSettingsKeys.WIFI_SAVED_NETWORKS_POSITION_DEFAULT
        )
    }

    private val settingWifiSavedNetworkCustomActions by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORK_CUSTOM_ACTIONS, false)
    }

    fun refreshSettings(keys: Set<String>): Boolean {
        HookSettingsSnapshots.refresh()
        if (keys.isEmpty() || PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORKS_ENTRY_POSITION in keys) {
            mainHandler.post { savedNetworksEntry?.refresh(settingWifiSavedNetworksEntryPosition) }
        }
        return true
    }

    override fun onHook() {
        hookSavedNetworksEntryRelocation()
        hookIpMultiline()
        hookPasswordShare()
        hookSavedAccessPointButtons()
    }

    private fun getSafeString(
        context: Context,
        resId: Int,
        fallbackZh: String,
        fallbackEn: String
    ): String {
        val moduleContext = runCatching {
            context.createPackageContext("com.takekazex.hypertweak", 0)
        }.getOrNull()
        if (moduleContext != null) {
            val str = runCatching { moduleContext.getString(resId) }.getOrNull()
            if (!str.isNullOrBlank()) return str
        }
        val locale = runCatching { context.resources.configuration.locales[0] }.getOrNull()
        val isZh = locale?.language == "zh"
        return if (isZh) fallbackZh else fallbackEn
    }

    private fun hookIpMultiline() {
        // 1. Hook formatIpAddresses in MiuiWifiDetailFragment to include all IPv4 and IPv6 addresses
        val detailFragmentClass = "com.android.settings.wifi.MiuiWifiDetailFragment".toClassOrNull()
        if (detailFragmentClass != null) {
            val formatIpMethod = detailFragmentClass.declaredMethods.firstOrNull {
                it.name == "formatIpAddresses" && it.parameterTypes.size == 2
            }?.apply { isAccessible = true }

            if (formatIpMethod != null) {
                deoptimize(formatIpMethod)
                formatIpMethod.hook("wifi_detail_format_ip_addresses") {
                    after { param ->
                        if (!settingWifiDetailIpMultiline) return@after
                        val linkProperties = param.args[0] as? LinkProperties
                        val staticConfig = param.args[1] as? StaticIpConfiguration
                        val ipv4List = mutableListOf<String>()
                        val ipv6List = mutableListOf<String>()

                        if (linkProperties != null) {
                            for (linkAddress in linkProperties.linkAddresses) {
                                val addr = linkAddress.address
                                if (addr is Inet4Address) {
                                    addr.hostAddress?.let { ipv4List.add(it) }
                                } else if (addr is Inet6Address) {
                                    addr.hostAddress?.let { ipv6List.add(it) }
                                }
                            }
                        }
                        val staticAddr = staticConfig?.ipAddress?.address
                        if (staticAddr is Inet4Address) {
                            staticAddr.hostAddress?.let { ipv4List.add(it) }
                        }
                        if (ipv4List.isNotEmpty() || ipv6List.isNotEmpty()) {
                            val formatted = WifiIpAddressText.format(ipv4List, ipv6List)
                            if (formatted.isNotEmpty()) {
                                param.result = formatted
                            }
                        }
                    }
                }
            } else {
                DebugLog.hookSkipped(TAG, "MiuiWifiDetailFragment#formatIpAddresses", "method not found")
            }
        }

        // 2. Hook WifiDetailLinearLayout$2.run() to prevent setSingleLine() / marquee compression
        val runnableClass = "com.android.settings.wifi.WifiDetailLinearLayout\$2".toClassOrNull()
        if (runnableClass != null) {
            val runMethod = runnableClass.declaredMethods.firstOrNull {
                it.name == "run" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }

            if (runMethod != null) {
                deoptimize(runMethod)
                runMethod.hook("wifi_detail_summary_marquee_run") {
                    before { param ->
                        if (!settingWifiDetailIpMultiline) return@before
                        val target = param.thisObject
                        val summaryField = target.javaClass.declaredFields.firstOrNull {
                            TextView::class.java.isAssignableFrom(it.type)
                        }?.apply { isAccessible = true }
                        val summaryTv = summaryField?.get(target) as? TextView ?: return@before
                        val text = summaryTv.text
                        if (WifiIpAddressText.isIpList(text) || text?.contains('\n') == true) {
                            param.result = null
                            summaryTv.isSingleLine = false
                            summaryTv.maxLines = Int.MAX_VALUE
                            summaryTv.ellipsize = null
                        }
                    }
                }
            } else {
                DebugLog.hookSkipped(TAG, "WifiDetailLinearLayout\$2#run", "method not found")
            }
        }

        // 3. Hook WifiDetailLinearLayout.getBaseView(int) to attach click-to-copy handler
        val layoutClass = "com.android.settings.wifi.WifiDetailLinearLayout".toClassOrNull()
        if (layoutClass != null) {
            val getBaseViewMethod = layoutClass.declaredMethods.firstOrNull {
                it.name == "getBaseView" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.apply { isAccessible = true }

            if (getBaseViewMethod != null) {
                deoptimize(getBaseViewMethod)
                getBaseViewMethod.hook("wifi_detail_get_base_view") {
                    after { param ->
                        val view = param.result as? View ?: return@after
                        val summaryTv = view.findViewById<TextView>(android.R.id.text2) ?: return@after
                        val titleTv = view.findViewById<TextView>(android.R.id.text1)

                        val isIp = WifiIpAddressText.isIpList(summaryTv.text) ||
                            (titleTv?.text?.contains("IP", ignoreCase = true) == true)
                        if (!isIp) return@after

                        summaryTv.isClickable = true
                        summaryTv.isFocusable = true
                        summaryTv.setOnClickListener {
                            if (!settingWifiDetailIpMultiline) return@setOnClickListener
                            val addresses = WifiIpAddressText.parse(summaryTv.text)
                            if (addresses.isEmpty()) return@setOnClickListener

                            val context = view.context
                            val activity = context.findActivity() ?: return@setOnClickListener
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

                            if (addresses.size == 1) {
                                val address = addresses[0]
                                clipboard?.setPrimaryClip(ClipData.newPlainText("IP", address))
                                val toastTemplate = getSafeString(
                                    activity,
                                    R.string.wifi_detail_ip_copied,
                                    "已复制: %s",
                                    "Copied: %s"
                                )
                                Toast.makeText(
                                    activity,
                                    String.format(toastTemplate, address),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                showIpCopyDialog(activity, addresses, clipboard)
                            }
                        }
                    }
                }
            } else {
                DebugLog.hookSkipped(TAG, "WifiDetailLinearLayout#getBaseView", "method not found")
            }
        }
    }

    private fun hookPasswordShare() {
        // Intercept WifiShareFloatingActivity.onCreate to unmask preSharedKey (fixing asterisk *)
        val shareActivityClass = "com.android.settings.wifi.WifiShareFloatingActivity".toClassOrNull()
        if (shareActivityClass != null) {
            val onCreateMethod = shareActivityClass.declaredMethods.firstOrNull {
                it.name == "onCreate" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Bundle::class.java
            }?.apply { isAccessible = true }

            if (onCreateMethod != null) {
                deoptimize(onCreateMethod)
                onCreateMethod.hook("wifi_share_floating_activity_on_create") {
                    before { param ->
                        val activity = param.thisObject as? Activity ?: return@before
                        val intent = activity.intent ?: return@before
                        @Suppress("DEPRECATION")
                        val wifiConfig = intent.getParcelableExtra<WifiConfiguration>("extra_wifi_config")
                        if (wifiConfig != null) {
                            val unmasked = getUnmaskedWifiConfiguration(activity, wifiConfig)
                            intent.putExtra("extra_wifi_config", unmasked)
                        }
                    }
                    after { param ->
                        val activity = param.thisObject as? Activity ?: return@after
                        val wifiConfigField = activity.javaClass.declaredFields.firstOrNull {
                            it.name == "mWifiConfig" && it.type == WifiConfiguration::class.java
                        }?.apply { isAccessible = true }
                        val current = wifiConfigField?.get(activity) as? WifiConfiguration
                        if (current != null && (current.preSharedKey == null || current.preSharedKey == "*")) {
                            val unmasked = getUnmaskedWifiConfiguration(activity, current)
                            wifiConfigField.set(activity, unmasked)
                        }
                    }
                }
            } else {
                DebugLog.hookSkipped(TAG, "WifiShareFloatingActivity#onCreate", "method not found")
            }
        }

    }

    private fun hookSavedNetworksEntryRelocation() {
        runCatching {
            val host = checkNotNull("com.android.settings.wifi.MiuiWifiSettings".toClassOrNull())
            val entryClass = checkNotNull("com.android.settingslib.miuisettings.preference.Preference".toClassOrNull())
            val group = checkNotNull("androidx.preference.PreferenceGroup".toClassOrNull())
            val preference = checkNotNull("androidx.preference.Preference".toClassOrNull())
            val holder = checkNotNull("androidx.preference.PreferenceViewHolder".toClassOrNull())
            val entry = SavedNetworksEntry(host, entryClass, group, preference)
            val init = host.getDeclaredMethod("initUI").apply { isAccessible = true }
            val create = host.getDeclaredMethod("onCreate", Bundle::class.java)
            // initUI removes unavailable native anchors. Apply after it, before the adapter binds.
            deoptimize(create)
            deoptimize(init)
            init.hook("wifi_saved_entry_after_init") {
                after { param ->
                    val fragment = param.thisObject
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        entry.attach(fragment, settingWifiSavedNetworksEntryPosition)
                    } else {
                        mainHandler.post { entry.attach(fragment, settingWifiSavedNetworksEntryPosition) }
                    }
                }
            }
            val viewCreated = host.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
            viewCreated.hook("wifi_saved_entry_after_view_created") {
                after { param ->
                    val fragment = param.thisObject
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        entry.attach(fragment, settingWifiSavedNetworksEntryPosition)
                    } else {
                        mainHandler.post { entry.attach(fragment, settingWifiSavedNetworksEntryPosition) }
                    }
                }
            }
            val bind = entryClass.getDeclaredMethod("onBindViewHolder", holder)
            bind.hook("wifi_saved_entry_verify_binding") {
                after { param -> entry.bound(param.thisObject) }
            }
            savedNetworksEntry = entry
            DebugLog.i(TAG, "Saved networks shortcut hooks installed")
        }.onFailure { DebugLog.w(TAG, "Saved networks shortcut contract unavailable", it) }
    }

    private class SavedNetworksEntry(
        host: Class<*>,
        entryClass: Class<*>,
        group: Class<*>,
        preference: Class<*>
    ) {
        private val getScreen = host.getMethod("getPreferenceScreen")
        private val getView = host.getMethod("getView")
        private val getContext = host.getMethod("getContext")
        private val find = group.getMethod("findPreference", CharSequence::class.java)
        private val add = group.getMethod("addPreference", preference)
        private val remove = group.getMethod("removePreference", preference)
        private val parent = preference.getMethod("getParent")
        private val visible = preference.getMethod("isVisible")
        private val order = preference.getMethod("getOrder")
        private val setOrder = preference.getMethod("setOrder", Int::class.javaPrimitiveType)
        private val create = entryClass.getConstructor(Context::class.java)
        private val setKey = preference.getMethod("setKey", String::class.java)
        private val setTitle = preference.getMethod("setTitle", Int::class.javaPrimitiveType)
        private val setFragment = preference.getMethod("setFragment", String::class.java)
        private val setArrow = entryClass.getMethod("setShowRightArrow", Boolean::class.javaPrimitiveType)
        private val setPersistent = preference.getMethod("setPersistent", Boolean::class.javaPrimitiveType)
        private var fragment: WeakReference<Any>? = null
        private var pendingBind: WeakReference<Any>? = null

        fun attach(host: Any, position: Int) {
            fragment = WeakReference(host)
            apply(host, position)
        }

        fun refresh(position: Int) {
            val host = fragment?.get() ?: return
            runCatching {
                if (getView.invoke(host) != null) apply(host, position)
            }.onFailure { DebugLog.w(TAG, "Saved networks shortcut refresh failed", it) }
        }

        fun bound(preference: Any) {
            if (pendingBind?.get() !== preference) return
            pendingBind = null
            DebugLog.i(TAG, "Saved networks shortcut bound by Settings")
        }

        private fun apply(host: Any, position: Int) {
            runCatching {
                val screen = checkNotNull(getScreen.invoke(host))
                val existing = find.invoke(screen, PREF_KEY_SAVED_WIFI_ENTRY)
                if (position == PlatformSettingsKeys.WIFI_SAVED_NETWORKS_POSITION_DEFAULT) {
                    if (existing != null) {
                        check(remove.invoke(parent.invoke(existing), existing) == true)
                    }
                    DebugLog.i(TAG, "Saved networks shortcut position=default")
                    return
                }

                val advanced = find.invoke(screen, "wifi_settings")
                // Match the host's restriction on the advanced Wi-Fi page.
                if (advanced == null || visible.invoke(advanced) != true) {
                    DebugLog.i(TAG, "Saved networks shortcut skipped: advanced settings unavailable")
                    return
                }
                val common = find.invoke(screen, "wifi_settings_common_category")
                val acceleration = common?.let { find.invoke(it, "network_acceleration") }
                val below = position == PlatformSettingsKeys.WIFI_SAVED_NETWORKS_POSITION_BELOW_ACCELERATION &&
                    acceleration != null && visible.invoke(acceleration) == true
                val target = if (below) checkNotNull(common) else advanced
                val targetOrder = if (below) Math.addExact(order.invoke(acceleration) as Int, 1) else -1
                val oldParent = existing?.let { parent.invoke(it) }
                if (existing != null && oldParent === target && order.invoke(existing) == targetOrder) return

                val context = getContext.invoke(host) as? Context ?: return
                val title = ResourceLookup.identifier(context.resources, "saved_wifi", "string", context.packageName)
                check(title != 0) { "Saved network title unavailable" }
                val shortcut = create.newInstance(context)
                setKey.invoke(shortcut, PREF_KEY_SAVED_WIFI_ENTRY)
                setTitle.invoke(shortcut, title)
                setFragment.invoke(shortcut, "com.android.settings.wifi.MiuiSavedAccessPointsWifiSettings")
                setPersistent.invoke(shortcut, false)
                setArrow.invoke(shortcut, true)
                setOrder.invoke(shortcut, targetOrder)

                if (existing != null) check(remove.invoke(oldParent, existing) == true)
                pendingBind = WeakReference(shortcut)
                try {
                    check(add.invoke(target, shortcut) == true)
                } catch (error: Throwable) {
                    pendingBind = null
                    if (parent.invoke(shortcut) === target) remove.invoke(target, shortcut)
                    if (existing != null) add.invoke(oldParent, existing)
                    throw error
                }
                DebugLog.i(TAG, "Saved networks shortcut inserted position=$position belowAcceleration=$below")
            }.onFailure { DebugLog.w(TAG, "Saved networks shortcut failed at position=$position", it) }
        }
    }

    private fun hookSavedAccessPointButtons() {
        val savedPrefClass = "com.android.settings.wifi.SavedAccessPointPreference".toClassOrNull() ?: run { DebugLog.hookSkipped("WifiDetailsHooker", "com.android.settings.wifi.SavedAccessPointPreference", "not found on host"); return }

        val onBindMethod = savedPrefClass.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }

        if (onBindMethod != null) {
            deoptimize(onBindMethod)
            onBindMethod.hook("wifi_saved_point_on_bind") {
                after { param ->
                    val preference = param.thisObject
                    val holder = param.args[0] ?: return@after
                    val view = runCatching { holder.javaClass.getField("itemView").get(holder) as? View }.getOrNull()
                        ?: return@after

                    val btnDeleteId = ResourceLookup.identifier(view.context.resources, "btn_delete", "id", "com.android.settings")
                    val checkboxId = ResourceLookup.identifier(view.context.resources, "checkbox", "id", "com.android.settings")

                    val btnDelete = if (btnDeleteId != 0) view.findViewById<View>(btnDeleteId) else null
                    val checkbox = if (checkboxId != 0) view.findViewById<View>(checkboxId) else null

                    val inActionModeField = preference.javaClass.declaredFields.firstOrNull {
                        it.type == Boolean::class.javaPrimitiveType && it.name.contains("Acti", ignoreCase = true)
                    }?.apply { isAccessible = true }
                    val inActionMode = inActionModeField?.getBoolean(preference) ?: false

                    val container = btnDelete?.parent as? ViewGroup ?: return@after
                    val customActions = container.findViewWithTag<View>(TAG_CUSTOM_ACTIONS)

                    if (!settingWifiSavedNetworkCustomActions) {
                        customActions?.visibility = View.GONE
                        if (!inActionMode) {
                            btnDelete.visibility = View.VISIBLE
                            checkbox?.visibility = View.GONE
                        }
                        return@after
                    }

                    if (inActionMode) {
                        customActions?.visibility = View.GONE
                        btnDelete.visibility = View.GONE
                        checkbox?.visibility = View.VISIBLE
                    } else {
                        btnDelete.visibility = View.GONE
                        checkbox?.visibility = View.GONE
                        val layout = findOrCreateCustomActionsLayout(container, preference)
                        layout.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            DebugLog.hookSkipped(TAG, "SavedAccessPointPreference#onBindViewHolder", "method not found")
        }

        val setActionModeMethod = savedPrefClass.declaredMethods.firstOrNull {
            it.name == "setActionMode" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }?.apply { isAccessible = true }

        if (setActionModeMethod != null) {
            deoptimize(setActionModeMethod)
            setActionModeMethod.hook("wifi_saved_point_set_action_mode") {
                after { param ->
                    if (!settingWifiSavedNetworkCustomActions) return@after
                    val preference = param.thisObject
                    val inActionMode = param.args[0] as? Boolean ?: false

                    val viewField = preference.javaClass.declaredFields.firstOrNull {
                        it.name == "mView" && View::class.java.isAssignableFrom(it.type)
                    }?.apply { isAccessible = true }
                    val view = viewField?.get(preference) as? View ?: return@after

                    val btnDeleteId = ResourceLookup.identifier(view.context.resources, "btn_delete", "id", "com.android.settings")
                    val checkboxId = ResourceLookup.identifier(view.context.resources, "checkbox", "id", "com.android.settings")
                    val btnDelete = if (btnDeleteId != 0) view.findViewById<View>(btnDeleteId) else null
                    val checkbox = if (checkboxId != 0) view.findViewById<View>(checkboxId) else null

                    val container = btnDelete?.parent as? ViewGroup ?: return@after
                    val customActions = container.findViewWithTag<View>(TAG_CUSTOM_ACTIONS)

                    if (inActionMode) {
                        customActions?.visibility = View.GONE
                        btnDelete.visibility = View.GONE
                        checkbox?.visibility = View.VISIBLE
                    } else {
                        btnDelete.visibility = View.GONE
                        checkbox?.visibility = View.GONE
                        val layout = findOrCreateCustomActionsLayout(container, preference)
                        layout.visibility = View.VISIBLE
                    }
                }
            }
        } else {
            DebugLog.hookSkipped(TAG, "SavedAccessPointPreference#setActionMode", "method not found")
        }
    }

    private fun findOrCreateCustomActionsLayout(
        container: ViewGroup,
        preference: Any
    ): View {
        val existing = container.findViewWithTag<View>(TAG_CUSTOM_ACTIONS)
        if (existing != null) {
            updateCustomActionsClickListeners(existing, preference)
            return existing
        }

        val context = container.context
        val density = context.resources.displayMetrics.density

        val layout = LinearLayout(context).apply {
            tag = TAG_CUSTOM_ACTIONS
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val inlineStyle = ResourceLookup.identifier(context.resources, "Widget.Button.Inline", "style", context.packageName)
        val shareIcon = ResourceLookup.identifier(context.resources, "ic_share", "drawable", context.packageName)
        val shareLabel = ResourceLookup.identifier(context.resources, "share", "string", context.packageName)
        val buttonTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(0x330d84ff, 0x180d84ff)
        )
        val viewPwdBtn = Button(context, null, if (inlineStyle == 0) android.R.attr.buttonStyle else 0, inlineStyle).apply {
            tag = TAG_VIEW_PWD_BTN
            text = getSafeString(context, R.string.wifi_view_password_btn, "查看密码", "View password")
            minHeight = dp(36)
            minWidth = 0
            minimumWidth = 0
            stateListAnimator = null
            backgroundTintList = buttonTint
            setPaddingRelative(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        val shareBtn = ImageButton(context).apply {
            tag = TAG_SHARE_BTN
            contentDescription = if (shareLabel != 0) context.getText(shareLabel) else
                if (context.resources.configuration.locales[0].language == "zh") "分享" else "Share"
            if (shareIcon != 0) setImageResource(shareIcon)
            imageTintList = viewPwdBtn.textColors
            background = viewPwdBtn.background?.constantState?.newDrawable(context.resources, context.theme)?.mutate()
            backgroundTintList = buttonTint
            stateListAnimator = null
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(6) }
        }

        layout.addView(shareBtn)
        layout.addView(viewPwdBtn)
        container.addView(layout, 0)

        updateCustomActionsClickListeners(layout, preference)
        return layout
    }

    private fun updateCustomActionsClickListeners(layout: View, preference: Any) {
        val shareBtn = layout.findViewWithTag<View>(TAG_SHARE_BTN)
        val viewPwdBtn = layout.findViewWithTag<View>(TAG_VIEW_PWD_BTN)
        val context = layout.context

        shareBtn?.setOnClickListener {
            val wifiEntry = runCatching {
                preference.javaClass.getMethod("getWifiEntry").invoke(preference)
            }.getOrNull() ?: return@setOnClickListener

            var wifiConfig = runCatching {
                wifiEntry.javaClass.methods.firstOrNull {
                    it.name == "getWifiConfiguration" && it.parameterTypes.isEmpty()
                }?.invoke(wifiEntry) as? WifiConfiguration
            }.getOrNull()

            if (wifiConfig == null) {
                val title = runCatching {
                    wifiEntry.javaClass.methods.firstOrNull {
                        it.name == "getTitle" && it.parameterTypes.isEmpty()
                    }?.invoke(wifiEntry) as? String
                }.getOrNull()
                if (!title.isNullOrEmpty()) {
                    wifiConfig = WifiConfiguration().apply {
                        SSID = "\"$title\""
                    }
                }
            }

            if (wifiConfig != null) {
                val activity = context.findActivity() ?: context
                launchWifiShareSheet(activity, wifiConfig)
            }
        }

        viewPwdBtn?.setOnClickListener {
            val wifiEntry = runCatching {
                preference.javaClass.getMethod("getWifiEntry").invoke(preference)
            }.getOrNull() ?: return@setOnClickListener

            val activity = context.findActivity() ?: return@setOnClickListener
            val password = getPasswordForWifiEntry(context, wifiEntry)
            showViewPasswordDialog(activity, wifiEntry, password)
        }
    }

    private fun getPrivilegedNetworks(wifiManager: WifiManager?): List<WifiConfiguration>? {
        if (wifiManager == null) return null
        return runCatching {
            val method = wifiManager.javaClass.getMethod("getPrivilegedConfiguredNetworks")
            @Suppress("UNCHECKED_CAST")
            method.invoke(wifiManager) as? List<WifiConfiguration>
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun getUnmaskedWifiConfiguration(context: Context, config: WifiConfiguration): WifiConfiguration {
        val curKey = config.preSharedKey
        if (!curKey.isNullOrEmpty() && curKey != "*") {
            return config
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return config
        val privilegedList = getPrivilegedNetworks(wifiManager)
        if (privilegedList.isNullOrEmpty()) {
            return config
        }

        val configSsidClean = config.SSID?.removePrefix("\"")?.removeSuffix("\"")
        val matched = (if (config.networkId != -1) {
            privilegedList.firstOrNull { it.networkId == config.networkId }
        } else null) ?: privilegedList.firstOrNull { priv ->
            val privSsidClean = priv.SSID?.removePrefix("\"")?.removeSuffix("\"")
            !configSsidClean.isNullOrEmpty() && privSsidClean == configSsidClean
        }

        if (matched != null) {
            val realKey = if (matched.allowedKeyManagement.get(0) && matched.allowedAuthAlgorithms.get(1)) {
                matched.wepKeys?.getOrNull(matched.wepTxKeyIndex) ?: matched.preSharedKey
            } else {
                matched.preSharedKey
            }
            if (!realKey.isNullOrEmpty() && realKey != "*") {
                config.preSharedKey = realKey
            }
        }
        return config
    }

    @Suppress("DEPRECATION")
    private fun getPasswordForWifiEntry(context: Context, wifiEntry: Any): String {
        val wifiConfig = runCatching {
            wifiEntry.javaClass.methods.firstOrNull {
                it.name == "getWifiConfiguration" && it.parameterTypes.isEmpty()
            }?.invoke(wifiEntry) as? WifiConfiguration
        }.getOrNull()

        if (wifiConfig != null) {
            val unmasked = getUnmaskedWifiConfiguration(context, wifiConfig)
            val key = unmasked.preSharedKey
            if (!key.isNullOrEmpty() && key != "*") {
                return key.removePrefix("\"").removeSuffix("\"")
            }
        }

        val title = runCatching {
            wifiEntry.javaClass.methods.firstOrNull {
                it.name == "getTitle" && it.parameterTypes.isEmpty()
            }?.invoke(wifiEntry) as? String
        }.getOrNull()

        if (!title.isNullOrEmpty()) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val privilegedList = getPrivilegedNetworks(wifiManager)
            val matched = privilegedList?.firstOrNull {
                val ssidClean = it.SSID?.removePrefix("\"")?.removeSuffix("\"")
                ssidClean == title
            }
            if (matched != null) {
                val realKey = if (matched.allowedKeyManagement.get(0) && matched.allowedAuthAlgorithms.get(1)) {
                    matched.wepKeys?.getOrNull(matched.wepTxKeyIndex) ?: matched.preSharedKey
                } else {
                    matched.preSharedKey
                }
                if (!realKey.isNullOrEmpty() && realKey != "*") {
                    return realKey.removePrefix("\"").removeSuffix("\"")
                }
            }
        }
        return ""
    }

    private fun showViewPasswordDialog(activity: Activity, wifiEntry: Any, password: String) {
        val ssid = runCatching {
            wifiEntry.javaClass.methods.firstOrNull {
                it.name == "getTitle" && it.parameterTypes.isEmpty()
            }?.invoke(wifiEntry) as? String
        }.getOrNull() ?: ""

        val security = runCatching {
            wifiEntry.javaClass.methods.firstOrNull {
                it.name == "getSecurityString" && it.parameterTypes.size == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.invoke(wifiEntry, false) as? String
        }.getOrNull()

        val ssidLabel = getSafeString(activity, R.string.wifi_view_password_ssid, "网络名称：", "SSID: ")
        val secLabel = getSafeString(activity, R.string.wifi_view_password_security, "安全类型：", "Security: ")
        val pwdLabel = getSafeString(activity, R.string.wifi_view_password_pwd, "密码：", "Password: ")
        val noPwdText = getSafeString(activity, R.string.wifi_view_password_no_pwd, "（无密码）", "(No password)")
        val copyBtnText = getSafeString(activity, R.string.wifi_view_password_copy, "复制密码", "Copy password")
        val closeBtnText = getSafeString(activity, R.string.wifi_view_password_close, "关闭", "Close")

        val msg = buildString {
            append(ssidLabel).append(ssid).append("\n")
            if (!security.isNullOrBlank()) {
                append(secLabel).append(security).append("\n")
            }
            append(pwdLabel).append(if (password.isNotEmpty()) password else noPwdText)
        }

        val miuixAlertClass = "miuix.appcompat.app.AlertDialog\$Builder".toClassOrNull()
        if (miuixAlertClass != null) {
            try {
                val builder = miuixAlertClass.getConstructor(Context::class.java).newInstance(activity)
                val setTitle = miuixAlertClass.getMethod("setTitle", CharSequence::class.java)
                val setMessage = miuixAlertClass.getMethod("setMessage", CharSequence::class.java)
                val setPositive = miuixAlertClass.getMethod(
                    "setPositiveButton",
                    CharSequence::class.java,
                    DialogInterface.OnClickListener::class.java
                )
                val setNegative = miuixAlertClass.getMethod(
                    "setNegativeButton",
                    CharSequence::class.java,
                    DialogInterface.OnClickListener::class.java
                )
                val create = miuixAlertClass.getMethod("create")

                setTitle.invoke(builder, ssid.ifEmpty { "Wi-Fi" })
                setMessage.invoke(builder, msg)

                val copyListener = DialogInterface.OnClickListener { dialog, _ ->
                    if (password.isNotEmpty()) {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Wi-Fi Password", password))
                        val copiedToast = getSafeString(activity, R.string.wifi_view_password_copied, "密码已复制", "Password copied")
                        Toast.makeText(activity, copiedToast, Toast.LENGTH_SHORT).show()
                    } else {
                        val noPwdToast = getSafeString(activity, R.string.wifi_view_password_no_pwd_toast, "该网络无密码", "No password for this network")
                        Toast.makeText(activity, noPwdToast, Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                val closeListener = DialogInterface.OnClickListener { dialog, _ -> dialog.dismiss() }

                setPositive.invoke(builder, copyBtnText, copyListener)
                setNegative.invoke(builder, closeBtnText, closeListener)
                val dialog = create.invoke(builder) as? Dialog
                dialog?.show()
                return
            } catch (e: Throwable) {
                DebugLog.w(TAG, "Failed to show miuix dialog, falling back to framework AlertDialog", e)
            }
        }

        android.app.AlertDialog.Builder(activity)
            .setTitle(ssid.ifEmpty { "Wi-Fi" })
            .setMessage(msg)
            .setPositiveButton(copyBtnText) { dialog, _ ->
                if (password.isNotEmpty()) {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Wi-Fi Password", password))
                    val copiedToast = getSafeString(activity, R.string.wifi_view_password_copied, "密码已复制", "Password copied")
                    Toast.makeText(activity, copiedToast, Toast.LENGTH_SHORT).show()
                } else {
                    val noPwdToast = getSafeString(activity, R.string.wifi_view_password_no_pwd_toast, "该网络无密码", "No password for this network")
                    Toast.makeText(activity, noPwdToast, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(closeBtnText) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showIpCopyDialog(
        activity: Activity,
        addresses: List<String>,
        clipboard: ClipboardManager?
    ) {
        val copyAllText = getSafeString(activity, R.string.wifi_detail_ip_copy_all, "复制全部地址", "Copy all addresses")
        val dialogTitle = getSafeString(activity, R.string.wifi_detail_ip_dialog_title, "选择要复制的 IP 地址", "Select IP address to copy")
        val displayItems = (addresses + copyAllText).toTypedArray<CharSequence>()

        val miuixAlertClass = "miuix.appcompat.app.AlertDialog\$Builder".toClassOrNull()
        if (miuixAlertClass != null) {
            try {
                val builder = miuixAlertClass.getConstructor(Context::class.java).newInstance(activity)
                val setTitle = miuixAlertClass.getMethod("setTitle", CharSequence::class.java)
                val setItems = miuixAlertClass.getMethod(
                    "setItems",
                    Array<CharSequence>::class.java,
                    DialogInterface.OnClickListener::class.java
                )
                val create = miuixAlertClass.getMethod("create")

                setTitle.invoke(builder, dialogTitle)
                val listener = DialogInterface.OnClickListener { dialog, which ->
                    if (which in addresses.indices) {
                        val selected = addresses[which]
                        clipboard?.setPrimaryClip(ClipData.newPlainText("IP", selected))
                        val toastTemplate = getSafeString(activity, R.string.wifi_detail_ip_copied, "已复制: %s", "Copied: %s")
                        Toast.makeText(
                            activity,
                            String.format(toastTemplate, selected),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (which == addresses.size) {
                        val allText = addresses.joinToString("\n")
                        clipboard?.setPrimaryClip(ClipData.newPlainText("IP", allText))
                        val toastAll = getSafeString(activity, R.string.wifi_detail_ip_copied_all, "已复制全部 IP 地址", "All IP addresses copied")
                        Toast.makeText(
                            activity,
                            toastAll,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    dialog.dismiss()
                }
                setItems.invoke(builder, displayItems, listener)
                val dialog = create.invoke(builder) as? Dialog
                dialog?.show()
                return
            } catch (e: Throwable) {
                DebugLog.w(TAG, "Failed to show miuix dialog, falling back to framework AlertDialog", e)
            }
        }

        android.app.AlertDialog.Builder(activity)
            .setTitle(dialogTitle)
            .setItems(displayItems) { dialog, which ->
                if (which in addresses.indices) {
                    val selected = addresses[which]
                    clipboard?.setPrimaryClip(ClipData.newPlainText("IP", selected))
                    val toastTemplate = getSafeString(activity, R.string.wifi_detail_ip_copied, "已复制: %s", "Copied: %s")
                    Toast.makeText(
                        activity,
                        String.format(toastTemplate, selected),
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (which == addresses.size) {
                    val allText = addresses.joinToString("\n")
                    clipboard?.setPrimaryClip(ClipData.newPlainText("IP", allText))
                    val toastAll = getSafeString(activity, R.string.wifi_detail_ip_copied_all, "已复制全部 IP 地址", "All IP addresses copied")
                    Toast.makeText(
                        activity,
                        toastAll,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun launchWifiShareSheet(context: Context, wifiConfig: WifiConfiguration) {
        try {
            val unmasked = getUnmaskedWifiConfiguration(context, wifiConfig)
            val intent = Intent()
            intent.setClassName(context.packageName, "com.android.settings.wifi.WifiShareFloatingActivity")
            intent.putExtra("extra_wifi_config", unmasked)
            intent.putExtra("extra_near_share_enable", false)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            DebugLog.w(TAG, "Failed to launch WifiShareFloatingActivity", e)
        }
    }

    private fun Context.findActivity(): Activity? {
        var ctx: Context? = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
