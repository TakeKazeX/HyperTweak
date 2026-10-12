package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Capture a complete native wrapper set; each leaf must belong to exactly one wrapper. */
final class TransitionAnimatorSet {
    interface Unwrapper<T> { T[] unwrap(Object wrapper) throws Exception; }

    static <T> List<T> collect(List<?> wrappers, int originalCount,
                               Unwrapper<T> unwrapper) throws Exception {
        if (originalCount <= 0 || wrappers.size() != originalCount) return null;
        List<T> result = new ArrayList<>();
        IdentityHashMap<T, Boolean> seen = new IdentityHashMap<>();
        for (Object wrapper : wrappers) {
            T[] parts = unwrapper.unwrap(wrapper);
            if (parts == null || parts.length == 0) return null;
            for (T part : parts) {
                if (part == null || seen.put(part, Boolean.TRUE) != null) return null;
                result.add(part);
            }
        }
        return result;
    }
}
