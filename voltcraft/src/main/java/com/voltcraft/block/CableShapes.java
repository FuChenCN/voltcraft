package com.voltcraft.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * 缓存 64 种连接组合的 VoxelShape，避免每次 getShape 都重新拼接。
 *
 * 几何：
 * - 核心 8x8x8 居中
 * - 每个连接方向：4x4 横截面、长度从核心边缘到方块边缘 (4 像素)
 */
public final class CableShapes {

    private static final VoxelShape CORE = box(6, 6, 6, 10, 10, 10);
    private static final VoxelShape NORTH = box(6, 6, 0, 10, 10, 6);
    private static final VoxelShape SOUTH = box(6, 6, 10, 10, 10, 16);
    private static final VoxelShape WEST  = box(0, 6, 6, 6, 10, 10);
    private static final VoxelShape EAST  = box(10, 6, 6, 16, 10, 10);
    private static final VoxelShape DOWN  = box(6, 0, 6, 10, 6, 10);
    private static final VoxelShape UP    = box(6, 10, 6, 10, 16, 10);

    /** 64 种组合的缓存 (按位编码：bit0=N bit1=S bit2=W bit3=E bit4=D bit5=U)。 */
    private static final VoxelShape[] CACHE = buildCache();

    private CableShapes() {}

    public static VoxelShape get(BlockState state,
                                 BooleanProperty north, BooleanProperty south,
                                 BooleanProperty east,  BooleanProperty west,
                                 BooleanProperty up,    BooleanProperty down) {
        int idx = 0;
        if (state.getValue(north)) idx |= 1;
        if (state.getValue(south)) idx |= 2;
        if (state.getValue(west))  idx |= 4;
        if (state.getValue(east))  idx |= 8;
        if (state.getValue(down))  idx |= 16;
        if (state.getValue(up))    idx |= 32;
        return CACHE[idx];
    }

    private static VoxelShape[] buildCache() {
        VoxelShape[] arr = new VoxelShape[64];
        for (int i = 0; i < 64; i++) {
            VoxelShape s = CORE;
            if ((i & 1) != 0)  s = Shapes.or(s, NORTH);
            if ((i & 2) != 0)  s = Shapes.or(s, SOUTH);
            if ((i & 4) != 0)  s = Shapes.or(s, WEST);
            if ((i & 8) != 0)  s = Shapes.or(s, EAST);
            if ((i & 16) != 0) s = Shapes.or(s, DOWN);
            if ((i & 32) != 0) s = Shapes.or(s, UP);
            arr[i] = s.optimize();
        }
        return arr;
    }

    private static VoxelShape box(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Shapes.box(x1 / 16.0, y1 / 16.0, z1 / 16.0, x2 / 16.0, y2 / 16.0, z2 / 16.0);
    }
}
