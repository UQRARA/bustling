package dev.bustling;

import java.util.ArrayList;
import java.util.Objects;

/** The settings carried by a crowd, apart from its placement and generated population. */
final class CrowdSettings {
    private CrowdSettings() {}

    static CrowdData.Emitter snapshot(CrowdData.Emitter source) {
        var template = new CrowdData.Emitter(source.id, source.type);
        copyShared(template, source);
        if (DiffuserData.isDiffuser(source)) {
            copyDeviceFields(template, source);
            template.redstoneControlled = source.redstoneControlled;
        } else {
            copyLayoutFields(template, source);
        }
        return template;
    }

    static void paste(CrowdData.Emitter target, CrowdData.Emitter template) {
        if (DiffuserData.isDiffuser(target)) {
            applyDeviceSettings(target, template);
        } else {
            copyShared(target, template);
            if (!DiffuserData.isDiffuser(template)) copyLayoutFields(target, template);
            target.applyDensity();
        }
    }

    /** Also used by the server when it applies an edit to an existing device. */
    static void applyDeviceSettings(CrowdData.Emitter target, CrowdData.Emitter source) {
        copyShared(target, source);
        if (DiffuserData.isDiffuser(source)) copyDeviceFields(target, source);
        target.appearance = "entity".equalsIgnoreCase(source.appearance) || "skin".equalsIgnoreCase(source.appearance)
                ? "entity" : "white";
        target.speed = DiffuserData.clamp(source.speed, .1, 6, 1);
        target.size = DiffuserData.clamp(source.size, .5, 4, 1.875);
        target.spacing = DiffuserData.clamp(source.spacing, .2, 5, .9);
        target.alpha = DiffuserData.clamp(source.alpha, 0, 1, .35);
        target.maxDist = DiffuserData.clamp(source.maxDist, 0, 160, 96);
        target.groundMode = source.groundMode == 1 ? 1 : 0;
        DiffuserData.refresh(target);
    }

    private static void copyShared(CrowdData.Emitter target, CrowdData.Emitter source) {
        target.density = source.density;
        target.speed = source.speed;
        target.size = source.size;
        target.spacing = source.spacing;
        target.appearance = source.appearance;
        target.ground = source.ground;
        target.groundBlocks = source.groundBlocks == null ? null : new ArrayList<>(source.groundBlocks);
        if (target.groundBlocks != null) target.groundBlocks.removeIf(Objects::isNull);
        target.groundMode = source.groundMode;
        target.stepHeight = DiffuserData.climb(source);
        target.alpha = source.alpha;
        target.maxDist = source.maxDist;
        target.color = source.color;

        target.skins = new ArrayList<>();
        if (source.skins != null) {
            for (var skin : source.skins) {
                if (skin != null) target.skins.add(new CrowdData.Emitter.SkinWeight(skin.id, skin.weight));
            }
        }
        target.mobs = new ArrayList<>();
        if (source.mobs != null) {
            for (var mob : source.mobs) {
                if (mob != null) target.mobs.add(new CrowdData.Emitter.MobWeight(mob.id, mob.weight, mob.nbt, mob.name));
            }
        }
    }

    private static void copyLayoutFields(CrowdData.Emitter target, CrowdData.Emitter source) {
        target.width = source.width;
        target.spread = source.spread;
        target.avoid = source.avoid;
        target.collide = source.collide;
        target.pushEnabled = source.pushEnabled;
        target.pushRadius = source.pushRadius;
        target.slowLevel = source.slowLevel;
        target.enabled = source.enabled;
    }

    private static void copyDeviceFields(CrowdData.Emitter target, CrowdData.Emitter source) {
        target.diffuserRadius = source.diffuserRadius;
        target.diffuserLimit = source.diffuserLimit;
        target.diffuserDecay = source.diffuserDecay;
    }
}
