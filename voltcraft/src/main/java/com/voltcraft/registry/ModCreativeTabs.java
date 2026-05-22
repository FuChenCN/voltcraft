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

    /** 设备标签页：电缆、变压器、空开、端子 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EQUIPMENT =
            TABS.register("equipment", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.voltcraft.equipment"))
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
                            output.accept(ModBlocks.TERMINALS.get(tier).get());
                        }
                    })
                    .build());

    /** 矿物标签页：矿石、原材料 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MINERALS =
            TABS.register("minerals", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.voltcraft.minerals"))
                    .icon(() -> ModItems.HEMIMORPHITE.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.HEMIMORPHITE_ORE.get());
                        output.accept(ModBlocks.DEEPSLATE_HEMIMORPHITE_ORE.get());
                        output.accept(ModItems.RAW_HEMIMORPHITE.get());
                        output.accept(ModItems.HEMIMORPHITE.get());
                        output.accept(ModBlocks.RHODONITE_ORE.get());
                        output.accept(ModBlocks.DEEPSLATE_RHODONITE_ORE.get());
                        output.accept(ModItems.RAW_MANGANESE.get());
                        output.accept(ModItems.MANGANESE_INGOT.get());
                        output.accept(ModBlocks.GARNIERITE_ORE.get());
                        output.accept(ModBlocks.DEEPSLATE_GARNIERITE_ORE.get());
                        output.accept(ModItems.RAW_NICKEL.get());
                        output.accept(ModItems.NICKEL_INGOT.get());
                        output.accept(ModBlocks.CERUSSITE_ORE.get());
                        output.accept(ModBlocks.DEEPSLATE_CERUSSITE_ORE.get());
                        output.accept(ModItems.RAW_LEAD.get());
                        output.accept(ModItems.LEAD_INGOT.get());
                    })
                    .build());

    /** 零部件标签页：弹簧、熔断器等 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PARTS =
            TABS.register("parts", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.voltcraft.parts"))
                    .icon(() -> ModItems.SPRING.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.SPRING.get());
                        output.accept(ModItems.FUSE.get());
                    })
                    .build());

    private ModCreativeTabs() {}

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
