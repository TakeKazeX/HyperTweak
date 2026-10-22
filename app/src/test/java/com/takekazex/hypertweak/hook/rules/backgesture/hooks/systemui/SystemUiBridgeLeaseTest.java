package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import java.util.Properties;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemUiBridgeLeaseTest {
    @Test public void leakedReceiverCannotConsumeAfterReplacementClaimsTheBridge() {
        Properties process = new Properties();
        SystemUiBridgeLease old = new SystemUiBridgeLease(process);
        SystemUiBridgeLease replacement = new SystemUiBridgeLease(process);
        old.claim();
        assertTrue(old.isActive());
        replacement.claim(); // Old unregister failed; old object remains alive.
        assertFalse(old.isActive());
        assertTrue(replacement.isActive());
        old.retire(); // Late teardown must not revoke the replacement.
        assertTrue(replacement.isActive());
        replacement.retire();
        assertFalse(replacement.isActive());
        assertTrue(process.isEmpty());
    }

    @Test public void retirementImmediatelyInvalidatesQueuedCallbacksAndFailedRegistration() {
        Properties process = new Properties();
        SystemUiBridgeLease owner = new SystemUiBridgeLease(process);
        assertFalse(owner.isActive());
        owner.claim();
        owner.retire();
        assertFalse(owner.isActive());
        SystemUiBridgeLease retry = new SystemUiBridgeLease(process);
        retry.claim();
        assertFalse(owner.isActive());
        assertTrue(retry.isActive());
    }

    @Test public void separateModuleClassLoadersStillShareOnlyStringOwnership() throws Exception {
        Properties process = new Properties();
        SystemUiBridgeLease old = new SystemUiBridgeLease(process);
        old.claim();
        String name = SystemUiBridgeLease.class.getName();
        ClassLoader isolated = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String target, boolean resolve) throws ClassNotFoundException {
                if (!target.equals(name)) return super.loadClass(target, resolve);
                try (java.io.InputStream stream = getParent().getResourceAsStream(target.replace('.', '/') + ".class")) {
                    byte[] bytes = stream.readAllBytes();
                    return defineClass(target, bytes, 0, bytes.length);
                } catch (java.io.IOException e) { throw new ClassNotFoundException(target, e); }
            }
        };
        Class<?> type = isolated.loadClass(name);
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        Object replacement = constructor.newInstance(process);
        java.lang.reflect.Method claim = type.getDeclaredMethod("claim");
        claim.setAccessible(true);
        claim.invoke(replacement);
        assertFalse(old.isActive());
        assertTrue(process.values().stream().allMatch(String.class::isInstance));
        old.retire();
        assertEquals(1, process.size());
    }
}
