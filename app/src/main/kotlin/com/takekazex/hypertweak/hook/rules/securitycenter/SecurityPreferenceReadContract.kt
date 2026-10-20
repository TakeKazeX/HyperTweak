package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.hook.rules.camera.CameraDexIndex

/** A typed boolean default may be computed by the host; only the preference key is replaced. */
internal object SecurityPreferenceReadContract {
    fun matches(call: CameraDexIndex.CallSite, key: String): Boolean =
        call.method.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "Z") &&
            call.method.returnType == "Z" &&
            // An instance call would have an additional receiver operand.
            call.arguments.size == 2 && call.arguments[0] == CameraDexIndex.Value.Text(key)
}
