package com.voltcraft.blockentity;

import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.VoltageTier;
import com.voltcraft.electric.network.EnergyNetwork;
import com.voltcraft.electric.network.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 电缆方块实体。
 *
 * 当前阶段持有：
 * - cableTier：从方块继承的等级（不存盘，因为 Block 决定了它）
 * - voltageTag：当前线路电压标签，由变压器写入；null 表示线路尚未通电（未被任何变压器加入过）
 *
 * 后续阶段会扩展：
 * - 引用所属 EnergyNetwork
 * - 连接方向位图（用于渲染和扫描）
 */
public class CableBlockEntity extends BlockEntity {

    private static final String NBT_VOLTAGE_TAG = "VoltageTag";

    private final CableTier cableTier;

    @Nullable
    private VoltageTier voltageTag;

    public CableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, CableTier cableTier) {
        super(type, pos, state);
        this.cableTier = cableTier;
    }

    public CableTier cableTier() {
        return cableTier;
    }

    /**
     * 当前所属网络。null 表示未在世界中（区块未加载或刚卸载）。
     */
    @Nullable
    public EnergyNetwork network() {
        Level level = getLevel();
        if (level == null || level.isClientSide) return null;
        return NetworkManager.get(level).networkAt(getBlockPos());
    }

    @Nullable
    public VoltageTier voltageTag() {
        return voltageTag;
    }

    public void setVoltageTag(@Nullable VoltageTier voltageTag) {
        if (voltageTag != null && voltageTag != cableTier.voltage()) {
            throw new IllegalArgumentException(
                    "Voltage tag " + voltageTag + " incompatible with cable tier " + cableTier);
        }
        this.voltageTag = voltageTag;
        setChanged();
    }

    /**
     * 区块加载或方块新放置时触发。把自己注册进 NetworkManager。
     * 持久化的电压标签会在 loadAdditional 中先恢复，加入网络后由
     * NetworkManager 在合并时同步整网电压。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        Level level = getLevel();
        if (level != null && !level.isClientSide) {
            EnergyNetwork net = NetworkManager.get(level).onCableAdded(level, getBlockPos(), cableTier);
            // 如果本节点持有电压标签而网络尚未设置，则把电压传给网络
            if (voltageTag != null && net.voltageTag() == null) {
                try {
                    net.setVoltageTag(voltageTag);
                } catch (IllegalArgumentException ignored) {
                    // 强绑定保证不会发生，兜底忽略
                }
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(NBT_VOLTAGE_TAG)) {
            String name = tag.getString(NBT_VOLTAGE_TAG);
            this.voltageTag = parseVoltage(name);
        } else {
            this.voltageTag = null;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (voltageTag != null) {
            tag.putString(NBT_VOLTAGE_TAG, voltageTag.getSerializedName());
        }
    }

    @Nullable
    private static VoltageTier parseVoltage(String name) {
        for (VoltageTier v : VoltageTier.values()) {
            if (v.getSerializedName().equals(name)) {
                return v;
            }
        }
        return null;
    }
}
