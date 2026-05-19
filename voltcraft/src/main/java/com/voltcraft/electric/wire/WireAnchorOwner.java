package com.voltcraft.electric.wire;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 一个能被软线接入的方块（通常是机器 BlockEntity）。
 *
 * 实现者在构造时定义自己有哪些 {@link WireAnchor}（每个柱子绑定一个 phase + 本地坐标），
 * SoftCableEntity 通过 {@link WireAnchorRef} 反向解析到这里取 anchor。
 *
 * 能量传输职责：实现者自己在 serverTick 中根据各 anchor 的状态管理流量。
 * SoftCableEntity 不直接 push/pull，而是在自己 tick 时通过 anchor.phase 找到两端各自的
 * EnergyNetwork（如果机器把柱子下方的"虚拟电缆"挂到了网络），然后做跨网传输。
 */
public interface WireAnchorOwner {

    /**
     * 拿到指定 index 的 anchor。index 越界或不存在返回 null。
     * 这是 lazy 查询接口，每次软线 tick 都会调一次，实现要做 O(1)。
     */
    @Nullable
    WireAnchor anchor(int index);

    /** 总共暴露多少个 anchor。 */
    int anchorCount();

    /**
     * 解析 anchor 在世界中的绝对位置。默认实现：方块原点 + anchor.localOffset。
     * 朝向感知的机器（变压器、空开等）可以 override 让接线柱跟着 FACING 旋转。
     */
    default Vec3 anchorWorldPos(WireAnchor anchor, net.minecraft.core.BlockPos blockPos) {
        return new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ()).add(anchor.localOffset());
    }
}
