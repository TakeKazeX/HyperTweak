package com.takekazex.hypertweak.hook.rules.personalassistant

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Process
import android.view.View
import android.widget.Toast
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.PlatformLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.Collator
import java.util.concurrent.atomic.AtomicBoolean

/** Existing PA classic-widget entry -> native provider chooser -> PA AppWidgetHost. */
object AndroidWidgetPickerHooker : StaticHooker() {
    private const val TAG = "PAAndroidWidgets"
    private var worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var retired = false
    private val loading = AtomicBoolean(false)
    private var dialog = WeakReference<Dialog>(null)
    private lateinit var entryContract: EntryContract
    @Volatile private var contract: Contract? = null
    private var apkPath: String = ""

    private data class EntryContract(
        val appCatalog: AppCatalog?, val mainCatalog: MainCatalog?, val navigator: Method, val source: Method,
        val footerCreated: Method, val footerStarted: Method, val footerActivity: Field, val footerRoot: Method
    )
    private data class AppCatalog(val entry: Method, val activity: Field)
    private data class MainCatalog(val entry: Method, val source: Field, val producer: Method)
    private data class Contract(
        val overlay: Method, val content: Field, val controller: Method, val add: Method,
        val model: Constructor<*>, val fields: Map<String, Field>, val cells: List<Field>,
        val builder: Constructor<*>, val title: Method, val items: Method, val create: Method,
        val configure: Method?
    )
    private data class Choice(val provider: AppWidgetProviderInfo, val span: AndroidWidgetGeometry.Span,
        val label: String, val appLabel: String)

