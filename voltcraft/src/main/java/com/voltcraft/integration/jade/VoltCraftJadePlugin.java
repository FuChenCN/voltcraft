package com.voltcraft.integration.jade;

import com.voltcraft.VoltCraft;
import com.voltcraft.block.BreakerBlock;
import com.voltcraft.block.CableBlock;
import com.voltcraft.block.TerminalBlock;
import com.voltcraft.block.ThreePhaseBreakerBlock;
import com.voltcraft.block.TransformerBlock;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * VoltCraft 与 Jade 的集成入口。
 * 使用 @WailaPlugin(VoltCraft.MOD_ID)：仅在 Jade 加载且本 mod 加载时生效；
 * Jade 不在时整个类不会被加载，所以不会因缺少 Jade 类而崩溃。
 */
@WailaPlugin(VoltCraft.MOD_ID)
public final class VoltCraftJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CableJadeProvider.INSTANCE, com.voltcraft.blockentity.CableBlockEntity.class);
        registration.registerBlockDataProvider(TransformerJadeProvider.INSTANCE, com.voltcraft.blockentity.TransformerBlockEntity.class);
        registration.registerBlockDataProvider(BreakerJadeProvider.INSTANCE, com.voltcraft.blockentity.BreakerBlockEntity.class);
        registration.registerBlockDataProvider(BreakerJadeProvider.INSTANCE, com.voltcraft.blockentity.ThreePhaseBreakerBlockEntity.class);
        registration.registerBlockDataProvider(TerminalJadeProvider.INSTANCE, com.voltcraft.blockentity.TerminalBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CableJadeProvider.INSTANCE, CableBlock.class);
        registration.registerBlockComponent(TransformerJadeProvider.INSTANCE, TransformerBlock.class);
        registration.registerBlockComponent(BreakerJadeProvider.INSTANCE, BreakerBlock.class);
        registration.registerBlockComponent(BreakerJadeProvider.INSTANCE, ThreePhaseBreakerBlock.class);
        registration.registerBlockComponent(TerminalJadeProvider.INSTANCE, TerminalBlock.class);
    }
}
