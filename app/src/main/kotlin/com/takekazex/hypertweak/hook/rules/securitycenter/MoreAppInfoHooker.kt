package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostActivityRefresh
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.WeakHashMap
import kotlin.coroutines.EmptyCoroutineContext

/** Extends the native App information page using its already user-scoped PackageInfo. */
object MoreAppInfoHooker : DynamicHooker() {
    private const val TAG = "MoreAppInfo"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val ACTIVITY = "com.miui.appmanager.AMAppInfomationActivity"
    private const val FRAGMENT = "com.miui.appmanager.fragment.AMAppInformationFragment"
    private const val CATEGORY = "category_app_infomation"
    private const val EXTRA_PAGE = "com.takekazex.hypertweak.APP_INFO_PAGE"
    private const val SIGNATURES = "signatures"
    private const val APK_PARTS = "apk_parts"

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_MORE_INFO, false)
    }

    private data class AddedRows(val remove: Method, val preferences: List<WeakReference<Any>>) {
        fun clear(parent: Any) {
            preferences.forEach { reference -> reference.get()?.let { remove.invoke(parent, it) } }
        }
    }

    private data class LongClick(val title: String, val text: String)
    private data class OriginalLongClick(val listener: View.OnLongClickListener?, val clickable: Boolean) {
        fun restore(view: View) {
            view.setOnLongClickListener(listener)
            view.isLongClickable = clickable
        }
    }

    private val copyRows = generationMainResource(WeakHashMap<Any, LongClick>()) { it.clear() }
    private val copyViews = generationMainResource(WeakHashMap<View, OriginalLongClick>()) { views ->
        views.entries.toList().forEach { (view, original) -> original.restore(view) }
        views.clear()
    }
    private val added = generationMainResource(WeakHashMap<Any, AddedRows>()) { groups ->
        groups.entries.toList().forEach { (parent, rows) ->
            HookFailurePolicy.open(TAG, "remove information rows", Unit) { rows.clear(parent) }
        }
        groups.clear()
    }

    private val subpages = generationMainResource(WeakHashMap<Activity, Unit>()) { pages ->
        pages.keys.toList().forEach { activity ->
            HookFailurePolicy.open(TAG, "close information subpage", Unit) {
                if (!activity.isDestroyed && !activity.isFinishing) activity.finish()
            }
        }
        pages.clear()
    }

    private data class Targets(
        val fragmentType: Class<*>,
        val activityType: Class<*>,
        val packageInfo: Field,
        val getActivity: Method,
        val getView: Method,
        val getFragmentManager: Method,
        val findFragment: Method,
        val findPreference: Method,
        val getPreferenceManager: Method,
        val setPreferenceScreen: Method,
        val attachScreen: Method,
        val categoryType: Class<*>,
        val category: Constructor<*>,
        val screen: Constructor<*>,
        val preference: Constructor<*>,
        val readOnlyPreference: Constructor<*>,
        val setKey: Method,
        val setTitle: Method,
        val setSummary: Method,
        val setText: Method,
        val setOrder: Method,
        val setSelectable: Method,
        val setListener: Method,
        val add: Method,
        val remove: Method,
        val actionBar: Method,
        val setActionBarTitle: Method,
    )

    private var installed by generationState<Targets?> { null }

    override fun onHook() {
        if (hookParam.processName != PACKAGE) return
        HookFailurePolicy.open(TAG, "resolve app information", Unit) {
            val fragment = FRAGMENT.toClassOrNull() ?: return@open
            val activity = ACTIVITY.toClassOrNull() ?: return@open
            val baseFragment = "androidx.fragment.app.Fragment".toClassOrNull() ?: return@open
            val preference = "androidx.preference.Preference".toClassOrNull() ?: return@open
            val category = "androidx.preference.PreferenceCategory".toClassOrNull() ?: return@open
            val miuixCategory = "miuix.preference.PreferenceCategory".toClassOrNull() ?: return@open
            val textPreference = "miuix.preference.TextPreference".toClassOrNull() ?: return@open
            val readOnly = "com.miui.permcenter.permissions.NoClickTextPreference".toClassOrNull()
                ?.takeIf(textPreference::isAssignableFrom) ?: return@open
            val screen = "androidx.preference.PreferenceScreen".toClassOrNull() ?: return@open
            val create = HostAccess.requireDeclaredMethod(
                fragment, "onCreatePreferences", Bundle::class.java, String::class.java, accessible = false,
            )
            val getManager = method(activity, "getSupportFragmentManager")
            val findFragment = getManager.returnType.methods.singleOrNull {
                it.returnType == baseFragment && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            } ?: return@open
            val info = fragment.declaredFields.singleOrNull { it.type == PackageInfo::class.java } ?: return@open
            val setListener = preference.methods.singleOrNull { candidate ->
                candidate.name == "setOnPreferenceClickListener" && candidate.parameterCount == 1 &&
                    candidate.parameterTypes.single().methods.any {
                        it.name == "onPreferenceClick" && it.returnType == Boolean::class.javaPrimitiveType &&
                            it.parameterTypes.contentEquals(arrayOf(preference))
                    }
            } ?: return@open
            val prefsManager = method(fragment, "getPreferenceManager")
            val textBind = textPreference.declaredMethods.singleOrNull {
                it.name == "onBindViewHolder" && it.parameterCount == 1 && it.returnType == Void.TYPE
            } ?: return@open
            val holder = textBind.parameterTypes.single()
            val itemView = holder.getField("itemView").takeIf { it.type == View::class.java } ?: return@open
            val baseBind = HostAccess.requireDeclaredMethod(preference, "onBindViewHolder", holder, accessible = false)
            val listenerInfo = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }
            val longListener = listenerInfo.type.getDeclaredField("mOnLongClickListener").apply { isAccessible = true }
            val miuixFragment = "miuix.preference.PreferenceFragment".toClassOrNull()
                ?.takeIf { it.isAssignableFrom(fragment) } ?: return@open
            val actionBar = method(activity, "getAppCompatActionBar")
            val createList = HostAccess.requireDeclaredMethod(
                miuixFragment, "onCreateRecyclerView", LayoutInflater::class.java, ViewGroup::class.java,
                Bundle::class.java, accessible = false,
            )
            val fragmentOverlay = miuixFragment.getDeclaredField("mIsOverlayMode")
                .takeIf { it.type == Boolean::class.javaPrimitiveType }?.apply { isAccessible = true } ?: return@open
            val overlayType = "miuix.appcompat.internal.app.widget.ActionBarOverlayLayout".toClassOrNull()
                ?: return@open
            val setOverlay = method(overlayType, "setOverlayMode", Boolean::class.javaPrimitiveType)
            val setOuterNestedScroll = method(overlayType, "setNestedScrollingParentEnabled", Boolean::class.javaPrimitiveType)
            val setResizable = method(actionBar.returnType, "setResizable", Boolean::class.javaPrimitiveType)
            val setExpandState = method(
                actionBar.returnType, "setExpandState", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
            val targets = Targets(
                fragment, activity, info.apply { isAccessible = true }, method(fragment, "getActivity"),
                method(fragment, "getView"),
                getManager, findFragment, method(fragment, "findPreference", CharSequence::class.java),
                prefsManager, method(fragment, "setPreferenceScreen", screen),
                HostAccess.requireDeclaredMethod(preference, "onAttachedToHierarchy", prefsManager.returnType, accessible = true),
                category, miuixCategory.getConstructor(Context::class.java, AttributeSet::class.java),
                screen.getConstructor(Context::class.java, AttributeSet::class.java),
                textPreference.getConstructor(Context::class.java), readOnly.getConstructor(Context::class.java),
                method(preference, "setKey", String::class.java),
                method(preference, "setTitle", CharSequence::class.java),
                method(preference, "setSummary", CharSequence::class.java),
                method(textPreference, "setText", String::class.java),
                method(preference, "setOrder", Int::class.javaPrimitiveType),
                method(preference, "setSelectable", Boolean::class.javaPrimitiveType), setListener,
                method(category, "addPreference", preference), method(category, "removePreference", preference),
                actionBar, method(actionBar.returnType, "setTitle", CharSequence::class.java),
            )
            installDexContract {
                baseBind.hook {
                    before { call ->
                        if (copyViews.isEmpty()) return@before
                        HookFailurePolicy.open(TAG, "restore recycled row", Unit) {
                            val view = itemView.get(call.args[0]) as? View ?: return@open
                            copyViews.remove(view)?.restore(view)
                        }
                    }
                }
                textBind.hook {
                    after { call -> HookFailurePolicy.open(TAG, "bind row long press", Unit) {
                        val copy = copyRows[call.thisObject]
                        if (copy == null && copyViews.isEmpty()) return@open
                        val view = itemView.get(call.args[0]) as? View ?: return@open
                        copyViews.remove(view)?.restore(view)
                        if (copy == null) return@open
                        val previous = listenerInfo.get(view)?.let { longListener.get(it) } as? View.OnLongClickListener
                        copyViews[view] = OriginalLongClick(previous, view.isLongClickable)
                        view.setOnLongClickListener { pressed -> enabled && copy(pressed.context, copy) }
                    } }
                }
                create.hook {
                    after { call -> HookFailurePolicy.open(TAG, "bind app information", Unit) {
                        bind(targets, call.thisObject)
                    } }
                }
                createList.hook {
                    before { call -> HookFailurePolicy.open(TAG, "set information subpage layout", Unit) {
                        val instance = call.thisObject
                        if (subpages.isEmpty() || !targets.fragmentType.isInstance(instance)) return@open
                        val host = targets.getActivity.invoke(instance) as? Activity ?: return@open
                        if (host !in subpages) return@open
                        val content = host.findViewById<View>(android.R.id.content) ?: return@open
                        val overlay = generateSequence(content) {
                            it.parent as? View
                        }.firstOrNull(overlayType::isInstance) ?: return@open
                        val bar = actionBar.invoke(host) ?: return@open
                        // Use the native list-only scrollbar and measure content below the compact bar.
                        setExpandState.invoke(bar, 0, false)
                        setResizable.invoke(bar, false)
                        setOverlay.invoke(overlay, false)
                        setOuterNestedScroll.invoke(overlay, false)
                        fragmentOverlay.setBoolean(instance, false)
                        overlay.requestLayout()
                    } }
                }
            }
            installed = targets
            refreshSettings()
            DebugLog.hookRegistered(TAG, "Miuix app information and subpages")
        }
    }

    private fun method(type: Class<*>, name: String, vararg parameters: Class<*>?) =
        HostAccess.requirePublicMethod(type, name, *parameters, accessible = false)

    fun refreshSettings() = HostAccess.onMain {
        val targets = installed ?: return@onMain
        HookFailurePolicy.open(TAG, "refresh app information", Unit) {
            if (!enabled) {
                copyViews.entries.toList().forEach { (view, original) -> original.restore(view) }
                copyViews.clear()
                copyRows.clear()
                added.entries.toList().forEach { (parent, rows) -> rows.clear(parent) }
                added.clear()
                HostActivityRefresh.activities().filter { it.javaClass == targets.activityType }
                    .filter { it.intent?.getStringExtra(EXTRA_PAGE) in setOf(SIGNATURES, APK_PARTS) }
                    .forEach(Activity::finish)
                subpages.clear()
                return@open
            }
            HostActivityRefresh.activities().filter { it.javaClass == targets.activityType }.forEach { activity ->
                val manager = targets.getFragmentManager.invoke(activity) ?: return@forEach
                val fragment = targets.findFragment.invoke(manager, android.R.id.content) ?: return@forEach
                if (targets.fragmentType.isInstance(fragment)) bind(targets, fragment)
            }
        }
    }

    private data class Row(
        val title: String,
        val text: String = "",
        val summary: String? = null,
        val copyText: String? = null,
        val click: ((Activity) -> Unit)? = null,
    )

    private fun bind(targets: Targets, fragment: Any) {
        val activity = targets.getActivity.invoke(fragment) as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val page = activity.intent?.getStringExtra(EXTRA_PAGE)
        if (!enabled) {
            if (page == SIGNATURES || page == APK_PARTS) activity.finish()
            return
        }
        val info = targets.packageInfo.get(fragment) as? PackageInfo ?: return
        val application = info.applicationInfo ?: return
        val apkPaths = listOfNotNull(application.sourceDir) + application.splitSourceDirs.orEmpty()
        if (page == SIGNATURES || page == APK_PARTS) {
            if (activity in subpages) return
            if (targets.getView.invoke(fragment) != null) {
                // A live upgrade must rebuild the host's overlay and scrollbar adapter together.
                subpages[activity] = Unit
                activity.recreate()
            } else {
                bindSubpage(targets, fragment, activity, page, info, application, apkPaths)
            }
            return
        }
        val parent = targets.findPreference.invoke(fragment, CATEGORY)
            ?.takeIf(targets.categoryType::isInstance) ?: return
        if (parent in added) return
        val rows = informationRows(info, application, apkPaths)
        val preferences = rows.mapIndexed { index, row -> newRow(targets, activity, row, 1000 + index) }
        added[parent] = AddedRows(targets.remove, preferences.map { WeakReference(it) })
        preferences.forEach { targets.add.invoke(parent, it) }
    }

    private fun informationRows(
        info: PackageInfo, application: ApplicationInfo, apkPaths: List<String>,
    ): List<Row> {
        val type = when {
            application.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 -> label("已更新的系统应用", "Updated system app")
            application.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> label("系统应用", "System app")
            else -> label("用户应用", "User app")
        }
        return buildList {
            add(Row(label("版本号", "Version code"), info.longVersionCode.toString()))
            add(Row(label("最低 SDK", "Minimum SDK"), application.minSdkVersion.toString()))
            add(Row(label("目标 SDK", "Target SDK"), application.targetSdkVersion.toString()))
            add(Row(label("应用 UID", "App UID"), application.uid.toString()))
            add(Row(label("Android 用户 ID", "Android user ID"), (application.uid / 100000).toString()))
            add(Row(label("应用类型", "App type"), type))
            val abis = listOf("primaryCpuAbi", "secondaryCpuAbi").mapNotNull { field ->
                runCatching { HostAccess.read(application, field) as? String }.getOrNull()
            }.distinct()
            if (abis.isNotEmpty()) add(Row(label("原生 ABI", "Native ABI"), abis.joinToString(" / ")))
            application.sourceDir?.let { add(pathRow(label("APK 路径", "APK path"), it, folder = false)) }
            application.dataDir?.let { add(pathRow(label("数据目录", "Data directory"), it, folder = true)) }
            if (apkPaths.size > 1) {
                add(Row(
                    label("安装包组成", "APK layout"),
                    label("${apkPaths.size} 个 APK", "${apkPaths.size} APKs"),
                    copyText = apkPaths.joinToString("\n"),
                    click = { openSubpage(it, info.packageName, application.uid, APK_PARTS) },
                ))
            } else if (apkPaths.isNotEmpty()) {
                add(Row(label("安装包组成", "APK layout"), label("单 APK", "Single APK")))
            }
            add(Row(label("签名信息", "Signing information"),
                click = { openSubpage(it, info.packageName, application.uid, SIGNATURES) }))
        }
    }

    private fun openSubpage(activity: Activity, packageName: String, uid: Int, page: String) {
        activity.startActivity(Intent(activity, activity.javaClass).apply {
            putExtra("am_app_pkgname", packageName)
            putExtra("am_app_uid", uid)
            putExtra(EXTRA_PAGE, page)
        })
    }

    private fun bindSubpage(
        targets: Targets, fragment: Any, activity: Activity, page: String,
        info: PackageInfo, application: ApplicationInfo, apkPaths: List<String>,
    ) {
        val title = if (page == SIGNATURES) label("签名信息", "Signing information") else label("安装包组成", "APK layout")
        val screen = targets.screen.newInstance(activity, null)
        val manager = targets.getPreferenceManager.invoke(fragment) ?: return
        targets.attachScreen.invoke(screen, manager)
        val category = targets.category.newInstance(activity, null)
        targets.setTitle.invoke(category, title)
        targets.add.invoke(screen, category)
        val loading = newRow(targets, activity, Row(label("正在读取…", "Loading…")), 0)
        targets.add.invoke(category, loading)
        targets.setPreferenceScreen.invoke(fragment, screen)
        activity.title = title
        targets.actionBar.invoke(activity)?.let { targets.setActionBarTitle.invoke(it, title) }
        subpages[activity] = Unit

        val activityRef = WeakReference(activity)
        val categoryRef = WeakReference(category)
        val loadingRef = WeakReference(loading)
        val context = activity.applicationContext
        val main = activity.mainExecutor
        val uid = application.uid
        val packageName = info.packageName
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            val rows = HookFailurePolicy.open(TAG, "read app information subpage", emptyList<Row>()) {
                if (page == SIGNATURES) signingRows(packageName, uid) else splitRows(context, apkPaths)
            }
            main.execute {
                val host = activityRef.get() ?: return@execute
                val parent = categoryRef.get() ?: return@execute
                if (installed !== targets || !enabled || host !in subpages || host.isFinishing || host.isDestroyed) {
                    return@execute
                }
                HookFailurePolicy.open(TAG, "render app information subpage", Unit) {
                    val result = rows.ifEmpty { listOf(Row(label("暂无可用信息", "Information unavailable"))) }
                    loadingRef.get()?.let { targets.remove.invoke(parent, it) }
                    result.forEachIndexed { index, row ->
                        targets.add.invoke(parent, newRow(targets, host, row, index))
                    }
                }
            }
        }
    }

    private fun splitRows(context: Context, apkPaths: List<String>): List<Row> {
        val files = apkPaths.map(::File)
        val sizes = files.map { it.length() }
        val total = sizes.sum().takeIf { sizes.all { size -> size > 0L } }
        return buildList {
            total?.let { add(Row(label("APK 总大小", "Total APK size"), Formatter.formatFileSize(context, it))) }
            files.forEachIndexed { index, file ->
                val size = sizes[index].takeIf { it > 0L }?.let { Formatter.formatFileSize(context, it) }.orEmpty()
                val title = if (index == 0) label("基础 APK", "Base APK") else file.name
                add(pathRow(title, file.path, folder = false, text = size))
            }
        }
    }

    private fun signingRows(packageName: String, uid: Int): List<Row> {
        val signing = readInstalledSigningInfo(packageName, uid) ?: return emptyList()
        return buildList {
            signing.schemeVersion.takeIf { it > 0 }?.let { add(Row(label("签名方案", "Signing scheme"), "v$it")) }
            signing.certificates.forEachIndexed { index, cert ->
                val title = if (cert.current) label("当前签名证书", "Current signing certificate")
                    else label("历史签名证书", "Historical signing certificate")
                add(Row("$title ${index + 1} · SHA-256", summary = cert.sha256, copyText = cert.sha256))
                cert.subject?.let { add(Row(label("主体", "Subject"), summary = it)) }
                cert.issuer?.let { add(Row(label("签发者", "Issuer"), summary = it)) }
                cert.serial?.let { add(Row(label("序列号", "Serial number"), summary = it, copyText = it)) }
                cert.validity?.let { add(Row(label("有效期", "Validity"), summary = it)) }
            }
        }
    }

    private fun pathRow(title: String, path: String, folder: Boolean, text: String = ""): Row {
        val directory = if (folder) path else File(path).parent
        return Row(title, text, summary = path, copyText = path, click = directory?.let { location ->
            { activity -> openAppInfoDirectory(activity, location) }
        })
    }

    private fun newRow(targets: Targets, activity: Activity, row: Row, order: Int): Any {
        val constructor = if (row.click == null) targets.readOnlyPreference else targets.preference
        return constructor.newInstance(activity).also { preference ->
            targets.setKey.invoke(preference, "hypertweak_app_info_$order")
            targets.setTitle.invoke(preference, row.title)
            if (row.text.isNotEmpty()) targets.setText.invoke(preference, row.text)
            row.summary?.let { targets.setSummary.invoke(preference, it) }
            targets.setOrder.invoke(preference, order)
            targets.setSelectable.invoke(preference, row.click != null)
            row.copyText?.let { copyRows[preference] = LongClick(row.title, it) }
            row.click?.let { setClickAction(targets, preference, activity, it) }
        }
    }

    private fun setClickAction(targets: Targets, preference: Any, activity: Activity, action: (Activity) -> Unit) {
        val type = targets.setListener.parameterTypes.single()
        val owner = WeakReference(activity)
        val listener = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "onPreferenceClick" -> HookFailurePolicy.open(TAG, "open app information row", false) {
                    val host = owner.get() ?: return@open false
                    if (!enabled || host.isFinishing || host.isDestroyed) return@open false
                    action(host)
                    true
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MoreAppInfoClickListener"
                else -> null
            }
        }
        targets.setListener.invoke(preference, listener)
    }

    private fun copy(context: Context, row: LongClick): Boolean =
        HookFailurePolicy.open(TAG, "copy app information", false) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@open false
            clipboard.setPrimaryClip(ClipData.newPlainText(row.title, row.text))
            true
        }

    private fun label(zh: String, en: String) = if (Locale.getDefault().language == "zh") zh else en
}