    override fun onInit() {
        retired = false
        contract = null
        loading.set(false)
        worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onHook() {
        if (!isMainProcess || !PlatformLevel.isOs4) return
        apkPath = hookParam.appInfo?.sourceDir ?: return
        val resolved = runCatching { DexKitManager.withBridge(apkPath) { resolveEntry(it) } }
            .onFailure { DebugLog.hookFailed(TAG, "native picker contract", it) }.getOrNull()
        if (resolved == null) {
            DebugLog.hookSkipped(TAG, "native Android-widget entry", "entry contract unavailable; inspect DexKit failure stage")
            return
        }
        entryContract = resolved
        resolved.appCatalog?.let { catalog ->
            catalog.entry.declaringClass.declaredMethods.forEach(::deoptimize)
            catalog.entry.hook {
                after { param ->
                    if (!enabled()) return@after
                    val activity = runCatching { catalog.activity.get(param.thisObject) as? Activity }.getOrNull()
                    if (activity != null && fromAssistant(activity)) param.result = true
                }
            }
        }
        resolved.mainCatalog?.let { catalog ->
            deoptimize(catalog.producer)
            catalog.entry.declaringClass.declaredMethods.forEach(::deoptimize)
            catalog.entry.hook {
                after { param ->
                    if (enabled() && runCatching { catalog.source.getInt(param.thisObject) == 1 }.getOrDefault(false)) param.result = true
                }
            }
        }
        // The native footer is independent of server pagination and catalog
        // loading. Restore its existing view/listener; do not invent a new row.
        for (method in listOf(resolved.footerCreated, resolved.footerStarted)) {
            deoptimize(method)
            method.hook {
                after { param ->
                    runCatching {
                        val activity = resolved.footerActivity.get(param.thisObject) as? Activity ?: return@runCatching
                        if (!fromAssistant(activity)) return@runCatching
                        val root = when (val first = param.args.firstOrNull()) {
                            is View -> first
                            else -> resolved.footerRoot.invoke(param.thisObject) as? View
                        } ?: return@runCatching
                        val id = activity.resources.getIdentifier("picker_nav_footer", "id", activity.packageName)
                        if (id != 0) root.findViewById<View>(id)?.visibility = if (enabled()) View.VISIBLE else View.GONE
                    }.onFailure { DebugLog.w(TAG, "native footer binding failed", it) }
                }
            }
        }
        deoptimize(resolved.navigator)
        resolved.navigator.hook {
            before { param ->
                if (!enabled()) return@before
                val activity = param.args.firstOrNull() as? Activity ?: return@before
                if (!fromAssistant(activity)) return@before
                // Preserve the picker activity until the user selects/cancels.
                // The original route dismisses it and opens a desktop picker.
                param.result = null
                choose(activity)
            }
        }
        DebugLog.i(TAG, "native picker attached footer=${resolved.footerCreated.toGenericString()} app=${resolved.appCatalog != null} main=${resolved.mainCatalog != null} navigator=${resolved.navigator.toGenericString()}")
    }

    private fun enabled() = !retired && Preferences.allowAndroidWidgetsToAssistant()
    private fun fromAssistant(activity: Activity) = runCatching {
        entryContract.source.invoke(activity) == 1
    }.getOrDefault(false)

    private fun choose(activity: Activity) {
        if (!loading.compareAndSet(false, true)) return
        val owner = WeakReference(activity)
        val context = activity.applicationContext
        worker.launch {
            try {
                val picker = contract ?: DexKitManager.withBridge(apkPath) { resolvePicker(it) }
                    ?: error("native provider picker contract unavailable")
                contract = picker
                val choices = providers(context, picker)
                withContext(Dispatchers.Main) {
                    val current = owner.get() ?: return@withContext
                    if (!enabled() || current.isFinishing || current.isDestroyed) return@withContext
                    if (choices.isEmpty()) {
                        message(current, "pa_picker_search_empty")
                        return@withContext
                    }
                    show(current, choices, picker)
                }
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                DebugLog.w(TAG, "provider chooser failed", t)
                withContext(Dispatchers.Main) {
                    owner.get()?.takeIf { enabled() && !it.isFinishing && !it.isDestroyed }?.let(::loadFailure)
                }
            } finally { loading.set(false) }
        }
    }

    private fun providers(context: Context, contract: Contract): List<Choice> {
        val cell = contract.cells.mapNotNull { runCatching { it.getInt(null) }.getOrNull() }
            .filter { it > 0 }.groupingBy { it }.eachCount().filterValues { it == 2 }.keys.singleOrNull()
            ?: error("native cell geometry unavailable")
        val manager = AppWidgetManager.getInstance(context)
        val pm = context.packageManager
        val collator = Collator.getInstance(context.resources.configuration.locales[0])
        return manager.getInstalledProvidersForProfile(Process.myUserHandle()).mapNotNull { info ->
            val receiver = runCatching { pm.getReceiverInfo(info.provider, android.content.pm.PackageManager.GET_META_DATA) }.getOrNull()
                ?: return@mapNotNull null
            if (receiver.metaData?.getBoolean("miuiWidget", false) == true) return@mapNotNull null
            val span = AndroidWidgetGeometry.span(info.targetCellWidth, info.targetCellHeight,
                info.minWidth, info.minHeight, cell) ?: return@mapNotNull null
            if (info.configure != null && contract.configure == null) return@mapNotNull null
            val application = receiver.applicationInfo ?: return@mapNotNull null
            val appLabel = application.loadLabel(pm).toString()
            Choice(info, span, "$appLabel · ${info.loadLabel(pm)} (${span.x}×${span.y})", appLabel)
        }.sortedWith { left, right -> collator.compare(left.label, right.label) }
    }

    private fun show(activity: Activity, choices: List<Choice>, contract: Contract) {
        runCatching {
            val builder = contract.builder.newInstance(activity, 0)
            val titleId = activity.resources.getIdentifier("pa_picker_classic_widget", "string", activity.packageName)
            contract.title.invoke(builder, activity.getString(titleId))
            contract.items.invoke(builder, choices.map { it.label as CharSequence }.toTypedArray(), -1,
                DialogInterface.OnClickListener { selected, index ->
                    selected.dismiss()
                    val choice = choices.getOrNull(index)
                    if (enabled() && choice != null) add(activity, choice, contract)
                })
            val nativeDialog = contract.create.invoke(builder) as Dialog
            dialog = WeakReference(nativeDialog)
            nativeDialog.show()
        }.onFailure {
            DebugLog.w(TAG, "native Miuix picker creation failed", it)
            loadFailure(activity)
        }
    }

    private fun add(activity: Activity, choice: Choice, contract: Contract) {
        runCatching {
            val window = contract.overlay.invoke(null) ?: error("assistant window unavailable")
            val content = contract.content.get(window) ?: error("assistant content unavailable")
            val controller = contract.controller.invoke(content) ?: error("assistant controller unavailable")
            val model = contract.model.newInstance(choice.provider)
            fun set(name: String, value: Any) = contract.fields.getValue(name).set(model, value)
            set("spanX", choice.span.x); set("spanY", choice.span.y)
            set("title", choice.provider.loadLabel(activity.packageManager).toString())
            set("appName", choice.appLabel)
            set("showAddAnimation", true)
            // Constructor parses actual provider metadata; do not fake MIUI identity.
            contract.add.invoke(controller, null, model)
            val id = contract.fields.getValue("appWidgetId").getInt(model)
            check(id > 0 && AppWidgetManager.getInstance(activity).getAppWidgetInfo(id)?.provider == choice.provider.provider) {
                "native AppWidget binding failed"
            }
            val optional = choice.provider.widgetFeatures and (AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE or
                AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) ==
                (AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE or AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL)
            if (choice.provider.configure != null && !optional) {
                val intent = contract.configure?.invoke(null, id, activity) as? Intent
                    ?: error("native configuration bridge unavailable")
                activity.startActivity(intent)
            }
            DebugLog.i(TAG, "native widget added provider=${choice.provider.provider} span=${choice.span.x}x${choice.span.y} id=$id")
            activity.finish()
        }.onFailure {
            DebugLog.w(TAG, "native widget add failed provider=${choice.provider.provider}", it)
            val chinese = activity.resources.configuration.locales[0].language == "zh"
            Toast.makeText(activity, if (chinese) "无法添加小部件，请重试" else "Could not add widget. Try again.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadFailure(context: Context) {
        val chinese = context.resources.configuration.locales[0].language == "zh"
        Toast.makeText(context, if (chinese) "无法加载安卓小部件，请重试" else "Could not load Android widgets. Try again.", Toast.LENGTH_SHORT).show()
    }

    private fun message(context: Context, name: String) {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        if (id != 0) Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    }

    private fun resolveEntry(bridge: DexKitBridge): EntryContract {
        fun marker(value: String) = bridge.findMethod {
            matcher { addUsingString(value, StringMatchType.Contains) }
        }.toList().singleOrNull()
        fun materialize(data: MethodData) = data.getMethodInstance(classLoader).apply { isAccessible = true }
        val navigator = marker("open_classic_picker")?.let(::materialize) ?: error("host contract: navigator")
        if (!Modifier.isStatic(navigator.modifiers) || navigator.returnType != Void.TYPE || navigator.parameterCount != 1 ||
            !Activity::class.java.isAssignableFrom(navigator.parameterTypes[0])) error("entry/classic navigator signature")
        val activityClass = navigator.parameterTypes[0]
        val source = activityClass.getDeclaredMethod("getOpenSource").apply { isAccessible = true }
        if (source.returnType != Int::class.javaPrimitiveType) error("entry/Activity source getter")
        val footerData = bridge.findMethod { matcher {
            paramTypes(View::class.java, android.os.Bundle::class.java)
            returnType(Void.TYPE)
            addUsingString("mPickerFooter", StringMatchType.Equals)
        } }.toList().singleOrNull() ?: error("entry/footer onViewCreated contract")
        val footerCreated = materialize(footerData)
        val footerStarted = footerCreated.declaringClass.getDeclaredMethod("onStart").apply { isAccessible = true }
        val footerActivity = allFields(footerCreated.declaringClass).singleOrNull { it.type == activityClass }
            ?.apply { isAccessible = true } ?: error("entry/footer Activity contract")
        val appCatalog = runCatching {
            val producer = marker("moveClassicWidgetEntryToEnd: appended classic widget entry") ?: error("app producer")
            val entry = producer.invokes.filter { it.className == producer.className && it.paramCount == 0 && it.returnTypeName == "boolean" }
                .distinctBy { it.descriptor }.singleOrNull()?.let(::materialize) ?: error("app gate")
            val field = allFields(entry.declaringClass).singleOrNull { it.type == activityClass }
                ?.apply { isAccessible = true } ?: error("app Activity field")
            AppCatalog(entry, field)
        }.onFailure { DebugLog.hookFailed(TAG, "app catalog", it) }.getOrNull()
        val mainCatalog = runCatching {
            val producer = marker("loadAppList cancelled: ") ?: error("default catalog producer")
            val owners = producer.usingFields.map { it.field.getFieldInstance(classLoader).type.name }.toSet()
            val calls = producer.invokes.filter { it.className in owners }
            val entryData = (calls + calls.flatMap { it.invokes }).distinctBy { it.descriptor }.filter {
                it.className in owners && it.paramCount == 0 && it.returnTypeName == "boolean" &&
                    it.usingFields.map { f -> f.field.descriptor }.distinct().size == 1 &&
                    it.usingFields.first().field.getFieldInstance(classLoader).type == Int::class.javaPrimitiveType
            }.singleOrNull() ?: error("default catalog gate")
            val entry = materialize(entryData)
            val field = entryData.usingFields.first().field.getFieldInstance(classLoader).apply { isAccessible = true }
            check(!Modifier.isStatic(field.modifiers) && field.declaringClass == entry.declaringClass)
            MainCatalog(entry, field, materialize(producer))
        }.onFailure { DebugLog.hookFailed(TAG, "default catalog", it) }.getOrNull()
        return EntryContract(appCatalog, mainCatalog, navigator, source, footerCreated, footerStarted, footerActivity,
            footerCreated.declaringClass.getMethod("getView"))
    }

    private fun resolvePicker(bridge: DexKitBridge): Contract {
        fun marker(value: String) = bridge.findMethod {
            matcher { addUsingString(value, StringMatchType.Contains) }
        }.toList().singleOrNull()
        fun materialize(data: MethodData) = data.getMethodInstance(classLoader).apply { isAccessible = true }
        val overlay = marker("sOverlayRef: ")?.let(::materialize) ?: error("host contract: overlay")
        if (!Modifier.isStatic(overlay.modifiers) || overlay.parameterCount != 0 || overlay.returnType != overlay.declaringClass) error("picker/overlay getter")
        val contentClass = Class.forName("com.miui.personalassistant.core.view.AssistContentView", false, classLoader)
        val containerClass = Class.forName("com.miui.personalassistant.widget.WidgetContainer", false, classLoader)
        val modelClass = Class.forName("com.miui.personalassistant.widget.iteminfo.AppWidgetItemInfo", false, classLoader)
        val content = allFields(overlay.declaringClass).singleOrNull { it.type == contentClass }?.apply { isAccessible = true } ?: error("host contract: content")
        val controller = contentClass.declaredMethods.singleOrNull { it.parameterCount == 0 && it.returnType == containerClass } ?: error("host contract: controller")
        val add = NativeWidgetAddContract.select(containerClass.methods.toList(), View::class.java, modelClass)
            ?: error("picker/required abstract container add operation")
        val fields = listOf("spanX", "spanY", "title", "appName", "showAddAnimation", "appWidgetId")
            .associateWith { name -> allFields(modelClass).single { it.name == name }.apply { isAccessible = true } }
        val cellInit = marker("sWidgetCellWidth = ")?.let(::materialize) ?: error("host contract: cellInit")
        val cells = cellInit.declaringClass.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        if (cells.size != 4) error("picker/native cell fields")
        val dialogClass = Class.forName("miuix.appcompat.app.AlertDialog", false, classLoader)
        val builderClass = dialogClass.declaredClasses.singleOrNull { candidate -> candidate.declaredMethods.any { method ->
            method.parameterTypes.contentEquals(arrayOf(Array<CharSequence>::class.java, Int::class.javaPrimitiveType, DialogInterface.OnClickListener::class.java))
        } } ?: error("picker/Miuix builder")
        val items = builderClass.declaredMethods.single { it.parameterTypes.contentEquals(
            arrayOf(Array<CharSequence>::class.java, Int::class.javaPrimitiveType, DialogInterface.OnClickListener::class.java)) }
        val title = builderClass.declaredMethods.filter { it.parameterTypes.contentEquals(arrayOf(CharSequence::class.java)) }
            .singleOrNull { bridge.getMethodData(it)?.usingFields?.any { field -> field.field.getFieldInstance(classLoader).name == "mTitle" } == true } ?: error("picker/Miuix title or create")
        val create = builderClass.declaredMethods.filter { it.parameterCount == 0 && it.returnType == dialogClass }
            .singleOrNull { bridge.getMethodData(it)?.invokes?.none { call -> call.methodName == "show" && call.paramCount == 0 } == true } ?: error("picker/Miuix title or create")
        val configure = bridge.findMethod { matcher {
            paramTypes(Int::class.javaPrimitiveType, Context::class.java); returnType(Intent::class.java)
            addUsingString("extra_appwidget_host_id", StringMatchType.Equals)
            addUsingString("extra_appwidget_id", StringMatchType.Equals)
        } }.toList().singleOrNull()?.let(::materialize)
        return Contract(overlay, content, controller.apply { isAccessible = true },
            add, modelClass.getConstructor(AppWidgetProviderInfo::class.java), fields, cells.onEach { it.isAccessible = true },
            builderClass.getConstructor(Context::class.java, Int::class.javaPrimitiveType),
            title.apply { isAccessible = true }, items.apply { isAccessible = true }, create.apply { isAccessible = true }, configure)
    }

    private fun allFields(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }.toList()

    override fun onPrepareHotReload() {
        retired = true
        worker.cancel()
        val old = dialog.get()
        if (old != null) android.os.Handler(android.os.Looper.getMainLooper()).post { old.dismiss() }
        dialog.clear()
    }
}
