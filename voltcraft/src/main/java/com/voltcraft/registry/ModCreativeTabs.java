package com.voltcraft.registry;

import com.voltcraft.VoltCraft;
import com.voltcraft.electric.CableTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, VoltCraft.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.voltcraft"))
                    .icon(() -> ModBlocks.CABLES.get(CableTier.LOW).get().asItem().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        for (CableTier tier : CableTier.values()) {
                            output.accept(ModBlocks.CABLES.get(tier).get());
                        }
                        for (CableTier tier : CableTier.values()) {
                            output.accept(ModBlocks.TRANSFORMERS.get(tier).get());
                        }
                        for (CableTier tier : CableTier.values()) {
                            output.accept(ModBlocks.BREAKERS.get(tier).get());
                        }
                        for (CableTier tier : CableTier.values()) {
                            output.accept(ModBlocks.THREE_PHASE_BREAKERS.get(tier).get());
                        }
                        for (CableTier tier : CableTier.values()) {
                            output.accept(ModBlocks.TERMINALS.get(tier).get());
                        }
                        // Hemimorphite items
                        output.accept(ModBlocks.HEMIMORPHITE_ORE.get());
                        output.accept(ModBlocks.DEEPSLATE_HEMIMORPHITE_ORE.get());
                        output.accept(ModItems.RAW_HEMIMORPHITE.get());
                        output.accept(ModItems.HEMIMORPHITE.get());
                    })
                    .build());

    private ModCreativeTabs() {}

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
