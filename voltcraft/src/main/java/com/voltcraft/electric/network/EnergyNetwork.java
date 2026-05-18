package com.voltcraft.electric.network;

import com.voltcraft.block.CableBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.VoltageTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 一条电力线路。对应设计文档 2.2.1：
 * 一组连通的同等级电缆 + 其上的端子/保护器件，共享同一个电压标签。
 *
 * 线程模型：仅服务端线程访问。
 */
public final class EnergyNetwork {

    private final UUID id = UUID.randomUUID();
    private final CableTier cableTier;
    private final Set<BlockPos> members = new HashSet<>();

    @Nullable
    private VoltageTier voltageTag;

    /** 变压器/储能等本 tick 推入的 FE，等待 tick 末分发。 */
    private long pendingInput;

    /** 上一 tick 实际通过网络的 FE 流量。用于电流推算。 */
    private long lastFlow;

    public EnergyNetwork(CableTier cableTier) {
        this.cableTier = cableTier;
    }

    public UUID id() { return id; }

    public CableTier cableTier() { return cableTier; }

    public Set<BlockPos> members() { return Collections.unmodifiableSet(members); }

    public int size() { return members.size(); }

    public boolean contains(BlockPos pos) { return members.contains(pos); }

    void addMember(BlockPos pos) { members.add(pos.immutable()); }

    void removeMember(BlockPos pos) { members.remove(pos); }

    @Nullable
    public VoltageTier voltageTag() { return voltageTag; }

    public void setVoltageTag(@Nullable VoltageTier voltage) {
        if (voltage != null && voltage != cableTier.voltage()) {
            throw new IllegalArgumentException(
                    "Voltage " + voltage + " incompatible with " + cableTier);
        }
        this.voltageTag = voltage;
    }

    public long lastFlow() { return lastFlow; }

    /**
     * 由变压器/储能调用，向网络注入 FE。受电缆等级 ratedTransfer 上限约束。
     * 当前 tick 累计的输入会在 distributeTick 中分发。
     *
     * @return 实际接受的 FE 数量
     */
    public long pushEnergy(long amount, boolean simulate) {
        if (amount <= 0) return 0;
        long capacity = (long) cableTier.ratedTransfer() - pendingInput;
        long accepted = Math.max(0, Math.min(amount, capacity));
        if (!simulate) pendingInput += accepted;
        return accepted;
    }

    /**
     * 自动推算电流（A）= 上 tick FE/t ÷ 电压（V）。
     * 设计文档 2.2.3。电压未设置时返回 0。
     */
    public double currentAmps() {
        if (voltageTag == null || voltageTag.volts() == 0) return 0.0;
        return (double) lastFlow / voltageTag.volts();
    }

    /**
     * 服务端 tick 末调用：把 pendingInput 按比例分发给所有相邻消费者。
     *
     * 当前阶段策略：扫描每根电缆的六个邻面，找到接受 FE 的非电缆方块，
     * 用一轮 round-robin 把电分给它们，直到 pendingInput 用完或没人收。
     */
    public void distributeTick(Level level) {
        if (pendingInput <= 0) {
            lastFlow = 0;
            return;
        }

        List<IEnergyStorage> consumers = collectConsumers(level);
        if (consumers.isEmpty()) {
            // 没消费者：能量丢弃（电缆没有储能能力）
            lastFlow = 0;
            pendingInput = 0;
            return;
        }

        long remaining = pendingInput;
        long delivered = 0;
        // 多轮平均分配，直到所有消费者饱和或电用完
        boolean progress = true;
        while (remaining > 0 && progress) {
            progress = false;
            long perConsumer = Math.max(1, remaining / consumers.size());
            for (IEnergyStorage c : consumers) {
                if (remaining <= 0) break;
                int give = (int) Math.min(perConsumer, Math.min(Integer.MAX_VALUE, remaining));
                int accepted = c.receiveEnergy(give, false);
                if (accepted > 0) {
                    remaining -= accepted;
                    delivered += accepted;
                    progress = true;
                }
            }
        }

        lastFlow = delivered;
        pendingInput = 0;
    }

    private List<IEnergyStorage> collectConsumers(Level level) {
        List<IEnergyStorage> out = new ArrayList<>();
        Set<BlockPos> seenConsumers = new HashSet<>();
        for (BlockPos cable : members) {
            for (Direction d : Direction.values()) {
                BlockPos neighbor = cable.relative(d);
                if (members.contains(neighbor)) continue;          // 是同网络电缆
                if (!seenConsumers.add(neighbor)) continue;         // 邻居方块去重
                BlockEntity be = level.getBlockEntity(neighbor);
                if (be == null) continue;
                // 排除其它电缆（不同等级或断网的）
                if (be.getBlockState().getBlock() instanceof CableBlock) continue;
                IEnergyStorage es = level.getCapability(
                        Capabilities.EnergyStorage.BLOCK,
                        neighbor,
                        be.getBlockState(),
                        be,
                        d.getOpposite()
                );
                if (es != null && es.canReceive()) {
                    out.add(es);
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "EnergyNetwork{id=" + id + ", tier=" + cableTier
                + ", voltage=" + voltageTag + ", size=" + members.size() + "}";
    }
}
