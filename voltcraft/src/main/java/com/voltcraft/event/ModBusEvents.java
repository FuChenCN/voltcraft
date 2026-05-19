package com.voltcraft.event;

import com.voltcraft.VoltCraft;
import com.voltcraft.blockentity.BreakerBlockEntity;
import com.voltcraft.blockentity.CableBlockEntity;
import com.voltcraft.blockentity.TerminalBlockEntity;
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

        // 变压器：FACING 反向（低压输入面）和底面都暴露 inputHandler。
        // 玩家把发电机贴在底面或背面都能塞电，避免朝向迷糊。
        // 顶面是 6 柱 anchor 不暴露 cap；FACING（铭牌面）和左右两侧是机壳。
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.TRANSFORMER.get(),
                (TransformerBlockEntity be, Direction side) -> {
                    if (side == null) return be.inputHandler();
                    if (side == be.inputFace()) return be.inputHandler();
                    if (side == Direction.DOWN)  return be.inputHandler();
                    return null;
                }
        );

        // 空开三相：6 个 anchor 通过软线接入，不暴露任何 capability
        // （旧的 cable 邻接 cap 已废弃；软线 entity 直接走 anchorBuffer）
        // 故意不注册 BREAKER 的 EnergyStorage cap。

        // 接线端子：仅在机器面（FACING）暴露合流后的单口 IEnergyStorage。
        // 三相 L/N/E 通过 anchor + 软线接入，不走 capability。
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.TERMINAL.get(),
                (TerminalBlockEntity be, Direction side) -> {
                    if (side == null) return be.machineHandler();
                    return side == be.machineFace() ? be.machineHandler() : null;
                }
        );
    }
}
