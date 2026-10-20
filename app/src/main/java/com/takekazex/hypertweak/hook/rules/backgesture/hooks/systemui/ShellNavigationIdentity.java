package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

/** Host navigation state must stay coherent before a MOVE can touch its tracker/callback. */
public final class ShellNavigationIdentity {
    private ShellNavigationIdentity() {}

    public static boolean isConsistent(Object navigation, boolean receivedNull) {
        return navigation == null || !receivedNull;
    }

    public static boolean matches(Object expected, boolean expectedNull,
                                  Object current, boolean receivedNull) {
        return isConsistent(current, receivedNull)
                && current == expected && receivedNull == expectedNull;
    }
}
