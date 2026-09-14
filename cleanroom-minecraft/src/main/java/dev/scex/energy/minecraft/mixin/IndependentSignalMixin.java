// SPDX-License-Identifier: Apache-2.0
package dev.scex.energy.minecraft.mixin;

import dev.scex.energy.minecraft.IndependentSpecialCableBlockEntity;
import dev.scex.energy.minecraft.integration.SpecialCableFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Public Minecraft signal surface for the independent special-wire entity. */
@Mixin(value=BlockBehaviour.BlockStateBase.class,remap=false)
public abstract class IndependentSignalMixin {
    @Inject(method="isSignalSource",at=@At("HEAD"),cancellable=true,require=1)
    private void scexSource(CallbackInfoReturnable<Boolean> callback) {
        var state=(BlockState)(Object)this;
        if(SpecialCableFactory.controls(state))callback.setReturnValue(SpecialCableFactory.detector(state));
    }
    @Inject(method="getSignal",at=@At("HEAD"),cancellable=true,require=1)
    private void scexWeak(BlockGetter getter,BlockPos at,Direction side,CallbackInfoReturnable<Integer> callback) {
        if(!SpecialCableFactory.controls((BlockState)(Object)this))return;
        net.minecraft.world.level.block.entity.BlockEntity tile;
        if(getter instanceof ServerLevel level) {
            var chunk=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
            tile=chunk==null ? null : chunk.getBlockEntity(at,LevelChunk.EntityCreationType.CHECK);
        } else tile=getter.getBlockEntity(at);
        callback.setReturnValue(tile instanceof IndependentSpecialCableBlockEntity cable ? cable.signal() : 0);
    }
    @Inject(method="getDirectSignal",at=@At("HEAD"),cancellable=true,require=1)
    private void scexDirect(BlockGetter getter,BlockPos at,Direction side,CallbackInfoReturnable<Integer> callback) {
        if(SpecialCableFactory.controls((BlockState)(Object)this))callback.setReturnValue(0);
    }
    @Inject(method="hasAnalogOutputSignal",at=@At("HEAD"),cancellable=true,require=1)
    private void scexAnalogAvailable(CallbackInfoReturnable<Boolean> callback) {
        // The measured detector weak signal does not drive a directly adjacent
        // comparator. Express that public behavior through a zero analog override.
        if(SpecialCableFactory.controls((BlockState)(Object)this))callback.setReturnValue(true);
    }
    @Inject(method="getAnalogOutputSignal",at=@At("HEAD"),cancellable=true,require=1)
    private void scexAnalog(Level level,BlockPos at,CallbackInfoReturnable<Integer> callback) {
        if(SpecialCableFactory.controls((BlockState)(Object)this))callback.setReturnValue(0);
    }
}
