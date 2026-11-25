package dev.bustling;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

public final class ClientSkinPacks {
    private static final Map<String, LoadedPack> LOCAL = new LinkedHashMap<>();
    private static final Map<String, LoadedPack> SERVER = new LinkedHashMap<>();
    private static final Map<String, LoadedPack> EXAMPLES = new LinkedHashMap<>();
    private static boolean loading;
    private static int revision;
    private static int remoteSkinCount;
    private static PlayerModel<?> previewWide;
    private static PlayerModel<?> previewSlim;

    enum Source {
        LOCAL("skin_pack.local"), SERVER("skin_pack.server"), EXAMPLE("skin_pack.example"),
        YSM("skin_pack.ysm");

        final String label;

        Source(String label) {
            this.label = label;
        }
    }

    record Listing(String id, String name, String description, int count, Source source) {
        String rowId() {
            return source == Source.YSM ? YsmClientModels.rowId(id) : SkinPackFiles.ROW_PREFIX + id;
        }
    }
    private record Catalog(List<SkinPackFiles.Pack> local, List<SkinPackFiles.Pack> examples) {}
    private record WeightedSkin(SkinLibrary.Skin skin, int upperBound) {}
    private record LoadedPack(Listing listing, List<WeightedSkin> skins) {}

    private ClientSkinPacks() {}

    static void reload() {
        if (loading) return;
        loading = true;
        Minecraft mc = Minecraft.getInstance();
        Set<String> loadedExamples = Set.copyOf(EXAMPLES.keySet());
        CompletableFuture.supplyAsync(() -> new Catalog(SkinPackFiles.load(), SkinPackFiles.loadExamples(loadedExamples)),
                        Util.backgroundExecutor())
                .whenCompleteAsync((catalog, error) -> {
                    loading = false;
                    if (error == null) {
                        loadCatalog(EXAMPLES, catalog.examples(), Source.EXAMPLE);
                        clear(LOCAL);
                        loadCatalog(LOCAL, catalog.local(), Source.LOCAL);
                    } else {
                        Bustling.LOGGER.warn("Could not reload Bustling skin packs", error);
                    }
                    revision++;
                }, mc);
    }

    static int revision() {
        return revision;
    }

    static boolean loading() {
        return loading;
    }

    static void receive(SkinPackFiles.Pack pack) {
        if (SERVER.size() == SkinPackFiles.MAX_PACKS || SERVER.containsKey(pack.id())
                || remoteSkinCount + pack.skins().size() > SkinPackFiles.MAX_SKINS) return;
        LoadedPack loaded = loadTextures(pack, Source.SERVER);
        if (loaded != null) {
            SERVER.put(pack.id(), loaded);
            remoteSkinCount += pack.skins().size();
            revision++;
        }
    }

    static void clearRemote() {
        clear(SERVER);
        remoteSkinCount = 0;
        revision++;
    }

    private static void loadCatalog(Map<String, LoadedPack> target, List<SkinPackFiles.Pack> packs, Source source) {
        for (SkinPackFiles.Pack pack : packs) {
            LoadedPack loaded = loadTextures(pack, source);
            if (loaded != null) target.put(pack.id(), loaded);
        }
    }

    private static void clear(Map<String, LoadedPack> packs) {
        var textures = Minecraft.getInstance().getTextureManager();
        for (LoadedPack pack : packs.values()) {
            for (WeightedSkin skin : pack.skins()) textures.release(skin.skin().texture());
        }
        packs.clear();
    }

    private static LoadedPack loadTextures(SkinPackFiles.Pack pack, Source source) {
        var textures = Minecraft.getInstance().getTextureManager();
        List<WeightedSkin> skins = new ArrayList<>(pack.skins().size());
        int cumulative = 0;
        String texturePrefix = "skin_pack/" + source.name().toLowerCase(Locale.ROOT) + "/" + pack.id() + "/";
        try {
            for (SkinPackFiles.Skin skin : pack.skins()) {
                ResourceLocation key = ResourceLocation.fromNamespaceAndPath("bustling", texturePrefix + skins.size());
                NativeImage image = new NativeImage(SkinPackFiles.SIZE, SkinPackFiles.SIZE, false);
                for (int i = 0; i < SkinPackFiles.PIXELS; i++) {
                    image.setPixelRGBA(i % SkinPackFiles.SIZE, i / SkinPackFiles.SIZE, skin.pixels()[i]);
                }
                DynamicTexture texture = new DynamicTexture(image);
                try {
                    textures.register(key, texture);
                } catch (RuntimeException failure) {
                    texture.close();
                    throw failure;
                }
                cumulative += skin.weight();
                skins.add(new WeightedSkin(new SkinLibrary.Skin(key.getPath(), key, skin.slim()), cumulative));
            }
        } catch (RuntimeException failure) {
            for (WeightedSkin skin : skins) textures.release(skin.skin().texture());
            Bustling.LOGGER.warn("Could not load textures for skin pack {}", pack.id(), failure);
            return null;
        }
        Listing listing = new Listing(pack.id(), pack.name(), pack.description(), pack.skins().size(), source);
        return new LoadedPack(listing, List.copyOf(skins));
    }

