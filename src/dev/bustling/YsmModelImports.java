package dev.bustling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Makes explicitly installed packs available to YSM's normal custom model loader. */
final class YsmModelImports {
    private static final String GENERATED = "bustling_skin_packs";
    private static final String MARKER = ".bustling-managed";

    private YsmModelImports() {}

    static void prepare(ServerAboutToStartEvent event) {
        if (!YsmSupport.available()) return;
        try {
            int count = stageModels(SkinPackFiles.root(), YsmSupport.customFolder());
            if (count > 0) Bustling.LOGGER.info("Prepared {} Bustling model pack(s) for YSM", count);
        } catch (IOException | RuntimeException failure) {
            Bustling.LOGGER.warn("Could not prepare Bustling's YSM model directory", failure);
        }
    }

    static int stageModels(Path root, Path custom) throws IOException {
        Path destination = custom.resolve(GENERATED);
        boolean exists = Files.exists(destination, NOFOLLOW_LINKS);
        if (exists && (!Files.isDirectory(destination, NOFOLLOW_LINKS)
                || !Files.isRegularFile(destination.resolve(MARKER), NOFOLLOW_LINKS))) {
            throw new IOException("YSM directory is already in use: " + destination);
        }
        Files.createDirectories(root);
        Path stage = Files.createTempDirectory(root.getParent(), "ysm-import-");
        Path next = stage.resolve("next");
        Path prior = stage.resolve("prior");
        try {
            Files.createDirectory(next);
            int count = 0;
            Path nested = root.resolve("ysm");
            if (Files.isDirectory(nested, NOFOLLOW_LINKS)) count += copyPacks(nested, next, false);
            count += copyPacks(root, next, true);
            if (count > 0) {
                Files.writeString(next.resolve(MARKER), GENERATED);
                Files.writeString(next.resolve("ysm-pack.json"), "{\"name\":\"Bustling\"}");
            }

            Files.createDirectories(custom);
            if (exists) Files.move(destination, prior);
            try {
                if (count > 0) Files.move(next, destination);
            } catch (IOException failure) {
                if (exists) {
                    try {
                        Files.move(prior, destination);
                    } catch (IOException recovery) {
                        failure.addSuppressed(recovery);
                        Bustling.LOGGER.warn("Previous YSM model directory is at {}", prior);
                    }
                }
                throw failure;
            }
            deleteTree(prior);
            return count;
        } finally {
            // Retain any previous catalog whose restoration failed.
            if (!Files.exists(prior, NOFOLLOW_LINKS)) deleteTree(stage);
        }
    }

    private static int copyPacks(Path source, Path target, boolean root) throws IOException {
        int count = 0;
        try (var candidates = Files.list(source)) {
            for (Path path : candidates.sorted().toList()) {
                String name = path.getFileName().toString();
                String extension = name.toLowerCase(Locale.ROOT);
                Path output = target.resolve(name);
                if (Files.exists(output, NOFOLLOW_LINKS)) continue;
                if (Files.isRegularFile(path, NOFOLLOW_LINKS)
                        && (extension.endsWith(".ysm")
                            || !root && (extension.endsWith(".zip") || extension.endsWith(".7z")))) {
                    Files.copy(path, output, NOFOLLOW_LINKS);
                } else if (!root && Files.isDirectory(path, NOFOLLOW_LINKS)
                        && Files.isRegularFile(path.resolve("ysm.json"), NOFOLLOW_LINKS)) {
                    copyModelDirectory(path, output);
                } else {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private static void copyModelDirectory(Path source, Path target) throws IOException {
        try (var entries = Files.walk(source)) {
            for (Path child : entries.toList()) {
                Path output = target.resolve(source.relativize(child));
                if (Files.isDirectory(child, NOFOLLOW_LINKS)) Files.createDirectories(output);
                else if (Files.isRegularFile(child, NOFOLLOW_LINKS)) Files.copy(child, output, NOFOLLOW_LINKS);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(root)) {
            for (Path child : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(child);
        }
    }
}
