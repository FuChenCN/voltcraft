package com.voltcraft.integration.jade;

import com.voltcraft.VoltCraft;
import com.voltcraft.block.BreakerBlock;
import com.voltcraft.block.ThreePhaseBreakerBlock;
import com.voltcraft.blockentity.BreakerBlockEntity;
import com.voltcraft.blockentity.ThreePhaseBreakerBlockEntity;
import com.voltcraft.electric.protection.BreakerState;
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
 * 空开悬停信息：当前状态、tier、上 tick 流量。
 */
public enum BreakerJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(VoltCraft.MOD_ID, "breaker");

    private static final String KEY_STATE = "State";
    private static final String KEY_TIER = "Tier";
    private static final String KEY_FLOW = "Flow";
    private static final String KEY_RATED = "Rated";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof BreakerBlockEntity be) {
            data.putString(KEY_STATE, accessor.getBlockState().getValue(BreakerBlock.STATE).getSerializedName());
            data.putString(KEY_TIER, be.tier().getSerializedName());
            data.putLong(KEY_FLOW, be.lastFlow());
            data.putInt(KEY_RATED, be.tier().ratedTransfer());
        } else if (accessor.getBlockEntity() instanceof ThreePhaseBreakerBlockEntity tpbe) {
            data.putString(KEY_STATE, accessor.getBlockState().getValue(ThreePhaseBreakerBlock.STATE).getSerializedName());
            data.putString(KEY_TIER, tpbe.tier().getSerializedName());
            data.putLong(KEY_FLOW, tpbe.lastFlow());
            data.putInt(KEY_RATED, tpbe.tier().ratedTransfer());
        }
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data == null || data.isEmpty()) return;

        if (data.contains(KEY_STATE)) {
            String stateName = data.getString(KEY_STATE);
            BreakerState state = parseState(stateName);
            ChatFormatting color = state == BreakerState.CLOSED ? ChatFormatting.GREEN : ChatFormatting.RED;
            tooltip.add(Component.translatable("voltcraft.jade.breaker_state",
                    Component.translatable("voltcraft.breaker_state." + stateName).withStyle(color)));
        }

        if (data.contains(KEY_TIER)) {
            tooltip.add(Component.translatable("voltcraft.jade.cable_tier",
                    Component.translatable("voltcraft.tier." + data.getString(KEY_TIER))));
        }

        if (data.contains(KEY_FLOW)) {
            long flow = data.getLong(KEY_FLOW);
            int rated = data.getInt(KEY_RATED);
            tooltip.add(Component.translatable("voltcraft.jade.flow", flow, rated));
        }
    }

    private static BreakerState parseState(String name) {
        for (BreakerState s : BreakerState.values()) {
            if (s.getSerializedName().equals(name)) return s;
        }
        return BreakerState.CLOSED;
    }
}
