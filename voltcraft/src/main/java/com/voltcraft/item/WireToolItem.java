package com.voltcraft.item;

import com.voltcraft.electric.Phase;
import com.voltcraft.electric.wire.WireAnchor;
import com.voltcraft.electric.wire.WireAnchorOwner;
import com.voltcraft.electric.wire.WireAnchorRef;
import com.voltcraft.entity.SoftCableEntity;
import com.voltcraft.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 接线钳：把两个 WireAnchor 连成一根 SoftCableEntity。
 *
 * 流程：
 *   1. 右键一个 WireAnchorOwner 方块 → 选距离玩家瞄准点最近的空闲 anchor，记入手中物品 NBT
 *   2. 再次右键另一个 owner（同 phase 且不同 owner）→ 创建 SoftCableEntity，清空 NBT
 *   3. 在空气中 sneak-右键 → 重置选择
 *
 * 不消耗物品，可视作创造模式的电工工具。后期再考虑掉耐久或耗费"线轴"。
 */
public class WireToolItem extends Item {

    private static final String NBT_OWNER_X = "X";
    private static final String NBT_OWNER_Y = "Y";
    private static final String NBT_OWNER_Z = "Z";
    private static final String NBT_ANCHOR_IDX = "Idx";
    private static final String NBT_PHASE = "Phase";

    public WireToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockPos pos = ctx.getClickedPos();
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof WireAnchorOwner owner)) {
            return InteractionResult.PASS;
        }

        // 选离玩家瞄准点最近的空闲 anchor
        Vec3 hit = ctx.getClickLocation();
        WireAnchor target = pickClosestFreeAnchor(owner, pos, hit);
        if (target == null) {
            player.displayClientMessage(
                    Component.translatable("voltcraft.wire_tool.no_free_anchor")
                            .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        ItemStack stack = ctx.getItemInHand();
        SelectedAnchor first = readSelected(stack);

        if (first == null) {
            // 第一次：记录
            writeSelected(stack, new SelectedAnchor(pos.immutable(), target.index(), target.phase()));
            player.displayClientMessage(
                    Component.translatable("voltcraft.wire_tool.first_picked",
                            target.phase().shortLabel(),
                            pos.toShortString())
                            .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }

        // 第二次：尝试连接
        if (first.owner.equals(pos)) {
            player.displayClientMessage(
                    Component.translatable("voltcraft.wire_tool.same_block")
                            .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        if (first.phase != target.phase()) {
            player.displayClientMessage(
                    Component.translatable("voltcraft.wire_tool.phase_mismatch",
                            first.phase.shortLabel(), target.phase().shortLabel())
                            .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        // 占用 anchor + 创建 entity
        BlockEntity beA = level.getBlockEntity(first.owner);
        if (!(beA instanceof WireAnchorOwner ownerA)) {
            clearSelected(stack);
            return InteractionResult.CONSUME;
        }
        WireAnchor anchorA = ownerA.anchor(first.anchorIndex);
        if (anchorA == null || !anchorA.isFree()) {
            clearSelected(stack);
            player.displayClientMessage(
                    Component.translatable("voltcraft.wire_tool.first_taken")
                            .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        SoftCableEntity wire = SoftCableEntity.place(level, ModEntities.SOFT_CABLE.get(),
                new WireAnchorRef(first.owner, first.anchorIndex),
                new WireAnchorRef(pos, target.index()),
                first.phase);
        if (!level.addFreshEntity(wire)) {
            clearSelected(stack);
            return InteractionResult.CONSUME;
        }
        anchorA.connect(wire.getId());
        target.connect(wire.getId());
        beA.setChanged();
        be.setChanged();
        clearSelected(stack);

        player.displayClientMessage(
                Component.translatable("voltcraft.wire_tool.connected",
                        first.phase.shortLabel())
                        .withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.CONSUME;
    }

    @Nullable
    private static WireAnchor pickClosestFreeAnchor(WireAnchorOwner owner, BlockPos pos, Vec3 aim) {
        WireAnchor best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < owner.anchorCount(); i++) {
            WireAnchor a = owner.anchor(i);
            if (a == null || !a.isFree()) continue;
            Vec3 worldPos = owner.anchorWorldPos(a, pos);
            double d = worldPos.distanceToSqr(aim);
            if (d < bestDist) {
                bestDist = d;
                best = a;
            }
        }
        return best;
    }

    @Nullable
    private static SelectedAnchor readSelected(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        if (!tag.contains(NBT_OWNER_X)) return null;
        Phase[] all = Phase.values();
        int phaseIdx = tag.getInt(NBT_PHASE);
        return new SelectedAnchor(
                new BlockPos(tag.getInt(NBT_OWNER_X), tag.getInt(NBT_OWNER_Y), tag.getInt(NBT_OWNER_Z)),
                tag.getInt(NBT_ANCHOR_IDX),
                all[Math.min(phaseIdx, all.length - 1)]
        );
    }

    private static void writeSelected(ItemStack stack, SelectedAnchor sel) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(NBT_OWNER_X, sel.owner.getX());
        tag.putInt(NBT_OWNER_Y, sel.owner.getY());
        tag.putInt(NBT_OWNER_Z, sel.owner.getZ());
        tag.putInt(NBT_ANCHOR_IDX, sel.anchorIndex);
        tag.putInt(NBT_PHASE, sel.phase.ordinal());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static void clearSelected(ItemStack stack) {
        stack.remove(DataComponents.CUSTOM_DATA);
    }

    private record SelectedAnchor(BlockPos owner, int anchorIndex, Phase phase) {}
}
