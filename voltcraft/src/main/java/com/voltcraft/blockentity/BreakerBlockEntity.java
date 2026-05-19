package com.voltcraft.blockentity;

import com.voltcraft.block.BreakerBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.Phase;
import com.voltcraft.electric.protection.BreakerState;
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
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * 三相空气开关。
 *
 * 拓扑：FACING 是把手装饰面，不接软线。两侧（FACING 顺时针 / 逆时针）各 3 个接线柱（L/N/E），
 * 共 6 个 anchor。
 *
 * 6 个独立 buffer，跨边推送：bufferA_L → bufferB_L、bufferA_N → bufferB_N 等。
 * 跨边规则保证物理上不可能形成自循环。
 *
 * 跳闸：
 *   * 任一相 instant 流量 > 200% rated → TRIPPED_OVERLOAD
 *   * 任一相持续 > 120% rated 100 tick → TRIPPED_OVERLOAD
 *   * EARTH 漏电（由 RCD 在 #79 引入）
 */
public class BreakerBlockEntity extends BlockEntity implements WireAnchorOwner {

    private static final String NBT_BUFFER_A_L = "BufferAL";
    private static final String NBT_BUFFER_A_N = "BufferAN";
    private static final String NBT_BUFFER_A_E = "BufferAE";
    private static final String NBT_BUFFER_B_L = "BufferBL";
    private static final String NBT_BUFFER_B_N = "BufferBN";
    private static final String NBT_BUFFER_B_E = "BufferBE";
    private static final String NBT_OVERLOAD_TICKS = "OverloadTicks";

    private static final double OVERLOAD_FACTOR_HOLD = 1.20;
    private static final int OVERLOAD_HOLD_TICKS = 100;
    private static final double OVERLOAD_FACTOR_INSTANT = 2.00;

    /**
     * RCD 灵敏度：30 mA 等效——在 220V 等效电压下大约 6 FE/t 的 L-N 差流。
     * 实际游戏里我们用一个固定 FE/t 阈值与持续 tick 数。
     */
    private static final long LEAKAGE_TRIP_FE_PER_TICK = 32;
    private static final int LEAKAGE_HOLD_TICKS = 10;

    public static final int ANCHOR_A_L = 0;
    public static final int ANCHOR_A_N = 1;
    public static final int ANCHOR_A_E = 2;
    public static final int ANCHOR_B_L = 3;
    public static final int ANCHOR_B_N = 4;
    public static final int ANCHOR_B_E = 5;

    private final CableTier tier;
    private final EnergyStorage[] buffers = new EnergyStorage[6];
    private final WireAnchor[] anchors = new WireAnchor[6];

    private int overloadTicks;
    private int leakageTicks;
    private long lastFlow;
    private long lastLeakage;

