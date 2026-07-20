package com.takekazex.hypertweak.hook.rules.backgesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NativeInputMonitorRegistryTest {
    @Test
    public void snapshotsAndClearHaveStableLifecycle() {
        NativeInputMonitorRegistry<Object, String> registry = new NativeInputMonitorRegistry<>();
        Object owner = new Object();
        registry.put(owner, "first");
        registry.put(owner, "replacement");

        assertEquals(1, registry.size());
        assertEquals("replacement", registry.get(owner));
        assertEquals(1, registry.valuesSnapshot().size());

        registry.clear();
        assertTrue(registry.valuesSnapshot().isEmpty());
    }
}
