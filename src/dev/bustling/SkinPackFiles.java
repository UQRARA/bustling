package dev.bustling;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.neoforged.fml.loading.FMLPaths;

/** Reads the same pack format on both sides. Pixel arrays use NativeImage's ABGR order. */
final class SkinPackFiles {
    static final int SIZE = 64;
    static final int PIXELS = SIZE * SIZE;
    static final int MAX_PACKS = 32;
    static final int MAX_SKINS = 256;
    static final int MAX_PACK_SKINS = 32;
    static final int MAX_NAME = 64;
    static final int MAX_DESCRIPTION = 240;
    static final int MAX_WEIGHT = 10_000;
    static final String ROW_PREFIX = "bustling:skin_pack/";
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final List<String> EXAMPLE_IDS = List.of("medieval_townsfolk", "modern_city_residents");
    private static final String EXAMPLE_ROOT = "/assets/bustling/skin_packs/";
    private static final Pattern PNG_FILE = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,59}\\.png");
    private static final byte[] PNG_HEADER = {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82};
    private static final Gson GSON = new Gson();

    private SkinPackFiles() {}

    record Skin(boolean slim, int weight, int[] pixels) {}
    record Pack(String id, String name, String description, List<Skin> skins) {}

    private record Index(String name, String description, List<Entry> skins, Layers steve, Layers alex) {}
    private record Entry(String texture, String model, Integer weight) {}
    private record Layers(List<Layer> a, List<Layer> b) {}
    private record Layer(String texture, Integer weight) {}
    private record LayerPixels(int weight, int[] pixels) {}

    @FunctionalInterface
    private interface PackReader {
        InputStream open(String name) throws IOException;
    }

    static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("bustling/skin-packs");
    }

    static boolean isPackRow(String rowId) {
        return rowId != null && rowId.startsWith(ROW_PREFIX);
    }

    static String packId(String rowId) {
        return rowId.substring(ROW_PREFIX.length());
    }

    static List<Pack> load() {
        Path root = root();
        List<Pack> packs = new ArrayList<>();
        int skins = 0;
        try {
            Files.createDirectories(root);
            try (var directories = Files.list(root)) {
                for (Path folder : directories.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted().toList()) {
                    if (packs.size() == MAX_PACKS || skins == MAX_SKINS) break;
                    if (!Files.exists(folder.resolve("pack.json"), LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        Pack pack = read(folder, MAX_SKINS - skins);
                        packs.add(pack);
                        skins += pack.skins().size();
                    } catch (IOException | RuntimeException error) {
                        Bustling.LOGGER.warn("Skipped skin pack {}: {}", folder.getFileName(), error.getMessage());
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            Bustling.LOGGER.warn("Could not read Bustling skin packs: {}", error.getMessage());
        }
        return List.copyOf(packs);
    }

    static List<Pack> loadExamples(Set<String> loaded) {
        List<Pack> examples = new ArrayList<>(EXAMPLE_IDS.size());
        for (String id : EXAMPLE_IDS) {
            if (loaded.contains(id)) continue;
            try {
                examples.add(read(id, MAX_PACK_SKINS,
                        name -> SkinPackFiles.class.getResourceAsStream(EXAMPLE_ROOT + id + "/" + name)));
            } catch (IOException | RuntimeException error) {
                Bustling.LOGGER.warn("Could not load the bundled {} skin pack", id, error);
            }
        }
        return List.copyOf(examples);
    }

    static Pack read(Path folder, int remaining) throws IOException {
        return read(folder.getFileName().toString(), remaining, name -> {
            Path file = folder.resolve(name);
            require(Files.isDirectory(file.getParent(), LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Missing file " + name);
            return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        });
    }

    private static Pack read(String id, int remaining, PackReader reader) throws IOException {
        require(ID.matcher(id).matches(), "Invalid folder ID");
        Index index = GSON.fromJson(new String(readBytes(reader, "pack.json", 16 * 1024),
                StandardCharsets.UTF_8), Index.class);
        require(index != null && validName(index.name()) && validDescription(index.description()), "Invalid pack name or description");
        int limit = Math.min(MAX_PACK_SKINS, remaining);
        List<Skin> skins;
        if (index.steve() == null && index.alex() == null) {
            skins = readFlatSkins(index.skins(), limit, reader);
        } else {
            require(index.skins() == null, "Use either skins or the steve/alex layer groups");
            skins = readLayeredSkins(index, limit, reader);
        }
        return new Pack(id, index.name(), index.description(), List.copyOf(skins));
    }

    private static List<Skin> readFlatSkins(List<Entry> entries, int limit, PackReader reader) throws IOException {
        require(entries != null && !entries.isEmpty() && entries.size() <= limit,
                "Skin count exceeds the pack or catalog limit");
        List<Skin> skins = new ArrayList<>(entries.size());
        var names = new HashSet<String>();
        for (Entry entry : entries) {
            require(entry != null && validTexture(entry.texture()) && names.add(entry.texture()),
                    "Invalid or duplicate texture filename");
            boolean slim = "slim".equals(entry.model());
            require(slim || "wide".equals(entry.model()), "Model must be wide or slim");
            skins.add(new Skin(slim, weight(entry.weight()), readTexture(reader, "textures/" + entry.texture())));
        }
        return skins;
    }

    private static List<Skin> readLayeredSkins(Index index, int limit, PackReader reader) throws IOException {
        long count = variantCount(index.steve()) + variantCount(index.alex());
        require(count > 0 && count <= limit, "A/B variant count exceeds the pack or catalog limit");
        List<Skin> variants = new ArrayList<>((int) count);
        addVariants(variants, index.steve(), false, reader);
        addVariants(variants, index.alex(), true, reader);
        int highestWeight = variants.stream().mapToInt(Skin::weight).max().orElseThrow();
        if (highestWeight <= MAX_WEIGHT) return variants;
        // Fit the existing skin protocol while keeping all combinations available.
        return variants.stream().map(skin -> new Skin(skin.slim(),
                Math.max(1, (int) ((skin.weight() * (long) MAX_WEIGHT + highestWeight / 2L) / highestWeight)),
                skin.pixels())).toList();
    }

    private static long variantCount(Layers layers) throws IOException {
        if (layers == null) return 0;
        require(layers.a() != null && !layers.a().isEmpty() && layers.b() != null && !layers.b().isEmpty(),
                "Each model group requires at least one A layer and one B layer");
        return (long) layers.a().size() * layers.b().size();
    }

    private static void addVariants(List<Skin> variants, Layers group, boolean slim, PackReader reader)
            throws IOException {
        if (group == null) return;
        List<LayerPixels> bases = readLayers(group.a(), "a/", reader);
        List<LayerPixels> outfits = readLayers(group.b(), "b/", reader);
        for (LayerPixels base : bases) {
            for (LayerPixels outfit : outfits) {
                int[] pixels = new int[PIXELS];
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = composite(base.pixels()[i], outfit.pixels()[i]);
                }
                variants.add(new Skin(slim, base.weight() * outfit.weight(), pixels));
            }
        }
    }

    private static List<LayerPixels> readLayers(List<Layer> entries, String folder, PackReader reader)
            throws IOException {
        List<LayerPixels> layers = new ArrayList<>(entries.size());
        var names = new HashSet<String>();
        for (Layer entry : entries) {
            require(entry != null && validTexture(entry.texture()) && names.add(entry.texture()),
                    "Invalid or duplicate texture filename in " + folder);
            layers.add(new LayerPixels(weight(entry.weight()), readTexture(reader, folder + entry.texture())));
        }
        return layers;
    }

    private static boolean validTexture(String filename) {
        return filename != null && PNG_FILE.matcher(filename).matches();
    }

    private static int weight(Integer value) throws IOException {
        int weight = value == null ? 1 : value;
        require(weight > 0 && weight <= MAX_WEIGHT, "Weight must be from 1 to " + MAX_WEIGHT);
        return weight;
    }

    private static int[] readTexture(PackReader reader, String name) throws IOException {
        return decodeSkin(readBytes(reader, name, 64 * 1024), name);
    }

    /** Source-over compositing on straight-alpha ABGR pixels. */
    private static int composite(int base, int outfit) {
        int topAlpha = outfit >>> 24;
        int baseAlpha = base >>> 24;
        if (topAlpha == 0) return base;
        if (topAlpha == 255 || baseAlpha == 0) return outfit;
        int top = topAlpha * 255;
        int bottom = baseAlpha * (255 - topAlpha);
        int alpha = top + bottom;
        int merged = ((alpha + 127) / 255) << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            int value = (outfit >>> shift & 255) * top + (base >>> shift & 255) * bottom;
            merged |= ((value + alpha / 2) / alpha) << shift;
        }
        return merged;
    }

    static boolean validName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME && name.chars().noneMatch(Character::isISOControl);
    }

    static boolean validDescription(String description) {
        return description != null && description.length() <= MAX_DESCRIPTION
                && description.chars().noneMatch(Character::isISOControl);
    }

    private static byte[] readBytes(PackReader reader, String name, int limit) throws IOException {
        try (InputStream stream = reader.open(name)) {
            require(stream != null, "Missing file " + name);
            byte[] bytes = stream.readNBytes(limit + 1);
            require(bytes.length <= limit, "File too large: " + name);
            return bytes;
        }
    }

    private static int[] decodeSkin(byte[] bytes, String name) throws IOException {
        require(bytes.length >= 33 && Arrays.equals(PNG_HEADER, 0, PNG_HEADER.length, bytes, 0, PNG_HEADER.length)
                && ByteBuffer.wrap(bytes).getInt(16) == SIZE && ByteBuffer.wrap(bytes).getInt(20) == SIZE,
                "Expected a 64×64 PNG: " + name);
        BufferedImage image;
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var reader = ImageIO.getImageReadersByFormatName("PNG").next();
            try {
                reader.setInput(stream, true, true);
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        int[] pixels = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            pixels[i] = argb & 0xff00ff00 | (argb & 0xff) << 16 | (argb >>> 16 & 0xff);
        }
        return pixels;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
