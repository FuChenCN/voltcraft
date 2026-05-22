package com.voltcraft.block;

import com.voltcraft.blockentity.ThreePhaseBreakerBlockEntity;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.protection.BreakerState;
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
 * 三相空气开关：支持火线、零线、地线三线系统。
 *
 * 布局（视角正对方块）：
 * - 正面：开关（玩家右键交互）
 * - 左面：输入端（零线、火线、地线三个端口）
 * - 右面：输出端（零线、火线、地线三个端口）
 *
 * 拓扑：本方块本身不是电缆；FACING 方向和反方向各连一根电缆，
 * 把两侧网络桥接起来。CLOSED 时桥接，TRIPPED 时切断。
 *
 * 玩家右键已跳闸的空开 → 合闸恢复。
 */
public class ThreePhaseBreakerBlock extends Block implements EntityBlock {

    public static final EnumProperty<BreakerState> STATE = EnumProperty.create("state", BreakerState.class);
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            HorizontalDirectionalBlock.FACING;

    private final CableTier tier;

    public ThreePhaseBreakerBlock(CableTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(STATE, BreakerState.CLOSED));
    }

    public CableTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STATE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // FACING 指向玩家正面，输入面在左，输出面在右
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection())
                .setValue(STATE, BreakerState.CLOSED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ThreePhaseBreakerBlockEntity(ModBlockEntities.THREE_PHASE_BREAKER.get(), pos, state, tier);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == ModBlockEntities.THREE_PHASE_BREAKER.get()
                ? (lvl, pos, st, be) -> ((ThreePhaseBreakerBlockEntity) be).serverTick()
                : null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ThreePhaseBreakerBlockEntity be) {
            BreakerState cur = state.getValue(STATE);
            if (cur.isTripped()) {
                be.reset();
                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 1.4f);
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(Component.translatable("voltcraft.breaker.reset"), true);
                }
            } else {
                be.tripManually();
                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.6f, 0.8f);
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(Component.translatable("voltcraft.breaker.tripped_manually"), true);
                }
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}
