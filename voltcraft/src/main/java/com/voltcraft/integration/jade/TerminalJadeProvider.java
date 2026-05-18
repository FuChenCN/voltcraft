package com.voltcraft.integration.jade;

import com.voltcraft.VoltCraft;
import com.voltcraft.block.TerminalBlock;
import com.voltcraft.blockentity.TerminalBlockEntity;
import com.voltcraft.electric.protection.WiringState;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 接线端子悬停信息：tier、wiring 状态（带颜色）、当前流量。
 */
public enum TerminalJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(VoltCraft.MOD_ID, "terminal");

    private static final String KEY_TIER = "Tier";
    private static final String KEY_WIRING = "Wiring";
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
        data.putString(KEY_WIRING, accessor.getBlockState().getValue(TerminalBlock.WIRING).getSerializedName());
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

        if (data.contains(KEY_WIRING)) {
            String wiringName = data.getString(KEY_WIRING);
            WiringState w = parseWiring(wiringName);
            ChatFormatting color = colorOf(w);
            tooltip.add(Component.translatable("voltcraft.jade.wiring",
                    Component.translatable("voltcraft.wiring." + wiringName).withStyle(color)));
        }

        if (data.contains(KEY_FLOW)) {
            tooltip.add(Component.translatable("voltcraft.jade.flow",
                    data.getLong(KEY_FLOW), data.getInt(KEY_RATED)));
        }
    }

    private static WiringState parseWiring(String name) {
        for (WiringState w : WiringState.values()) {
            if (w.getSerializedName().equals(name)) return w;
        }
        return WiringState.CORRECT;
    }

    private static ChatFormatting colorOf(WiringState w) {
        return switch (w) {
            case CORRECT -> ChatFormatting.GREEN;
            case MISSING_GROUND, HOT_NEUTRAL_SWAPPED -> ChatFormatting.YELLOW;
            case SHORT_HOT_NEUTRAL, SHORT_HOT_GROUND -> ChatFormatting.RED;
        };
    }
}
