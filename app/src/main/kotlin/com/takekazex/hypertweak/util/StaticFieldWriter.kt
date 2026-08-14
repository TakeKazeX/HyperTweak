package com.takekazex.hypertweak.util

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Writes static fields that ART refuses to write reflectively.
 *
 * ART on newer platforms (observed on HyperOS OS4 / Android 16+) rejects `Field.set` on
 * `static final` fields of initialized classes with `IllegalAccessException: Cannot set
 * public static final field ...`, which silently breaks any feature that patches such a field
 * through reflection (for example `UnlockClipboardHooker`'s `sCtsTestPkgList` and
 * `AospImeHooker`'s `IS_INTERNATIONAL_BUILD`). [set]/[setBoolean] try the reflective write
 * first and fall back to `Unsafe`.
 *
 * The deprecated `sun.misc.Unsafe` shim dropped `staticFieldBase`/`staticFieldOffset` on this
 * platform (`NoSuchMethodError`), so the offset and base come from the shim's internal
 * `jdk.internal.misc.Unsafe` instance (`sun.misc.Unsafe.theInternalUnsafe`), whose
 * `staticFieldBase`/`staticFieldOffset` are the platform's own static-field accessors; the
 * write itself goes through the shim's `putObject`/`putBoolean`, which delegate to the
 * internal instance. All reflection is resolved once and cached.
 */
object StaticFieldWriter {

    private val unsafe: sun.misc.Unsafe by lazy {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        f.get(null) as sun.misc.Unsafe
    }

    private val internalUnsafe: Any by lazy {
        Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theInternalUnsafe")
            .apply { isAccessible = true }
            .get(null)
    }

    private val staticFieldOffsetMethod: Method by lazy {
        internalUnsafe.javaClass.getMethod("staticFieldOffset", Field::class.java)
    }

    private val staticFieldBaseMethod: Method by lazy {
        internalUnsafe.javaClass.getMethod("staticFieldBase", Field::class.java)
    }

    /** Writes [value] into the static [field]. */
    fun set(field: Field, value: Any?) {
        if (!field.isAccessible) runCatching { field.isAccessible = true }
        runCatching { field.set(null, value) }.onFailure {
            unsafe.putObject(staticFieldBase(field), staticFieldOffset(field), value)
        }
    }

    /** Writes [value] into the static boolean [field]. */
    fun setBoolean(field: Field, value: Boolean) {
        if (!field.isAccessible) runCatching { field.isAccessible = true }
        runCatching { field.setBoolean(null, value) }.onFailure {
            unsafe.putBoolean(staticFieldBase(field), staticFieldOffset(field), value)
        }
    }

    private fun staticFieldOffset(field: Field): Long =
        (staticFieldOffsetMethod.invoke(internalUnsafe, field) as Number).toLong()

    private fun staticFieldBase(field: Field): Any? =
        staticFieldBaseMethod.invoke(internalUnsafe, field)
}
