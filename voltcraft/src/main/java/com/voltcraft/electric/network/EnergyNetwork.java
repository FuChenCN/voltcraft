package com.voltcraft.electric.network;

import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.VoltageTier;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 一条电力线路。对应设计文档 2.2.1：
 * 一组连通的同等级电缆 + 其上的端子/保护器件，共享同一个电压标签。
 *
 * 当前阶段持有：
 * - 唯一 id（仅运行时存在，不存盘；区块加载后由扫描重建）
 * - 电缆等级（整网统一）
 * - 电压标签（可空，由变压器写入）
 * - 成员 BlockPos 集合
 * - 当前 tick 的 FE 流量（用于电流推算）
 */
public final class EnergyNetwork {

    private final UUID id = UUID.randomUUID();
    private final CableTier cableTier;
    private final Set<BlockPos> members = new HashSet<>();

    @Nullable
    private VoltageTier voltageTag;

    /** 当前 tick 内通过该网络的 FE 流量。电流 = flow / 电压。 */
    private long currentFlow;

    public EnergyNetwork(CableTier cableTier) {
        this.cableTier = cableTier;
    }

    public UUID id() {
        return id;
    }

    public CableTier cableTier() {
        return cableTier;
    }

    public Set<BlockPos> members() {
        return Collections.unmodifiableSet(members);
    }

    public int size() {
        return members.size();
    }

    public boolean contains(BlockPos pos) {
        return members.contains(pos);
    }

    void addMember(BlockPos pos) {
        members.add(pos.immutable());
    }

    void removeMember(BlockPos pos) {
        members.remove(pos);
    }

    @Nullable
    public VoltageTier voltageTag() {
        return voltageTag;
    }

    /**
     * 设置整网电压标签。仅由变压器/储能在写入时调用。
     * @throws IllegalArgumentException 当电压与电缆等级不匹配
     */
    public void setVoltageTag(@Nullable VoltageTier voltage) {
        if (voltage != null && voltage != cableTier.voltage()) {
            throw new IllegalArgumentException(
                    "Voltage " + voltage + " incompatible with " + cableTier);
        }
        this.voltageTag = voltage;
    }

    public long currentFlow() {
        return currentFlow;
    }

    public void setCurrentFlow(long flow) {
        this.currentFlow = flow;
    }

    /**
     * 自动推算电流（A）= 当前 FE/t ÷ 电压（V）。
     * 设计文档 2.2.3。电压未设置时返回 0。
     */
    public double currentAmps() {
        if (voltageTag == null || voltageTag.volts() == 0) {
            return 0.0;
        }
        return (double) currentFlow / voltageTag.volts();
    }

    @Override
    public String toString() {
        return "EnergyNetwork{id=" + id + ", tier=" + cableTier
                + ", voltage=" + voltageTag + ", size=" + members.size() + "}";
    }
}
