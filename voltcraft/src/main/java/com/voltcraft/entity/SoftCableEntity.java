package com.voltcraft.entity;

import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.Phase;
import com.voltcraft.electric.wire.WireAnchor;
import com.voltcraft.electric.wire.WireAnchorOwner;
import com.voltcraft.electric.wire.WireAnchorRef;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * 软线 Entity。
 *
 * 两端绑定到机器 BlockEntity 上的 {@link WireAnchor}，自身不储能、不暴露 Capability。
 * tick 时从 OUTPUT 端 anchor 的 buffer 抽电，推到 INPUT 端 anchor 的 buffer。
 *
 * 持久化：两端 {@link WireAnchorRef}、phase、tier。
 * 客户端同步：两端 owner BlockPos + anchorIndex、phase（决定颜色）。
 *
 * 失效条件（discard 自身）：
 *   * 任一端 anchor 解析不到（区块卸载除外，那是 owner BE 临时为空）
 *   * 任一端 anchor 已被其它 entity 占用且不是自己
 *   * phase / tier / direction 不一致
 *   * 玩家直接攻击（hurt 钩子）
 */
public class SoftCableEntity extends Entity {

    /** 客户端同步：相位序数（0=L,1=N,2=E,3=LEGACY）。 */
    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(SoftCableEntity.class, EntityDataSerializers.INT);

