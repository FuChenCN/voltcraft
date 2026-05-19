package com.voltcraft.blockentity;

import com.voltcraft.block.TransformerBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.wire.TopAnchorLayout;
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
 * 变压器（三相重构 v2）：顶面 6 柱（3 in + 3 out），双向变压。
 *
 * 拓扑：
 *   * 顶面：6 个软线接线柱（L_IN/N_IN/E_IN + L_OUT/N_OUT/E_OUT），按 {@link TopAnchorLayout}
 *   * 背面（FACING.opposite）：保留单口 IEnergyStorage，兼容外部 mod 直接塞 FE
 *     接收的电量进入 in 侧 buffer（视作"低压侧虚拟单相输入"）
 *   * FACING（铭牌面）、底面、左右两个非顶面：装饰
 *
 * 数据流（双向）：
 *   每 tick 比较 (in_L+in_N) vs (out_L+out_N)：
 *     * 哪边能量多就推到对边，差额按 LOSS_RATE=1% 扣损耗
 *     * E 不参与正常电流；只承载漏电（外部 RCD 注入）
 *
 * 单相兼容输入：背面 cap 收到的 FE 全部塞进 inputBuffer，serverTick 把它平分到 in_L 和 in_N，
 * 然后再由双向逻辑流向 out 侧。
 */
public class TransformerBlockEntity extends BlockEntity implements WireAnchorOwner {

    private static final String NBT_INPUT_BUFFER = "InputBuffer";
    private static final String NBT_BUFFER_PREFIX = "Buf";

    private static final double LOSS_RATE = 0.01;

    private final CableTier outputTier;

    /**
     * 背面单口 cap 收到的 FE 暂存。每 tick 拆到 in 侧 L/N。
     * 内部允许 extract（serverTick 抽空），外部 cap wrapper {@link #inputHandlerCap} 拒绝 extract，
     * 防止外部 mod 把变压器误判成生产者。
     */
    private final EnergyStorage inputBuffer;

    /** 6 个 anchor 各自的 buffer：与 TopAnchorLayout 索引对应。 */
    private final EnergyStorage[] buffers = new EnergyStorage[TopAnchorLayout.COUNT];

    private final WireAnchor[] anchors;

