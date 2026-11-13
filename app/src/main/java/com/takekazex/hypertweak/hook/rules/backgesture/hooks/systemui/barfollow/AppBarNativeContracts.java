package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Resolve native Dagger products from retained generic contracts, without creating other products.
 */
final class AppBarNativeContracts {
    private AppBarNativeContracts() {
    }

    static Object provider(Object owner, Class<?> product) throws ReflectiveOperationException {
        Field selected = null;
        for (Field field : owner.getClass().getDeclaredFields()) {
            Type generic = field.getGenericType();
            if (!(generic instanceof ParameterizedType)) continue;
            ParameterizedType parameterized = (ParameterizedType) generic;
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length != 1 || arguments[0] != product) continue;
            if (!field.getType().getName().equals("dagger.internal.Provider")
                    && !field.getType().getName().equals("javax.inject.Provider")) continue;
            if (selected != null)
                throw new NoSuchFieldException("Ambiguous native provider for " + product);
            selected = field;
        }
        if (selected == null) throw new NoSuchFieldException("No native provider for " + product);
        selected.setAccessible(true);
        Object provider = selected.get(owner);
        Method get = provider.getClass().getMethod("get");
        get.setAccessible(true);
        Object value = get.invoke(provider);
        if (value == null || value.getClass() != product)
            throw new IllegalStateException("Native provider returned a different product for " + product);
        return value;
    }
}
