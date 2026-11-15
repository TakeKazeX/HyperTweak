package com.takekazex.hypertweak.hook.rules.sms

import android.content.Context
import android.os.Handler
import android.os.Message
import com.takekazex.hypertweak.hook.base.dex.MethodDescriptorCodec
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class NativeSmsCodeAccess(val receive: Method, val recognize: Method, val id: Method, val otp: Method) {
    companion object {
        fun decode(values: Map<String, String>, loader: ClassLoader): NativeSmsCodeAccess? {
            fun method(key: String) = values[key]?.let { MethodDescriptorCodec.method(it, loader) }
            val receive = method("receive") ?: return null
            val recognize = method("recognize") ?: return null
            val id = method("id") ?: return null
            val otp = method("otp") ?: return null
            if (Modifier.isStatic(receive.modifiers) || receive.returnType != Void.TYPE ||
                receive.parameterTypes.toList() != listOf(Message::class.java) ||
                !Handler::class.java.isAssignableFrom(receive.declaringClass) ||
                !Modifier.isStatic(recognize.modifiers) || recognize.parameterCount != 5 ||
                recognize.parameterTypes[0] != Context::class.java ||
                recognize.parameterTypes.drop(2) != listOf(Integer.TYPE, Integer.TYPE, java.lang.Boolean.TYPE) ||
                id.declaringClass != recognize.parameterTypes[1] || id.parameterCount != 0 ||
                id.returnType != java.lang.Long.TYPE || Modifier.isStatic(id.modifiers) ||
                otp.declaringClass != recognize.returnType || otp.parameterCount != 0 ||
                otp.returnType != String::class.java || Modifier.isStatic(otp.modifiers)) return null
            return NativeSmsCodeAccess(receive, recognize, id, otp)
        }
    }
}
