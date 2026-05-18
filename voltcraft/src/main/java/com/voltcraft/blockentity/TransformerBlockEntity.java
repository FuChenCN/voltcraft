package com.voltcraft.blockentity;

import com.voltcraft.block.CableBlock;
import com.voltcraft.block.TransformerBlock;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.network.EnergyNetwork;
import com.voltcraft.electric.network.NetworkManager;
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
import org.jetbrains.annotations.Nullable;

/**
 * 变压器方块实体。
 *
 * 数据流：
 *   外部 mod → inputBuffer (IEnergyStorage) → serverTick 推入高压侧网络（损耗 1%）
 *
 * 输出策略：
 * - 仅在 FACING 方向必须是对应 CableTier 的电缆才推送
 * - 推送同时写入网络电压标签
 *
 * 损耗模型（设计文档 2.2.3）：功率守恒，FE/t 为功率单位，所以 FE 数值不变，仅扣损耗。
 */
public class TransformerBlockEntity extends BlockEntity {

    private static final String NBT_INPUT_BUFFER = "InputBuffer";

    /** 设计文档 2.2.3：普通变压器 1% 损耗。后续会改为 ConfigSpec 可调。 */
    private static final double LOSS_RATE = 0.01;

    private final CableTier outputTier;

    /**
     * 内部能量缓冲。容量 = 8 倍输出端电缆的额定/tick，
     * 给玩家一个抗瞬时波动的余量。
     */
    private final EnergyStorage inputBuffer;

    public TransformerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CableTier outputTier) {
        super(type, pos, state);
        this.outputTier = outputTier;
        int rate = outputTier.ratedTransfer();
        this.inputBuffer = new EnergyStorage(rate * 8, rate, 0); // 只接收，不向外抽
    }

    public CableTier outputTier() {
        return outputTier;
    }

    /**
     * 暴露给 IEnergyStorage Capability 的输入端。
     * 仅在 FACING 反方向（低压侧）暴露，由 ModBusEvents 负责按面分发。
     */
    public IEnergyStorage inputHandler() {
        return inputBuffer;
    }

    /**
     * 服务端每 tick 调用：
     * 1. 检查 FACING 方向是否接同等级电缆
     * 2. 写入电压标签
     * 3. 从 inputBuffer 抽取并推入网络（按 LOSS_RATE 扣损耗）
     */
    public void serverTick() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;

        Direction outputDir = getBlockState().getValue(TransformerBlock.FACING);
        BlockPos outputPos = getBlockPos().relative(outputDir);

        BlockState outputState = level.getBlockState(outputPos);
        if (!(outputState.getBlock() instanceof CableBlock cb) || cb.tier() != outputTier) {
            return; // 高压侧没接对，本 tick 不工作
        }

        EnergyNetwork net = NetworkManager.get(level).networkAt(outputPos);
        if (net == null) return;

        // 写入电压标签（幂等）
        if (net.voltageTag() == null) {
            net.setVoltageTag(outputTier.voltage());
        } else if (net.voltageTag() != outputTier.voltage()) {
            // 电压冲突——后续阶段会触发跳闸；当前阶段静默拒绝输出
            return;
        }

        // 推送：按损耗扣减，同时受 buffer 余额和电缆 ratedTransfer 上限约束
        int available = inputBuffer.getEnergyStored();
        if (available <= 0) return;

        long afterLoss = (long) (available * (1.0 - LOSS_RATE));
        if (afterLoss <= 0) return;

        long pushed = net.pushEnergy(afterLoss, false);
        if (pushed <= 0) return;

        // 从 buffer 扣除：消费的是损耗前的量
        int consumed = (int) Math.min(Integer.MAX_VALUE, Math.ceil(pushed / (1.0 - LOSS_RATE)));
        consumed = Math.min(consumed, available);
        inputBuffer.extractEnergy(consumed, false);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(NBT_INPUT_BUFFER)) {
            inputBuffer.deserializeNBT(registries, tag.get(NBT_INPUT_BUFFER));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put(NBT_INPUT_BUFFER, inputBuffer.serializeNBT(registries));
    }

    /** 从 BlockState 拿到这个变压器的输出端面。 */
    public Direction outputFace() {
        return getBlockState().getValue(TransformerBlock.FACING);
    }

    /** 输入端面（FACING 反方向）。 */
    public Direction inputFace() {
        return outputFace().getOpposite();
    }
}
