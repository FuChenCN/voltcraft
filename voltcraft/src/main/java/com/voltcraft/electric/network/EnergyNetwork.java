package com.voltcraft.electric.network;

import com.voltcraft.block.CableBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.Phase;
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
 * 三相重构后（2026-05-19）：
 *   每个 EnergyNetwork 绑定一个 {@link Phase}（LIVE / NEUTRAL / EARTH），
 *   或 {@link Phase#LEGACY} 表示旧硬电缆未分相状态。L/N 各自传递一半 FE，
 *   E 平时零流量，仅由 RCD 节点通过 {@link #injectLeakage} 注入漏电流。
 *
 * 线程模型：仅服务端线程访问。
 */
public final class EnergyNetwork {

    private final UUID id = UUID.randomUUID();
    private final CableTier cableTier;
    private final Phase phase;
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

    /** 本 tick 已被 pullEnergy 拿走的 FE，distributeTick 末尾合入 lastFlow。 */
    private long pulledThisTick;

    /**
     * 仅 EARTH 相用：本 tick 累计的漏电流（FE/t 等效）。
     * 由各 RCD 节点在自己的 serverTick 中通过 {@link #injectLeakage} 写入：
     * 节点对账上游 L 流量和 N 流量，差额作为漏电注入到对应的 EARTH 网络。
     * RCD 模块在 distributeTick 后读取 {@link #leakageCurrent()} 决定是否跳闸。
     * 工作相（L/N/LEGACY）上调用 injectLeakage 是 no-op。
     */
    private long leakageThisTick;
    private long leakageCurrent;

    /** 本 tick 已注入但未分发的 FE。供空开/端子 pullEnergy 透视上游。 */
    public long pendingInput() { return pendingInput; }

    public EnergyNetwork(CableTier cableTier) {
        this(cableTier, Phase.LEGACY);
    }

    public EnergyNetwork(CableTier cableTier, Phase phase) {
        this.cableTier = cableTier;
        this.phase = phase;
    }

    public UUID id() { return id; }

    public CableTier cableTier() { return cableTier; }

    public Phase phase() { return phase; }

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
     * RCD 节点在本 tick 注入漏电流。仅 EARTH 相生效，其它相忽略。
     * 多次注入累加，distributeTick 末尾收口为 leakageCurrent。
     */
    public void injectLeakage(long amount) {
        if (phase != Phase.EARTH) return;
        if (amount <= 0) return;
        leakageThisTick += amount;
    }

    /** RCD 在 distributeTick 之后读取，决定是否跳闸。 */
    public long leakageCurrent() { return leakageCurrent; }

    /** 端子在 serverTick 中调用，标记本网络上有短路。 */
    public void reportShortCircuit(BlockPos source) {
        this.shortCircuitSource = source.immutable();
        this.shortCircuitStaleTicks = 0;
    }

    @Nullable
    public BlockPos shortCircuitSource() {
        return shortCircuitSource;
    }

    public boolean hasShortCircuit() {
        return shortCircuitSource != null;
    }

    /**
     * 由变压器/储能/端子调用，向网络注入 FE。
     *
     * 上限有两层：
     *   1. 电缆等级 ratedTransfer（本 tick 通过整网的 FE/t 总额）
     *   2. 下游消费者剩余可吸收量（simulate receive 累加）—— 防止消费者满后还在抽源头
     *
     * 取两者较小值。这样发电机在下游全满时不会被持续抽空。
     *
     * @return 实际接受的 FE 数量
     */
    public long pushEnergy(Level level, long amount, boolean simulate) {
        if (amount <= 0) return 0;
        long cableCap = (long) cableTier.ratedTransfer() - pendingInput;
        if (cableCap <= 0) return 0;

        long sinkCap = simulateSinkHeadroom(level);
        long capacity = Math.min(cableCap, Math.max(0, sinkCap - pendingInput));
        long accepted = Math.max(0, Math.min(amount, capacity));
        if (!simulate) pendingInput += accepted;
        return accepted;
    }

    /**
     * 由端子等内部模块从网络拉电。语义：从本 tick 已注入的 pendingInput 里直接取走，
     * 这部分电相当于"绕过 distributeTick 的下发，提前从生产者侧分流给端子"。
     *
     * 只有 pendingInput 里的电才能被 pull——外部第三方机器作为生产者也通过电缆 cap 走 push 路径，
     * 所以这条接口对所有上游统一。
     *
     * @return 实际抽到的 FE 数量
     */
    public long pullEnergy(Level level, long amount, boolean simulate) {
        if (amount <= 0) return 0;
        long taken = Math.min(amount, pendingInput);
        if (taken <= 0) return 0;
        if (!simulate) {
            pendingInput -= taken;
            pulledThisTick += taken;
        }
        return taken;
    }

    /** 不带状态地扫描所有消费者的 simulate-receive 上限。用于反压。 */
    private long simulateSinkHeadroom(Level level) {
        if (level == null) return cableTier.ratedTransfer();
        long total = 0;
        long cap = cableTier.ratedTransfer();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos cable : members) {
            if (total >= cap) return cap;
            for (Direction d : Direction.values()) {
                if (total >= cap) return cap;
                BlockPos neighbor = cable.relative(d);
                if (members.contains(neighbor)) continue;
                if (!seen.add(neighbor)) continue;
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
                if (es == null || !es.canReceive()) continue;
                int got = es.receiveEnergy(Integer.MAX_VALUE, true);
                if (got > 0) total += got;
            }
        }
        return total;
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
            pulledThisTick = 0;
            leakageCurrent = leakageThisTick;
            leakageThisTick = 0;
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
            lastFlow = pulledThisTick;
            pulledThisTick = 0;
            leakageCurrent = leakageThisTick;
            leakageThisTick = 0;
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
        lastFlow = delivered + pulledThisTick;
        pulledThisTick = 0;
        leakageCurrent = leakageThisTick;
        leakageThisTick = 0;
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
                + ", phase=" + phase
                + ", voltage=" + voltageTag + ", size=" + members.size() + "}";
    }
}
