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
 * 软线渲染器：两端 anchor 间画一条贝塞尔曲线，渲染为 4 棱柱（不是十字平面，避免侧视扁平）。
 *
 * 算法：
 *   1. 二次贝塞尔曲线，控制点在两端中点正下方（sag = chord × SAG_FACTOR）
 *   2. 沿弦构造正交 frame {n1, n2}：n1 = 水平横向，n2 = 弦平面内的垂直方向
 *   3. 每个曲线分段画一个 4 棱柱节，4 个矩形面环绕一周
 */
public class SoftCableRenderer extends EntityRenderer<SoftCableEntity> {

    /** 单像素纯白贴图，颜色靠 vertex color。 */
    private static final ResourceLocation BLANK =
            ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png");

    private static final int SEGMENTS_NEAR = 16;
    private static final int SEGMENTS_FAR = 8;
    private static final double FAR_DIST = 24.0;

    /** 下垂系数：中点 Y 偏移 = 弦长 × SAG_FACTOR。 */
    private static final double SAG_FACTOR = 0.18;

    /** 软线半径（4 棱柱半边长）。直径 ≈ 0.08 个方块 ≈ 1.3 像素，接近 IE 风格的中粗线。 */
    private static final float WIRE_RADIUS = 0.04f;

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
        Vector3f la = new Vector3f(
                (float)(a.x - entityPos.x), (float)(a.y - entityPos.y), (float)(a.z - entityPos.z));
        Vector3f lb = new Vector3f(
                (float)(b.x - entityPos.x), (float)(b.y - entityPos.y), (float)(b.z - entityPos.z));

        double chord = a.distanceTo(b);
        float sag = (float)(chord * SAG_FACTOR);
        Vector3f ctrl = new Vector3f(
                (la.x + lb.x) * 0.5f,
                (la.y + lb.y) * 0.5f - sag,
                (la.z + lb.z) * 0.5f);

        int segments = chord > FAR_DIST ? SEGMENTS_FAR : SEGMENTS_NEAR;
        int[] color = phaseColor(entity.phase());

        // 沿弦构造正交 frame {n1, n2}（整根线共用，避免段间扭转）
        Vector3f chordDir = new Vector3f(lb).sub(la);
        if (chordDir.lengthSquared() < 1e-6f) return;
        chordDir.normalize();
        Vector3f up = new Vector3f(0f, 1f, 0f);
        Vector3f n1 = new Vector3f();
        chordDir.cross(up, n1);
        if (n1.lengthSquared() < 1e-6f) {
            // 弦近乎垂直，退化 fallback
            n1.set(1f, 0f, 0f);
        }
        n1.normalize().mul(WIRE_RADIUS);
        Vector3f n2 = new Vector3f();
        n1.cross(chordDir, n2);
        n2.normalize().mul(WIRE_RADIUS);

        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(BLANK));
        Matrix4f matrix = pose.last().pose();

        Vector3f prev = la;
        for (int i = 1; i <= segments; i++) {
            float t = (float) i / segments;
            Vector3f cur = bezier(la, ctrl, lb, t);
            drawSegment(vc, matrix, prev, cur, n1, n2, color, packedLight);
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

    /** 画一段 4 棱柱：a→b 之间，截面是 (±n1) × (±n2) 矩形。 */
    private static void drawSegment(VertexConsumer vc, Matrix4f m,
                                    Vector3f a, Vector3f b,
                                    Vector3f n1, Vector3f n2,
                                    int[] rgb, int light) {
        float r = rgb[0] / 255f, g = rgb[1] / 255f, bl = rgb[2] / 255f;

        // 8 个角点：a/b 各 4 个（+n1+n2、+n1-n2、-n1-n2、-n1+n2）
        Vector3f a1 = add(a,  n1,  n2);  // a, +n1+n2
        Vector3f a2 = add(a,  n1, neg(n2));  // a, +n1-n2
        Vector3f a3 = add(a, neg(n1), neg(n2));  // a, -n1-n2
        Vector3f a4 = add(a, neg(n1),  n2);  // a, -n1+n2
        Vector3f b1 = add(b,  n1,  n2);
        Vector3f b2 = add(b,  n1, neg(n2));
        Vector3f b3 = add(b, neg(n1), neg(n2));
        Vector3f b4 = add(b, neg(n1),  n2);

        // 4 个侧面（顺时针绕一周）：+n1, -n2, -n1, +n2
        quad(vc, m, a1, b1, b2, a2, r, g, bl, light);  // +n1 面
        quad(vc, m, a2, b2, b3, a3, r, g, bl, light);  // -n2 面
        quad(vc, m, a3, b3, b4, a4, r, g, bl, light);  // -n1 面
        quad(vc, m, a4, b4, b1, a1, r, g, bl, light);  // +n2 面
    }

    private static Vector3f add(Vector3f base, Vector3f d1, Vector3f d2) {
        return new Vector3f(base).add(d1).add(d2);
    }

    private static Vector3f neg(Vector3f v) {
        return new Vector3f(-v.x, -v.y, -v.z);
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             Vector3f p1, Vector3f p2, Vector3f p3, Vector3f p4,
                             float r, float g, float b, int light) {
        vert(vc, m, p1, r, g, b, light);
        vert(vc, m, p2, r, g, b, light);
        vert(vc, m, p3, r, g, b, light);
        vert(vc, m, p4, r, g, b, light);
    }

    private static void vert(VertexConsumer vc, Matrix4f m,
                             Vector3f p, float r, float g, float b, int light) {
        vc.addVertex(m, p.x, p.y, p.z)
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