    /** 客户端同步：两端 owner 的 BlockPos + anchorIndex。 */
    private static final EntityDataAccessor<BlockPos> DATA_OWNER_A =
            SynchedEntityData.defineId(SoftCableEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<BlockPos> DATA_OWNER_B =
            SynchedEntityData.defineId(SoftCableEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Integer> DATA_ANCHOR_IDX_A =
            SynchedEntityData.defineId(SoftCableEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ANCHOR_IDX_B =
            SynchedEntityData.defineId(SoftCableEntity.class, EntityDataSerializers.INT);

    /** 仅服务端：两端 anchor 引用。 */
    @Nullable private WireAnchorRef endA;
    @Nullable private WireAnchorRef endB;
    private Phase phase = Phase.LEGACY;
    private CableTier tier = CableTier.LOW;

    /** 几 tick 抽查一次 anchor 健在。每秒一次足够。 */
    private static final int VALIDATE_INTERVAL = 20;

    public SoftCableEntity(EntityType<? extends SoftCableEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * 服务端构造：放置一根新软线。
     * 调用方必须先校验：两端 anchor 都 free、phase 一致、tier 一致、且必须一端 INPUT 一端 OUTPUT。
     */
    public static SoftCableEntity place(Level level, EntityType<SoftCableEntity> type,
                                        WireAnchorRef a, WireAnchorRef b,
                                        Phase phase, CableTier tier) {
        SoftCableEntity ent = new SoftCableEntity(type, level);
        ent.endA = a;
        ent.endB = b;
        ent.phase = phase;
        ent.tier = tier;
        ent.getEntityData().set(DATA_PHASE, phase.ordinal());
        ent.refreshSyncedEnds(level);
        // 把自身位置放在两端中点（便于客户端发现）
        Vec3 mid = ent.midpointWorld(level);
        ent.setPos(mid.x, mid.y, mid.z);
        return ent;
    }

    public CableTier tier() { return tier; }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_PHASE, Phase.LEGACY.ordinal());
        builder.define(DATA_OWNER_A, BlockPos.ZERO);
        builder.define(DATA_OWNER_B, BlockPos.ZERO);
        builder.define(DATA_ANCHOR_IDX_A, 0);
        builder.define(DATA_ANCHOR_IDX_B, 0);
    }

    public Phase phase() {
        if (!level().isClientSide) return phase;
        int ord = getEntityData().get(DATA_PHASE);
        Phase[] all = Phase.values();
        return all[Math.min(ord, all.length - 1)];
    }

    /**
     * 客户端解析端点世界坐标：通过同步的 owner+index 找 BE，再问 anchor 在哪儿。
     * 服务端也可调用，但服务端已有 endA/endB 引用，更直接走 resolveWorldPos。
     */
    public Vec3 endAWorld() {
        return resolveSyncedEndPos(getEntityData().get(DATA_OWNER_A),
                                    getEntityData().get(DATA_ANCHOR_IDX_A));
    }

    public Vec3 endBWorld() {
        return resolveSyncedEndPos(getEntityData().get(DATA_OWNER_B),
                                    getEntityData().get(DATA_ANCHOR_IDX_B));
    }

    private Vec3 resolveSyncedEndPos(BlockPos owner, int idx) {
        if (BlockPos.ZERO.equals(owner)) return position();
        BlockEntity be = level().getBlockEntity(owner);
        if (!(be instanceof WireAnchorOwner o)) {
            return new Vec3(owner.getX() + 0.5, owner.getY() + 0.5, owner.getZ() + 0.5);
        }
        WireAnchor a = o.anchor(idx);
        if (a == null) {
            return new Vec3(owner.getX() + 0.5, owner.getY() + 0.5, owner.getZ() + 0.5);
        }
        return o.anchorWorldPos(a, owner);
    }

    @Nullable public WireAnchorRef endARef() { return endA; }
    @Nullable public WireAnchorRef endBRef() { return endB; }

    /** 解析 ref 到机器 BE，再到 anchor。owner 离线或 BE 不存在返回 null。 */
    @Nullable
    public WireAnchor resolve(WireAnchorRef ref) {
        if (ref == null) return null;
        Level lvl = level();
        if (!lvl.isLoaded(ref.owner())) return null;
        BlockEntity be = lvl.getBlockEntity(ref.owner());
        if (!(be instanceof WireAnchorOwner owner)) return null;
        return owner.anchor(ref.anchorIndex());
    }

    @Nullable
    public Vec3 resolveWorldPos(WireAnchorRef ref) {
        if (ref == null) return null;
        WireAnchor a = resolve(ref);
        if (a == null) return null;
        BlockEntity be = level().getBlockEntity(ref.owner());
        if (!(be instanceof WireAnchorOwner owner)) return null;
        return owner.anchorWorldPos(a, ref.owner());
    }

    /** 两端世界坐标的中点；任一端缺失时退化到自身 position。 */
    private Vec3 midpointWorld(Level lvl) {
        Vec3 a = endA == null ? null : resolveWorldPos(endA);
        Vec3 b = endB == null ? null : resolveWorldPos(endB);
        if (a != null && b != null) return a.add(b).scale(0.5);
        if (a != null) return a;
        if (b != null) return b;
        return position();
    }

    /** 把当前 endA/endB 推到客户端同步槽位（owner BlockPos + anchorIndex）。 */
    private void refreshSyncedEnds(Level lvl) {
        getEntityData().set(DATA_OWNER_A, endA == null ? BlockPos.ZERO : endA.owner());
        getEntityData().set(DATA_OWNER_B, endB == null ? BlockPos.ZERO : endB.owner());
        getEntityData().set(DATA_ANCHOR_IDX_A, endA == null ? 0 : endA.anchorIndex());
        getEntityData().set(DATA_ANCHOR_IDX_B, endB == null ? 0 : endB.anchorIndex());
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        if (tickCount % VALIDATE_INTERVAL == 0) {
            if (!validateEnds()) {
                discard();
                return;
            }
            // 端点偶尔会因机器朝向变化挪位置，重新同步
            refreshSyncedEnds(level());
            updateBoundingBox();
        }
        // 单向转移：从 output 端 buffer 抽电 → 推到 input 端 buffer。
        // output/input 的方向语义在 anchor 上预先定义，软线只是搬运工。
        WireAnchorRef src = outputEnd();
        WireAnchorRef dst = inputEnd();
        transferOnce(src, dst);
    }

    private void transferOnce(@Nullable WireAnchorRef src, @Nullable WireAnchorRef dst) {
        if (src == null || dst == null) return;
        IEnergyStorage source = bufferOf(src);
        IEnergyStorage sink = bufferOf(dst);
        if (source == null || sink == null) return;
        if (!source.canExtract() || !sink.canReceive()) return;
        int avail = source.extractEnergy(Integer.MAX_VALUE, true);
        if (avail <= 0) return;
        int accepted = sink.receiveEnergy(avail, true);
        if (accepted <= 0) return;
        source.extractEnergy(accepted, false);
        sink.receiveEnergy(accepted, false);
    }

    @Nullable
    private IEnergyStorage bufferOf(WireAnchorRef ref) {
        BlockEntity be = level().getBlockEntity(ref.owner());
        if (!(be instanceof WireAnchorOwner owner)) return null;
        return owner.anchorBuffer(ref.anchorIndex());
    }

    /** 两端 anchor 必须能解析、属于自己、phase/tier 一致、且方向一在 INPUT 一在 OUTPUT。 */
    private boolean validateEnds() {
        if (endA == null || endB == null) return false;
        WireAnchor a = resolve(endA);
        WireAnchor b = resolve(endB);
        if (a == null || b == null) return false;
        if (a.connectedEntityId() != null && a.connectedEntityId() != getId()) return false;
        if (b.connectedEntityId() != null && b.connectedEntityId() != getId()) return false;
        if (a.phase() != phase || b.phase() != phase) return false;
        if (a.tier() != tier || b.tier() != tier) return false;
        // 一根软线必须 input↔output；同向是错接
        return a.direction() != b.direction();
    }

    /** 找到「output 端」和「input 端」，让传输有明确方向（output→input）。 */
    @Nullable
    private WireAnchorRef outputEnd() {
        if (endA == null || endB == null) return null;
        WireAnchor a = resolve(endA);
        if (a == null) return null;
        return a.isOutput() ? endA : endB;
    }

    @Nullable
    private WireAnchorRef inputEnd() {
        if (endA == null || endB == null) return null;
        WireAnchor a = resolve(endA);
        if (a == null) return null;
        return a.isInput() ? endA : endB;
    }

    /** AABB = 两端连线的最小包围盒，向下扩 1 格容纳下垂。 */
    private void updateBoundingBox() {
        Vec3 a = endAWorld();
        Vec3 b = endBWorld();
        double minX = Math.min(a.x, b.x);
        double maxX = Math.max(a.x, b.x);
        double minY = Math.min(a.y, b.y) - 1.0;
        double maxY = Math.max(a.y, b.y);
        double minZ = Math.min(a.z, b.z);
        double maxZ = Math.max(a.z, b.z);
        setBoundingBox(new AABB(minX, minY, minZ, maxX, maxY, maxZ));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) return false;
        // 任何攻击都立刻断线（玩家剪线刀、爆炸、岩浆等）
        if (source.getEntity() != null || amount > 0) {
            discard();
            return true;
        }
        return false;
    }

    @Override
    public boolean isPickable() { return true; }

    @Override
    public boolean canBeCollidedWith() { return false; }

    @Override
    public void remove(RemovalReason reason) {
        // 释放两端 anchor 占用
        if (!level().isClientSide) {
            releaseAnchor(endA);
            releaseAnchor(endB);
        }
        super.remove(reason);
    }

    private void releaseAnchor(@Nullable WireAnchorRef ref) {
        if (ref == null) return;
        WireAnchor a = resolve(ref);
        if (a != null && a.connectedEntityId() != null && a.connectedEntityId() == getId()) {
            a.disconnect();
            BlockEntity be = level().getBlockEntity(ref.owner());
            if (be != null) be.setChanged();
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("EndA")) endA = WireAnchorRef.load(tag.getCompound("EndA"));
        if (tag.contains("EndB")) endB = WireAnchorRef.load(tag.getCompound("EndB"));
        int ord = tag.getInt("Phase");
        Phase[] all = Phase.values();
        phase = all[Math.min(ord, all.length - 1)];
        if (tag.contains("Tier")) {
            int tIdx = tag.getInt("Tier");
            CableTier[] tiers = CableTier.values();
            tier = tiers[Math.min(tIdx, tiers.length - 1)];
        }
        getEntityData().set(DATA_PHASE, phase.ordinal());
        refreshSyncedEnds(level());
        updateBoundingBox();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (endA != null) tag.put("EndA", endA.save());
        if (endB != null) tag.put("EndB", endB.save());
        tag.putInt("Phase", phase.ordinal());
        tag.putInt("Tier", tier.ordinal());
    }
}
