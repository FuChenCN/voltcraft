package com.voltcraft.blockentity;

import com.voltcraft.block.CableBlock;
import com.voltcraft.block.TerminalBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.network.EnergyNetwork;
import com.voltcraft.electric.network.NetworkManager;
import com.voltcraft.electric.protection.WiringState;
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
 * 接线端子方块实体。
 *
 * 数据流：
 *   外部机器 → inputBuffer (机器面 IEnergyStorage)
 *           → serverTick: 推入电缆侧网络（仅 WiringState.CORRECT 或 HOT_NEUTRAL_SWAPPED 时）
 *
 *   电缆侧网络（distributeTick）→ 端子的电缆面 IEnergyStorage（无 receive，仅 extract 给机器）
 *   机器面 IEnergyStorage 同时支持 receive（被外部塞 FE）和 extract（让外部机器从端子拉电网的电）
 *
 * 短路状态下：
 * - 机器面 IEnergyStorage 拒收
 * - 网络被打上 shortCircuitSource 标志
 * - 上下游空开会立即 TRIPPED_SHORT
 */
public class TerminalBlockEntity extends BlockEntity {

    private static final String NBT_BUFFER = "Buffer";

    private final CableTier tier;
    private final EnergyStorage buffer;

    /** 端子内部"通流量"统计——用于 Jade 显示和漏保计算。 */
    private long lastFlow;

    public TerminalBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CableTier tier) {
        super(type, pos, state);
        this.tier = tier;
        int rate = tier.ratedTransfer();
        // buffer 双向，容量 4×rate
        this.buffer = new EnergyStorage(rate * 4, rate * 2, rate * 2);
    }

    public CableTier tier() { return tier; }

    public long lastFlow() { return lastFlow; }

    public WiringState wiring() {
        return getBlockState().getValue(TerminalBlock.WIRING);
    }

    public Direction cableFace() {
        return getBlockState().getValue(TerminalBlock.FACING);
    }

    public Direction machineFace() {
        return cableFace().getOpposite();
    }

    /** 暴露给机器面的 IEnergyStorage：受 wiring 制约。 */
    public IEnergyStorage machineHandler() {
        if (!wiring().conducts()) return BlockedHandler.INSTANCE;
        return buffer;
    }

    public void serverTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;

        WiringState wiring = wiring();
        BlockPos cablePos = getBlockPos().relative(cableFace());
        BlockState cableState = level.getBlockState(cablePos);

        boolean cableOk = cableState.getBlock() instanceof CableBlock cb && cb.tier() == tier;
        EnergyNetwork net = cableOk ? NetworkManager.get(level).networkAt(cablePos) : null;

        // 短路：把标志写到电缆侧网络，并清空 buffer
        if (wiring.isShort()) {
            if (net != null) net.reportShortCircuit(getBlockPos());
            buffer.extractEnergy(buffer.getEnergyStored(), false);
            lastFlow = 0;
            return;
        }

        // 正常导通：推 buffer 到网络
        if (net == null) {
            lastFlow = 0;
            return;
        }
        int available = buffer.getEnergyStored();
        if (available <= 0) {
            lastFlow = 0;
            return;
        }
        long pushed = net.pushEnergy(available, false);
        if (pushed > 0) {
            buffer.extractEnergy((int) Math.min(Integer.MAX_VALUE, pushed), false);
        }
        lastFlow = pushed;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(NBT_BUFFER)) {
            buffer.deserializeNBT(registries, tag.get(NBT_BUFFER));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(NBT_BUFFER, buffer.serializeNBT(registries));
    }

    /** 短路时机器面挂的"假"句柄。 */
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
