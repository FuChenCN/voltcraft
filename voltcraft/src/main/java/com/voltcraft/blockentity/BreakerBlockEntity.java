package com.voltcraft.blockentity;

import com.voltcraft.block.BreakerBlock;
import com.voltcraft.block.CableBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.network.EnergyNetwork;
import com.voltcraft.electric.network.NetworkManager;
import com.voltcraft.electric.protection.BreakerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 空开方块实体。
 *
 * 跳闸阈值（设计文档 4.2，可调）：
 * - 电流 > 200% 额定 → 立刻跳闸（电磁脱扣模拟）
 * - 电流 > 120% 额定 持续 100 tick → 跳闸（热脱扣模拟）
 *
 * 当前阶段把"电流"近似为"通过本空开的 FE/t"。等接线端子完成后，
 * 短路检测会作为独立分支补上。
 */
public class BreakerBlockEntity extends BlockEntity {

    private static final String NBT_BUFFER = "Buffer";
    private static final String NBT_STATE = "State";
    private static final String NBT_OVERLOAD_TICKS = "OverloadTicks";

    /** 设计文档 4.2 的阈值，后续会迁移到 ModConfigSpec。 */
    private static final double OVERLOAD_FACTOR_HOLD = 1.20;     // 120%
    private static final int OVERLOAD_HOLD_TICKS = 100;           // 5s
    private static final double OVERLOAD_FACTOR_INSTANT = 2.00;  // 200%

    private final CableTier tier;
    private final EnergyStorage buffer;

    private int overloadTicks;
    private long lastFlow;

    public BreakerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CableTier tier) {
        super(type, pos, state);
        this.tier = tier;
        int rate = tier.ratedTransfer();
        // buffer 容量 = 4×rate，输入/输出上限均 = 2×rate（允许瞬时过载，让阈值有意义）
        this.buffer = new EnergyStorage(rate * 4, rate * 2, rate * 2);
    }

    public CableTier tier() {
        return tier;
    }

    public IEnergyStorage inputHandler() {
        if (currentState().isTripped()) return BlockedHandler.INSTANCE;
        return buffer;
    }

    private BreakerState currentState() {
        return getBlockState().getValue(BreakerBlock.STATE);
    }

    public Direction outputFace() {
        return getBlockState().getValue(BreakerBlock.FACING);
    }

    public Direction inputFace() {
        return outputFace().getOpposite();
    }

    public long lastFlow() {
        return lastFlow;
    }

    public void serverTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;

        BreakerState state = currentState();
        if (state.isTripped()) {
            lastFlow = 0;
            return;
        }

        // 短路检测：扫描上游（输入面）网络是否被端子打了短路标志
        Direction inDir = inputFace();
        BlockPos inPos = getBlockPos().relative(inDir);
        BlockState inState = level.getBlockState(inPos);
        if (inState.getBlock() instanceof CableBlock inCable && inCable.tier() == tier) {
            EnergyNetwork inNet = NetworkManager.get(level).networkAt(inPos);
            if (inNet != null && inNet.hasShortCircuit()) {
                trip(level, BreakerState.TRIPPED_SHORT);
                return;
            }
        }

        Direction outDir = outputFace();
        BlockPos outPos = getBlockPos().relative(outDir);
        BlockState outState = level.getBlockState(outPos);
        if (!(outState.getBlock() instanceof CableBlock cb) || cb.tier() != tier) {
            lastFlow = 0;
            return;
        }
        EnergyNetwork outNet = NetworkManager.get(level).networkAt(outPos);
        if (outNet == null) return;

        // 下游短路也跳：可能短路源在下游某个端子上
        if (outNet.hasShortCircuit()) {
            trip(level, BreakerState.TRIPPED_SHORT);
            return;
        }

        // 推 buffer 里的能量到下游电缆网络
        int available = buffer.getEnergyStored();
        if (available <= 0) {
            decayOverload();
            lastFlow = 0;
            return;
        }
        long pushed = outNet.pushEnergy(available, false);
        if (pushed > 0) {
            buffer.extractEnergy((int) Math.min(Integer.MAX_VALUE, pushed), false);
        }
        lastFlow = pushed;

        evaluateOverload(level, pushed);
    }

    private void evaluateOverload(Level level, long flow) {
        long rated = tier.ratedTransfer();
        double factor = (double) flow / rated;

        if (factor > OVERLOAD_FACTOR_INSTANT) {
            trip(level, BreakerState.TRIPPED_OVERLOAD);
            return;
        }
        if (factor > OVERLOAD_FACTOR_HOLD) {
            overloadTicks++;
            if (overloadTicks >= OVERLOAD_HOLD_TICKS) {
                trip(level, BreakerState.TRIPPED_OVERLOAD);
            }
        } else {
            decayOverload();
        }
    }

    private void decayOverload() {
        if (overloadTicks > 0) overloadTicks--;
    }

    private void trip(Level level, BreakerState reason) {
        overloadTicks = 0;
        BlockState newState = getBlockState().setValue(BreakerBlock.STATE, reason);
        level.setBlock(getBlockPos(), newState, 3);
        // 清空 buffer：跳闸后存量电不应继续推送（避免合闸瞬间冲击）
        buffer.extractEnergy(buffer.getEnergyStored(), false);
        setChanged();
    }

    /** 玩家合闸交互入口。 */
    public void reset() {
        Level level = getLevel();
        if (level == null) return;
        BlockState newState = getBlockState().setValue(BreakerBlock.STATE, BreakerState.CLOSED);
        level.setBlock(getBlockPos(), newState, 3);
        overloadTicks = 0;
        setChanged();
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(NBT_BUFFER)) {
            buffer.deserializeNBT(registries, tag.get(NBT_BUFFER));
        }
        overloadTicks = tag.getInt(NBT_OVERLOAD_TICKS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(NBT_BUFFER, buffer.serializeNBT(registries));
        tag.putInt(NBT_OVERLOAD_TICKS, overloadTicks);
    }

    /** 跳闸时给输入面挂的"假"句柄：什么都不收。 */
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