    public TransformerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CableTier outputTier) {
        super(type, pos, state);
        this.outputTier = outputTier;
        int rate = outputTier.ratedTransfer();
        // 内部 inputBuffer：容量 = 8 tick；maxReceive = 4 tick；maxExtract = 4 tick
        // （以前 maxExtract=0 导致 buffer 永不释放，整链路死锁）
        this.inputBuffer = new EnergyStorage(rate * 8, rate * 4, rate * 4);
        for (int i = 0; i < TopAnchorLayout.COUNT; i++) {
            buffers[i] = new EnergyStorage(rate, rate, rate);
        }
        this.anchors = TopAnchorLayout.createAnchors(outputTier);
    }

    public CableTier outputTier() { return outputTier; }
    public CableTier tier() { return outputTier; }

    /** 仅用于 Jade 调试：返回某个 anchor 的当前存量。index 越界返 0。 */
    public int anchorStored(int index) {
        if (index < 0 || index >= TopAnchorLayout.COUNT) return 0;
        return buffers[index].getEnergyStored();
    }

    public int anchorCapacity(int index) {
        if (index < 0 || index >= TopAnchorLayout.COUNT) return 0;
        return buffers[index].getMaxEnergyStored();
    }

    /**
     * 背面 IEnergyStorage：只允许外部 mod receive，不让 extract。
     * 这是 wrapper，内部 inputBuffer 仍然能被 serverTick 抽空。
     */
    public IEnergyStorage inputHandler() { return inputHandlerCap; }

    private final IEnergyStorage inputHandlerCap = new IEnergyStorage() {
        @Override public int receiveEnergy(int maxReceive, boolean simulate) {
            return inputBuffer.receiveEnergy(maxReceive, simulate);
        }
        @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return inputBuffer.getEnergyStored(); }
        @Override public int getMaxEnergyStored() { return inputBuffer.getMaxEnergyStored(); }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    };

    @Override
    @Nullable
    public WireAnchor anchor(int index) {
        return (index < 0 || index >= TopAnchorLayout.COUNT) ? null : anchors[index];
    }

    @Override public int anchorCount() { return TopAnchorLayout.COUNT; }

    @Override
    public IEnergyStorage anchorBuffer(int index) {
        if (index < 0 || index >= TopAnchorLayout.COUNT) return null;
        return buffers[index];
    }

    @Override
    public Vec3 anchorWorldPos(WireAnchor anchor, BlockPos blockPos) {
        Direction facing = getBlockState().getValue(TransformerBlock.FACING);
        return TopAnchorLayout.worldPos(facing, anchor.localOffset(), blockPos);
    }

    public void serverTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;

        // 1) 把 inputBuffer 的电平分注入 in_L / in_N（兼容单相外部 mod 输入）
        int avail = inputBuffer.getEnergyStored();
        if (avail > 0) {
            int half = avail / 2;
            int l = buffers[TopAnchorLayout.L_IN].receiveEnergy(half, false);
            int n = buffers[TopAnchorLayout.N_IN].receiveEnergy(half, false);
            inputBuffer.extractEnergy(l + n, false);
        }

        // 2) 双向变压：比较 in vs out 总量，从多的一边流向少的一边，扣损耗
        long inSum  = (long) buffers[TopAnchorLayout.L_IN].getEnergyStored()
                    + buffers[TopAnchorLayout.N_IN].getEnergyStored();
        long outSum = (long) buffers[TopAnchorLayout.L_OUT].getEnergyStored()
                    + buffers[TopAnchorLayout.N_OUT].getEnergyStored();
        if (inSum > outSum) {
            transformPair(buffers[TopAnchorLayout.L_IN],  buffers[TopAnchorLayout.L_OUT]);
            transformPair(buffers[TopAnchorLayout.N_IN],  buffers[TopAnchorLayout.N_OUT]);
        } else if (outSum > inSum) {
            transformPair(buffers[TopAnchorLayout.L_OUT], buffers[TopAnchorLayout.L_IN]);
            transformPair(buffers[TopAnchorLayout.N_OUT], buffers[TopAnchorLayout.N_IN]);
        }
        // E 不主动转移（仅承载漏电）
    }

    /** src→dst 转移并扣 LOSS_RATE 损耗。 */
    private static void transformPair(EnergyStorage src, EnergyStorage dst) {
        int avail = src.getEnergyStored();
        if (avail <= 0) return;
        int afterLoss = (int) (avail * (1.0 - LOSS_RATE));
        if (afterLoss <= 0) return;
        int accepted = dst.receiveEnergy(afterLoss, true);
        if (accepted <= 0) return;
        // 真实从 src 抽出 accepted/(1-loss) 的电（向上取整）
        int consumed = (int) Math.min(avail, Math.ceil(accepted / (1.0 - LOSS_RATE)));
        src.extractEnergy(consumed, false);
        dst.receiveEnergy(accepted, false);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(NBT_INPUT_BUFFER)) inputBuffer.deserializeNBT(registries, tag.get(NBT_INPUT_BUFFER));
        for (int i = 0; i < TopAnchorLayout.COUNT; i++) {
            String k = NBT_BUFFER_PREFIX + i;
            if (tag.contains(k)) buffers[i].deserializeNBT(registries, tag.get(k));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(NBT_INPUT_BUFFER, inputBuffer.serializeNBT(registries));
        for (int i = 0; i < TopAnchorLayout.COUNT; i++) {
            tag.put(NBT_BUFFER_PREFIX + i, buffers[i].serializeNBT(registries));
        }
    }

    /** 输入面（外部 mod 塞 FE 的方向）= FACING 反向。 */
    public Direction inputFace() {
        return getBlockState().getValue(TransformerBlock.FACING).getOpposite();
    }

    public Direction decorFace() {
        return getBlockState().getValue(TransformerBlock.FACING);
    }
}
