package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Looper
import android.os.UserHandle
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections

/**
 * Publishes module-owned bitmap icons through the OS4 StatusBarIconController.
 *
 * The bridge is intentionally strict about the host main thread and ownership. It never creates
 * a fake resource id, never overwrites a holder owned by another package, and only reports success
 * after reading the installed holder back from the host list.
 */
class HostIconBridge(
    private val controller: Any,
    private val hostClassLoader: ClassLoader
) {
    companion object {
        const val MODULE_PACKAGE = "com.takekazex.hypertweak"

        private const val TAG = "IconTuner"
        private const val HOLDER_TYPE_ICON = 0
    }

    private val lock = Any()
    private val ownedSlots = Collections.synchronizedSet(LinkedHashSet<String>())

    private val iconListField: Field? = findField(controller.javaClass, "mStatusBarIconList")
        ?.apply { isAccessible = true }
    private var holderMethod: Method? = null
    private var setIconMethod: Method? = null
    private var setVisibilityMethod: Method? = null
    private var removeSlotMethod: Method? = null
    private var holderClass: Class<*>? = null
    private var holderCtor: Constructor<*>? = null
    private var holderIconField: Field? = null
    private var holderTypeField: Field? = null
    private var holderTagField: Field? = null
    private var packageField: Field? = null

    /** Publishes or updates one ordinary (type 0, tag 0) module holder. Main-thread only. */
    fun publish(slot: String, icon: Bitmap, description: CharSequence? = null): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            DebugLog.w(TAG, "refusing off-main StatusBarIcon publish slot=$slot")
            return false
        }
        if (slot.isBlank() || icon.isRecycled) return false
        synchronized(lock) {
            val existing = findHolder(slot)
            val existingPackage = holderPackage(existing)
            if (existing != null && (existingPackage != MODULE_PACKAGE || !isOrdinary(existing))) {
                ownedSlots.remove(slot)
                if (existingPackage != null) {
                    DebugLog.w(TAG, "icon slot conflict slot=$slot package=$existingPackage")
                }
                return false
            }

            val statusIcon = createStatusIcon(icon, description) ?: return false
            val holder = existing ?: createHolder() ?: return false
            val isNew = existing == null
            val previous = if (isNew) null else HolderSnapshot(holder)
            if (!writeHolder(holder, statusIcon)) return false

            val installed = runCatching { resolveSetIconMethod(holder).invoke(controller, slot, holder) }
                .isSuccess
            if (!installed) {
                if (previous != null) restorePrevious(slot, holder, previous)
                else removeCandidate(slot, holder)
                return false
            }

            val verified = findHolder(slot)
            if (verified !== holder || holderPackage(verified) != MODULE_PACKAGE || !isOrdinary(verified)) {
                if (previous != null && verified === holder) restorePrevious(slot, holder, previous)
                else removeCandidate(slot, holder)
                return false
            }
            ownedSlots += slot
            return true
        }
    }

    /** Changes visibility only for a holder successfully published by this bridge. */
    fun setVisible(slot: String, visible: Boolean): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        synchronized(lock) {
            if (slot !in ownedSlots) return false
            val holder = findHolder(slot) ?: return false
            if (holderPackage(holder) != MODULE_PACKAGE || !isOrdinary(holder)) {
                ownedSlots.remove(slot)
                return false
            }
            return runCatching {
                resolveVisibilityMethod().invoke(controller, slot, visible)
                val current = findHolder(slot)
                current != null && holderPackage(current) == MODULE_PACKAGE &&
                    isOrdinary(current) && holderVisible(current) == visible
            }.getOrElse {
                DebugLog.w(TAG, "failed to set icon visibility slot=$slot", it)
                false
            }
        }
    }

    /** Removes a holder only when it is still the module-owned holder. */
    fun removeOwned(slot: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        synchronized(lock) {
            if (slot !in ownedSlots) return false
            val holder = findHolder(slot) ?: run {
                ownedSlots.remove(slot)
                return true
            }
            if (holderPackage(holder) != MODULE_PACKAGE) {
                ownedSlots.remove(slot)
                return false
            }
            val removed = runCatching {
                resolveRemoveMethod().invoke(controller, slot, true)
            }.onFailure { DebugLog.w(TAG, "failed to remove icon slot=$slot", it) }.isSuccess
            val currentPackage = holderPackage(findHolder(slot))
            if (removed && currentPackage != MODULE_PACKAGE) ownedSlots.remove(slot)
            return removed && currentPackage != MODULE_PACKAGE
        }
    }

    fun removeAllOwned() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        synchronized(lock) { ownedSlots.toList().forEach(::removeOwned) }
    }

    fun owns(slot: String): Boolean = synchronized(lock) { slot in ownedSlots }

    private fun createStatusIcon(bitmap: Bitmap, description: CharSequence?): Any? = runCatching {
        val statusIconClass = Class.forName(
            IconTunerFlows.hostClassName("com.android.internal", "statusbar.StatusBarIcon"),
            false,
            hostClassLoader
        )
        val typeClass = statusIconClass.declaredClasses.firstOrNull { it.simpleName == "Type" }
            ?: error("StatusBarIcon.Type not found")
        val shapeClass = statusIconClass.declaredClasses.firstOrNull { it.simpleName == "Shape" }
            ?: error("StatusBarIcon.Shape not found")
        val type = typeClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "SystemIcon" }
            ?: error("StatusBarIcon.Type.SystemIcon not found")
        val shape = shapeClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "WRAP_CONTENT" }
            ?: error("StatusBarIcon.Shape.WRAP_CONTENT not found")
        val ctor = statusIconClass.getDeclaredConstructor(
            UserHandle::class.java,
            String::class.java,
            Icon::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            CharSequence::class.java,
            typeClass,
            shapeClass
        ).apply { isAccessible = true }
        val systemUser = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
            .newInstance(0)
        ctor.newInstance(
            systemUser,
            MODULE_PACKAGE,
            Icon.createWithBitmap(bitmap),
            0,
            0,
            description,
            type,
            shape
        ).also { statusIcon ->
            findField(statusIcon.javaClass, "visible")?.apply {
                isAccessible = true
                setBoolean(statusIcon, true)
            }
        }
    }.onFailure { DebugLog.w(TAG, "failed to construct StatusBarIcon", it) }.getOrNull()

    private fun createHolder(): Any? = runCatching {
        val clazz = holderClass ?: Class.forName(
            IconTunerFlows.hostClassName("com.android.systemui.statusbar.phone", "StatusBarIconHolder"),
            false,
            hostClassLoader
        ).also { holderClass = it }
        val constructor = holderCtor ?: runCatching {
            clazz.getDeclaredConstructor().apply { isAccessible = true }
        }.getOrNull()
        if (constructor != null) {
            holderCtor = constructor
            constructor.newInstance()
        } else {
            // OS4's StatusBarIconHolder has no dex-declared constructor; host smali initializes it
            // by calling Object.<init> on the new instance. Reflection cannot reproduce that call,
            // but the class is a plain zero-initialized holder, so Unsafe allocation is equivalent.
            DebugLog.i(TAG, "StatusBarIconHolder has no declared constructor; using host allocation")
            IconTunerFlows.allocateInstance(clazz)
        }
    }.onFailure { DebugLog.w(TAG, "failed to construct StatusBarIconHolder", it) }.getOrNull()

    private fun writeHolder(holder: Any, statusIcon: Any): Boolean = runCatching {
        holderIconField(holder).set(holder, statusIcon)
        holderTypeField(holder).setInt(holder, HOLDER_TYPE_ICON)
        holderTagField(holder).setInt(holder, 0)
    }.onFailure { DebugLog.w(TAG, "failed to fill StatusBarIconHolder", it) }.isSuccess

    private fun findHolder(slot: String): Any? = runCatching {
        val list = iconListField?.get(controller) ?: return null
        val method = holderMethod ?: findMethod(list.javaClass) {
            it.name == "getIconHolder" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == String::class.java
        }?.apply { isAccessible = true }.also { holderMethod = it }
            ?: return null
        method.invoke(list, 0, slot)
    }.onFailure { DebugLog.w(TAG, "failed to inspect icon slot=$slot", it) }.getOrNull()

    private fun holderPackage(holder: Any?): String? = runCatching {
        val icon = holder?.let { holderIconField(it).get(it) } ?: return null
        (packageField ?: findField(icon.javaClass, "pkg")?.apply { isAccessible = true }
            .also { packageField = it })?.get(icon) as? String
    }.getOrNull()

    private fun isOrdinary(holder: Any): Boolean = runCatching {
        holderTypeField(holder).getInt(holder) == HOLDER_TYPE_ICON &&
            holderTagField(holder).getInt(holder) == 0 &&
            holderIconField(holder).get(holder) != null
    }.getOrDefault(false)

    private fun holderVisible(holder: Any): Boolean = runCatching {
        val icon = holderIconField(holder).get(holder) ?: return false
        findField(icon.javaClass, "visible")?.getBoolean(icon) ?: true
    }.getOrDefault(true)

    private fun resolveSetIconMethod(holder: Any): Method = setIconMethod ?: findMethod(controller.javaClass) {
        it.name == "setIcon" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isAssignableFrom(holder.javaClass)
    }?.apply { isAccessible = true }.also { setIconMethod = it }
        ?: error("StatusBarIconController#setIcon(String,Holder) not found")

    private fun resolveVisibilityMethod(): Method = setVisibilityMethod ?: findMethod(controller.javaClass) {
        it.name == "setIconVisibility" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
    }?.apply { isAccessible = true }.also { setVisibilityMethod = it }
        ?: error("StatusBarIconController#setIconVisibility not found")

    private fun resolveRemoveMethod(): Method = removeSlotMethod ?: findMethod(controller.javaClass) {
        it.name == "removeAllIconsForSlot" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == Boolean::class.javaPrimitiveType
    }?.apply { isAccessible = true }.also { removeSlotMethod = it }
        ?: error("StatusBarIconController#removeAllIconsForSlot not found")

    private fun removeCandidate(slot: String, candidate: Any) {
        runCatching {
            if (findHolder(slot) === candidate) resolveRemoveMethod().invoke(controller, slot, true)
        }.onFailure { DebugLog.w(TAG, "failed to clean half-created icon slot=$slot", it) }
    }

    private fun restorePrevious(slot: String, holder: Any, previous: HolderSnapshot) {
        previous.restore(holder)
        runCatching { resolveSetIconMethod(holder).invoke(controller, slot, holder) }
            .onFailure { DebugLog.w(TAG, "failed to restore icon slot=$slot after publish failure", it) }
    }

    private fun holderIconField(holder: Any): Field = holderIconField ?: findField(holder.javaClass, "icon")
        ?.apply { isAccessible = true }.also { holderIconField = it }
        ?: error("StatusBarIconHolder.icon not found")

    private fun holderTypeField(holder: Any): Field = holderTypeField ?: findField(holder.javaClass, "type")
        ?.apply { isAccessible = true }.also { holderTypeField = it }
        ?: error("StatusBarIconHolder.type not found")

    private fun holderTagField(holder: Any): Field = holderTagField ?: findField(holder.javaClass, "tag")
        ?.apply { isAccessible = true }.also { holderTagField = it }
        ?: error("StatusBarIconHolder.tag not found")

    private inner class HolderSnapshot(holder: Any) {
        private val icon = holderIconField(holder).get(holder)
        private val type = holderTypeField(holder).getInt(holder)
        private val tag = holderTagField(holder).getInt(holder)

        fun restore(holder: Any) {
            runCatching {
                holderIconField(holder).set(holder, icon)
                holderTypeField(holder).setInt(holder, type)
                holderTagField(holder).setInt(holder, tag)
            }
        }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            current.getDeclaredFieldOrNull(name)?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, predicate: (Method) -> Boolean): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull(predicate)?.let { return it }
            current = current.superclass
        }
        return type.methods.firstOrNull(predicate)
    }

    private fun Class<*>.getDeclaredFieldOrNull(name: String): Field? =
        runCatching { getDeclaredField(name) }.getOrNull()
}
