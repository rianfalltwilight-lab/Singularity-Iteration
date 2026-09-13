// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.mixin;

import dev.scex.si.TransformerFactoryProbe;
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

@Mixin(value=LevelChunk.class,remap=false)
public abstract class TransformerChunkProbeMixin {
    @Shadow private void removeBlockEntityTicker(BlockPos position) { throw new AssertionError(); }
    @Redirect(method={"setBlockState","createBlockEntity","promotePendingBlockEntity"},
        at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/EntityBlock;newBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/entity/BlockEntity;"),require=3)
    private BlockEntity scexCreate(EntityBlock block,BlockPos position,net.minecraft.world.level.block.state.BlockState state) {
        var replacement=TransformerFactoryProbe.placed(position,state);
        return replacement!=null ? replacement : block.newBlockEntity(position,state);
    }
    @Inject(method="updateBlockEntityTicker",at=@At("HEAD"),cancellable=true,require=1)
    private void scexTicker(BlockEntity entity,CallbackInfo callback) {
        if(TransformerFactoryProbe.suppressTicker(entity)) {
            removeBlockEntityTicker(entity.getBlockPos());callback.cancel();
        }
    }
}
