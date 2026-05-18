package com.voltcraft.block;

import com.voltcraft.blockentity.TerminalBlockEntity;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.protection.WiringState;
import com.voltcraft.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 接线端子：把外部机器的 FE 桥接到电缆网络。
 *
 * - FACING 方向：电缆面（必须接同等级电缆）
 * - FACING 反方向：机器面（接外部 FE 设备）
 * - WIRING：火/零/地接线状态，决定是否短路、漏保是否生效
 *
 * 玩家右键端子 → 循环切换 WiringState（用于演示与调试；
 * 后期会替换为电工钳 GUI）。
 */
public class TerminalBlock extends Block implements EntityBlock {

    public static final EnumProperty<WiringState> WIRING = EnumProperty.create("wiring", WiringState.class);
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            HorizontalDirectionalBlock.FACING;

    private final CableTier tier;

    public TerminalBlock(CableTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(WIRING, WiringState.CORRECT));
    }

    public CableTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WIRING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(WIRING, WiringState.CORRECT);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TerminalBlockEntity(ModBlockEntities.TERMINAL.get(), pos, state, tier);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == ModBlockEntities.TERMINAL.get()
                ? (lvl, pos, st, be) -> ((TerminalBlockEntity) be).serverTick()
                : null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        WiringState cur = state.getValue(WIRING);
        WiringState next = cur.next();
        level.setBlock(pos, state.setValue(WIRING, next), 3);
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.5f, 1.0f);
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.translatable("voltcraft.terminal.wiring_set",
                    Component.translatable("voltcraft.wiring." + next.getSerializedName())), true);
        }
        return InteractionResult.CONSUME;
    }
}
