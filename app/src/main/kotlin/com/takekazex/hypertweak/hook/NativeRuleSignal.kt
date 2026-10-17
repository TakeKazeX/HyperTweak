package com.takekazex.hypertweak.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.util.DebugLog

/** Authenticated app -> SystemUI hints carry only acknowledged, complete Preferences snapshots. */
internal object NativeRuleProtocol {
    const val MODULE = "com.takekazex.hypertweak"
    const val SYSTEM_UI = "com.android.systemui"
    const val CHANGED = "$MODULE.action.NATIVE_SETTINGS_CHANGED"
    const val REQUEST = "$MODULE.action.NATIVE_SETTINGS_REQUEST"
    private val options by lazy { Class.forName("android.app.BroadcastOptions") }
    private val make by lazy { options.getMethod("makeBasic") }
    private val identity by lazy { options.getMethod("setShareIdentityEnabled", Boolean::class.javaPrimitiveType) }
    private val bundle by lazy { options.getMethod("toBundle") }
    fun send(context: Context, intent: Intent) {
        val value = make.invoke(null)
        identity.invoke(value, true)
        context.sendBroadcast(intent, null, bundle.invoke(value) as Bundle)
    }
    fun trusted(context: Context, senderPackage: String?, senderUid: Int, expected: String): Boolean =
        senderPackage == expected && senderUid >= 0 && runCatching {
            context.packageManager.getPackageUid(expected, 0) == senderUid
        }.getOrDefault(false)
    fun encode(snapshot: NativeRuleStateSnapshot): Intent = Intent(CHANGED).setPackage(SYSTEM_UI)
        .putExtra("hidden", snapshot.hidden).putExtra("columns", snapshot.columns)
        .putExtra("contextual", snapshot.contextualSearch).putExtra("revision", snapshot.revision)
        .putExtra("epoch", snapshot.epoch).putExtra("assistant_widgets", snapshot.assistantWidgets)
    fun encodeBackSettings(intent: Intent, snapshot: BackGestureSettingsSnapshot?): Intent {
        if (snapshot != null) {
            intent.putExtra("back_revision", snapshot.revision).putExtra("back_epoch", snapshot.epoch)
            BackGestureSettingsSnapshot.KEYS.forEachIndexed { index, key -> intent.putExtra(key, snapshot.values[index]) }
        }
        return intent
    }
    fun decodeBackSettings(intent: Intent): BackGestureSettingsSnapshot? = runCatching {
        val extras = intent.extras ?: return null
        val keys = BackGestureSettingsSnapshot.KEYS
        if (!extras.containsKey("back_revision") || !extras.containsKey("back_epoch") || keys.any { !extras.containsKey(it) }) return null
        @Suppress("DEPRECATION")
        val values = keys.associateWith { extras.get(it) } + mapOf(
            BackGestureSettingsSnapshot.REVISION to extras.get("back_revision"), "prefs_epoch" to extras.get("back_epoch"))
        if (values.values.any { it == null }) return null
        BackGestureSettingsSnapshot.read(values)
    }.getOrNull()
    fun decode(intent: Intent): NativeRuleStateSnapshot? = runCatching {
        val extras = intent.extras ?: return null
        @Suppress("DEPRECATION")
        val values = mapOf(Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON to extras.get("hidden"),
            Preferences.KEY_OPENED_FOLDER_COLUMNS to extras.get("columns"),
            Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS to extras.get("contextual"),
            Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT to extras.get("assistant_widgets"),
            Preferences.KEY_NATIVE_RULE_REVISION to extras.get("revision"), "prefs_epoch" to extras.get("epoch"))
        if (values.values.any { it == null }) return null
        NativeRuleStateSnapshot.read { values }
    }.getOrNull()
}

