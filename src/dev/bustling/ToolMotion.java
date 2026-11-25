package dev.bustling;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;

public final class ToolMotion {
    private static final double REST_SPEED = 48;
    private static final double OPEN_SPEED = 168;
    private static final double SPEED_RESPONSE = 8;
    private static final double TURN = Math.PI * 2;
    private static long lastFrame;
    private static double elapsed;
    private static double gearAngle;
    private static double gearSpeed = REST_SPEED;

    private ToolMotion() {}

    static void advance(RenderFrameEvent.Pre event) {
        long now = System.nanoTime();
        double delta = lastFrame == 0 ? 0 : Math.clamp((now - lastFrame) / 1_000_000_000.0, 0, 0.1);
        lastFrame = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        boolean open = mc.screen instanceof WorldScreen
                || mc.screen instanceof ResetConfirmationScreen confirmation && confirmation.isForWorldSettings();
        double target = open ? OPEN_SPEED : REST_SPEED;
        double keep = Math.exp(-SPEED_RESPONSE * delta);
        gearAngle += target * delta + (gearSpeed - target) * (1 - keep) / SPEED_RESPONSE;
        gearSpeed = target + (gearSpeed - target) * keep;
        elapsed += delta;
    }

    public enum Movement {
        CRYSTAL_CORE, CRYSTAL_SHELL, LEFT_GEAR, CENTER_GEAR, RIGHT_GEAR, OBJECT_LENS, LAMPS, RUNES
    }

    static final class Assembly extends BakedModelWrapper<BakedModel> {
        private final List<BakedModel> passes;

        Assembly(BakedModel body, List<BakedModel> passes) {
            super(body);
            this.passes = List.copyOf(passes);
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
            originalModel.applyTransform(context, pose, leftHand);
            return this;
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            return passes;
        }
    }

    public static final class Part extends BakedModelWrapper<BakedModel> {
        private final Movement movement;

        Part(BakedModel source, Movement movement) {
            super(source);
            this.movement = movement;
        }

        public BakedModel source() {
            return originalModel;
        }

        public void applyMotion(PoseStack pose) {
            switch (movement) {
                case CRYSTAL_CORE, CRYSTAL_SHELL -> {
                    double height = Math.sin(elapsed * TURN / 2.7) * (0.42 / 16);
                    pose.translate(0.5, height, 0.5);
                    pose.mulPose(Axis.YP.rotationDegrees((float) (elapsed * 42 % 360)));
                    pose.translate(-0.5, 0, -0.5);
                }
                case LEFT_GEAR, CENTER_GEAR, RIGHT_GEAR -> {
                    double centerZ = switch (movement) {
                        case LEFT_GEAR -> 4.73;
                        case RIGHT_GEAR -> 11.27;
                        default -> 8;
                    };
                    double angle = movement == Movement.CENTER_GEAR ? -gearAngle * 0.62 + 22.5 : gearAngle;
                    pose.translate(14.55 / 16, 1.35 / 16, centerZ / 16);
                    pose.mulPose(Axis.YP.rotationDegrees((float) (angle % 360)));
                    pose.translate(-14.55 / 16, -1.35 / 16, -centerZ / 16);
                }
                case OBJECT_LENS, LAMPS, RUNES -> {}
            }
        }

        public boolean pulses() {
            return switch (movement) {
                case CRYSTAL_CORE, CRYSTAL_SHELL, OBJECT_LENS, LAMPS, RUNES -> true;
                default -> false;
            };
        }

        public VertexConsumer litVertices(VertexConsumer vertices) {
            return new WaveLight(vertices, movement);
        }
    }

    private static float wave(double period, double spatialOffset, float minimum) {
        return minimum + (1 - minimum) * (float) (.5 + .5 * Math.cos(TURN * (elapsed / period - spatialOffset)));
    }

    private static final class WaveLight extends VertexConsumerWrapper {
        private final Movement movement;
        private final float flash;

        private WaveLight(VertexConsumer delegate, Movement movement) {
            super(delegate);
            this.movement = movement;
            flash = movement == Movement.RUNES ? PasteEffects.whiteFlash() : 0;
        }

        @Override
        public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float red, float green, float blue,
                                float alpha, int light, int overlay, boolean readExistingColor) {
            int[] data = quad.getVertices();
            int stride = data.length / 4;
            float x = 0, y = 0, z = 0;
            for (int i = 0; i < data.length; i += stride) {
                x += Float.intBitsToFloat(data[i]) * 0.25f;
                y += Float.intBitsToFloat(data[i + 1]) * 0.25f;
                z += Float.intBitsToFloat(data[i + 2]) * 0.25f;
            }
            float intensity = switch (movement) {
                case RUNES -> wave(1.65, x * 1.35 + z * .36, .42f);
                case CRYSTAL_CORE -> wave(1.56, x * 1.5 + y * 2.2 + z * .8, .63f);
                case CRYSTAL_SHELL -> wave(1.56, x * 1.5 + y * 2.2 + z * .8, .76f);
                case OBJECT_LENS -> wave(2.4, z * .52, .86f);
                case LAMPS -> wave(1.84, x * 2.3, .23f);
                default -> 1;
            };
            if (movement == Movement.RUNES) {
                red *= flash + (1 - flash) * intensity * (150 / 255f);
                green *= flash + (1 - flash) * intensity * (92 / 255f);
                blue *= flash + (1 - flash) * intensity * (240 / 255f);
            } else {
                red *= intensity;
                green *= intensity;
                blue *= intensity;
            }
            boolean scanner = movement == Movement.OBJECT_LENS;
            parent.putBulkData(pose, quad, red, green, blue, alpha, light, overlay, readExistingColor && !scanner);
        }
    }
}
