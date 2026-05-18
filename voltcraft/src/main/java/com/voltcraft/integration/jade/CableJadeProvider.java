package com.voltcraft.integration.jade;

import com.voltcraft.VoltCraft;
import com.voltcraft.blockentity.CableBlockEntity;
import com.voltcraft.electric.network.EnergyNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 电缆悬停信息：等级、电压、当前电流、网络规模。
 */
public enum CableJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(VoltCraft.MOD_ID, "cable");

    private static final String KEY_TIER = "Tier";
    private static final String KEY_VOLTAGE = "Voltage";
    private static final String KEY_FLOW = "Flow";
    private static final String KEY_AMPS = "Amps";
    private static final String KEY_RATED = "Rated";
    private static final String KEY_NETWORK_SIZE = "NetSize";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof CableBlockEntity be)) return;
        data.putString(KEY_TIER, be.cableTier().getSerializedName());
        data.putInt(KEY_RATED, be.cableTier().ratedTransfer());
        EnergyNetwork net = be.network();
        if (net != null) {
            if (net.voltageTag() != null) {
                data.putString(KEY_VOLTAGE, net.voltageTag().getSerializedName());
            }
            data.putLong(KEY_FLOW, net.lastFlow());
            data.putDouble(KEY_AMPS, net.currentAmps());
            data.putInt(KEY_NETWORK_SIZE, net.size());
        }
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data == null || data.isEmpty()) return;

        // 电缆等级
        if (data.contains(KEY_TIER)) {
            tooltip.add(Component.translatable("voltcraft.jade.cable_tier",
                    Component.translatable("voltcraft.tier." + data.getString(KEY_TIER))));
        }

        // 电压（未通电时显示 unpowered）
        if (data.contains(KEY_VOLTAGE)) {
            tooltip.add(Component.translatable("voltcraft.jade.voltage",
                    Component.translatable("voltcraft.voltage." + data.getString(KEY_VOLTAGE))));
        } else {
            tooltip.add(Component.translatable("voltcraft.jade.voltage_unpowered"));
        }

        // 电流（A）+ 当前 / 额定 FE/t
        if (data.contains(KEY_FLOW)) {
            long flow = data.getLong(KEY_FLOW);
            int rated = data.getInt(KEY_RATED);
            tooltip.add(Component.translatable("voltcraft.jade.flow",
                    flow, rated));
            if (data.contains(KEY_AMPS)) {
                double amps = data.getDouble(KEY_AMPS);
                tooltip.add(Component.translatable("voltcraft.jade.amps",
                        String.format("%.3f", amps)));
            }
        }

        // 网络规模（电缆数）
        if (data.contains(KEY_NETWORK_SIZE)) {
            tooltip.add(Component.translatable("voltcraft.jade.network_size",
                    data.getInt(KEY_NETWORK_SIZE)));
        }
    }
}
