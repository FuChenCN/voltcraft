package com.voltcraft.registry;

import com.voltcraft.VoltCraft;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(VoltCraft.MOD_ID);

    // Hemimorphite items
    public static final DeferredItem<Item> RAW_HEMIMORPHITE = ITEMS.register(
            "raw_hemimorphite",
            () -> new Item(new Item.Properties())
    );

    public static final DeferredItem<Item> HEMIMORPHITE = ITEMS.register(
            "hemimorphite",
            () -> new Item(new Item.Properties())
    );

    private ModItems() {}

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
