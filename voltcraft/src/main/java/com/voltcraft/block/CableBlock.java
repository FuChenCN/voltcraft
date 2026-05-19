package com.voltcraft.block;

import com.voltcraft.blockentity.CableBlockEntity;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.network.NetworkManager;
import com.voltcraft.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

/**
 * 参数化电缆方块。中心核心 + 6 方向连接臂的细管造型。
 *
 * 连接判定（智能连接）：朝向 d 的邻居满足以下任一条件即连接
 *   1. 同等级电缆
 *   2. 暴露 IEnergyStorage Capability 在 d 的反面（变压器/空开/端子/第三方机器）
 */
public class CableBlock extends Block implements EntityBlock {

    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST  = BlockStateProperties.EAST;
    public static final BooleanProperty WEST  = BlockStateProperties.WEST;
    public static final BooleanProperty UP    = BlockStateProperties.UP;
    public static final BooleanProperty DOWN  = BlockStateProperties.DOWN;

    private final CableTier tier;

    public CableBlock(CableTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false).setValue(SOUTH, false)
                .setValue(EAST, false).setValue(WEST, false)
                .setValue(UP, false).setValue(DOWN, false));
    }

    public CableTier tier() {
        return tier;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return computeConnections(defaultBlockState(), context.getLevel(), context.getClickedPos());
    }

    /** 邻居变化时重算自己 6 方向的连接位。 */
    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        boolean connected = canConnectTo(level, pos, direction);
        return state.setValue(propertyFor(direction), connected);
    }

    private BlockState computeConnections(BlockState state, LevelReader level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            state = state.setValue(propertyFor(d), canConnectTo(level, pos, d));
        }
        return state;
    }

    /** 判定本电缆向 dir 方向是否应连接邻居。 */
    private boolean canConnectTo(LevelReader level, BlockPos pos, Direction dir) {
        BlockPos neighborPos = pos.relative(dir);
        BlockState neighbor = level.getBlockState(neighborPos);

        // 1. 同等级电缆
        if (neighbor.getBlock() instanceof CableBlock cb && cb.tier() == this.tier) {
            return true;
        }

        // 三相重构后：变压器/空开/端子改用软线 anchor 连接，硬电缆不再贴面接它们。
        // 仅对外部第三方提供 IEnergyStorage capability 的方块保留贴面连接（兼容其它 mod）。
        if (level instanceof Level lvl) {
            BlockEntity be = lvl.getBlockEntity(neighborPos);
            if (be != null) {
                var es = lvl.getCapability(
                        Capabilities.EnergyStorage.BLOCK,
                        neighborPos,
                        neighbor,
                        be,
                        dir.getOpposite()
                );
                if (es != null) return true;
            }
        }
        return false;
    }

    public static BooleanProperty propertyFor(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST  -> EAST;
            case WEST  -> WEST;
            case UP    -> UP;
            case DOWN  -> DOWN;
        };
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CableShapes.get(state, NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CableShapes.get(state, NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CableBlockEntity(ModBlockEntities.CABLE.get(), pos, state, tier);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            NetworkManager.get(level).onCableRemoved(level, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