internal object NativeRuleSignal {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var context: Context? = null
    private var source: SharedPreferences? = null
    private val acknowledged = NativeSnapshotLedger()
    private val acknowledgedBack = BackGestureSettingsLedger()
    fun attach(context: Context) { this.context = context.applicationContext ?: context }
    @Synchronized fun bind(prefs: SharedPreferences) {
        if (source === prefs) return
        source = prefs
        Preferences.nativeRuleSnapshot()?.let(acknowledged::offer)
        runCatching { BackGestureSettingsSnapshot.read(prefs.all) }
            .onFailure { DebugLog.w("BackGesture", "settings baseline unavailable", it) }
            .getOrNull()?.let(acknowledgedBack::offer)
        publishCommitted()
    }
    fun prepareMutation(key: String, value: Any, revision: Long): NativeRuleStateSnapshot = NativeRuleMutation.apply(
        acknowledged.latest() ?: Preferences.nativeRuleSnapshot() ?: error("no authoritative native settings baseline"),
        key, value, revision)
    fun lastAcknowledged(): NativeRuleStateSnapshot? = acknowledged.latest()
    fun prepareBackMutation(key: String, value: Boolean): BackGestureSettingsSnapshot {
        val old = acknowledgedBack.latest() ?: BackGestureSettingsSnapshot.read(source?.all ?: error("back settings unavailable"))
            ?: error("invalid back settings baseline")
        return old.mutate(key, value, NativeSnapshotLedger.nextRevision(old.revision, android.os.SystemClock.elapsedRealtimeNanos()))
    }
    fun writeBackSettings(editor: SharedPreferences.Editor, snapshot: BackGestureSettingsSnapshot) {
        BackGestureSettingsSnapshot.KEYS.forEachIndexed { index, key -> editor.putBoolean(key, snapshot.values[index]) }
        editor.putLong(BackGestureSettingsSnapshot.REVISION, snapshot.revision)
    }
    fun lastAcknowledgedBack(): BackGestureSettingsSnapshot? = acknowledgedBack.latest()
    fun acknowledgeBack(snapshot: BackGestureSettingsSnapshot) {
        if (acknowledgedBack.offer(snapshot)) publishCommitted()
    }
    fun acknowledge(snapshot: NativeRuleStateSnapshot) {
        if (acknowledged.offer(snapshot)) {
            Preferences.invalidateRuntimeReadCache()
            publishCommitted(snapshot.revision)
        }
    }
    fun publishCommitted(minimumRevision: Long = 0) {
        val target = context ?: return
        // A Binder notification may race the editor acknowledgement. Retry finitely, with no timer
        // retained once the complete acknowledged revision is readable.
        fun attempt(index: Int) {
            val snapshot = acknowledged.latest()
            if (snapshot == null || snapshot.revision < minimumRevision) {
                if (index < 3) handler.postDelayed({ attempt(index + 1) }, 250L)
                else DebugLog.w("NativeRules", "app settings snapshot unavailable after commit revision=$minimumRevision")
                return
            }
            runCatching { NativeRuleProtocol.send(target, NativeRuleProtocol.encodeBackSettings(
                NativeRuleProtocol.encode(snapshot), acknowledgedBack.latest())) }
                .onFailure { DebugLog.w("NativeRules", "app settings notification failed", it) }
        }
        handler.post { attempt(0) }
    }
}

/** Only framework-authenticated SystemUI or screenshot requests may wake their sync response. */
class NativeRuleSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == com.takekazex.hypertweak.hook.rules.screenshot.ScreenshotSettingsProtocol.REQUEST) {
            if (NativeRuleProtocol.trusted(context, sentFromPackage, sentFromUid,
                    com.takekazex.hypertweak.hook.rules.screenshot.ScreenshotSettingsProtocol.PACKAGE)) {
                com.takekazex.hypertweak.hook.rules.screenshot.ScreenshotSettingsSignal.publishCommitted()
            }
            return
        }
        if (!NativeRuleProtocol.trusted(context, sentFromPackage, sentFromUid, NativeRuleProtocol.SYSTEM_UI)) return
        when (intent.action) {
            NativeRuleProtocol.REQUEST -> NativeRuleSignal.publishCommitted()
            IconSettingsProtocol.REQUEST -> IconSettingsSignal.publishCommitted()
        }
    }
}
