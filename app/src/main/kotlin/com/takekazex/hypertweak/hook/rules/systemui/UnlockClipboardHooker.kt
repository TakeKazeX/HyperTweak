package com.takekazex.hypertweak.hook.rules.systemui

import android.content.ClipboardManager
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Restores AOSP's clipboard overlay editor.
 *
 * `ClipboardListener.onPrimaryClipChanged` gates the whole overlay on
 * `sCtsTestPkgList.contains(getPrimaryClipSource())`, and on this baseline that list is
 * `Arrays.asList("com.android.cts.verifier")` — so the AOSP editor only ever appears under CTS.
 * Adding the app that owns the current clip makes the check pass for real copies.
 *
 * The list is rebuilt as `original + currentSource` rather than accumulated, so it stays at two
 * entries instead of growing by one for every app that has ever copied. `sCtsTestPkgList` is read
 * from exactly one place in SystemUI, so nothing else observes the substitution.
 *
 * HyperOS keeps showing its own editor as well; both appear.
 */
object UnlockClipboardHooker : StaticHooker() {
    private const val TAG = "UnlockClipboard"

    private const val CLIPBOARD_LISTENER = "com.android.systemui.clipboardoverlay.ClipboardListener"

    /** `mClipboardManager` on baselines before the per-user split. */
    private val CLIPBOARD_MANAGER_FIELDS = listOf("mClipboardManagerForUser", "mClipboardManager")

    @Volatile
    private var enabled = false

    private var ctsTestPkgListField: Field? = null
    private var originalCtsTestPkgList: List<*>? = null
    private var primaryClipSourceMethod: Method? = null

    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    override fun onPrepareHotReload() {
        restoreOriginalList()
        enabled = false
        ctsTestPkgListField = null
        originalCtsTestPkgList = null
        primaryClipSourceMethod = null
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_AOSP_CLIPBOARD_EDITOR, false)
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "clipboard overlay", "disabled")
            return
        }

        val listener = CLIPBOARD_LISTENER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CLIPBOARD_LISTENER, "class not found")
            return
        }

        val field = runCatching {
            listener.getDeclaredField("sCtsTestPkgList").apply { isAccessible = true }
        }.getOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "$CLIPBOARD_LISTENER#sCtsTestPkgList", "field not found")
            return
        }
        ctsTestPkgListField = field
        originalCtsTestPkgList = runCatching { field.get(null) as? List<*> }.getOrNull() ?: emptyList<String>()

        val method = CompatibleMethodResolver.find(listener, "onPrimaryClipChanged") ?: run {
            DebugLog.hookSkipped(TAG, "$CLIPBOARD_LISTENER#onPrimaryClipChanged()", "method not found")
            return
        }

        runCatching {
            method.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "onPrimaryClipChanged", Unit) {
                        allowCurrentClipSource(param.thisObject)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$CLIPBOARD_LISTENER#onPrimaryClipChanged()", it)
        }
    }

    private fun allowCurrentClipSource(listenerInstance: Any?) {
        if (!enabled || listenerInstance == null) return
        val field = ctsTestPkgListField ?: return
        val original = originalCtsTestPkgList ?: return

        val clipboardManager = CLIPBOARD_MANAGER_FIELDS.firstNotNullOfOrNull { name ->
            runCatching {
                listenerInstance.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    .get(listenerInstance) as? ClipboardManager
            }.getOrNull()
        } ?: return

        // getPrimaryClipSource() is @hide.
        val sourceMethod = primaryClipSourceMethod ?: runCatching {
            ClipboardManager::class.java.getMethod("getPrimaryClipSource").apply { isAccessible = true }
        }.getOrNull()?.also { primaryClipSourceMethod = it } ?: return

        val source = runCatching { sourceMethod.invoke(clipboardManager) as? String }.getOrNull() ?: return
        if (source in original) return

        field.set(null, original + source)
    }

    private fun restoreOriginalList() {
        val field = ctsTestPkgListField ?: return
        val original = originalCtsTestPkgList ?: return
        runCatching { field.set(null, original) }
            .onFailure { DebugLog.w(TAG, "failed to restore sCtsTestPkgList", it) }
    }
}
