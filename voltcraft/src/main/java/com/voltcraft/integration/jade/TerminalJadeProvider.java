package com.voltcraft.integration.jade;

import com.voltcraft.VoltCraft;
import com.voltcraft.blockentity.TerminalBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 接线端子悬停信息：tier、当前流量。
 * 三相重构后 wiring 状态机已废弃，接错线由空开 RCD 直接跳闸表现。
 */
public enum TerminalJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(VoltCraft.MOD_ID, "terminal");

    private static final String KEY_TIER = "Tier";
    private static final String KEY_FLOW = "Flow";
    private static final String KEY_RATED = "Rated";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof TerminalBlockEntity be)) return;
        data.putString(KEY_TIER, be.tier().getSerializedName());
        data.putLong(KEY_FLOW, be.lastFlow());
        data.putInt(KEY_RATED, be.tier().ratedTransfer());
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data == null || data.isEmpty()) return;

        if (data.contains(KEY_TIER)) {
            tooltip.add(Component.translatable("voltcraft.jade.cable_tier",
                    Component.translatable("voltcraft.tier." + data.getString(KEY_TIER))));
        }
        if (data.contains(KEY_FLOW)) {
            tooltip.add(Component.translatable("voltcraft.jade.flow",
                    data.getLong(KEY_FLOW), data.getInt(KEY_RATED)));
        }
    }
}
