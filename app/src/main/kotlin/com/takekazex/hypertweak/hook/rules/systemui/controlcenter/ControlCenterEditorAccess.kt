package com.takekazex.hypertweak.hook.rules.systemui.controlcenter

import com.takekazex.hypertweak.hook.base.HostAccess
import android.util.Log
import android.view.View
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Plugin reflection and reversible listener/animation access, without hook registration. */
internal class ControlCenterEditorAccess(private val resolveClass: (String) -> Class<*>?) {
    private companion object {
        const val TAG = "HyperTweak"
        const val ADAPTER_NAME = "miui.systemui.controlcenter.panel.main.recyclerview.MainPanelAdapter"
    }
    /** Cached per-controller-class reader that reports whether the panel is in EDIT mode. */

    private val editModeReaders = ConcurrentHashMap<Class<*>, ((Any) -> Boolean)>()
    /** `ControlCenterViewHolder.endAnimation()` — resets translation/alpha/scale to rest state. */

    var endAnimationMethod: java.lang.reflect.Method? = null
    /** The adapter's `attachedHolders` field (ArrayList[] of per-spread-row holder buckets). */

    var attachedHoldersField: Field? = null
    val NULL_TOUCH_LISTENER = Any()
    /**
     * The itemView touch listeners the host installed before the edit-mode blocker replaced
     * them (e.g. [ScaleItemViewHolder] holders — 设备中心, sliders — install themselves as the
     * itemView's OnTouchListener in their constructor). Restored when the panel leaves EDIT;
     * the value is [NULL_TOUCH_LISTENER] when the host had none.
     */

    val originalTouchListeners = java.util.WeakHashMap<View, Any>()
    fun isEditMode(controller: Any): Boolean {
        val reader = editModeReaders.computeIfAbsent(controller.javaClass) { cls ->
            buildEditModeReader(cls)
        }
        return reader(controller)
    }

