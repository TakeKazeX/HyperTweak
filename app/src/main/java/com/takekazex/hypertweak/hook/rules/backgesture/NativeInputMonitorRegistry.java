package com.takekazex.hypertweak.hook.rules.backgesture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class NativeInputMonitorRegistry<K, V> {
    private final WeakHashMap<K, V> monitors = new WeakHashMap<>();

    synchronized V get(K key) {
        return monitors.get(key);
    }

    synchronized void put(K key, V value) {
        monitors.put(key, value);
    }

    synchronized boolean contains(K key) {
        return monitors.containsKey(key);
    }

    synchronized List<Map.Entry<K, V>> entriesSnapshot() {
        return new ArrayList<>(monitors.entrySet());
    }

    synchronized List<V> valuesSnapshot() {
        return new ArrayList<>(monitors.values());
    }

    synchronized int size() {
        return monitors.size();
    }

    synchronized void clear() {
        monitors.clear();
    }
}
