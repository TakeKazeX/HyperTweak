package com.takekazex.hypertweak.hook.rules.phrase

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.text.InputFilter
import android.text.SpannedString
import android.view.View
import android.widget.EditText
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.hook.base.HostActivityRefresh
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.dex.DexContractMembers
import com.takekazex.hypertweak.hook.base.dex.DexFeature
import com.takekazex.hypertweak.hook.base.dex.DexRuntime
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.PhraseSettingsKeys
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.PlatformLevel
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

private object PhraseLimitFeature : DexFeature {
    override val id = "phrase.unlock_limits"
    override val schema = 5
}

object PhraseLimitHooker : StaticHooker() {
    private const val TAG = "PhraseLimits"
    private const val NATIVE_PHRASE_COUNT = 20
    private const val NATIVE_PHRASE_LENGTH = 256
    private const val LOCAL_CLIP_DATA = 10
    private val enabled by hookSetting(false) { Preferences.getBoolean(PhraseSettingsKeys.KEY_UNLOCK_LIMITS, false) }
    private val allowIme by hookSetting(false) { Preferences.getBoolean(PhraseSettingsKeys.KEY_ALLOW_IME_HISTORY, false) }
    private val customLimits by hookSetting(false) { Preferences.getBoolean(PhraseSettingsKeys.KEY_CLIPBOARD_LIMITS, false) }
    private val countLimit by hookSetting(PhraseSettingsKeys.DEFAULT_COUNT) {
        PhraseSettingsKeys.normalizeCount(Preferences.getInt(PhraseSettingsKeys.KEY_CLIPBOARD_COUNT, PhraseSettingsKeys.DEFAULT_COUNT))
    }
    private val daysLimit by hookSetting(PhraseSettingsKeys.DEFAULT_DAYS) {
        PhraseSettingsKeys.normalizeDays(Preferences.getInt(PhraseSettingsKeys.KEY_CLIPBOARD_DAYS, PhraseSettingsKeys.DEFAULT_DAYS))
    }
    private val queryHistory = ThreadLocal<Boolean>()
    private val allowProvider = ThreadLocal<ContentProvider>()
    private val allowSignature = ThreadLocal<Boolean>()
    private val assemblies = generationResource(mutableListOf<ClipboardChunkTransfer>()) { bridges ->
        bridges.forEach { assembly -> clipboardResult("retire clipboard transfers") { assembly.retireAll() } }
        bridges.clear()
    }
    private var editorInput by generationState<Field?> { null }
    private val editorFilters = generationMainResource(WeakHashMap<EditText, Array<InputFilter>>()) { editors ->
        editors.forEach { (editor, filters) -> restoreFilters(editor, filters) }
        editors.clear()
    }

    init {
        hostRecovery(HostRecoveryPhase.CONTENT) { refreshEditors() }
    }

    override fun onHook() {
        if (!PlatformLevel.isOs4 || hookParam.processName != PhraseSettingsKeys.PACKAGE) return
        val apk = hookParam.appInfo?.sourceDir ?: return
        val cached = resolve(apk, cacheOnly = true)
        if (cached != null) installDexContract { install(cached) }
        else prepareDexTargets(PhraseLimitFeature.id, { resolve(apk, cacheOnly = false) }, ::install)
    }

    private fun resolve(apk: String, cacheOnly: Boolean): DexContractMembers? = DexRuntime.resolve(
        feature = PhraseLimitFeature, apkPath = apk, cacheDir = dexCacheDirectory, cacheOnly = cacheOnly,
        compute = {
            val dex = DexRuntime.operands(apk)
            PhraseLimitResolver(dex).resolve() + ClipboardProviderResolver(dex).resolve()
        },
        decode = { values, _ -> DexContractMembers(values, classLoader) },
    )

