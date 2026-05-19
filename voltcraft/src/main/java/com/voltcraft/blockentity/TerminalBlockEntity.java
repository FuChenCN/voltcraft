package com.voltcraft.blockentity;

import com.voltcraft.block.TerminalBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.Phase;
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
 * 接线端子（三相适配器）。
 *
 * 拓扑：
 *   * FACING（机器面）：朝向外部 mod 机器，暴露单口 IEnergyStorage
 *   * 其余 5 面：水平 4 面 + 顶面用作 L/N/E 接线柱
 *
 * 三个独立 buffer L/N/E，每相 anchor 通过软线接变压器同相。
 * serverTick：把 L 和 N buffer 中的电合流（取两者较小值再 ×2，保证 L=N），推到机器面外部 mod。
 *   * 若任一相 buffer 为空 → 不输出（缺相）
 *   * E 不参与传输
 *
 * 注意：FACING 取代之前的 cable face 语义。机器面就是 FACING；其它 5 面的 anchor 位置硬编码。
 */
public class TerminalBlockEntity extends BlockEntity implements WireAnchorOwner {

    private static final String NBT_BUFFER_L = "BufferL";
    private static final String NBT_BUFFER_N = "BufferN";
    private static final String NBT_BUFFER_E = "BufferE";

    public static final int ANCHOR_L = 0;
    public static final int ANCHOR_N = 1;
    public static final int ANCHOR_E = 2;

    private final CableTier tier;

    private final EnergyStorage bufferL;
    private final EnergyStorage bufferN;
    private final EnergyStorage bufferE;

    private final WireAnchor anchorL;
    private final WireAnchor anchorN;
    private final WireAnchor anchorE;

    /** 端子总通流量，用于 Jade。 */
    private long lastFlow;

    public TerminalBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CableTier tier) {
        super(type, pos, state);
        this.tier = tier;
        int rate = tier.ratedTransfer();
        // 每相缓存 = 1 tick 额定流量
        this.bufferL = new EnergyStorage(rate, rate, rate);
        this.bufferN = new EnergyStorage(rate, rate, rate);
        this.bufferE = new EnergyStorage(rate, rate, rate);
        // 三个柱子放在面板上（机器面对侧、左右两个水平面、顶面）
        // L 顶面右、N 顶面左、E 顶面后（远离 FACING 一侧）
        this.anchorL = new WireAnchor(ANCHOR_L, Phase.LIVE, new Vec3(0.75, 1.05, 0.5));
        this.anchorN = new WireAnchor(ANCHOR_N, Phase.NEUTRAL, new Vec3(0.25, 1.05, 0.5));
        this.anchorE = new WireAnchor(ANCHOR_E, Phase.EARTH, new Vec3(0.5, 1.05, 0.85));
    }

    public CableTier tier() { return tier; }

    public long lastFlow() { return lastFlow; }

    public Direction machineFace() {
        return getBlockState().getValue(TerminalBlock.FACING);
    }

    @Override
    @Nullable
    public WireAnchor anchor(int index) {
        return switch (index) {
            case ANCHOR_L -> anchorL;
            case ANCHOR_N -> anchorN;
            case ANCHOR_E -> anchorE;
            default -> null;
        };
    }

    @Override
    public int anchorCount() { return 3; }

    @Override
    public IEnergyStorage anchorBuffer(int index) {
        return switch (index) {
            case ANCHOR_L -> bufferL;
            case ANCHOR_N -> bufferN;
            case ANCHOR_E -> bufferE;
            default -> null;
        };
    }

    @Override
    public Vec3 anchorWorldPos(WireAnchor anchor, BlockPos blockPos) {
        Direction face = getBlockState().getValue(TerminalBlock.FACING);
        // FACING 是机器面（朝外）；anchor 设计放在反面或顶面，按 FACING 旋转
        Vec3 lo = anchor.localOffset();
        double dx = lo.x - 0.5;
        double dz = lo.z - 0.5;
        double rx, rz;
        switch (face) {
            case NORTH -> { rx = -dx; rz = -dz; }
            case SOUTH -> { rx = dx; rz = dz; }
            case EAST  -> { rx = dz; rz = -dx; }
            case WEST  -> { rx = -dz; rz = dx; }
            default    -> { rx = dx; rz = dz; }
        }
        return new Vec3(blockPos.getX() + 0.5 + rx, blockPos.getY() + lo.y, blockPos.getZ() + 0.5 + rz);
    }

    /**
     * 机器面对外暴露的合流 IEnergyStorage：
     *   * receiveEnergy = min(L_room, N_room) × 2，accept 后两 buffer 各加一半
     *   * extractEnergy = min(L_stored, N_stored) × 2，extract 后两 buffer 各减一半
     *   * 任一相为 0 时 → 该方向流量也为 0（缺相保护）
     */
    public IEnergyStorage machineHandler() {
        return mergedHandler;
    }

    private final IEnergyStorage mergedHandler = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int half = maxReceive / 2;
            int l = bufferL.receiveEnergy(half, true);
            int n = bufferN.receiveEnergy(half, true);
            int per = Math.min(l, n);
            if (per <= 0) return 0;
            if (!simulate) {
                bufferL.receiveEnergy(per, false);
                bufferN.receiveEnergy(per, false);
            }
            return per * 2;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int half = maxExtract / 2;
            int l = bufferL.extractEnergy(half, true);
            int n = bufferN.extractEnergy(half, true);
            int per = Math.min(l, n);
            if (per <= 0) return 0;
            if (!simulate) {
                bufferL.extractEnergy(per, false);
                bufferN.extractEnergy(per, false);
            }
            return per * 2;
        }

        @Override
        public int getEnergyStored() {
            return Math.min(bufferL.getEnergyStored(), bufferN.getEnergyStored()) * 2;
        }

        @Override
        public int getMaxEnergyStored() {
            return Math.min(bufferL.getMaxEnergyStored(), bufferN.getMaxEnergyStored()) * 2;
        }

        @Override public boolean canExtract() { return true; }
        @Override public boolean canReceive() { return true; }
    };

    /** 仅用于 Jade。 */
    public int bufferStored() {
        return bufferL.getEnergyStored() + bufferN.getEnergyStored() + bufferE.getEnergyStored();
    }
    public int bufferCapacity() {
        return bufferL.getMaxEnergyStored() + bufferN.getMaxEnergyStored() + bufferE.getMaxEnergyStored();
    }

    public void serverTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;

        // 主动把合流后的电推给机器面邻居（多数 mod 机器是被动收电）
        long flow = 0;
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
                        flow = pushed;
                    }
                }
            }
        }
        lastFlow = flow;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(NBT_BUFFER_L)) bufferL.deserializeNBT(registries, tag.get(NBT_BUFFER_L));
        if (tag.contains(NBT_BUFFER_N)) bufferN.deserializeNBT(registries, tag.get(NBT_BUFFER_N));
        if (tag.contains(NBT_BUFFER_E)) bufferE.deserializeNBT(registries, tag.get(NBT_BUFFER_E));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(NBT_BUFFER_L, bufferL.serializeNBT(registries));
        tag.put(NBT_BUFFER_N, bufferN.serializeNBT(registries));
        tag.put(NBT_BUFFER_E, bufferE.serializeNBT(registries));
    }
}
