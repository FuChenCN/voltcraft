package com.voltcraft.block;

import com.voltcraft.blockentity.CableBlockEntity;
import com.voltcraft.electric.CableTier;
import com.voltcraft.electric.network.NetworkManager;
import com.voltcraft.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 参数化电缆方块。所有电压等级共享同一个 Block 类，
 * 通过 CableTier 区分上限和电压。
 *
 * 当前阶段：方块外观 + BlockEntity 持有电压标签 NBT + EnergyNetwork 拓扑维护；
 * 后续阶段：连接方向 BlockState、FE Capability、变压器电压写入。
 */
public class CableBlock extends Block implements EntityBlock {

    private final CableTier tier;

    public CableBlock(CableTier tier, BlockBehaviour.Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public CableTier tier() {
        return tier;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CableBlockEntity(ModBlockEntities.CABLE.get(), pos, state, tier);
    }

    /**
     * 方块被真正破坏（不是区块卸载）时从网络剔除。
     * 区块卸载只会触发 BlockEntity.setRemoved，不会走 onRemove。
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            NetworkManager.get(level).onCableRemoved(level, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