    private fun install(members: DexContractMembers) {
        val writer = members.method("writer")
        val parse = members.method("parse")
        val serialize = members.method("serialize")
        val click = members.method("click")
        val adapter = members.field("adapter")
        val count = members.method("count")
        val button = members.text("button").toInt()
        val editor = members.method("editor")
        val input = members.field("input")
        val save = members.method("save")
        val models = ClipboardModels(
            members.method("time"), members.method("content"), members.method("type"), members.method("across"),
        )
        val cleanupTask = members.constructor("cleanupTask")
        val postCleanup = members.method("postCleanup")
        check(deoptimize(save)) { "Cannot prepare native clipboard save caller" }
        installProvider(members, models)

        writer.hook("phrase_clipboard_history") {
            intercept { chain ->
                val newest = chain.args.getOrNull(2)
                val replacement = if ((customLimits || allowIme) && newest != null) {
                    clipboardResult("merge clipboard history") {
                        val previous = parse.invoke(null, chain.args.getOrNull(3)) as List<*>
                        val history = previous.filterNotNull().toMutableList()
                        history.remove(newest)
                        history.add(0, newest)
                        val distinct = models.coalesce(history)
                        val custom = customLimits
                        if (!custom && distinct.size == history.size) return@clipboardResult null
                        val retained = bounded(distinct, models, custom)
                        val json = encodeHistory(retained, serialize)
                        val retainedByIdentity = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
                        retainedByIdentity.addAll(retained)
                        val discarded = distinct.filter { it !== newest && it !in retainedByIdentity }
                        if (discarded.isNotEmpty()) {
                            clipboardResult("schedule clipboard file cleanup") {
                                val task = cleanupTask.newInstance(
                                    chain.thisObject, chain.args.getOrNull(0), chain.args.getOrNull(1), discarded,
                                )
                                postCleanup.invoke(null, task)
                            }
                        }
                        json
                    }
                } else null
                replacement ?: chain.proceed()
            }
        }

        save.hook("phrase_bulk_history_limits") {
            intercept { chain ->
                val extras = chain.args.getOrNull(2) as? Bundle
                val json = if (customLimits || allowIme) extras?.getString("jsonArray") else null
                val replacement = if (json != null && extras != null) {
                    clipboardResult("bound clipboard write-back") {
                        val rows = (parse.invoke(null, json) as List<*>).filterNotNull()
                        val distinct = models.coalesce(rows)
                        val custom = customLimits
                        if (!custom && distinct.size == rows.size) return@clipboardResult null
                        val retained = if (custom) bounded(distinct, models, custom = true) else distinct
                        Bundle(extras).apply { putString("jsonArray", encodeHistory(retained, serialize)) }
                    }
                } else null
                if (replacement == null) chain.proceed()
                else chain.proceed(chain.args.toTypedArray().also { it[2] = replacement })
            }
        }

        click.hook("phrase_add_count") {
            intercept { chain ->
                val activity = chain.thisObject as? Activity
                val view = chain.args.getOrNull(0) as? View
                val launched = if (enabled && activity != null && view?.id == button) {
                    HookFailurePolicy.open(TAG, "open phrase editor", false) {
                        val listAdapter = adapter.get(activity) ?: return@open false
                        val size = count.invoke(listAdapter) as? Int ?: return@open false
                        if (size < NATIVE_PHRASE_COUNT) return@open false
                        val intent = Intent(activity, editor.declaringClass).setAction(PhraseLimitResolver.ACTION_ADD)
                        activity.startActivityForResult(intent, 0)
                        true
                    }
                } else false
                if (launched) null else chain.proceed()
            }
        }

        editor.hook("phrase_edit_length") {
            intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (enabled && activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    HookFailurePolicy.open(TAG, "refresh phrase length filter", Unit) {
                        val field = input.get(activity) as? EditText ?: return@open
                        unlockEditor(field, activity.intent)
                    }
                }
                result
            }
        }
        editorInput = input
        refreshEditors()
        DebugLog.hookRegistered(TAG, "provider access, history retention and phrase limits ready")
    }

    private fun installProvider(members: DexContractMembers, models: ClipboardModels) {
        val query = members.method("query")
        val insert = members.method("insert")
        val gate = members.method("gate")
        val parser = members.method("queryParser")
        val assembly = ClipboardChunkTransfer(members.field("assembly"), members.field("receivedBytes"))
        val certificates = members.field("signatureList").get(null) as List<*>
        val contains = HostAccess.requireDeclaredMethod(
            certificates.javaClass, "contains", Any::class.java, accessible = true,
        )
        check(deoptimize(query) && deoptimize(insert)) { "Cannot prepare native clipboard provider callers" }

        gate.hook("phrase_default_ime_gate") {
            intercept { chain ->
                val provider = allowProvider.get()
                if (provider != null && provider === chain.thisObject) true else chain.proceed()
            }
        }
        contains.hook("phrase_default_ime_insert_signer") {
            intercept { chain ->
                if (allowSignature.get() == true && chain.thisObject === certificates) true else chain.proceed()
            }
        }
        query.hook("phrase_history_query") {
            intercept { chain ->
                val provider = chain.thisObject as? ContentProvider
                val local = ClipboardProviderAccess.isLocalHistory(chain.args.getOrNull(0) as? Uri)
                val grant = if (local) defaultImeGrant(provider) else null
                val project = local && (customLimits || allowIme)
                val result = withThreadValue(queryHistory, project) {
                    withThreadValue(allowProvider, grant) { chain.proceed() }
                }
                if (project && result is Cursor) suppressRawHistoryFallback(result) else result
            }
        }
        insert.hook("phrase_history_insert") {
            intercept { chain ->
                val provider = chain.thisObject as? ContentProvider
                val local = ClipboardProviderAccess.isLocalHistory(chain.args.getOrNull(0) as? Uri)
                if (!local || provider == null) {
                    withThreadValue(allowProvider, null) {
                        withThreadValue(allowSignature, false) { chain.proceed() }
                    }
                } else synchronized(provider) {
                    val grant = defaultImeGrant(provider)
                    if (grant != null) {
                        val extras = chain.args.getOrNull(1) as? ContentValues
                        val admitted = clipboardResult("admit clipboard chunk") {
                            assembly.admit(provider, Binder.getCallingUid(), extras)
                        } == true
                        if (!admitted) null else {
                            try {
                                withThreadValue(allowProvider, grant) {
                                    withThreadValue(allowSignature, true) { chain.proceed() }
                                }
                            } finally {
                                clipboardResult("finish clipboard chunk") { assembly.finish(provider) }
                            }
                        }
                    } else {
                        val canProceed = clipboardResult("check clipboard transfer state") {
                            if (!allowIme) assembly.retire(provider)
                            !assembly.hasPending(provider)
                        } == true
                        if (!canProceed) null else withThreadValue(allowProvider, null) {
                            withThreadValue(allowSignature, false) { chain.proceed() }
                        }
                    }
                }
            }
        }
        parser.hook("phrase_history_query_limits") {
            intercept { chain ->
                val native = chain.proceed()
                if (queryHistory.get() == true && native is ArrayList<*>) {
                    clipboardResult("project clipboard query") {
                        val distinct = models.coalesce(native.filterNotNull())
                        if (customLimits) bounded(distinct, models, custom = true) else distinct
                    } ?: native
                } else native
            }
        }
        assemblies.add(assembly)
    }

    private fun suppressRawHistoryFallback(cursor: Cursor): Cursor {
        // The native provider emits one raw JSON array row when no model row was written.
        // A history cursor exposes one JSON object per row, including the single-item case.
        val valid = clipboardResult("validate clipboard cursor") {
            if (cursor.count != 1) true else {
                val position = cursor.position
                try {
                    val column = cursor.getColumnIndexOrThrow("phrase_content")
                    cursor.moveToFirst() && cursor.getString(column)?.trimStart()?.startsWith('{') == true
                } finally {
                    cursor.moveToPosition(position)
                }
            }
        } == true
        if (valid) return cursor
        clipboardResult("close unfiltered clipboard cursor") { cursor.close() }
        return MatrixCursor(arrayOf("phrase_content"))
    }

    private fun defaultImeGrant(provider: ContentProvider?): ContentProvider? =
        if (allowIme && provider != null) {
            clipboardResult("check current input method") {
                provider.takeIf(ClipboardProviderAccess::isDefaultIme)
            }
        } else null

    private class ClipboardModels(
        private val time: Method,
        private val content: Method,
        private val type: Method,
        private val across: Method,
    ) {
        fun timeOf(item: Any): Long = time.invoke(item) as Long

        fun coalesce(rows: List<Any>): ArrayList<Any> =
            ClipboardTextCoalescer.merge(rows, ::timeOf) { item ->
                if (type.invoke(item) == LOCAL_CLIP_DATA && across.invoke(item) == false) {
                    content.invoke(item) as? String
                } else null
            }
    }

    private fun bounded(rows: List<Any>, models: ClipboardModels, custom: Boolean): ArrayList<Any> {
        val count = if (custom) countLimit else PhraseSettingsKeys.DEFAULT_COUNT
        val days = if (custom) daysLimit else PhraseSettingsKeys.DEFAULT_DAYS
        val cutoff = System.currentTimeMillis() - PhraseSettingsKeys.retentionMillis(days)
        val kept = ArrayList<Any>(minOf(count, rows.size))
        for (item in rows) {
            if (kept.size == count) break
            if (models.timeOf(item) >= cutoff) kept.add(item)
        }
        return kept
    }

    private fun encodeHistory(history: List<Any>, serialize: Method): String =
        JSONArray().apply { history.forEach { put(serialize.invoke(it) as JSONObject) } }.toString()

    private inline fun <T> clipboardResult(operation: String, action: () -> T): T? =
        runCatching(action).onFailure { DebugLog.w(TAG, "$operation failed (${it.javaClass.simpleName})") }.getOrNull()

    private inline fun <T, R> withThreadValue(slot: ThreadLocal<T>, value: T?, action: () -> R): R {
        val old = slot.get()
        if (value == null) slot.remove() else slot.set(value)
        return try { action() } finally {
            if (old == null) slot.remove() else slot.set(old)
        }
    }

    fun refreshEditors(): Boolean = HostAccess.onMain {
        val input = editorInput ?: return@onMain true
        HookFailurePolicy.open(TAG, "refresh open phrase editors", false) {
            if (enabled) {
                HostActivityRefresh.activities().filter(input.declaringClass::isInstance).forEach { activity ->
                    (input.get(activity) as? EditText)?.let { unlockEditor(it) }
                }
            } else {
                editorFilters.forEach { (editor, filters) -> editor.filters = filters }
                editorFilters.clear()
            }
            true
        }
    }

    private fun unlockEditor(editor: EditText, launch: Intent? = null) {
        val original = editor.filters
        val limit = original.filterIsInstance<InputFilter.LengthFilter>()
            .singleOrNull { it.max == NATIVE_PHRASE_LENGTH } ?: return
        editorFilters[editor] = original
        editor.filters = original.filterNot { it === limit }.toTypedArray()

        // onCreate pre-fills through the XML filter before the hook can reach the field.
        // Restore only a recognized clipped prefill; live refresh must never replace a draft.
        val extra = if (launch?.action == PhraseLimitResolver.ACTION_EDIT) "words" else "phrase_list"
        val source = launch?.getStringExtra(extra) ?: return
        val clipped = limit.filter(source, 0, source.length, SpannedString(""), 0, 0) ?: return
        if (editor.text.contentEquals(clipped)) {
            editor.setText(source)
            editor.setSelection(editor.length())
        }
    }

    private fun restoreFilters(editor: EditText, original: Array<InputFilter>) {
        HookFailurePolicy.open(TAG, "restore phrase length filter", Unit) { editor.filters = original }
    }
}
