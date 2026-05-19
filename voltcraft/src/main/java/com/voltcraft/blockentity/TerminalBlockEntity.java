package com.voltcraft.blockentity;

import com.voltcraft.block.TerminalBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.wire.TopAnchorLayout;
import com.voltcraft.electric.wire.WireAnchor;
import com.voltcraft.electric.wire.WireAnchorOwner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * 接线端子（v2 顶面 6 柱中继）。
 *
 * 拓扑：
 *   * 顶面：6 柱（3 in + 3 out）
 *   * FACING（机器面）：合流后的单口 IEnergyStorage，推给外部 mod 机器
 *
 * 数据流（每 tick）：
 *   1. 把 in_L、in_N 中较小值 ×2 推到 FACING 邻居（合流给外部 mod）
 *   2. 剩余的 in_L、in_N、in_E 直通到 out_L、out_N、out_E（中继给下一台机器）
 *
 * E 不参与合流，只承载漏电流；by-pass 到 out_E。
 */
public class TerminalBlockEntity extends BlockEntity implements WireAnchorOwner {

    private static final String NBT_BUFFER_PREFIX = "Buf";

    private final CableTier tier;
    private final EnergyStorage[] buffers = new EnergyStorage[TopAnchorLayout.COUNT];
    private final WireAnchor[] anchors;
    private long lastFlow;

    public TerminalBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CableTier tier) {
        super(type, pos, state);
        this.tier = tier;
        int rate = tier.ratedTransfer();
        for (int i = 0; i < TopAnchorLayout.COUNT; i++) {
            buffers[i] = new EnergyStorage(rate, rate, rate);
        }
        this.anchors = TopAnchorLayout.createAnchors(tier);
    }

    public CableTier tier() { return tier; }
    public long lastFlow() { return lastFlow; }

    public Direction machineFace() { return getBlockState().getValue(TerminalBlock.FACING); }

    @Override
    @Nullable
    public WireAnchor anchor(int index) {
        return (index < 0 || index >= TopAnchorLayout.COUNT) ? null : anchors[index];
    }

    @Override public int anchorCount() { return TopAnchorLayout.COUNT; }

    @Override
    public IEnergyStorage anchorBuffer(int index) {
        if (index < 0 || index >= TopAnchorLayout.COUNT) return null;
        return buffers[index];
    }

    @Override
    public Vec3 anchorWorldPos(WireAnchor anchor, BlockPos blockPos) {
        Direction facing = getBlockState().getValue(TerminalBlock.FACING);
        return TopAnchorLayout.worldPos(facing, anchor.localOffset(), blockPos);
    }

    /**
     * 机器面合流 IEnergyStorage：仅 extract，由外部 mod 拉电。
     * 内部从 in_L 和 in_N 各取一半合流。in_E 不参与合流。
     */
    public IEnergyStorage machineHandler() { return mergedHandler; }

    private final IEnergyStorage mergedHandler = new IEnergyStorage() {
        @Override public int receiveEnergy(int maxReceive, boolean simulate) {
            // 端子的"机器面"对外是输出端：不接收 FE
            return 0;
        }
        @Override public int extractEnergy(int maxExtract, boolean simulate) {
            int half = maxExtract / 2;
            int l = buffers[TopAnchorLayout.L_IN].extractEnergy(half, true);
            int n = buffers[TopAnchorLayout.N_IN].extractEnergy(half, true);
            int per = Math.min(l, n);
            if (per <= 0) return 0;
            if (!simulate) {
                buffers[TopAnchorLayout.L_IN].extractEnergy(per, false);
                buffers[TopAnchorLayout.N_IN].extractEnergy(per, false);
            }
            return per * 2;
        }
        @Override public int getEnergyStored() {
            return Math.min(buffers[TopAnchorLayout.L_IN].getEnergyStored(),
                            buffers[TopAnchorLayout.N_IN].getEnergyStored()) * 2;
        }
        @Override public int getMaxEnergyStored() {
            return Math.min(buffers[TopAnchorLayout.L_IN].getMaxEnergyStored(),
                            buffers[TopAnchorLayout.N_IN].getMaxEnergyStored()) * 2;
        }
        @Override public boolean canExtract() { return true; }
        @Override public boolean canReceive() { return false; }
    };

    public int bufferStored() {
        int sum = 0;
        for (EnergyStorage b : buffers) sum += b.getEnergyStored();
        return sum;
    }
    public int bufferCapacity() {
        int sum = 0;
        for (EnergyStorage b : buffers) sum += b.getMaxEnergyStored();
        return sum;
    }

    /** 仅用于 Jade 调试：返回某个 anchor 的当前存量。index 越界返 0。 */
    public int anchorStored(int index) {
        if (index < 0 || index >= TopAnchorLayout.COUNT) return 0;
        return buffers[index].getEnergyStored();
    }

    public void serverTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;

        long flow = 0;

        // 1) 合流推给外部 mod 机器（FACING 邻居）
        int avail = mergedHandler.getEnergyStored();
        if (avail > 0) {
            BlockPos machinePos = getBlockPos().relative(machineFace());
            BlockEntity mbe = level.getBlockEntity(machinePos);
            if (mbe != null) {
                IEnergyStorage sink = level.getCapability(
                        Capabilities.EnergyStorage.BLOCK,
                        machinePos,
                        level.getBlockState(machinePos),
                        mbe,
                        machineFace().getOpposite()
                );
                if (sink != null && sink.canReceive()) {
                    int pushed = sink.receiveEnergy(avail, false);
                    if (pushed > 0) {
                        mergedHandler.extractEnergy(pushed, false);
                        flow += pushed;
                    }
                }
            }
        }

        // 2) in_L/N/E → out_L/N/E 直通（中继给下一台机器）
        flow += relay(buffers[TopAnchorLayout.L_IN], buffers[TopAnchorLayout.L_OUT]);
        flow += relay(buffers[TopAnchorLayout.N_IN], buffers[TopAnchorLayout.N_OUT]);
        flow += relay(buffers[TopAnchorLayout.E_IN], buffers[TopAnchorLayout.E_OUT]);

        lastFlow = flow;
    }

    private static long relay(EnergyStorage src, EnergyStorage dst) {
        int avail = src.extractEnergy(Integer.MAX_VALUE, true);
        if (avail <= 0) return 0;
        int accepted = dst.receiveEnergy(avail, true);
        if (accepted <= 0) return 0;
        src.extractEnergy(accepted, false);
        dst.receiveEnergy(accepted, false);
        return accepted;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        for (int i = 0; i < TopAnchorLayout.COUNT; i++) {
            String k = NBT_BUFFER_PREFIX + i;
            if (tag.contains(k)) buffers[i].deserializeNBT(registries, tag.get(k));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        for (int i = 0; i < TopAnchorLayout.COUNT; i++) {
            tag.put(NBT_BUFFER_PREFIX + i, buffers[i].serializeNBT(registries));
        }
    }
}
