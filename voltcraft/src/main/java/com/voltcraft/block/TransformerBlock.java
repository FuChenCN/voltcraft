package com.voltcraft.block;

import com.voltcraft.blockentity.TransformerBlockEntity;
import com.voltcraft.electric.CableTier;
import com.voltcraft.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
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
import org.jetbrains.annotations.Nullable;

/**
 * 升压变压器：
 * - 低压侧 (FACING 反方向)：通用 IEnergyStorage 输入端，吃任何 mod 的 FE
 * - 高压侧 (FACING 方向)：必须连接对应 CableTier 的电缆，写电压标签 + 推 FE
 *
 * 每种 CableTier 对应一个变压器方块，通过 outputTier 区分。
 * 后续阶段：GUI 选档（多档位变压器）、降压变压器。
 */
public class TransformerBlock extends Block implements EntityBlock {

    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            HorizontalDirectionalBlock.FACING;

    private final CableTier outputTier;

    public TransformerBlock(CableTier outputTier, BlockBehaviour.Properties properties) {
        super(properties);
        this.outputTier = outputTier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public CableTier outputTier() {
        return outputTier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TransformerBlockEntity(ModBlockEntities.TRANSFORMER.get(), pos, state, outputTier);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return type == ModBlockEntities.TRANSFORMER.get()
                ? (lvl, pos, st, be) -> ((TransformerBlockEntity) be).serverTick()
                : null;
    }
}
