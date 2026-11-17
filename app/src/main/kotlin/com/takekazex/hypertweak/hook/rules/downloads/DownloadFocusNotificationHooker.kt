package com.takekazex.hypertweak.hook.rules.downloads

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import com.takekazex.hypertweak.hook.rules.downloads.DownloadFocusNotificationPayload.Action
import com.takekazex.hypertweak.hook.rules.shared.DownloadFocusIdentity
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives each download task its own notification and the host's native `file_download` focus
 * payload, so SystemUI renders one Super Island entry per task with its own templates.
 *
 * Four provider boundaries, all resolved structurally:
 * - the static tag builder splits `1:<pkg>` / `3:<pkg>:success` by download id and keeps
 *   user-paused (193) tasks on the active identity, so pause/resume never recreates the island;
 * - `updateWithLocked` captures the live notifier/collection for settings refresh;
 * - `NotificationHelper.notify` attaches the payload and clears the icon URL, because the host's
 *   async icon queue is keyed by package + URL and would hold back same-source peers;
 * - the native notification receiver handles the injected pause/resume actions.
 * Native channels, content/delete intents, grouping and failure notifications stay untouched.
 */
object DownloadFocusNotificationHooker : StaticHooker() {
    private val enabled by hookSetting(false) {
        Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_FOCUS_NOTIFICATION, false)
    }

    private const val TAG = "DownloadFocusNotification"
    private const val ACTION_PAUSE = "com.takekazex.hypertweak.action.DOWNLOAD_PAUSE"
    private const val ACTION_RESUME = "com.takekazex.hypertweak.action.DOWNLOAD_RESUME"
    private const val EXTRA_DOWNLOAD_ID = "hypertweak_download_id"
    private const val STATUS_RUNNING = 190
    private const val STATUS_PAUSED_BY_APP = 193
    private const val STATUS_WAITING_FOR_NETWORK = 195
    private const val STATUS_QUEUED_FOR_WIFI = 196

    private class Targets(
        val buildTag: Method,
        val updateWith: Method,
        val updateWithLocked: Method,
        val notify: Method,
        val itemTag: Field,
        val itemBuilder: Field,
        val helperContext: Field,
        val notifierSpeeds: Field,
        val id: Field,
        val title: Field,
        val fileName: Field,
        val currentBytes: Field,
        val totalBytes: Field,
        val percent: Field,
        val status: Field,
        val visibility: Field,
        val notifyIsClear: Field,
        val pkg: Field,
        val subPkg: Field,
        val receiver: Class<*>?,
        val onReceive: Method?,
        val cancelAll: Method,
        val activeNotifs: Field,
    )

    private class Task(
        val id: Long,
        val name: String,
        val currentBytes: Long,
        val totalBytes: Long,
        val percent: Int,
        val status: Int,
        val completed: Boolean,
    ) {
        val paused get() = !completed && status in PAUSED
    }

    private val PAUSED = setOf(STATUS_PAUSED_BY_APP, STATUS_WAITING_FOR_NETWORK, STATUS_QUEUED_FOR_WIFI)

    private val tasksByTag = generationResource(ConcurrentHashMap<String, Task>()) { it.clear() }
    private val hostResources = generationResource(ConcurrentHashMap<String, Int>()) { it.clear() }
    private val islandIcons = generationResource(ConcurrentHashMap<String, Icon>()) { it.clear() }
    private var targets: Targets? by generationState { null }
    private var liveNotifier: Any? by generationState { null }
    private var liveCollection: Collection<*>? by generationState { null }
    @Volatile private var retiring = false

    override fun onHook() {
        retiring = false
        val apkPath = hookParam.appInfo?.sourceDir ?: run {
            DebugLog.hookSkipped(TAG, "download focus notification", "source APK unavailable")
            return
        }
        prepareDexTargets(
            name = "download.focus.notification",
            resolve = { resolveTargets(apkPath) },
            install = ::installHooks,
        )
    }

    private fun installHooks(target: Targets) {
        targets = target

        deoptimize(target.buildTag)
        target.buildTag.hook("download_focus_tag") {
            after { param ->
                if (!enabled) return@after
                HookFailurePolicy.open(TAG, "DownloadNotifier tag", Unit) {
                    val info = param.args.getOrNull(0) ?: return@open
                    val id = target.id.getLong(info)
                    val pkg = (target.subPkg.get(info) as? String)?.takeIf(String::isNotBlank)
                        ?: (target.pkg.get(info) as? String).orEmpty()
                    val status = target.status.getInt(info)
                    val native = param.result as? String
                    // Native drops user-paused tasks; keep visible ones on the active identity.
                    val userPaused = native == null && status == STATUS_PAUSED_BY_APP &&
                        target.visibility.getInt(info) in 0..1 && !target.notifyIsClear.getBoolean(info)
                    val tag = DownloadFocusTaskPolicy.splitTag(if (userPaused) "1:$pkg" else native, id, pkg)
                        ?: return@open
                    // All-or-nothing: a task without a name keeps its native tag and grouping.
                    val name = baseName(target.fileName.get(info) as? String)
                        ?: baseName(target.title.get(info) as? String) ?: return@open
                    tasksByTag[tag] = Task(id, name, target.currentBytes.getLong(info), target.totalBytes.getLong(info),
                        target.percent.getInt(info), status, DownloadFocusTaskPolicy.completed(tag))
                    param.result = tag
                }
            }
        }

        // A restarted DownloadService (e.g. resuming while every task was paused) creates a new
        // notifier and cancels every provider notification. Natively only the merged active
        // notification existed; with per-task islands that tears down and re-creates every
        // island. Keep marked task notifications and seed the new notifier with their tags and
        // original `when`, so its own stale-tag sweep still cancels tasks that are gone.
        deoptimize(target.cancelAll)
        target.cancelAll.hook("download_focus_cancel_all") {
            before { param ->
                if (!enabled) return@before
                HookFailurePolicy.open(TAG, "DownloadNotifier cancelAll", Unit) {
                    val context = HostAccess.read(param.thisObject, "mContext") as? Context ?: return@open
                    val manager = context.getSystemService(NotificationManager::class.java) ?: return@open
                    @Suppress("UNCHECKED_CAST")
                    val active = target.activeNotifs.get(param.thisObject) as? MutableMap<String, Long> ?: return@open
                    var kept = 0
                    synchronized(active) {
                        manager.activeNotifications.forEach { sbn ->
                            val marker = sbn.notification.extras.getLong(DownloadFocusIdentity.EXTRA_TASK_ID, -1)
                            if (DownloadFocusIdentity.owns(sbn.packageName, sbn.id, sbn.tag, marker)) {
                                active[sbn.tag] = sbn.notification.`when`
                                kept++
                            } else {
                                manager.cancel(sbn.tag, sbn.id)
                            }
                        }
                    }
                    param.result = null
                    DebugLog.d(TAG, "service restart kept task notifications=$kept")
                }
            }
        }

        deoptimize(target.updateWithLocked)
        target.updateWithLocked.hook("download_focus_capture") {
            before { param ->
                liveNotifier = param.thisObject
                liveCollection = param.args.getOrNull(0) as? Collection<*>
                tasksByTag.clear()
            }
        }

        deoptimize(target.notify)
        target.notify.hook("download_focus_notify") {
            before { param ->
                if (!enabled) return@before
                HookFailurePolicy.open(TAG, "NotificationHelper notify", Unit) {
                    val item = param.args.getOrNull(0) ?: return@open
                    // Only tags split above carry a snapshot; anything else stays native.
                    val task = tasksByTag.remove(target.itemTag.get(item) as? String ?: return@open) ?: return@open
                    val builder = target.itemBuilder.get(item) as? Notification.Builder ?: return@open
                    val context = target.helperContext.get(param.thisObject) as? Context ?: return@open
                    if (!attach(target, builder, context, task)) return@open
                    param.args[2] = null
                }
            }
        }

        target.onReceive?.let { onReceive ->
            deoptimize(onReceive)
            onReceive.hook("download_focus_action") {
                before { param ->
                    val intent = param.args.getOrNull(1) as? Intent ?: return@before
                    val pause = when (intent.action) {
                        ACTION_PAUSE -> true
                        ACTION_RESUME -> false
                        else -> return@before
                    }
                    param.result = null
                    HookFailurePolicy.open(TAG, "download pause/resume", Unit) {
                        val context = param.args.getOrNull(0) as? Context ?: return@open
                        val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L).takeIf { it > 0 } ?: return@open
                        val values = ContentValues().apply {
                            put("status", if (pause) STATUS_PAUSED_BY_APP else STATUS_RUNNING)
                            put("control", if (pause) 1 else 0)
                        }
                        context.contentResolver.update(Uri.parse("content://downloads/my_downloads"),
                            values, "_id=?", arrayOf(id.toString()))
                        DebugLog.i(TAG, (if (pause) "paused" else "resumed") + " download id=" + id)
                    }
                }
            }
        }

        DebugLog.d(TAG, "download focus notification hooks installed actions=${target.onReceive != null}")
        refreshSettings()
    }

    private fun attach(target: Targets, builder: Notification.Builder, context: Context, task: Task): Boolean {
        val progress = when {
            task.completed -> 100
            task.totalBytes > 0 -> (task.currentBytes * 100 / task.totalBytes).toInt()
            task.percent in 1..100 -> task.percent // M3U8 tasks only report a percentage.
            else -> null
        }
        val size = DownloadFocusNotificationPayload::formatBytes
        val detail = when {
            task.completed -> listOfNotNull(hostString(context, "notification_download_complete"),
                (task.totalBytes.takeIf { it > 0 } ?: task.currentBytes.takeIf { it > 0 })?.let(size))
            task.paused -> listOfNotNull(hostString(context, when (task.status) {
                STATUS_WAITING_FOR_NETWORK -> "paused_waiting_for_network"
                STATUS_QUEUED_FOR_WIFI -> "paused_queued_for_wifi"
                else -> "paused_by_app"
            }), progressBytes(task))
            else -> listOfNotNull(progressBytes(task), speedOf(target, task.id)?.let { size(it) + "/s" })
        }.joinToString(" · ")
        val action = when {
            task.completed || target.receiver == null -> null
            task.paused -> hostString(context, "download_status_continue")?.let { Action(DownloadFocusNotificationPayload.ACTION_RESUME, it) }
            else -> hostString(context, "download_status_paused")?.let { Action(DownloadFocusNotificationPayload.ACTION_PAUSE, it) }
        }
        val icon = DownloadFocusNotificationPayload.iconKey(task.name)
        val json = DownloadFocusNotificationPayload.build(
            DownloadFocusNotificationPayload.Content(task.name, detail, progress, icon, action, task.completed),
        )
        if (json.toByteArray(Charsets.UTF_8).size > DownloadFocusNotificationPayload.MAX_BYTES) return false

        val extras = builder.extras
        extras.putLong(DownloadFocusIdentity.EXTRA_TASK_ID, task.id)
        extras.putString("miui.focus.param", json)
        extras.putBundle("miui.focus.pics", Bundle().apply {
            putParcelable(icon, fileIcon(context, icon))
            putParcelable(DownloadFocusNotificationPayload.ISLAND_ICON, islandIcon(context, icon))
            if (task.completed) putParcelable(DownloadFocusNotificationPayload.DONE_ICON, doneIcon(context))
        })
        if (action != null) {
            val pending = broadcast(context, target.receiver!!, if (task.paused) ACTION_RESUME else ACTION_PAUSE, task.id) {
                putExtra(EXTRA_DOWNLOAD_ID, task.id).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val nativeAction = Notification.Action.Builder(Icon.createWithResource(context,
                if (task.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
                action.title, pending).build()
            // Explicit false keeps the island open when the action is tapped.
            nativeAction.extras.putBoolean("click_with_collapse", false)
            extras.putBundle("miui.focus.actions", Bundle().apply { putParcelable(action.key, nativeAction) })
        }
        if (task.paused) {
            // The tag type is now active (ongoing); restore the native waiting shape: swipeable,
            // and a swipe hides the task exactly like the native waiting notification.
            builder.setOngoing(false)
            target.receiver?.let { receiver ->
                builder.setDeleteIntent(broadcast(context, receiver, "miui.intent.action.DOWNLOAD_HIDE", task.id) {
                    putExtra("extra_click_download_ids", longArrayOf(task.id))
                })
            }
        }
        return true
    }

    private fun broadcast(context: Context, receiver: Class<*>, action: String, id: Long, extras: Intent.() -> Unit) =
        PendingIntent.getBroadcast(context, (id * 31 + action.hashCode()).toInt(),
            Intent(context, receiver).setAction(action).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun progressBytes(task: Task): String? = when {
        task.totalBytes > 0 -> DownloadFocusNotificationPayload.formatBytes(task.currentBytes) + "/" +
            DownloadFocusNotificationPayload.formatBytes(task.totalBytes)
        task.currentBytes > 0 -> DownloadFocusNotificationPayload.formatBytes(task.currentBytes)
        else -> null
    }

    /** The notifier's own per-task speed samples; native removes a task on a zero sample. */
    private fun speedOf(target: Targets, id: Long): Long? {
        val speeds = liveNotifier?.let(target.notifierSpeeds::get) ?: return null
        val value = synchronized(speeds) {
            HostAccess.method(speeds, "get", Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!)
                ?.invoke(speeds, id, 0L) as? Long
        }
        return value?.takeIf { it > 0 }
    }

    private fun hostString(context: Context, name: String): String? {
        val id = hostResources.getOrPut("string/$name") { context.resources.getIdentifier(name, "string", context.packageName) }
        return if (id == 0) null else context.getString(id)
    }

    private fun fileIcon(context: Context, key: String): Icon {
        val id = hostResources.getOrPut("drawable/$key") { context.resources.getIdentifier(key, "drawable", context.packageName) }
        return Icon.createWithResource(context, if (id != 0) id else android.R.drawable.stat_sys_download)
    }

    /** The island slots fill their icon edge to edge; draw the native icon inset by 15%. */
    private fun islandIcon(context: Context, key: String): Icon = islandIcons.getOrPut(key) {
        val id = hostResources.getOrPut("drawable/$key") { context.resources.getIdentifier(key, "drawable", context.packageName) }
        val drawable = (if (id != 0) context.getDrawable(id) else null) ?: return@getOrPut fileIcon(context, key)
        val size = (context.resources.displayMetrics.density * 56f).toInt().coerceAtLeast(32)
        val inset = (size * 0.15f).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(inset, inset, size - inset, size - inset)
        drawable.draw(Canvas(bitmap))
        Icon.createWithBitmap(bitmap)
    }

    /** The native recorder's 22dp finish vector: a #1DCD3A 2dp ring with a round-capped check. */
    private fun doneIcon(context: Context): Icon = islandIcons.getOrPut("done") {
        val scale = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap((22 * scale).toInt(), (22 * scale).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { scale(scale, scale) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFF1DCD3A.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawCircle(11f, 11f, 9f, paint)
        canvas.drawPath(Path().apply { moveTo(7.89f, 10.826f); lineTo(9.986f, 13.578f); lineTo(14.452f, 8.384f) }, paint)
        Icon.createWithBitmap(bitmap)
    }

    private fun baseName(value: String?): String? =
        value?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank)

    /** Republishes through the native service lock; enable/disable also restores native grouping. */
    fun refreshSettings() {
        val target = targets ?: return
        if (retiring) return
        HostAccess.onMain {
            HookFailurePolicy.open(TAG, "refresh download notifications", Unit) {
                val thread = HostAccess.requireDeclaredMethod(Class.forName("android.app.ActivityThread"),
                    "currentActivityThread", accessible = true).invoke(null) ?: return@open
                val services = HostAccess.read(thread, "mServices") as? Map<*, *> ?: return@open
                val serviceClass = classLoader.loadClass("com.android.providers.downloads.service.DownloadService")
                val service = services.values.singleOrNull(serviceClass::isInstance)
                    ?: return@open refreshRetainedNotifier(target)
                val notifier = HostAccess.requireDeclaredField(serviceClass, "mNotifier", true)
                check(notifier.type == target.updateWith.declaringClass)
                val downloads = HostAccess.requireDeclaredField(serviceClass, "mDownloads", true)
                check(Map::class.java.isAssignableFrom(downloads.type))
                val tasks = downloads.get(service) as? Map<*, *> ?: return@open
                synchronized(tasks) {
                    val owner = notifier.get(service) ?: return@open
                    target.updateWith.invoke(owner, tasks.values.toList())
                    DebugLog.i(TAG, "refreshed live service notifications tasks=${tasks.size}")
                }
            }
        }
    }

    /**
     * Paused/finished-only services stop while their notifier survives. Replay only tasks still
     * represented by posted notifications (task marker or native click ids), never old history.
     */
    private fun refreshRetainedNotifier(target: Targets) {
        val notifier = liveNotifier
        val collection = liveCollection?.filterNotNull()
        if (notifier == null || collection == null) return DebugLog.d(TAG, "refresh skipped: no service or retained notifier")
        val context = HostAccess.read(notifier, "mContext") as? Context ?: return
        val posted = context.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.filter { it.packageName == DownloadFocusIdentity.PACKAGE && it.id == DownloadFocusIdentity.NOTIFICATION_ID }
            .orEmpty()
        val postedIds = posted.map { sbn ->
            val marker = sbn.notification.extras.getLong(DownloadFocusIdentity.EXTRA_TASK_ID, -1)
            if (DownloadFocusIdentity.owns(sbn.packageName, sbn.id, sbn.tag, marker)) longArrayOf(marker)
            else (sbn.notification.contentIntent?.let { HostAccess.invoke(it, "getIntent") } as? Intent)
                ?.getLongArrayExtra("extra_click_download_ids")
        }
        val known = collection.associateBy { target.id.getLong(it) }
        val retained = DownloadFocusTaskPolicy.postedTasks(known, postedIds)
            ?: return DebugLog.d(TAG, "retained refresh skipped: posted=${posted.size} known=${known.size}")
        target.updateWith.invoke(notifier, retained)
        DebugLog.i(TAG, "refreshed retained notifier tasks=${retained.size}")
    }

    override fun onPrepareHotReload() { retiring = true }

    override fun saveHotReloadState(): Any? = liveNotifier?.let { listOf(it, liveCollection) }

    override fun restoreHotReloadState(state: Any?) {
        val entry = state as? List<*> ?: return
        liveNotifier = entry.getOrNull(0)
        liveCollection = entry.getOrNull(1) as? Collection<*>
    }

    override fun recoverHotReloadHosts() { refreshSettings() }

    private fun resolveTargets(apkPath: String): Targets? =
        resolveDexContract(
            id = "downloads.focus_notification.v3",
            apk = apkPath,
            compute = ::computeTargets,
            encode = { t ->
                DexContractMembers.encode(
                    "buildTag" to t.buildTag, "updateWith" to t.updateWith, "updateWithLocked" to t.updateWithLocked,
                    "notify" to t.notify, "itemTag" to t.itemTag, "itemBuilder" to t.itemBuilder,
                    "helperContext" to t.helperContext, "notifierSpeeds" to t.notifierSpeeds,
                    "id" to t.id, "title" to t.title, "fileName" to t.fileName, "currentBytes" to t.currentBytes,
                    "totalBytes" to t.totalBytes, "percent" to t.percent, "status" to t.status,
                    "visibility" to t.visibility, "notifyIsClear" to t.notifyIsClear, "pkg" to t.pkg,
                    "subPkg" to t.subPkg, "receiver" to t.receiver, "onReceive" to t.onReceive,
                    "cancelAll" to t.cancelAll, "activeNotifs" to t.activeNotifs,
                )
            },
            decode = { m ->
                runCatching {
                    Targets(
                        m.method("buildTag"), m.method("updateWith"), m.method("updateWithLocked"), m.method("notify"),
                        m.field("itemTag"), m.field("itemBuilder"), m.field("helperContext"), m.field("notifierSpeeds"),
                        m.field("id"), m.field("title"), m.field("fileName"), m.field("currentBytes"),
                        m.field("totalBytes"), m.field("percent"), m.field("status"), m.field("visibility"),
                        m.field("notifyIsClear"), m.field("pkg"), m.field("subPkg"),
                        m.optionalType("receiver"), m.optionalMethod("onReceive"),
                        m.method("cancelAll"), m.field("activeNotifs"),
                    ).takeIf(::valid)
                }.getOrNull()
            },
        )

    private fun computeTargets(bridge: DexKitBridge): Targets? {
        // NotificationItem: five fields (RemoteViews, Builder, tag, two ints), nested in the helper
        // whose notify(item, String, String) posts it.
        val itemClass = bridge.findClass {
            matcher {
                fieldCount(5)
                addFieldForType(RemoteViews::class.java)
                addFieldForType(Notification.Builder::class.java)
                addFieldForType(String::class.java)
                addFieldForType(Int::class.javaPrimitiveType!!)
                addFieldForType(Int::class.javaPrimitiveType!!)
            }
        }.mapNotNull { runCatching { classLoader.loadClass(it.name) }.getOrNull() }
            .singleOrNull { it.declaringClass != null } ?: return failed("notification item")
        val helperClass = itemClass.declaringClass
        val notify = helperClass.declaredMethods.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(itemClass, String::class.java, String::class.java))
        } ?: return failed("notify")
        val lockedData = bridge.findMethod {
            matcher {
                paramTypes(Collection::class.java)
                returnType(Void.TYPE)
                addInvoke(MethodMatcher.create().descriptor(MethodDescriptorCodec.descriptor(notify)))
            }
        }.singleOrNull { !Modifier.isStatic(it.modifiers) } ?: return failed("updateWithLocked")
        val notifierClass = classLoader.loadClass(lockedData.className)
        val updateWithLocked = lockedData.getMethodInstance(classLoader)
        val updateWith = bridge.findMethod {
            matcher {
                declaredClass(notifierClass.name, StringMatchType.Equals)
                paramTypes(Collection::class.java)
                returnType(Void.TYPE)
                addInvoke(MethodMatcher.create().descriptor(MethodDescriptorCodec.descriptor(updateWithLocked)))
            }
        }.singleOrNull()?.getMethodInstance(classLoader) ?: return failed("updateWith")
        val invokes = lockedData.invokes.distinctBy { it.descriptor }.filter { it.className == notifierClass.name && it.paramCount == 1 }
        val buildTag = invokes.singleOrNull { it.returnTypeName == "java.lang.String" }
            ?.getMethodInstance(classLoader) ?: return failed("tag builder")
        val info = buildTag.parameterTypes.singleOrNull() ?: return failed("DownloadInfo")
        val id = invokes.singleOrNull { it.returnTypeName == "long[]" }?.usingFields?.singleOrNull {
            it.usingType.isRead() && it.field.declaredClassName == info.name && it.field.typeName == "long"
        }?.field?.getFieldInstance(classLoader) ?: return failed("id field")
        val long = Long::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        fun field(owner: Class<*>, name: String, type: Class<*>): Field? = runCatching {
            owner.getDeclaredField(name).takeIf { it.type == type }?.apply { isAccessible = true }
        }.getOrNull()
        // The notifier's no-argument method that calls NotificationManager.cancelAll().
        val cancelAll = bridge.findMethod {
            matcher {
                declaredClass(notifierClass.name, StringMatchType.Equals)
                paramCount(0)
                returnType(Void.TYPE)
                addInvoke(MethodMatcher.create().descriptor("Landroid/app/NotificationManager;->cancelAll()V"))
            }
        }.singleOrNull()?.getMethodInstance(classLoader) ?: return failed("cancelAll")
        val receiver = runCatching {
            classLoader.loadClass("com.android.providers.downloads.receiver.DownloadNotificationReceiver")
        }.getOrNull()
        return Targets(
            buildTag = buildTag,
            updateWith = updateWith,
            updateWithLocked = updateWithLocked,
            notify = notify,
            itemTag = itemClass.declaredFields.singleOrNull { it.type == String::class.java } ?: return failed("item tag"),
            itemBuilder = itemClass.declaredFields.singleOrNull { it.type == Notification.Builder::class.java } ?: return failed("item builder"),
            helperContext = helperClass.declaredFields.singleOrNull { it.type == Context::class.java } ?: return failed("helper context"),
            notifierSpeeds = field(notifierClass, "mDownloadSpeed", Class.forName("android.util.LongSparseLongArray")) ?: return failed("speeds"),
            id = id,
            title = field(info, "mTitle", String::class.java) ?: return failed("title"),
            fileName = field(info, "mFileName", String::class.java) ?: return failed("file name"),
            currentBytes = field(info, "mCurrentBytes", long) ?: return failed("current bytes"),
            totalBytes = field(info, "mTotalBytes", long) ?: return failed("total bytes"),
            percent = field(info, "mPercent", int) ?: return failed("percent"),
            status = field(info, "mStatus", int) ?: return failed("status"),
            visibility = field(info, "mVisibility", int) ?: return failed("visibility"),
            notifyIsClear = field(info, "mNotifyIsClear", Boolean::class.javaPrimitiveType!!) ?: return failed("notify clear"),
            pkg = field(info, "mPackage", String::class.java) ?: return failed("package"),
            subPkg = field(info, "mSubPackage", String::class.java) ?: return failed("sub package"),
            receiver = receiver,
            cancelAll = cancelAll,
            activeNotifs = field(notifierClass, "mActiveNotifs", java.util.HashMap::class.java) ?: return failed("active notifs"),
            onReceive = receiver?.declaredMethods?.singleOrNull {
                it.name == "onReceive" && it.parameterTypes.contentEquals(arrayOf(Context::class.java, Intent::class.java))
            }?.apply { isAccessible = true },
        ).takeIf(::valid) ?: failed("validation")
    }

    private fun valid(t: Targets): Boolean =
        Modifier.isStatic(t.buildTag.modifiers) && t.buildTag.returnType == String::class.java &&
            t.buildTag.parameterTypes.contentEquals(arrayOf(t.id.declaringClass)) &&
            t.id.type == Long::class.javaPrimitiveType &&
            t.updateWith.declaringClass == t.updateWithLocked.declaringClass &&
            t.notifierSpeeds.declaringClass == t.updateWith.declaringClass &&
            t.itemTag.declaringClass == t.itemBuilder.declaringClass &&
            t.helperContext.declaringClass == t.notify.declaringClass &&
            (t.receiver == null) == (t.onReceive == null) &&
            t.cancelAll.declaringClass == t.updateWith.declaringClass && t.cancelAll.parameterCount == 0 &&
            t.activeNotifs.declaringClass == t.updateWith.declaringClass

    private fun failed(stage: String): Targets? {
        DebugLog.w(TAG, "target resolution stopped at $stage")
        return null
    }
}
