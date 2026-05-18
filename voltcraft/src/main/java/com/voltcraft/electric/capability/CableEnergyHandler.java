package com.voltcraft.electric.capability;

import com.voltcraft.blockentity.CableBlockEntity;
import com.voltcraft.electric.network.EnergyNetwork;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * 电缆方块向外暴露的 IEnergyStorage。
 *
 * 语义：
 * - canExtract = false：电缆不储能；外部消费者由 {@link EnergyNetwork#distributeTick} 主动推送
 * - canReceive = true：允许外部生产者（包括第三方 mod）推 FE 进网络
 *
 * 当电缆所属网络尚未设置电压标签（未通过变压器接入）时，receiveEnergy 返回 0
 * ——避免普通 FE 不经变压器直接进入电网，符合设计文档 12.5 节"必须经过变压器"的要求。
 */
public final class CableEnergyHandler implements IEnergyStorage {

    private final CableBlockEntity owner;

    public CableEnergyHandler(CableBlockEntity owner) {
        this.owner = owner;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        EnergyNetwork net = network();
        if (net == null || net.voltageTag() == null) return 0;
        long accepted = net.pushEnergy(maxReceive, simulate);
        return (int) Math.min(accepted, Integer.MAX_VALUE);
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        return 0;
    }

    @Override
    public int getMaxEnergyStored() {
        EnergyNetwork net = network();
        return net == null ? 0 : net.cableTier().ratedTransfer();
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        EnergyNetwork net = network();
        return net != null && net.voltageTag() != null;
    }

    @Nullable
    private EnergyNetwork network() {
        return owner.network();
    }
}