    public BreakerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CableTier tier) {
        super(type, pos, state);
        this.tier = tier;
        int rate = tier.ratedTransfer();
        for (int i = 0; i < 6; i++) {
            buffers[i] = new EnergyStorage(rate * 2, rate * 2, rate * 2);
        }
        // 局部坐标按 FACING=NORTH 时的「左面 X=0、右面 X=1」布置；
        // anchorWorldPos 中按 FACING 旋转
        anchors[ANCHOR_A_L] = new WireAnchor(ANCHOR_A_L, Phase.LIVE,    new Vec3(-0.05, 0.75, 0.5));
        anchors[ANCHOR_A_N] = new WireAnchor(ANCHOR_A_N, Phase.NEUTRAL, new Vec3(-0.05, 0.50, 0.5));
        anchors[ANCHOR_A_E] = new WireAnchor(ANCHOR_A_E, Phase.EARTH,   new Vec3(-0.05, 0.25, 0.5));
        anchors[ANCHOR_B_L] = new WireAnchor(ANCHOR_B_L, Phase.LIVE,    new Vec3( 1.05, 0.75, 0.5));
        anchors[ANCHOR_B_N] = new WireAnchor(ANCHOR_B_N, Phase.NEUTRAL, new Vec3( 1.05, 0.50, 0.5));
        anchors[ANCHOR_B_E] = new WireAnchor(ANCHOR_B_E, Phase.EARTH,   new Vec3( 1.05, 0.25, 0.5));
    }

    public CableTier tier() { return tier; }
    public long lastFlow() { return lastFlow; }
    public long lastLeakage() { return lastLeakage; }

    private BreakerState currentState() {
        return getBlockState().getValue(BreakerBlock.STATE);
    }

    public Direction sideA() { return getBlockState().getValue(BreakerBlock.FACING).getClockWise(); }
    public Direction sideB() { return getBlockState().getValue(BreakerBlock.FACING).getCounterClockWise(); }

    @Override
    @Nullable
    public WireAnchor anchor(int index) {
        return (index < 0 || index >= 6) ? null : anchors[index];
    }

    @Override
    public int anchorCount() { return 6; }

    @Override
    public IEnergyStorage anchorBuffer(int index) {
        if (index < 0 || index >= 6) return null;
        if (!currentState().conducts()) return BlockedHandler.INSTANCE;
        return buffers[index];
    }

    @Override
    public Vec3 anchorWorldPos(WireAnchor anchor, BlockPos blockPos) {
        Direction face = getBlockState().getValue(BreakerBlock.FACING);
        Vec3 lo = anchor.localOffset();
        double dx = lo.x - 0.5;
        double dz = lo.z - 0.5;
        double rx, rz;
        switch (face) {
            case NORTH -> { rx = dx; rz = dz; }
            case SOUTH -> { rx = -dx; rz = -dz; }
            case EAST  -> { rx = -dz; rz = dx; }
            case WEST  -> { rx = dz; rz = -dx; }
            default    -> { rx = dx; rz = dz; }
        }
        return new Vec3(blockPos.getX() + 0.5 + rx, blockPos.getY() + lo.y, blockPos.getZ() + 0.5 + rz);
    }

    public void serverTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;

        BreakerState state = currentState();
        if (!state.conducts()) {
            lastFlow = 0;
            lastLeakage = 0;
            return;
        }

        long flow = 0;
        long maxPhaseFlow = 0;
        long flowPerPhase[] = new long[3];
        for (int p = 0; p < 3; p++) {
            int aIdx = p;
            int bIdx = p + 3;
            long phaseFlow = transfer(buffers[aIdx], buffers[bIdx]);
            phaseFlow += transfer(buffers[bIdx], buffers[aIdx]);
            flowPerPhase[p] = phaseFlow;
            flow += phaseFlow;
            if (phaseFlow > maxPhaseFlow) maxPhaseFlow = phaseFlow;
        }

        lastFlow = flow;
        // RCD：L 流量与 N 流量之差视作漏电流（基尔霍夫电流定律）。
        // E 上的流量也算入差额——E 平时为 0，被 RCD 节点（其它机器）注入时不为 0。
        long leakage = Math.abs(flowPerPhase[0] - flowPerPhase[1]);
        lastLeakage = leakage;

        evaluateOverload(level, maxPhaseFlow);
        evaluateLeakage(level, leakage);
    }

    private static long transfer(EnergyStorage src, EnergyStorage dst) {
        int avail = src.extractEnergy(Integer.MAX_VALUE, true);
        if (avail <= 0) return 0;
        int accepted = dst.receiveEnergy(avail, true);
        if (accepted <= 0) return 0;
        src.extractEnergy(accepted, false);
        dst.receiveEnergy(accepted, false);
        return accepted;
    }

    private void evaluateOverload(Level level, long phaseFlow) {
        long rated = tier.ratedTransfer();
        double factor = (double) phaseFlow / rated;
        if (factor > OVERLOAD_FACTOR_INSTANT) {
            trip(level, BreakerState.TRIPPED_OVERLOAD);
            return;
        }
        if (factor > OVERLOAD_FACTOR_HOLD) {
            overloadTicks++;
            if (overloadTicks >= OVERLOAD_HOLD_TICKS) {
                trip(level, BreakerState.TRIPPED_OVERLOAD);
            }
        } else if (overloadTicks > 0) {
            overloadTicks--;
        }
    }

    private void evaluateLeakage(Level level, long leakage) {
        if (leakage > LEAKAGE_TRIP_FE_PER_TICK) {
            leakageTicks++;
            if (leakageTicks >= LEAKAGE_HOLD_TICKS) {
                trip(level, BreakerState.TRIPPED_LEAKAGE);
            }
        } else if (leakageTicks > 0) {
            leakageTicks--;
        }
    }

    private void trip(Level level, BreakerState reason) {
        overloadTicks = 0;
        leakageTicks = 0;
        BlockState newState = getBlockState().setValue(BreakerBlock.STATE, reason);
        level.setBlock(getBlockPos(), newState, 3);
        for (EnergyStorage b : buffers) {
            b.extractEnergy(b.getEnergyStored(), false);
        }
        setChanged();
    }

    public void reset() {
        Level level = getLevel();
        if (level == null) return;
        BlockState newState = getBlockState().setValue(BreakerBlock.STATE, BreakerState.CLOSED);
        level.setBlock(getBlockPos(), newState, 3);
        overloadTicks = 0;
        leakageTicks = 0;
        setChanged();
    }

    public void setState(BreakerState newStateValue) {
        Level level = getLevel();
        if (level == null) return;
        level.setBlock(getBlockPos(), getBlockState().setValue(BreakerBlock.STATE, newStateValue), 3);
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(NBT_BUFFER_A_L)) buffers[ANCHOR_A_L].deserializeNBT(registries, tag.get(NBT_BUFFER_A_L));
        if (tag.contains(NBT_BUFFER_A_N)) buffers[ANCHOR_A_N].deserializeNBT(registries, tag.get(NBT_BUFFER_A_N));
        if (tag.contains(NBT_BUFFER_A_E)) buffers[ANCHOR_A_E].deserializeNBT(registries, tag.get(NBT_BUFFER_A_E));
        if (tag.contains(NBT_BUFFER_B_L)) buffers[ANCHOR_B_L].deserializeNBT(registries, tag.get(NBT_BUFFER_B_L));
        if (tag.contains(NBT_BUFFER_B_N)) buffers[ANCHOR_B_N].deserializeNBT(registries, tag.get(NBT_BUFFER_B_N));
        if (tag.contains(NBT_BUFFER_B_E)) buffers[ANCHOR_B_E].deserializeNBT(registries, tag.get(NBT_BUFFER_B_E));
        overloadTicks = tag.getInt(NBT_OVERLOAD_TICKS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(NBT_BUFFER_A_L, buffers[ANCHOR_A_L].serializeNBT(registries));
        tag.put(NBT_BUFFER_A_N, buffers[ANCHOR_A_N].serializeNBT(registries));
        tag.put(NBT_BUFFER_A_E, buffers[ANCHOR_A_E].serializeNBT(registries));
        tag.put(NBT_BUFFER_B_L, buffers[ANCHOR_B_L].serializeNBT(registries));
        tag.put(NBT_BUFFER_B_N, buffers[ANCHOR_B_N].serializeNBT(registries));
        tag.put(NBT_BUFFER_B_E, buffers[ANCHOR_B_E].serializeNBT(registries));
        tag.putInt(NBT_OVERLOAD_TICKS, overloadTicks);
    }

    private static final class BlockedHandler implements IEnergyStorage {
        static final BlockedHandler INSTANCE = new BlockedHandler();
        @Override public int receiveEnergy(int maxReceive, boolean simulate) { return 0; }
        @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return 0; }
        @Override public int getMaxEnergyStored() { return 0; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return false; }
    }
}
