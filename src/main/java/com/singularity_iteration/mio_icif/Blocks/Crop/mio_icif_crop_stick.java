// SPDX-License-Identifier: Apache-2.0
package com.singularity_iteration.mio_icif.Blocks.Crop;

import com.mojang.serialization.MapCodec;
import com.singularity_iteration.mio_icif.Blocks.entity.crop.mio_icif_crop_entity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Independent crop-stick block with a seven-stage synchronized render property. */
public class mio_icif_crop_stick extends BaseEntityBlock {
    public static final MapCodec<mio_icif_crop_stick> CODEC = simpleCodec(mio_icif_crop_stick::new);
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, 7);
    private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 16, 12);

    public mio_icif_crop_stick(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AGE, 0));
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new mio_icif_crop_entity(pos, state); }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof mio_icif_crop_entity crop) crop.tick(tickLevel, pos, tickState, crop);
        };
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(net.minecraft.world.level.block.Blocks.FARMLAND) || below.isSolidRender(level, pos.below());
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(AGE, 0);
    }

    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }
    @Override public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        super.neighborChanged(state, level, pos, block, fromPos, moving);
        if (!state.canSurvive(level, pos)) level.destroyBlock(pos, true);
    }

    public boolean isEmpty(BlockState state) { return getAge(state) == 0; }
    public int getAge(BlockState state) { return state.getValue(AGE); }
    public int getMaxAge() { return 7; }
    public BlockState withAge(int age) { return defaultBlockState().setValue(AGE, Math.clamp(age, 0, getMaxAge())); }
    public boolean isMaxAge(BlockState state) { return getAge(state) >= getMaxAge(); }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof mio_icif_crop_entity crop) {
            crop.doManualHarvest();
        }
        super.attack(state, level, pos, player);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }
}
