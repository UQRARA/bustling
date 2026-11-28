package dev.bustling;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;

/** Poses white and skinned player models for running and swimming. */
final class CrowdPlayerAnimation {
    private CrowdPlayerAnimation() {}

    static void renderPlayer(PlayerModel<?> model, PoseStack pose, VertexConsumer vertices,
                             int light, int overlay, int color, boolean layered, CrowdSampler.Ghost ghost) {
        if (CrowdWater.floating(ghost.move())) {
            renderInWater(model, pose, vertices, light, overlay, color, layered, ghost);
            return;
        }
        float run = CrowdPace.runBlend(ghost.move());
        if (run <= .001f) {
            ClientSkinPacks.renderPlayer(model, pose, vertices, light, overlay, color, layered);
            return;
        }

        float stride = (float) Math.sin(ghost.gait());
        float armSwing = stride * (.62f + .58f * run);
        float legSwing = stride * (.62f * .9f + .48f * run);
        model.rightArm.xRot = armSwing - .20f * run;
        model.leftArm.xRot = -armSwing - .20f * run;
        model.rightLeg.xRot = -legSwing;
        model.leftLeg.xRot = legSwing;

        pose.pushPose();
        try {
            // The player model is translated by this amount before drawing. Lean around its feet.
            pose.translate(0, 1.501, 0);
            pose.mulPose(Axis.XP.rotation(.20f * run));
            pose.translate(0, -1.501, 0);
            ClientSkinPacks.renderPlayer(model, pose, vertices, light, overlay, color, layered);
        } finally {
            pose.popPose();
        }
    }

    private static void renderInWater(PlayerModel<?> model, PoseStack pose, VertexConsumer vertices,
                                      int light, int overlay, int color, boolean layered, CrowdSampler.Ghost ghost) {
        float swim = CrowdWater.swimBlend(ghost.move());
        float stroke = (float)Math.sin(ghost.gait());
        model.rightArm.xRot = (-1.7f + 1.25f * stroke) * swim;
        model.leftArm.xRot = (-1.7f - 1.25f * stroke) * swim;
        model.rightArm.zRot = .14f + .14f * swim;
        model.leftArm.zRot = -model.rightArm.zRot;
        model.rightLeg.xRot = .32f * stroke * swim;
        model.leftLeg.xRot = -model.rightLeg.xRot;
        model.head.xRot = -.2f * swim;

        if (swim <= .001f) {
            ClientSkinPacks.renderPlayer(model, pose, vertices, light, overlay, color, layered);
            return;
        }
        pose.pushPose();
        try {
            // Turn around the torso and raise it to the waterline as the body levels out.
            double pivot = .75;
            pose.translate(0, pivot - .28 * swim, 0);
            pose.mulPose(Axis.XP.rotation(1.45f * swim));
            pose.translate(0, -pivot, 0);
            ClientSkinPacks.renderPlayer(model, pose, vertices, light, overlay, color, layered);
        } finally {
            pose.popPose();
        }
    }
}
