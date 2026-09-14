// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy.minecraft.mixin;

import dev.scex.energy.minecraft.integration.TransformerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(value=LevelChunk.class,remap=false)
public abstract class IndependentChunkMixin {
    @Inject(method="setBlockState",at=@At("RETURN"),require=1)
    private void scexBlockHistory(BlockPos position,BlockState state,boolean moving,CallbackInfoReturnable<BlockState> callback) {
        BlockState before=callback.getReturnValue();
        if(Boolean.getBoolean("scex.independent.energy") && before!=null && before.getBlock()!=state.getBlock()
                && ((LevelChunk)(Object)this).getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            dev.scex.energy.minecraft.PlatformTopology.physicalBlockChanged(level,position,before,state);
        }
    }
    @Shadow private void removeBlockEntityTicker(BlockPos position) { throw new AssertionError(); }
    @Redirect(method={"setBlockState","createBlockEntity","promotePendingBlockEntity"},
        at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/EntityBlock;newBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/entity/BlockEntity;"),require=3)
    private BlockEntity scexCreate(EntityBlock block,BlockPos position,net.minecraft.world.level.block.state.BlockState state) {
        var replacement=TransformerFactory.placed(position,state);
        if(replacement==null)replacement=dev.scex.energy.minecraft.integration.SpecialCableFactory.create(null,position,state);
        return replacement!=null ? replacement : block.newBlockEntity(position,state);
    }
    @Inject(method="updateBlockEntityTicker",at=@At("HEAD"),cancellable=true,require=1)
    private void scexTicker(BlockEntity entity,CallbackInfo callback) {
        if(TransformerFactory.suppressTicker(entity) || dev.scex.energy.minecraft.integration.SpecialCableFactory.suppressTicker(entity)) {
            removeBlockEntityTicker(entity.getBlockPos());callback.cancel();
        }
    }
}
