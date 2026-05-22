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

    // Manganese items (from rhodonite ore)
    public static final DeferredItem<Item> RAW_MANGANESE = ITEMS.register(
            "raw_manganese",
            () -> new Item(new Item.Properties())
    );

    public static final DeferredItem<Item> MANGANESE_INGOT = ITEMS.register(
            "manganese_ingot",
            () -> new Item(new Item.Properties())
    );

    // Parts items
    public static final DeferredItem<Item> SPRING = ITEMS.register(
            "spring",
            () -> new Item(new Item.Properties())
    );

    public static final DeferredItem<Item> FUSE = ITEMS.register(
            "fuse",
            () -> new Item(new Item.Properties())
    );

    private ModItems() {}

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
