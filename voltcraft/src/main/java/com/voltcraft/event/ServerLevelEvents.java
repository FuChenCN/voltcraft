package com.voltcraft.event;

import com.voltcraft.VoltCraft;
import com.voltcraft.electric.network.NetworkManager;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 服务端 NeoForge 事件总线监听器。
 * 负责清理 NetworkManager 这类纯运行时数据。
 */
@EventBusSubscriber(modid = VoltCraft.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ServerLevelEvents {

    private ServerLevelEvents() {}

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level lvl && !lvl.isClientSide) {
            NetworkManager.onLevelUnload(lvl);
        }
    }
}
