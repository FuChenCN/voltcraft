package com.voltcraft.registry;

import com.voltcraft.VoltCraft;
import com.voltcraft.item.WireToolItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(VoltCraft.MOD_ID);

    public static final DeferredItem<Item> WIRE_TOOL =
            ITEMS.register("wire_tool", () -> new WireToolItem(new Item.Properties()));

    private ModItems() {}

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
