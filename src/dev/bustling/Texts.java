package dev.bustling;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

public final class Texts {
    private Texts() {}

    public static String tr(String key) {
        return Language.getInstance().getOrDefault("bustling." + key);
    }

    public static String tr(String key, Object... args) {
        return I18n.get("bustling." + key, args);
    }

    /** Localize the format of a legacy concatenation on every call, including after a language reload. */
    public static CallSite legacyFormat(MethodHandles.Lookup lookup, String name, MethodType type, String key)
            throws NoSuchMethodException, IllegalAccessException {
        var handle = MethodHandles.lookup().findStatic(Texts.class, "tr",
                MethodType.methodType(String.class, String.class, Object[].class));
        return new ConstantCallSite(handle.bindTo(key).asCollector(Object[].class, type.parameterCount()).asType(type));
    }
}
