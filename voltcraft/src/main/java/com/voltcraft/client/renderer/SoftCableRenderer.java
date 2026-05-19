package com.voltcraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.voltcraft.electric.Phase;
import com.voltcraft.entity.SoftCableEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 软线渲染器：两端 anchor 间画一条贝塞尔曲线，颜色按相位。
 *
 * 控制点 = 两端中点向下偏移（重力下垂效果），偏移量正比于线长。
 * 段数随相机距离 LOD：近 16 段、远 8 段。
 */
public class SoftCableRenderer extends EntityRenderer<SoftCableEntity> {

    /** 贴图任意选一个像素纯色的，颜色靠 vertex color。这里复用基岩纹理。 */
    private static final ResourceLocation BLANK = ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");

    private static final int SEGMENTS_NEAR = 16;
    private static final int SEGMENTS_FAR = 8;
    private static final double FAR_DIST = 24.0;

    /** 下垂系数：中点 Y 偏移 = 弦长 × SAG_FACTOR。 */
    private static final double SAG_FACTOR = 0.18;

    private static final float WIRE_THICKNESS = 0.04f;

    public SoftCableRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public ResourceLocation getTextureLocation(SoftCableEntity entity) {
        return BLANK;
    }

    @Override
    public void render(SoftCableEntity entity, float yaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffers, int packedLight) {
        Vec3 a = entity.endAWorld();
        Vec3 b = entity.endBWorld();
        if (a == null || b == null) return;

        // 转到 entity 局部坐标系（render 已经把 pose 平移到 entity 位置）
        Vec3 entityPos = entity.position();
        Vector3f la = new Vector3f((float)(a.x - entityPos.x), (float)(a.y - entityPos.y), (float)(a.z - entityPos.z));
        Vector3f lb = new Vector3f((float)(b.x - entityPos.x), (float)(b.y - entityPos.y), (float)(b.z - entityPos.z));

        // 控制点在 ab 中点正下方
        double chord = a.distanceTo(b);
        float sag = (float)(chord * SAG_FACTOR);
        Vector3f ctrl = new Vector3f((la.x + lb.x) * 0.5f,
                                     (la.y + lb.y) * 0.5f - sag,
                                     (la.z + lb.z) * 0.5f);

        int segments = chord > FAR_DIST ? SEGMENTS_FAR : SEGMENTS_NEAR;
        int[] color = phaseColor(entity.phase());

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANK));
        Matrix4f matrix = pose.last().pose();

        Vector3f prev = la;
        for (int i = 1; i <= segments; i++) {
            float t = (float) i / segments;
            Vector3f cur = bezier(la, ctrl, lb, t);
            drawSegment(vc, matrix, prev, cur, color, packedLight);
            prev = cur;
        }
    }

    /** 二次贝塞尔。 */
    private static Vector3f bezier(Vector3f p0, Vector3f p1, Vector3f p2, float t) {
        float u = 1f - t;
        return new Vector3f(
                u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
                u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y,
                u * u * p0.z + 2f * u * t * p1.z + t * t * p2.z
        );
    }

    /** 画一个面向相机的"丝带"段。简化做法：画两个垂直交叉的四边形让任意视角都看得见。 */
    private static void drawSegment(VertexConsumer vc, Matrix4f m,
                                    Vector3f a, Vector3f b, int[] rgb, int light) {
        float r = rgb[0] / 255f, g = rgb[1] / 255f, bl = rgb[2] / 255f;
        float t = WIRE_THICKNESS;
        // 沿 X-Y 平面拉宽
        quad(vc, m, a.x - t, a.y, a.z, a.x + t, a.y, a.z,
                    b.x + t, b.y, b.z, b.x - t, b.y, b.z, r, g, bl, light);
        // 沿 Z-Y 平面再拉宽（十字断面）
        quad(vc, m, a.x, a.y, a.z - t, a.x, a.y, a.z + t,
                    b.x, b.y, b.z + t, b.x, b.y, b.z - t, r, g, bl, light);
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             float r, float g, float b, int light) {
        vert(vc, m, x1, y1, z1, r, g, b, light);
        vert(vc, m, x2, y2, z2, r, g, b, light);
        vert(vc, m, x3, y3, z3, r, g, b, light);
        vert(vc, m, x4, y4, z4, r, g, b, light);
    }

    private static void vert(VertexConsumer vc, Matrix4f m,
                             float x, float y, float z,
                             float r, float g, float b, int light) {
        vc.addVertex(m, x, y, z)
          .setColor(r, g, b, 1.0f)
          .setUv(0f, 0f)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(light)
          .setNormal(0f, 1f, 0f);
    }

    private static int[] phaseColor(Phase phase) {
        return switch (phase) {
            case LIVE -> new int[]{220, 50, 47};       // 红
            case NEUTRAL -> new int[]{38, 139, 210};   // 蓝
            case EARTH -> new int[]{220, 200, 30};     // 黄绿
            case LEGACY -> new int[]{40, 40, 40};      // 黑
        };
    }
}
