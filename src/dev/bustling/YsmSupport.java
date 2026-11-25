package dev.bustling;

import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** Optional integration whose method bindings are specific to this YSM release. */
final class YsmSupport {
    static final String PACKAGE = "com.elfmcys.yesstevemodel.";
    static final String VERSION = "2.6.5-neoforge+mc1.21.1";
    private static Boolean available;

    private YsmSupport() {}

    static Path customFolder() {
        return FMLPaths.CONFIGDIR.get().resolve("yes_steve_model/custom");
    }

    static synchronized boolean available() {
        if (available != null) return available;
        available = false;
        var mod = ModList.get().getModContainerById("yes_steve_model");
        if (mod.isEmpty()) return false;
        if (!VERSION.equals(mod.get().getModInfo().getVersion().toString())) {
            Bustling.LOGGER.info("Bustling YSM integration supports {}", VERSION);
            return false;
        }
        try {
            available = (boolean) type("YesSteveModel").getMethod("isAvailable").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Bustling.LOGGER.warn("Could not initialize the optional YSM integration", failure);
        }
        return available;
    }

    static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(PACKAGE + name, false, YsmSupport.class.getClassLoader());
    }
}
