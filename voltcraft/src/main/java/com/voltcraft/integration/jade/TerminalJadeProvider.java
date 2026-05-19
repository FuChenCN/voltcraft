package com.voltcraft.integration.jade;

import com.voltcraft.VoltCraft;
import com.voltcraft.blockentity.TerminalBlockEntity;
import com.voltcraft.electric.wire.TopAnchorLayout;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 接线端子悬停信息：tier、流量、6 个 anchor buffer 存量。
 */
public enum TerminalJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(VoltCraft.MOD_ID, "terminal");

    private static final String KEY_TIER = "Tier";
    private static final String KEY_FLOW = "Flow";
    private static final String KEY_RATED = "Rated";
    private static final String KEY_ANCHORS = "Anchors";

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
        int[] arr = new int[TopAnchorLayout.COUNT];
        for (int i = 0; i < TopAnchorLayout.COUNT; i++) {
            arr[i] = be.anchorStored(i);
        }
        data.putIntArray(KEY_ANCHORS, arr);
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
        if (data.contains(KEY_ANCHORS)) {
            int[] a = data.getIntArray(KEY_ANCHORS);
            if (a.length == TopAnchorLayout.COUNT) {
                tooltip.add(Component.translatable("voltcraft.jade.anchors_in",
                        a[TopAnchorLayout.L_IN], a[TopAnchorLayout.N_IN], a[TopAnchorLayout.E_IN]));
                tooltip.add(Component.translatable("voltcraft.jade.anchors_out",
                        a[TopAnchorLayout.L_OUT], a[TopAnchorLayout.N_OUT], a[TopAnchorLayout.E_OUT]));
            }
        }
    }
}
