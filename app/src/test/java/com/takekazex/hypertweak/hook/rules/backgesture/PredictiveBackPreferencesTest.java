package com.takekazex.hypertweak.hook.rules.backgesture;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class PredictiveBackPreferencesTest {
    private int effect(Map<String, Boolean> flags) {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoolean")) return flags.getOrDefault(args[0], (Boolean) args[1]);
                    throw new UnsupportedOperationException(method.getName());
                });
        return PredictiveBackPreferences.readGestureHapticEffect(preferences);
    }

    @Test public void oldSelectionSurvivesAndMuteTakesPrecedenceWithoutErasingIt() {
        assertEquals(PredictiveBackPreferences.GESTURE_HAPTIC_AOSP, effect(Map.of()));
        assertEquals(PredictiveBackPreferences.GESTURE_HAPTIC_HYPEROS,
                effect(Map.of(PredictiveBackPreferences.KEY_HYPEROS_HAPTICS, true)));
        assertEquals(PredictiveBackPreferences.GESTURE_HAPTIC_DISABLED,
                effect(Map.of(PredictiveBackPreferences.KEY_HYPEROS_HAPTICS, true,
                        PredictiveBackPreferences.KEY_GESTURE_HAPTICS_DISABLED, true)));
        assertEquals(PredictiveBackPreferences.GESTURE_HAPTIC_HYPEROS,
                effect(Map.of(PredictiveBackPreferences.KEY_HYPEROS_HAPTICS, true,
                        PredictiveBackPreferences.KEY_GESTURE_HAPTICS_DISABLED, false)));
    }
}
