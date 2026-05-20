package com.voltcraft.electric.wire;

import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.Phase;
import net.minecraft.world.phys.Vec3;

/**
 * 通用 6 柱顶面布局：3 input + 3 output，全部在方块顶面。
 *
 * 局部坐标按 FACING=NORTH 朝向时计算；机器 BE 的 anchorWorldPos override
 * 应该按自己的 FACING 旋转。
 *
 * 顶面布局（FACING=NORTH，玩家从南向北看）：
 *
 *     +Z (北/远) ─── E_in ──── E_out
 *                     ║         ║
 *                   N_in ───── N_out
 *                     ║         ║
 *     -Z (南/近) ─── L_in ──── L_out
 *                  (-X 左)    (+X 右)
 *
 * input 在 X=0.25 一列，output 在 X=0.75 一列；L/N/E 沿 Z 排开。
 *
 * anchor index 约定（所有机器共用，不允许偏离）：
 *   0: L_IN   1: N_IN   2: E_IN
 *   3: L_OUT  4: N_OUT  5: E_OUT
 */
public final class TopAnchorLayout {

    public static final int L_IN = 0;
    public static final int N_IN = 1;
    public static final int E_IN = 2;
    public static final int L_OUT = 3;
    public static final int N_OUT = 4;
    public static final int E_OUT = 5;

    public static final int COUNT = 6;

    private static final double Y = 1.125;  // 柱子顶面 = 主方块 y=1 + 凸起 2/16
    private static final double X_IN = 0.25;
    private static final double X_OUT = 0.75;
    // Z 与贴图 v 对齐：贴图 v 大（下方）= 世界 +Z（南/近）= L 红色（最显眼）
    private static final double Z_L = 0.80;  // 贴图 v=12-13 (近)
    private static final double Z_N = 0.50;  // 贴图 v=7-8  (中)
    private static final double Z_E = 0.20;  // 贴图 v=2-3  (远)

    private static final Vec3 LOCAL_L_IN  = new Vec3(X_IN,  Y, Z_L);
    private static final Vec3 LOCAL_N_IN  = new Vec3(X_IN,  Y, Z_N);
    private static final Vec3 LOCAL_E_IN  = new Vec3(X_IN,  Y, Z_E);
    private static final Vec3 LOCAL_L_OUT = new Vec3(X_OUT, Y, Z_L);
    private static final Vec3 LOCAL_N_OUT = new Vec3(X_OUT, Y, Z_N);
    private static final Vec3 LOCAL_E_OUT = new Vec3(X_OUT, Y, Z_E);

    private TopAnchorLayout() {}

    /** 创建一个 6 柱完整布局。tier 决定本机器接什么等级软线。 */
    public static WireAnchor[] createAnchors(CableTier tier) {
        WireAnchor[] arr = new WireAnchor[COUNT];
        arr[L_IN]  = new WireAnchor(L_IN,  Phase.LIVE,    tier, WireAnchor.Direction.INPUT,  LOCAL_L_IN);
        arr[N_IN]  = new WireAnchor(N_IN,  Phase.NEUTRAL, tier, WireAnchor.Direction.INPUT,  LOCAL_N_IN);
        arr[E_IN]  = new WireAnchor(E_IN,  Phase.EARTH,   tier, WireAnchor.Direction.INPUT,  LOCAL_E_IN);
        arr[L_OUT] = new WireAnchor(L_OUT, Phase.LIVE,    tier, WireAnchor.Direction.OUTPUT, LOCAL_L_OUT);
        arr[N_OUT] = new WireAnchor(N_OUT, Phase.NEUTRAL, tier, WireAnchor.Direction.OUTPUT, LOCAL_N_OUT);
        arr[E_OUT] = new WireAnchor(E_OUT, Phase.EARTH,   tier, WireAnchor.Direction.OUTPUT, LOCAL_E_OUT);
        return arr;
    }

    /**
     * 把局部坐标按机器的水平 FACING 旋转到世界坐标。
     * FACING=NORTH 时不变；NORTH 是基准。
     */
    public static Vec3 worldPos(net.minecraft.core.Direction facing, Vec3 local,
                                net.minecraft.core.BlockPos blockPos) {
        double dx = local.x - 0.5;
        double dz = local.z - 0.5;
        double rx, rz;
        switch (facing) {
            case NORTH -> { rx = dx; rz = dz; }
            case SOUTH -> { rx = -dx; rz = -dz; }
            case EAST  -> { rx = -dz; rz = dx; }
            case WEST  -> { rx = dz; rz = -dx; }
            default    -> { rx = dx; rz = dz; }
        }
        return new Vec3(blockPos.getX() + 0.5 + rx, blockPos.getY() + local.y, blockPos.getZ() + 0.5 + rz);
    }
}
