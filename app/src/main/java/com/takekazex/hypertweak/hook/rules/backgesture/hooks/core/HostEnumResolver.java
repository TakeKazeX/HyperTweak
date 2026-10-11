package com.takekazex.hypertweak.hook.rules.backgesture.hooks.core;

/** Resolves a host-loader enum without an unchecked self-referential generic cast. */
public final class HostEnumResolver {
    private HostEnumResolver() {}

    public static Enum<?> valueOf(Class<?> type, String name) {
        if (!type.isEnum()) {
            throw new IllegalArgumentException(type.getName() + " is not an enum");
        }
        for (Object constant : type.getEnumConstants()) {
            Enum<?> value = (Enum<?>) constant;
            if (value.name().equals(name)) {
                return value;
            }
        }
        throw new IllegalArgumentException("No enum constant " + type.getName() + "." + name);
    }
}