    /**
     * Walks the controller's field hierarchy once and builds a reader that reports whether any
     * mode-holder reachable through the fields reads EDIT. Fields hold either the mode controller
     * itself (`getMode()`) or a lazy/provider wrapper around it (`get()` — dagger Provider, or
     * `invoke()` — kotlin Function0). Enum comparison is by name so no host enum class is loaded.
     */
    private fun buildEditModeReader(cls: Class<*>): (Any) -> Boolean {
        val paths = mutableListOf<Pair<Field, String?>>() // field to optional provider-unwrap method
        var type: Class<*>? = cls
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val fieldType = field.type
                runCatching { field.isAccessible = true }
                when {
                    declaresZeroArg(fieldType, "getMode") -> paths += field to null
                    declaresZeroArg(fieldType, "get") -> paths += field to "get"
                    declaresZeroArg(fieldType, "invoke") -> paths += field to "invoke"
                }
            }
            type = type.superclass
        }
        if (paths.isEmpty()) {
            Log.w(TAG, "ControlCenterCardsEdit: no mode reader on ${cls.name}")
        }
        return { controller ->
            paths.any { (field, unwrap) ->
                runCatching {
                    var value = field.get(controller) ?: return@runCatching false
                    if (unwrap != null) {
                        value = invokeChain(value, unwrap) ?: return@runCatching false
                    }
                    invokeChain(value, "getMode")?.toString()?.endsWith("EDIT") == true
                }.getOrDefault(false)
            }
        }
    }

    private fun declaresZeroArg(cls: Class<*>, name: String): Boolean =
        cls.declaredMethods.any {
            it.name == name && it.parameterCount == 0 && it.returnType != Void.TYPE
        }

    fun invokeChain(receiver: Any?, method: String): Any? {
        if (receiver == null) return null
        return runCatching {
            receiver.javaClass.methods.firstOrNull {
                it.name == method && it.parameterCount == 0
            }?.apply { isAccessible = true }?.invoke(receiver)
        }.getOrNull()
    }

    // ─── Draggability ──────────────────────────────────────────────────────────

    fun findItemTouchCallback(adapter: Class<*>): Class<*>? {
        val callbackSuper = "androidx.recyclerview.widget.ItemTouchHelper\$Callback"
        runCatching { adapter.declaredClasses }.getOrNull()
            ?.firstOrNull { cls -> cls.superclass?.name == callbackSuper }
            ?.let { return it }
        for (name in listOf("$ADAPTER_NAME\$itemTouchHelper\$2")) {
            resolveClass(name)?.let { candidate ->
                if (candidate.superclass?.name == callbackSuper ||
                    candidate.name.endsWith("\$itemTouchHelper\$2")
                ) {
                    return candidate
                }
            }
        }
        // Ordinal probing: the object-expression ordinal can shift between plugin builds.
        for (ordinal in 0..16) {
            resolveClass("$ADAPTER_NAME\$itemTouchHelper\$$ordinal")?.let { candidate ->
                if (callbackSuper.let { runCatching { Class.forName(it, false, candidate.classLoader) }.getOrNull() }
                        ?.isAssignableFrom(candidate) == true
                ) {
                    return candidate
                }
            }
        }
        return null
    }

    fun holderItemSpec(holder: Any): String? {
        val method = holder.javaClass.methods.firstOrNull {
            it.name == "getItem\$miui_controlcenter_release" && it.parameterCount == 0
        } ?: holder.javaClass.methods.firstOrNull {
            it.name.startsWith("getItem") && it.parameterCount == 0 &&
                it.returnType != Int::class.javaPrimitiveType &&
                it.returnType.name.endsWith("MainPanelListItem")
        } ?: return null
        val item = runCatching { method.apply { isAccessible = true }.invoke(holder) }.getOrNull()
            ?: return null
        return itemSpec(item)
    }

    fun itemSpec(item: Any?): String? {
        if (item == null) return null
        return invokeChain(item, "getSpec") as? String
    }

    /**
     * Iterates the adapter's `attachedHolders` buckets (one ArrayList per spread row).
     */
    fun forEachAttachedHolder(adapter: Any, block: (Any) -> Unit) {
        if (attachedHoldersField == null) return
        runCatching {
            val buckets = attachedHoldersField?.get(adapter) as? Array<*> ?: return@runCatching
            for (bucket in buckets) {
                val list = bucket as? List<*> ?: continue
                for (holder in list) {
                    if (holder != null) block(holder)
                }
            }
        }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: attached holders", t) }
    }

    /**
     * Clears any residual holder transforms (translation/alpha/scale) left by interrupted move
     * animations, so a reordered card's rendered position always matches its touch bounds —
     * a drifted card looks fine but is "inoperable" because the tap lands elsewhere.
     */
    fun clearResidualTransforms(adapter: Any) {
        if (endAnimationMethod == null) return
        forEachAttachedHolder(adapter) { holder ->
            runCatching { endAnimationMethod?.invoke(holder) }
                .onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: clear transforms", t) }
        }
    }

    /**
     * Resolves a way to suppress MainPanelAdapter's ControlCenterItemAnimator. While EDIT mode
     * is active the distributor's handleNotifyChanged keeps the animator unsuppressed (so the
     * pool-tile add/remove animates), and ControlCenterItemAnimator.prepareMove applies each
     * move's delta to the holder's *current* translation (`getHolderTransX() + delta`) — when
     * the next refresh interrupts those Folme moves (constant during a drag), views end up at
     * wrong translations, which is the grid visibly scrambling once big cards sit among the
     * tiles. Suppressing around drag refreshes makes the layout follow the finger instantly.
     */
    fun resolveAnimatorSuppressor(adapterCls: Class<*>): ((Any, Boolean) -> Unit)? {
        val rvField = findField(adapterCls, "recyclerView") ?: return null
        val animatorCls = "miui.systemui.controlcenter.panel.main.recyclerview.ControlCenterItemAnimator"
            .let(resolveClass) ?: return null
        val setter = animatorCls.methods.firstOrNull {
            it.name == "setSuppressAnimation" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: return null
        val getItemAnimator = resolveClass("androidx.recyclerview.widget.RecyclerView")
            ?.methods?.firstOrNull { it.name == "getItemAnimator" && it.parameterCount == 0 }
        return { adapter, suppress ->
            runCatching {
                val rv = rvField.get(adapter) ?: return@runCatching
                val animator = getItemAnimator?.invoke(rv) ?: return@runCatching
                setter.invoke(animator, suppress)
            }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: animator suppress", t) }
        }
    }

    fun restoreManagedTouchListeners(adapter: Any, itemViewField: Field?) {
        if (itemViewField == null) return
        forEachAttachedHolder(adapter) { holder ->
            runCatching {
                val itemView = itemViewField.get(holder) as? View ?: return@runCatching
                val saved = originalTouchListeners.remove(itemView) ?: return@runCatching
                itemView.setOnTouchListener(saved as? View.OnTouchListener)
            }.onFailure { t -> Log.e(TAG, "ControlCenterCardsEdit: restore touch listeners", t) }
        }
    }

    /**
     * Reads the itemView's current OnTouchListener. The SDK stub does not expose
     * `View.getOnTouchListener()` (it is @UnsupportedAppUsage), so it goes through reflection;
     * the hook runs in the SystemUI process, which is exempt from hidden-API enforcement.
     */
    fun currentTouchListener(view: View): View.OnTouchListener? =
        runCatching {
            val getter = HostAccess.requireDeclaredMethod(View::class.java, "getOnTouchListener", accessible = true)
            getter.invoke(view) as? View.OnTouchListener
        }.getOrNull()

    fun resolveDistributeContent(adapter: Class<*>): java.lang.reflect.Method? =
        CompatibleMethodResolver.find(adapter, "distributeContent", parameterTypes = listOf(Boolean::class.javaPrimitiveType!!))
            ?: adapter.declaredMethods.singleOrNull {
                it.name == "distributeContent" && it.parameterTypes.size == 1
            }

    fun findContentMapField(adapter: Class<*>): Field? =
        findField(adapter, "contentMap")
            ?: adapter.declaredFields.firstOrNull {
                !Modifier.isStatic(it.modifiers) &&
                    it.type.name.contains("LinkedHashMap")
            }?.apply { isAccessible = true }

    fun findField(cls: Class<*>, name: String): Field? =
        runCatching {
            var type: Class<*>? = cls
            while (type != null && type != Any::class.java) {
                try {
                    return HostAccess.requireDeclaredField(type, name, accessible = true)
                } catch (_: NoSuchFieldException) {
                    type = type.superclass
                }
            }
            null
        }.getOrNull()

    fun contentKey(content: Any): String {
        val name = content.javaClass.simpleName.lowercase()
        return when {
            name.contains("qscards") -> "qscards"
            name.contains("mediaplayer") -> "media"
            name.contains("brightness") -> "brightness"
            name.contains("volume") -> "volume"
            name.contains("devicecenter") -> "devicecenter"
            name.contains("qslist") -> "qslist"
            else -> name.replace("controller", "").replace("content", "")
        }
    }
}
