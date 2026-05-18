package com.voltcraft.integration.jade;

import com.voltcraft.VoltCraft;
import com.voltcraft.blockentity.TransformerBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 变压器悬停信息：输出电压、buffer 当前/总量、是否接通同等级电缆。
 */
public enum TransformerJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(VoltCraft.MOD_ID, "transformer");

    private static final String KEY_OUTPUT_TIER = "OutTier";
    private static final String KEY_BUFFER_STORED = "BufferStored";
    private static final String KEY_BUFFER_MAX = "BufferMax";

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof TransformerBlockEntity be)) return;
        data.putString(KEY_OUTPUT_TIER, be.outputTier().voltage().getSerializedName());
        data.putInt(KEY_BUFFER_STORED, be.inputHandler().getEnergyStored());
        data.putInt(KEY_BUFFER_MAX, be.inputHandler().getMaxEnergyStored());
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (data == null || data.isEmpty()) return;

        if (data.contains(KEY_OUTPUT_TIER)) {
            tooltip.add(Component.translatable("voltcraft.jade.transformer_output",
                    Component.translatable("voltcraft.voltage." + data.getString(KEY_OUTPUT_TIER))));
        }

        if (data.contains(KEY_BUFFER_STORED) && data.contains(KEY_BUFFER_MAX)) {
            int stored = data.getInt(KEY_BUFFER_STORED);
            int max = data.getInt(KEY_BUFFER_MAX);
            tooltip.add(Component.translatable("voltcraft.jade.buffer", stored, max));
        }
    }
}
