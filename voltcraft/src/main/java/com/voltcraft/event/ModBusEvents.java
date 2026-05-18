package com.voltcraft.event;

import com.voltcraft.VoltCraft;
import com.voltcraft.blockentity.CableBlockEntity;
import com.voltcraft.blockentity.TransformerBlockEntity;
import com.voltcraft.electric.capability.CableEnergyHandler;
import com.voltcraft.registry.ModBlockEntities;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Mod 事件总线监听器。注册 Capability 等启动期回调。
 */
@EventBusSubscriber(modid = VoltCraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModBusEvents {

    private ModBusEvents() {}

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        // 电缆向所有六面暴露 EnergyHandler，由网络统一处理
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.CABLE.get(),
                (CableBlockEntity be, Direction side) -> new CableEnergyHandler(be)
        );

        // 变压器仅在输入面（FACING 反方向）暴露低压侧 IEnergyStorage
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.TRANSFORMER.get(),
                (TransformerBlockEntity be, Direction side) -> {
                    if (side == null) return be.inputHandler(); // 物品/无向访问
                    return side == be.inputFace() ? be.inputHandler() : null;
                }
        );
    }
}
