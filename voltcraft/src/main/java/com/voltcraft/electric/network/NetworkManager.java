package com.voltcraft.electric.network;

import com.voltcraft.VoltCraft;
import com.voltcraft.block.CableBlock;
import com.voltcraft.electric.CableTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个 Level 的电力网络拓扑管理器。
 *
 * 职责：
 * - 维护 BlockPos -> EnergyNetwork 的索引
 * - 电缆放置：合并相邻网络
 * - 电缆破坏：从网络剔除，必要时分裂
 *
 * 数据纯运行时，不持久化。区块加载触发的 BlockEntity onLoad 会重新调用 add，
 * 所以即使服务端重启，拓扑会被自动重建。
 */
public final class NetworkManager {

    private static final Map<ResourceKey<Level>, NetworkManager> INSTANCES = new ConcurrentHashMap<>();

    /** 获取或创建指定 Level 的管理器。仅服务端使用。 */
    public static NetworkManager get(LevelAccessor level) {
        if (!(level instanceof Level lvl) || lvl.isClientSide) {
            throw new IllegalStateException("NetworkManager is server-side only");
        }
        return INSTANCES.computeIfAbsent(lvl.dimension(), k -> new NetworkManager());
    }

    /** 世界卸载时调用，丢弃数据。 */
    public static void onLevelUnload(Level level) {
        if (!level.isClientSide) {
            INSTANCES.remove(level.dimension());
        }
    }

    private final Map<BlockPos, EnergyNetwork> byPos = new HashMap<>();

    private NetworkManager() {}

    @Nullable
    public EnergyNetwork networkAt(BlockPos pos) {
        return byPos.get(pos);
    }

    /**
     * 电缆方块加载时调用（onLoad / 放置）。
     * 扫描六邻居，合并所有同等级邻居网络，把自己加进去。
     */
    public EnergyNetwork onCableAdded(Level level, BlockPos pos, CableTier tier) {
        BlockPos immut = pos.immutable();

        // 已经在某个网络里——幂等，直接返回
        EnergyNetwork existing = byPos.get(immut);
        if (existing != null) {
            return existing;
        }

        // 收集相邻同等级网络（去重）
        Set<EnergyNetwork> neighborNets = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction dir : Direction.values()) {
            BlockPos npos = immut.relative(dir);
            if (!isCableOfTier(level, npos, tier)) continue;
            EnergyNetwork n = byPos.get(npos);
            if (n != null) neighborNets.add(n);
        }

        EnergyNetwork target;
        if (neighborNets.isEmpty()) {
            target = new EnergyNetwork(tier);
        } else {
            // 合并：选成员最多的作为主网，其它合并进来
            target = neighborNets.stream()
                    .max((a, b) -> Integer.compare(a.size(), b.size()))
                    .orElseThrow();
            for (EnergyNetwork other : neighborNets) {
                if (other == target) continue;
                mergeInto(target, other);
            }
        }

        target.addMember(immut);
        byPos.put(immut, target);
        VoltCraft.LOGGER.debug("Cable added at {} -> {}", immut, target);
        return target;
    }

    /**
     * 电缆方块破坏时调用。
     * 从所属网络剔除后，对网络剩余成员做 BFS，必要时分裂为多张网。
     */
    public void onCableRemoved(Level level, BlockPos pos) {
        BlockPos immut = pos.immutable();
        EnergyNetwork net = byPos.remove(immut);
        if (net == null) return;
        net.removeMember(immut);

        if (net.size() == 0) {
            VoltCraft.LOGGER.debug("Network {} emptied at {}", net.id(), immut);
            return;
        }

        // 从被移除位置的每个邻居出发，BFS 找连通片
        Set<BlockPos> visited = new HashSet<>();
        List<Set<BlockPos>> components = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos start = immut.relative(dir);
            if (!net.contains(start) || visited.contains(start)) continue;
            Set<BlockPos> comp = bfs(level, start, net.cableTier(), visited, net);
            if (!comp.isEmpty()) components.add(comp);
        }

        if (components.size() <= 1) {
            // 只剩一个连通片或 0 个：原网络保留即可（可能已变小）
            return;
        }

        // 分裂：原网络保留第一个组件，其余拆成新网络
        Set<BlockPos> keep = components.get(0);
        Set<BlockPos> toDrop = new HashSet<>(net.members());
        toDrop.removeAll(keep);
        for (BlockPos p : toDrop) net.removeMember(p);

        for (int i = 1; i < components.size(); i++) {
            EnergyNetwork newNet = new EnergyNetwork(net.cableTier());
            // 分裂时电压标签不继承——避免歧义。变压器会重新写入。
            for (BlockPos p : components.get(i)) {
                newNet.addMember(p);
                byPos.put(p, newNet);
            }
            VoltCraft.LOGGER.debug("Network split: {} -> {}", net.id(), newNet);
        }
    }

    private Set<BlockPos> bfs(Level level, BlockPos start, CableTier tier,
                              Set<BlockPos> visited, EnergyNetwork ofNet) {
        Set<BlockPos> comp = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (!ofNet.contains(cur)) continue;
            if (!isCableOfTier(level, cur, tier)) continue;
            comp.add(cur);
            for (Direction d : Direction.values()) {
                BlockPos n = cur.relative(d);
                if (visited.contains(n)) continue;
                if (!ofNet.contains(n)) continue;
                visited.add(n);
                queue.add(n);
            }
        }
        return comp;
    }

    private void mergeInto(EnergyNetwork target, EnergyNetwork source) {
        if (source.cableTier() != target.cableTier()) {
            // 理论不会发生：调用方已用 tier 过滤邻居
            throw new IllegalStateException("Cannot merge cables of different tiers");
        }
        // 电压标签合并策略：
        // - 都为 null：保持 null
        // - 一方非 null：取非 null 的
        // - 两方都非 null 且相同：保持
        // - 两方非 null 且不同：理论不会发生（强绑定 + 同 tier），但兜底设 null
        if (target.voltageTag() == null && source.voltageTag() != null) {
            target.setVoltageTag(source.voltageTag());
        } else if (target.voltageTag() != null && source.voltageTag() != null
                && target.voltageTag() != source.voltageTag()) {
            target.setVoltageTag(null);
        }
        for (BlockPos p : source.members()) {
            target.addMember(p);
            byPos.put(p, target);
        }
    }

    private static boolean isCableOfTier(Level level, BlockPos pos, CableTier tier) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof CableBlock cb && cb.tier() == tier;
    }

    /**
     * 服务端 tick 末调用：让所有网络分发本 tick 的 pending 输入。
     * 通过 IdentityHashMap 去重以保证同一网络只 tick 一次。
     */
    public void tickAll(Level level) {
        Set<EnergyNetwork> ticked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EnergyNetwork n : byPos.values()) {
            if (ticked.add(n)) {
                n.distributeTick(level);
            }
        }
    }
}
