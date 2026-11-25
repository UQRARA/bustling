package dev.bustling;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;

public final class YsmClientModels {
    private static final String PREFIX = "bustling:ysm_model/";
    private static final int REFRESH_TICKS = 20;
    private static final int PROXY_TTL = 200;
    private static final Map<ProxyKey, Proxy> PROXIES = new HashMap<>();
    private static ClientLevel level;
    private static Bindings api;
    private static boolean attempted;
    private static int tick;
    private static int revision;
    private static Map<String, Model> models = Map.of();
    private static Map<?, ?> lastCatalog;
    private static Set<?> lastPermissions = Set.of();
    private static String lastLanguage;

    record Model(String id, String texture, ClientSkinPacks.Listing listing) {}
    private record ProxyKey(String group, int index) {}

    private static final class Proxy {
        final Model model;
        final RemotePlayer player;
        int lastSeen;

        Proxy(Model model, RemotePlayer player) {
            this.model = model;
            this.player = player;
        }
    }

    private static final class CrowdPlayer extends RemotePlayer {
        CrowdPlayer(ClientLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "Bustling"));
            setOnGround(true);
        }

        @Override public Component getDisplayName() {
            return Component.empty();
        }

        @Override public void tick() {
            // MobMotion advances the visual clocks without running player physics or world interactions.
        }
    }

    private YsmClientModels() {}

    static String rowId(String id) {
        return PREFIX + id;
    }

    static boolean isModelRow(String row) {
        return row != null && row.startsWith(PREFIX);
    }

    static void reloadLocalModels() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !YsmSupport.available()) return;
        // The integrated server owns the local YSM catalog, including unpacked models.
        server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(), "ysm model reload"));
    }

    static int revision() {
        return revision;
    }

    static List<Model> models() {
        return List.copyOf(models.values());
    }

    static boolean addModels(CrowdData.Emitter group, List<Model> selected) {
        if (group == null || AppearanceModes.isWhite(group) || selected.isEmpty()) return false;
        if (group.mobs == null) group.mobs = new ArrayList<>();
        Set<String> assigned = new HashSet<>();
        for (var row : group.mobs) {
            if (row != null) assigned.add(row.id);
        }
        boolean added = false;
        for (Model item : selected) {
            String id = item.listing().rowId();
            Model current = models.get(id);
            if (current == null || !assigned.add(id)) continue;
            group.mobs.add(new CrowdData.Emitter.MobWeight(id, 1, "", current.listing().name()));
            added = true;
        }
        if (!added) return false;
        group.mobs.removeIf(row -> row == null || AppearanceModes.isDefault(row));
        double weight = 1.0 / group.mobs.size();
        for (var row : group.mobs) row.weight = weight;
        MobMotion.dropGroup(group.id);
        CrowdData.save();
        return true;
    }

    static ClientSkinPacks.Listing listing(String row) {
        Model model = models.get(row);
        return model == null ? null : model.listing();
    }

    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            clear();
            level = mc.level;
        }
        if (level == null || mc.player == null) return;
        if (!attempted) {
            attempted = true;
            if (YsmSupport.available()) {
                try {
                    api = Bindings.load();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    disable(failure);
                }
            }
        }
        if (api == null) return;
        if (tick++ % REFRESH_TICKS != 0) return;
        if (!PROXIES.isEmpty()) PROXIES.values().removeIf(proxy -> tick - proxy.lastSeen > PROXY_TTL);
        try {
            refreshCatalog(mc.player, mc.getLanguageManager().getSelected());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            disable(failure);
        }
    }

    private static void refreshCatalog(Player viewer, String language) throws ReflectiveOperationException {
        Map<?, ?> catalog = (Map<?, ?>) api.catalog().invoke(null);
        Object access = ((Optional<?>) api.permissions().invoke(null, viewer)).orElse(null);
        Set<?> permissions = access == null ? Set.of() : (Set<?>) api.allowedModelIds().invoke(access);
        if (catalog == lastCatalog && permissions.equals(lastPermissions) && language.equals(lastLanguage)) return;
        Map<String, Model> next = new LinkedHashMap<>();
        for (var entry : catalog.entrySet().stream()
                .sorted(Comparator.comparing(e -> (String) e.getKey())).toList()) {
            String id = (String) entry.getKey();
            Object container = entry.getValue();
            Object metadata = api.metadata().invoke(container);
            if ((boolean) api.restricted().invoke(metadata) && !permissions.contains(id)) continue;

            String key = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
            String name = (String) api.displayName().invoke(container, id);
            if (name == null || name.isBlank()) name = id;
            String texture = (String) api.defaultTexture().invoke(api.modelProperties().invoke(container));
            if (texture == null || texture.isBlank()) texture = "default";
            ClientSkinPacks.Listing listing = new ClientSkinPacks.Listing(key, name, "", 1, ClientSkinPacks.Source.YSM);
            next.put(rowId(key), new Model(id, texture, listing));
        }
        Map<?, ?> previous = lastCatalog;
        PROXIES.values().removeIf(proxy -> !next.containsKey(proxy.model.listing().rowId())
                || previous != null && previous.get(proxy.model.id()) != catalog.get(proxy.model.id()));
        lastCatalog = catalog;
        lastPermissions = Set.copyOf(permissions);
        lastLanguage = language;
        if (!models.equals(next)) {
            models = next;
            revision++;
        }
    }

    static void clear() {
        level = null;
        tick = 0;
        if (!models.isEmpty()) revision++;
        models = Map.of();
        lastCatalog = null;
        lastPermissions = Set.of();
        lastLanguage = null;
        clearProxies();
    }

    static void clearProxies() {
        PROXIES.clear();
    }

    private static void disable(Throwable failure) {
        api = null;
        clear();
        Bustling.LOGGER.warn("Could not use Bustling's optional YSM client integration", failure);
    }

    /** Entry point for the existing entity rendering path. The proxy is never added to the world. */
    public static Entity instance(String group, int index, String row, String nbt) {
        if (!isModelRow(row)) return MobMotion.instance(group, index, row, nbt);
        Model model = models.get(row);
        if (api == null || model == null || level == null || level != Minecraft.getInstance().level) return null;
        ProxyKey key = new ProxyKey(group, index);
        Proxy proxy = PROXIES.get(key);
        if (proxy == null || !proxy.model.equals(model)) {
            try {
                RemotePlayer player = new CrowdPlayer(level);
                Object attachment = ((Optional<?>) api.attachment().invoke(null, player)).orElse(null);
                if (attachment == null) return null;
                api.select().invoke(attachment, model.id(), model.texture());
                proxy = new Proxy(model, player);
                PROXIES.put(key, proxy);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                disable(failure);
                return null;
            }
        }
        proxy.lastSeen = tick;
        return proxy.player;
    }

    public static void animate(Entity entity, CrowdData.Emitter emitter, CrowdSampler.Ghost ghost) {
        if (entity instanceof CrowdPlayer player) {
            boolean grounded = CrowdNavigation.grounded(emitter, ghost.idx());
            if (player.onGround() != grounded) player.setOnGround(grounded);
        }
        MobMotion.animate(entity, emitter, ghost);
    }

    static void hideNameTag(RenderNameTagEvent event) {
        if (event.getEntity() instanceof CrowdPlayer) event.setCanRender(TriState.FALSE);
    }

    private record Bindings(Method catalog, Method metadata, Method restricted, Method permissions,
                            Method allowedModelIds, Method displayName, Method modelProperties, Method defaultTexture,
                            Method attachment, Method select) {
        static Bindings load() throws ReflectiveOperationException {
            // YSM 2.6.5's model picker and NeoForge player-render layer use these same methods.
            String primary = "oOo0OO0O0o000OO0O000oo0o";
            String secondary = "oOoo00O0o0oO0o0oO00OO0O0";
            var library = YsmSupport.type("oo0o0Oooo0OOO00OOo0OO000");
            var container = YsmSupport.type("o0o0o00oo0OO0o000OOo0oo0");
            var permissions = YsmSupport.type("OOo0o0000Ooo0o00OO0oOOoO");
            var attachment = YsmSupport.type("OoooO0OO0000O00oo0Oo00OO");
            Method data = container.getMethod("OoooO0OO0000O00oo0Oo00OO");
            Method properties = container.getMethod(primary);
            return new Bindings(library.getMethod(secondary), data, data.getReturnType().getMethod(secondary),
                    permissions.getMethod(primary, Player.class),
                    permissions.getMethod(primary),
                    container.getMethod(primary, String.class), properties,
                    properties.getReturnType().getMethod("ooOooOO0oO00o00o0o0oOOoO"),
                    attachment.getMethod(secondary, Player.class),
                    attachment.getMethod(primary, String.class, String.class));
        }
    }
}
