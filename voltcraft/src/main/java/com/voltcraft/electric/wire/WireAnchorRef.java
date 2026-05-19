package com.voltcraft.electric.wire;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * 软线 Entity 持久化的 anchor 引用。
 *
 * 软线只记录"哪个方块的第几个柱子"，运行时再 resolve 出 {@link WireAnchor}。
 * 这样区块卸载/机器破坏时软线能感知 anchor 失效。
 */
public record WireAnchorRef(BlockPos owner, int anchorIndex) {

    public WireAnchorRef {
        owner = owner.immutable();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", owner.getX());
        tag.putInt("Y", owner.getY());
        tag.putInt("Z", owner.getZ());
        tag.putInt("Idx", anchorIndex);
        return tag;
    }

    @Nullable
    public static WireAnchorRef load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("X")) return null;
        return new WireAnchorRef(
                new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                tag.getInt("Idx")
        );
    }
}
