// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.mixin;

import dev.scex.si.TransformerFactoryProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BlockEntityType.class,remap=false)
public abstract class TransformerTypeProbeMixin {
    @Inject(method="create",at=@At("HEAD"),cancellable=true,require=1)
    private void scexLoad(BlockPos position,BlockState state,CallbackInfoReturnable<BlockEntity> callback) {
        var replacement=TransformerFactoryProbe.loaded((BlockEntityType<?>)(Object)this,position,state);
        if(replacement!=null)callback.setReturnValue(replacement);
    }
}
