package com.takekazex.hypertweak.hook.rules.backgesture.hooks.core;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Storage adapter only for the existing legacy resolver; never a new target-discovery API. */
final class LegacyMethodCache {
    private final ClassValue<ConcurrentHashMap<String, ConcurrentHashMap<Integer, Optional<Method>>>> owners =
            new ClassValue<>() {
                @Override protected ConcurrentHashMap<String, ConcurrentHashMap<Integer, Optional<Method>>> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };
    Optional<Method> get(Class<?> type, String name, int count) {
        var arities = owners.get(type).get(name);
        return arities == null ? null : arities.get(count);
    }
    Optional<Method> publish(Class<?> type, String name, int count, Method method) {
        var arities = owners.get(type).computeIfAbsent(name, unused -> new ConcurrentHashMap<>());
        Optional<Method> entry = Optional.ofNullable(method);
        Optional<Method> raced = arities.putIfAbsent(count, entry);
        return raced == null ? entry : raced;
    }
}
