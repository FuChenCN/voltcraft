package com.voltcraft.electric.wire;

import com.voltcraft.electric.Phase;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 软线的一个接线点。
 *
 * 由 {@link WireAnchorOwner}（通常是机器 BlockEntity）通过 {@link WireAnchorOwner#anchor(int)}
 * 暴露。SoftCableEntity 持有两个 {@link WireAnchorRef}（owner BlockPos + anchorIndex），
 * tick 时通过 ref 解析回 owner，再拿到这里的 phase / network 来传输能量。
 *
 * 局部坐标 {@link #localOffset()} 是相对方块原点（最小角）的偏移，用于贝塞尔渲染端点
 * 落在机器面板上的接线柱位置。
 */
public final class WireAnchor {

    private final int index;
    private final Phase phase;
    private final Vec3 localOffset;

    /**
     * 当前已连接到这个 anchor 的软线 entity ID。null 表示空闲。
     * 一个 anchor 同时只能接一根软线，避免一柱接多线导致 phase 歧义。
     */
    @Nullable
    private Integer connectedEntityId;

    public WireAnchor(int index, Phase phase, Vec3 localOffset) {
        this.index = index;
        this.phase = phase;
        this.localOffset = localOffset;
    }

    public int index() { return index; }

    public Phase phase() { return phase; }

    /** 接线柱在方块本地坐标系中的位置（0..1）。 */
    public Vec3 localOffset() { return localOffset; }

    public boolean isFree() { return connectedEntityId == null; }

    @Nullable
    public Integer connectedEntityId() { return connectedEntityId; }

    public void connect(int entityId) {
        if (connectedEntityId != null && connectedEntityId != entityId) {
            throw new IllegalStateException(
                    "Anchor #" + index + " already taken by entity " + connectedEntityId);
        }
        this.connectedEntityId = entityId;
    }

    public void disconnect() {
        this.connectedEntityId = null;
    }
}
