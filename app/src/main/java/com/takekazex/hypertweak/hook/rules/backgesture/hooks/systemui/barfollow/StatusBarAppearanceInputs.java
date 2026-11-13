package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow;

/** Preserve a shared native Flow packet, changing only its transient-mode input. */
public final class StatusBarAppearanceInputs {
    private StatusBarAppearanceInputs() { }

    public static Object withoutTransient(Object packet, Class<?> attributes) {
        if (!(packet instanceof Object[])) return packet;
        Object[] values = (Object[]) packet;
        if (values.length != 6 || values[0] == null || values[0].getClass() != attributes
                || !Boolean.TRUE.equals(values[1])) return packet;
        for (int i = 2; i < values.length; i++) if (!(values[i] instanceof Boolean)) return packet;
        Object[] corrected = values.clone();
        corrected[1] = Boolean.FALSE;
        return corrected;
    }
}