    static List<Listing> listings() {
        List<Listing> listings = new ArrayList<>();
        EXAMPLES.forEach((id, pack) -> {
            if (!SERVER.containsKey(id) && !LOCAL.containsKey(id)) listings.add(pack.listing());
        });
        SERVER.values().forEach(pack -> listings.add(pack.listing()));
        LOCAL.forEach((id, pack) -> {
            if (!SERVER.containsKey(id)) listings.add(pack.listing());
        });
        return listings;
    }

    static Listing listing(String rowId) {
        if (YsmClientModels.isModelRow(rowId)) return YsmClientModels.listing(rowId);
        LoadedPack pack = find(rowId);
        return pack == null ? null : pack.listing();
    }

    private static LoadedPack find(String rowId) {
        if (!SkinPackFiles.isPackRow(rowId)) return null;
        String id = SkinPackFiles.packId(rowId);
        LoadedPack pack = SERVER.get(id);
        if (pack == null) pack = LOCAL.get(id);
        return pack != null ? pack : EXAMPLES.get(id);
    }

    public static SkinLibrary.Skin pickSkin(CrowdData.Emitter.MobWeight row, String group, int index) {
        LoadedPack pack = row == null ? null : find(row.id);
        if (pack == null) return null;
        List<WeightedSkin> skins = pack.skins();
        long seed = (long) group.hashCode() * 31 + index + 0x9e3779b97f4a7c15L;
        seed = (seed ^ seed >>> 30) * 0xbf58476d1ce4e5b9L;
        seed = (seed ^ seed >>> 27) * 0x94d049bb133111ebL;
        int roll = Math.floorMod(seed ^ seed >>> 31, skins.getLast().upperBound());
        for (WeightedSkin skin : skins) {
            if (roll < skin.upperBound()) return skin.skin();
        }
        return skins.getLast().skin();
    }

    public static SkinLibrary.Skin selectSkin(CrowdData.Emitter emitter, int index) {
        return pickSkin(AppearanceModes.pickMob(emitter, index), emitter.id, index);
    }

    public static boolean hasPackSkin(CrowdData.Emitter.MobWeight row) {
        return row != null && find(row.id) != null;
    }

    /** Pack rows use the player renderer and never enter the entity-type registry. */
    public static String entityId(String id) {
        return SkinPackFiles.isPackRow(id) ? null : id;
    }

    public static void renderPlayer(PlayerModel<?> model, PoseStack pose, VertexConsumer vertices,
                                    int light, int overlay, int color, boolean layered) {
        if (!layered) {
            model.renderToBuffer(pose, vertices, light, overlay, color);
            return;
        }
        setLayers(model, true);
        try {
            model.renderToBuffer(pose, vertices, light, overlay, color);
        } finally {
            setLayers(model, false);
        }
    }

    private static void setLayers(PlayerModel<?> model, boolean visible) {
        model.hat.visible = model.jacket.visible = model.leftSleeve.visible = model.rightSleeve.visible
                = model.leftPants.visible = model.rightPants.visible = visible;
        if (!visible) return;
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftPants.copyFrom(model.leftLeg);
        model.rightPants.copyFrom(model.rightLeg);
    }

    static void clearModels() {
        previewWide = null;
        previewSlim = null;
    }

    static PlayerModel<?> previewModel(boolean slim) {
        if (slim) {
            if (previewSlim == null) previewSlim = bakePreview(true);
            return previewSlim;
        }
        if (previewWide == null) previewWide = bakePreview(false);
        return previewWide;
    }

    private static PlayerModel<?> bakePreview(boolean slim) {
        var layer = slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER;
        var part = Minecraft.getInstance().getEntityModels().bakeLayer(layer);
        PlayerModel<?> model = new PlayerModel<>(part, slim);
        model.young = false;
        return model;
    }
}
