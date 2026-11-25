package dev.bustling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Local, short tool cues that follow the vanilla Players sound slider. */
public final class ToolSounds {
    private static final SoundSource SOURCE = SoundSource.PLAYERS;

    private enum Cue {
        STEP(85), MODE(55), COPY(250), PASTE(160), PANEL(250), SCAN(280),
        PLACE(150), COMMIT(190), REMOVE(105), DELETE(260);

        final long interval;
        long lastPlayed = Long.MIN_VALUE;
        Cue(long milliseconds) { interval = milliseconds * 1_000_000L; }
    }

    private ToolSounds() {}

    private static void play(Cue cue, SoundEvent event, float volume, float pitch) {
        long now = System.nanoTime();
        if (cue.lastPlayed != Long.MIN_VALUE && now - cue.lastPlayed < cue.interval) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null
                || mc.options.getSoundSourceVolume(SOURCE) <= 0
                || mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0) return;
        var manager = mc.getSoundManager();
        if (manager == null) return;
        cue.lastPlayed = now;
        manager.play(new SimpleSoundInstance(event.getLocation(), SOURCE, volume, pitch,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    public static void step(boolean increase) {
        play(Cue.STEP, SoundEvents.NOTE_BLOCK_HAT.value(), .16f, increase ? 1.28f : 1.06f);
    }

    public static void copied() {
        // The vanilla page-turn definition already applies a 2× volume multiplier.
        play(Cue.COPY, SoundEvents.BOOK_PAGE_TURN, .48f, 1.16f);
    }

    public static void pasted() {
        play(Cue.PASTE, SoundEvents.BOOK_PUT, .82f, .86f);
    }

    public static void changeMode(ToolConfig.Mode mode) {
        if (ToolConfig.mode == mode) return;
        ToolConfig.mode = mode;
        float pitch = switch (mode) {
            case AREA -> 1.12f;
            case PATH -> 1.42f;
            case DELETE -> .80f;
        };
        play(Cue.MODE, SoundEvents.NOTE_BLOCK_BIT.value(), .37f, pitch);
    }

    public static void gear() {
        play(Cue.PANEL, SoundEvents.LEVER_CLICK, .23f, 1.12f);
    }

    public static void pick() {
        play(Cue.SCAN, SoundEvents.NOTE_BLOCK_BIT.value(), .24f, 1.43f);
    }

    public static void place(int pointCount) {
        float base = ToolConfig.mode == ToolConfig.Mode.PATH ? 1.09f : .97f;
        float pitch = base + .035f * Math.clamp(pointCount - 1, 0, 6);
        play(Cue.PLACE, SoundEvents.NOTE_BLOCK_XYLOPHONE.value(), .28f, pitch);
    }

    public static void commit() {
        play(Cue.COMMIT, SoundEvents.NOTE_BLOCK_BELL.value(), .31f, 1.27f);
    }

    public static void remove() {
        play(Cue.REMOVE, SoundEvents.NOTE_BLOCK_SNARE.value(), .19f, .89f);
    }

    public static void deleted() {
        play(Cue.DELETE, SoundEvents.GLASS_BREAK, .28f, .98f);
    }
}
