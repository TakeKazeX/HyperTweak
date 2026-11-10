package com.takekazex.hypertweak.hook.rules.securitycenter

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.view.MenuItem
import android.widget.Toast
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.DynamicHooker
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface
import kotlinx.coroutines.Dispatchers
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import com.takekazex.hypertweak.util.platform.ResourceLookup

/** Gives the native Security Center share action an immutable, named copy of its selected APK. */
object NamedApkShareHooker : DynamicHooker() {
    private const val TAG = "NamedApkShare"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val FRAGMENT = "com.miui.appmanager.fragment.ApplicationsDetailsFragment"
    private const val AUTHORITY = "com.miui.securitycenter.zman.fileProvider"
    private const val MIME = "application/vnd.android.package-archive"
    private val cacheLifetimeMs = TimeUnit.DAYS.toMillis(1)

    private val enabled by hookSetting(false) {
        Preferences.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_NAMED_SHARE, false)
    }

    // A queued worker may finish after the activity or this hook generation has retired.
    private var pending by generationState<Any?> { null }

    private data class Targets(
        val dispatch: Method,
        val uriForFile: Method,
        val source: Field,
        val application: Field,
        val packageInfo: Field,
    ) {
        fun encode() = DexContractMembers.encode(
            "dispatch" to dispatch, "uri" to uriForFile, "source" to source,
            "application" to application, "package" to packageInfo,
        )
    }

    override fun onHook() {
        if (hookParam.processName != PACKAGE) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        prepareDexTargets(
            resolve = {
                resolveDexContract(
                    id = "securitycenter.named_apk_share",
                    apk = apk,
                    compute = compute@ { bridge ->
                        val owner = FRAGMENT.toClassOrNull() ?: return@compute null
                        val dispatch = bridge.findClass { matcher { className(FRAGMENT, StringMatchType.Equals) } }
                            .singleOrNull()?.methods?.singleOrNull {
                                it.methodName == "optionsItemSelected" && it.returnTypeName == "void" &&
                                    it.paramTypeNames == listOf("android.view.MenuItem") &&
                                    listOf("android.intent.action.SEND", MIME, AUTHORITY, "android.intent.extra.STREAM")
                                        .all { marker -> marker in it.usingStrings } &&
                                    it.invokes.any { call -> call.descriptor ==
                                        "Landroid/content/Intent;->createChooser(Landroid/content/Intent;Ljava/lang/CharSequence;)Landroid/content/Intent;" }
                            } ?: return@compute null
                        val uri = dispatch.invokes.distinctBy { it.descriptor }.singleOrNull {
                            it.className == "androidx.core.content.FileProvider" &&
                                it.paramTypeNames == listOf("android.content.Context", "java.lang.String", "java.io.File") &&
                                it.returnTypeName == "android.net.Uri"
                        } ?: return@compute null
                        val source = dispatch.usingFields.filter {
                            it.usingType.isRead() && it.field.declaredClassName == FRAGMENT &&
                                it.field.typeName == "java.io.File"
                        }.map { it.field.descriptor }.distinct().singleOrNull() ?: return@compute null
                        val app = owner.declaredFields.singleOrNull { it.type == ApplicationInfo::class.java }
                            ?: return@compute null
                        val pkg = owner.declaredFields.singleOrNull { it.type == PackageInfo::class.java }
                            ?: return@compute null
                        Targets(
                            MethodDescriptorCodec.method(dispatch.descriptor, classLoader) ?: return@compute null,
                            MethodDescriptorCodec.method(uri.descriptor, classLoader) ?: return@compute null,
                            MethodDescriptorCodec.field(source, classLoader) ?: return@compute null,
                            app.apply { isAccessible = true }, pkg.apply { isAccessible = true },
                        )
                    },
                    encode = Targets::encode,
                    decode = {
                        Targets(it.method("dispatch"), it.method("uri"), it.field("source"),
                            it.field("application"), it.field("package"))
                    },
                ) ?: run {
                    DebugLog.hookSkipped(TAG, "named APK share", "missing or ambiguous share contract")
                    null
                }
            },
            install = ::install,
        )
    }

    private fun install(targets: Targets) {
        val getActivity = HostAccess.requirePublicMethod(targets.dispatch.declaringClass, "getActivity", accessible = false)
        val original = module.getInvoker(targets.dispatch).setType(XposedInterface.Invoker.Type.ORIGIN)
        targets.dispatch.hook {
            intercept { chain ->
                if (!enabled) return@intercept chain.proceed()
                val item = chain.args.firstOrNull() as? MenuItem ?: return@intercept chain.proceed()
                val fragment = chain.thisObject ?: return@intercept chain.proceed()
                val queued = HookFailurePolicy.open(TAG, "prepare named APK", false) {
                    val activity = getActivity.invoke(fragment) as? Activity ?: return@open false
                    val shareId = ResourceLookup.identifier(activity.resources, "app_share", "id", PACKAGE)
                    if (shareId == 0 || item.itemId != shareId || !item.isVisible || !item.isEnabled) return@open false
                    if (pending != null) return@open true
                    val source = targets.source.get(fragment) as? File ?: return@open false
                    val app = targets.application.get(fragment) as? ApplicationInfo ?: return@open false
                    if (source.path != app.sourceDir || !app.splitSourceDirs.isNullOrEmpty()) return@open false
                    val pkg = targets.packageInfo.get(fragment) as? PackageInfo
                    val label = app.loadLabel(activity.packageManager).toString()
                    val name = apkShareFileName(label, app.packageName, pkg?.takeIf { it.packageName == app.packageName }?.versionName)
                    queueCopy(activity, fragment, source, name, targets) { receiver ->
                        original.invoke(receiver, item)
                    }
                    true
                }
                if (queued) null else chain.proceed()
            }
        }
        DebugLog.hookRegistered(TAG, "named APK share")
    }

    private fun queueCopy(
        activity: Activity,
        fragment: Any,
        source: File,
        name: String,
        targets: Targets,
        openOriginal: (Any) -> Unit,
    ) {
        val request = Any()
        val activityRef = WeakReference(activity)
        val fragmentRef = WeakReference(fragment)
        val context = activity.applicationContext
        val main = activity.mainExecutor
        pending = request
        try {
            Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                val copy = HookFailurePolicy.open<File?>(TAG, "copy APK to share cache", null) {
                    stageCopy(context, source, name, targets.uriForFile)
                }
                main.execute {
                    if (pending !== request) return@execute
                    pending = null
                    HookFailurePolicy.open(TAG, "open native APK share", Unit) {
                        val host = activityRef.get() ?: return@open
                        val receiver = fragmentRef.get() ?: return@open
                        if (host.isFinishing || host.isDestroyed || targets.source.get(receiver) != source) return@open
                        if (copy == null || !enabled) {
                            openOriginal(receiver)
                            return@open
                        }
                        // The host reads this field synchronously to build the granted URI and
                        // chooser. Restore the page's source before returning to its event loop.
                        targets.source.set(receiver, copy)
                        try {
                            openOriginal(receiver)
                        } finally {
                            targets.source.set(receiver, source)
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            pending = null
            throw failure
        }
        val message = if (Locale.getDefault().language == "zh") "正在准备 APK…" else "Preparing APK…"
        HookFailurePolicy.open(TAG, "show share progress", Unit) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stageCopy(context: Context, source: File, name: String, uriForFile: Method): File? {
        val cache = context.externalCacheDir ?: return null
        val root = File(cache, "security_share/hypertweak_apk")
        val cutoff = System.currentTimeMillis() - cacheLifetimeMs
        root.listFiles()?.filter { it.lastModified() in 1 until cutoff }?.forEach { it.deleteRecursively() }
        val staging = File(root, UUID.randomUUID().toString())
        if (!staging.mkdirs()) return null
        val copy = File(staging, name)
        var ready = false
        try {
            val length = source.length()
            val modified = source.lastModified()
            if (length <= 0) return null
            source.copyTo(copy)
            if (copy.length() != length || source.length() != length || source.lastModified() != modified) return null
            val uri = uriForFile.invoke(null, context, AUTHORITY, copy) as? Uri ?: return null
            if (uri.authority != AUTHORITY || uri.pathSegments.firstOrNull() != "cache" ||
                uri.lastPathSegment != copy.name
            ) return null
            ready = true
            return copy
        } finally {
            if (!ready) staging.deleteRecursively()
        }
    }
}

private val unsafeApkNameCharacters = Regex("""[\p{Cc}\p{Cf}/\\:*?"<>|]""")

internal fun apkShareFileName(label: String, packageName: String, version: String?): String {
    fun clean(value: String, limit: Int) = value.replace(unsafeApkNameCharacters, "_")
        .trim { it.isWhitespace() || it == '.' }.take(limit).trimEnd { it.isHighSurrogate() }
    val name = clean(label, 52).ifEmpty { clean(packageName, 52) }.ifEmpty { "application" }
    val versionName = version?.let { clean(it, 24) }.orEmpty()
    return if (versionName.isEmpty()) "$name.apk" else "${name}_$versionName.apk"
}
