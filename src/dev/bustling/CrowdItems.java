package dev.bustling;

import net.neoforged.bus.api.IEventBus;

public final class CrowdItems {
    private CrowdItems() {}

    public static void register(IEventBus bus) { WorldItems.register(bus); }
}
