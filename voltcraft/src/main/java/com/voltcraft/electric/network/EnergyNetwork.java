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

    /**
     * 短路源位置。非 null 表示本网络上有端子检测到短路，
     * 空开看到这个标志会立即 TRIPPED_SHORT。每 tick 由端子重新写入；
     * 端子停止写入后 staleTicks 计数到上限即视为故障消除。
     */
    @Nullable
    private BlockPos shortCircuitSource;
    private int shortCircuitStaleTicks;
    private static final int SHORT_STALE_THRESHOLD = 20; // 1s 没续写就清空

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

    /** 端子在 serverTick 中调用，标记本网络上有短路。 */
    public void reportShortCircuit(BlockPos source) {
        this.shortCircuitSource = source.immutable();
        this.shortCircuitStaleTicks = 0;
    }

    public boolean hasShortCircuit() {
        return shortCircuitSource != null;
    }

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
     * 服务端 tick 末调用：
     * 1. pull 阶段：扫描相邻生产者（canExtract=true），simulate 抽电累加额度
     * 2. push 阶段：把 pendingInput + 抽来的电分发给相邻消费者（canReceive=true）
     * 3. commit：按实际分发量回头真正 extract
     *
     * 用 simulate-then-commit 二阶段，确保 push 失败时不会真的从生产者抽电。
     * 同位置同时 canExtract+canReceive 的机器只算消费者，避免把刚送进去的电又抽出来。
     */
    public void distributeTick(Level level) {
        // 短路标志衰减
        if (shortCircuitSource != null) {
            shortCircuitStaleTicks++;
            if (shortCircuitStaleTicks >= SHORT_STALE_THRESHOLD) {
                shortCircuitSource = null;
                shortCircuitStaleTicks = 0;
            }
        }
        // 短路时整网停止传输（空开会在自己的 tick 中自行跳闸）
        if (shortCircuitSource != null) {
            pendingInput = 0;
            lastFlow = 0;
            return;
        }

        Endpoints ep = collectEndpoints(level);
        long ratedCap = cableTier.ratedTransfer();
        long budget = Math.min(pendingInput, ratedCap);
        pendingInput = 0;

        // 1. pull 阶段（simulate）
        long[] pullPlan = new long[ep.producers.size()];
        long pulled = 0;
        if (budget < ratedCap) {
            long pullCap = ratedCap - budget;
            for (int i = 0; i < ep.producers.size() && pullCap > 0; i++) {
                IEnergyStorage src = ep.producers.get(i);
                int take = (int) Math.min(Integer.MAX_VALUE, pullCap);
                int got = src.extractEnergy(take, true);
                if (got > 0) {
                    pullPlan[i] = got;
                    pulled += got;
                    pullCap -= got;
                }
            }
        }

        long total = budget + pulled;
        if (total <= 0 || ep.consumers.isEmpty()) {
            lastFlow = 0;
            return;
        }

        // 2. push 阶段（real）：round-robin 平均分配
        long remaining = total;
        long delivered = 0;
        boolean progress = true;
        while (remaining > 0 && progress) {
            progress = false;
            long perConsumer = Math.max(1, remaining / ep.consumers.size());
            for (IEnergyStorage c : ep.consumers) {
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

        // 3. commit：按 push 实际消化量，从生产者真正抽电（按比例从 pullPlan 扣除）
        long needFromProducers = Math.max(0, delivered - budget);
        if (needFromProducers > 0 && pulled > 0) {
            for (int i = 0; i < ep.producers.size() && needFromProducers > 0; i++) {
                long planned = pullPlan[i];
                if (planned <= 0) continue;
                int take = (int) Math.min(planned, needFromProducers);
                int actuallyTaken = ep.producers.get(i).extractEnergy(take, false);
                needFromProducers -= actuallyTaken;
            }
        }

        // 未消化的 budget 部分被丢弃（电缆没有储能）
        lastFlow = delivered;
    }

    /** 单次扫描分类邻居为生产者 / 消费者。 */
    private Endpoints collectEndpoints(Level level) {
        List<IEnergyStorage> producers = new ArrayList<>();
        List<IEnergyStorage> consumers = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos cable : members) {
            for (Direction d : Direction.values()) {
                BlockPos neighbor = cable.relative(d);
                if (members.contains(neighbor)) continue;       // 同网络电缆
                if (!seen.add(neighbor)) continue;               // 邻居方块去重
                BlockEntity be = level.getBlockEntity(neighbor);
                if (be == null) continue;
                if (be.getBlockState().getBlock() instanceof CableBlock) continue;
                IEnergyStorage es = level.getCapability(
                        Capabilities.EnergyStorage.BLOCK,
                        neighbor,
                        be.getBlockState(),
                        be,
                        d.getOpposite()
                );
                if (es == null) continue;
                // 优先归类为消费者，避免双向机器的自循环
                if (es.canReceive()) {
                    consumers.add(es);
                } else if (es.canExtract()) {
                    producers.add(es);
                }
            }
        }
        return new Endpoints(producers, consumers);
    }

    private record Endpoints(List<IEnergyStorage> producers, List<IEnergyStorage> consumers) {}

    @Override
    public String toString() {
        return "EnergyNetwork{id=" + id + ", tier=" + cableTier
                + ", voltage=" + voltageTag + ", size=" + members.size() + "}";
    }
}
