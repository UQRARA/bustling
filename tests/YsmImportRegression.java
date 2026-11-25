package dev.bustling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class YsmImportRegression {
    public static void main(String[] args) throws Exception {
        Path config = Path.of(args[0]);
        Path root = config.resolve("bustling/skin-packs");
        Path custom = config.resolve("yes_steve_model/custom");
        Path generated = custom.resolve("bustling_skin_packs");
        Path nested = Files.createDirectories(root.resolve("ysm"));
        Path model = Files.createDirectories(nested.resolve("model"));
        Path regular = Files.createDirectories(root.resolve("example"));
        Path manual = Files.createDirectories(custom.resolve("manual"));
        Files.writeString(manual.resolve("ysm.json"), "manual model");
        Files.writeString(regular.resolve("pack.json"), "unrelated skin pack");
        Files.writeString(model.resolve("ysm.json"), "model index");
        Files.writeString(model.resolve("geometry.json"), "model geometry");
        Files.createSymbolicLink(model.resolve("external.png"), manual.resolve("ysm.json"));
        Files.createSymbolicLink(root.resolve("external.ysm"), manual.resolve("ysm.json"));
        Files.writeString(root.resolve("root.ysm"), "raw pack");
        Files.writeString(nested.resolve("packed.zip"), "zip pack");

        Files.createDirectory(generated);
        try {
            YsmModelImports.stageModels(root, custom);
            throw new AssertionError("Pre-existing YSM directory must be preserved");
        } catch (IOException expected) {
            try (var entries = Files.list(generated)) {
                check(entries.findAny().isEmpty(), "unmanaged directory preserved");
            }
        }
        Files.delete(generated);

        check(YsmModelImports.stageModels(root, custom) == 3, "found folder, zip and root .ysm");
        check(Files.mismatch(root.resolve("root.ysm"), generated.resolve("root.ysm")) == -1, "copied pack verbatim");
        check(Files.isRegularFile(generated.resolve("model/geometry.json")), "copied model geometry");
        check(!Files.exists(generated.resolve("model/external.png")), "ignored symlink inside model");
        check(!Files.exists(generated.resolve("external.ysm")), "ignored symlink to pack");
        check(!Files.exists(generated.resolve("example")), "kept regular pack separate");

        Files.writeString(root.resolve("root.ysm"), "updated");
        Files.delete(nested.resolve("packed.zip"));
        check(YsmModelImports.stageModels(root, custom) == 2, "refreshed the generated models");
        check(Files.readString(generated.resolve("root.ysm")).equals("updated"), "replaced updated file");
        check(!Files.exists(generated.resolve("packed.zip")), "removed stale import");
        check(Files.readString(manual.resolve("ysm.json")).equals("manual model"), "preserved YSM's own packs");
        check(Files.readString(regular.resolve("pack.json")).equals("unrelated skin pack"), "preserved the PNG pack source");
        System.out.println("PASS YSM staging, updates, cleanup and isolation from ordinary packs");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
