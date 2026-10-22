package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import java.util.Properties;
import java.util.UUID;

/** Process-local ownership survives module class-loader replacement, without retaining it. */
final class SystemUiBridgeLease {
    private static final String KEY = "com.takekazex.hypertweak.systemui.launcher_bridge_owner";
    private final Properties owners;
    private final String token = UUID.randomUUID().toString();

    SystemUiBridgeLease() { this(System.getProperties()); }
    SystemUiBridgeLease(Properties owners) { this.owners = owners; }

    void claim() { owners.setProperty(KEY, token); }
    boolean isActive() { return token.equals(owners.getProperty(KEY)); }

    void retire() {
        synchronized (owners) {
            if (isActive()) owners.remove(KEY);
        }
    }
}
