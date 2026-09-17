// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.singularity_iteration.mio_icif.Blocks.entity.pipe.mio_icif_pipe_item;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Starts from the saved R90 world; does not recreate its unresolved transfers. */
public final class PipeRecoveryWorldProbe {
    private static final List<BlockPos> ORIGINAL = List.of(new BlockPos(2020,80,4),new BlockPos(2030,80,4),
        new BlockPos(2040,80,4),new BlockPos(2050,80,4),new BlockPos(2060,80,4));
    private final List<BlockPos> restored = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private int assertions;
    private void check(boolean pass,String label){assertions++;if(!pass)throw new AssertionError("R91 pipe recovery: "+label);}
    private mio_icif_pipe_item pipe(ServerLevel world,BlockPos pos){return (mio_icif_pipe_item)world.getBlockEntity(pos);}
    private ItemStack batch(int count){var stack=new ItemStack(Items.DIAMOND,count);stack.set(DataComponents.CUSTOM_NAME,Component.literal("R90 retained batch"));return stack;}
    private String coords(BlockPos pos){return pos.getX()+" "+pos.getY()+" "+pos.getZ();}
    private int command(ServerLevel world,int permission,String command) throws CommandSyntaxException {
        return world.getServer().getCommands().getDispatcher().execute(command,world.getServer().createCommandSourceStack().withPermission(permission));
    }
    private void placePacked(ServerLevel world,FakePlayer player,BlockPos pos,ItemStack item){
        world.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        player.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+2.5);player.setItemInHand(InteractionHand.MAIN_HAND,item);
        var ctx=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,item,new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0,0.5,0),Direction.UP,pos.below(),false));
        check(((BlockItem)item.getItem()).place(ctx).consumesAction(),"real BlockItem placement succeeds");
        check(item.isEmpty(),"survival placement consumes the one packed pipe");
    }
    private ItemStack dismantle(ServerLevel world,FakePlayer player,BlockPos pos,boolean ordinaryDrop){
        var state=world.getBlockState(pos);var tile=pipe(world,pos);
        var params=new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN,Vec3.atCenterOf(pos))
            .withParameter(LootContextParams.TOOL,ItemStack.EMPTY).withParameter(LootContextParams.BLOCK_ENTITY,tile)
            .withOptionalParameter(LootContextParams.THIS_ENTITY,player);
        check(state.getDrops(params).isEmpty(),"loot previews do not clone packed custody");
        var snapshot=tile.saveWithId(world.registryAccess());
        if(ordinaryDrop)check(world.destroyBlock(pos,true,player),"ordinary block destruction succeeds");
        else check(world.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState()),"replacement without loot succeeds");
        var entities=world.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(1));
        check(entities.size()==1 && entities.getFirst().getItem().is(state.getBlock().asItem()),"removal emits exactly one packed pipe and no loose duplicates");
        var packed=entities.getFirst().getItem().copy();entities.getFirst().discard();
        var saved=packed.get(DataComponents.BLOCK_ENTITY_DATA);
        check(saved!=null && saved.copyTag().equals(snapshot),"packed block item contains exact buffer and transfer record");
        return packed;
    }
    private void exercise(ServerLevel world) throws Exception {
        var player=FakePlayerFactory.get(world,new GameProfile(UUID.fromString("07f292f0-17d7-4e40-82e2-024934ba61ef"),"R91PipeRecovery"));
        check(!player.getAbilities().instabuild,"recovery placement uses survival consumption");
        for(int i=0;i<ORIGINAL.size();i++){
            var original=ORIGINAL.get(i);var old=pipe(world,original);
            check(old.hasUncertainTransfer() && ItemStack.matches(old.getBufferItem(),batch(192)),"load real R90 unresolved state");
            var external=((BannerBlockEntity)world.getBlockEntity(original.east())).getPersistentData();
            check(external.getInt("r90_received")==64 && external.getInt("r90_calls")==1,"external commit still happened exactly once");
            String id=old.getUncertainTransferId();check(!id.isEmpty(),"old saved intent receives a recovery identity");
            boolean denied=false;try{command(world,0,"mio_icif pipe resolve "+coords(original)+" "+id+" 0");}catch(CommandSyntaxException expected){denied=true;}
            check(denied && old.hasUncertainTransfer(),"non-operator cannot resolve transfers");
            check(command(world,2,"mio_icif pipe inspect "+coords(original))==1,"actual registered inspect command works");
            check(command(world,2,"mio_icif pipe resolve "+coords(original)+" stale-id 64")==0,"stale intent rejected at command entry");
            var packed=dismantle(world,player,original,i%2==0);
            var at=original.south(6);restored.add(at);placePacked(world,player,at,packed);
            var resumed=pipe(world,at);
            check(resumed.hasUncertainTransfer() && resumed.getUncertainTransferId().equals(id) && ItemStack.matches(resumed.getBufferItem(),batch(192)),"placement retains transfer identity and exact unresolved custody");
            check(command(world,2,"mio_icif pipe resolve "+coords(at)+" "+id+" 65")==0 && resumed.hasUncertainTransfer(),"invalid external amount changes nothing");
            check(command(world,2,"mio_icif pipe resolve "+coords(at)+" "+id+" 64")==1,"confirmed external acceptance resolves once");
            check(command(world,2,"mio_icif pipe resolve "+coords(at)+" "+id+" 64")==0,"repeated command cannot duplicate refund");
            check(!resumed.hasUncertainTransfer() && ItemStack.matches(resumed.getBufferItem(),batch(192)),"already accepted batch is never refunded");
            world.setBlockAndUpdate(at.east(),Blocks.CHEST.defaultBlockState());
        }
        groups.add("real-old-world-break-replace-place-and-operator-command");
        // A resolved historical oversized buffer must also survive breaking before it drains.
        var largePos=new BlockPos(2070,80,4);var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:pipe/block_pipe_item"));
        world.setBlockAndUpdate(largePos,block.defaultBlockState());var large=pipe(world,largePos);
        var tag=large.saveWithoutMetadata(world.registryAccess());var saved=(CompoundTag)batch(99).save(world.registryAccess());saved.putInt("scex_pipe_count",256);tag.put("buffer",saved);
        large.loadWithComponents(tag,world.registryAccess());large.setChanged();
        var packed=dismantle(world,player,largePos,true);var placed=largePos.south(6);placePacked(world,player,placed,packed);
        check(!pipe(world,placed).hasUncertainTransfer() && ItemStack.matches(pipe(world,placed).getBufferItem(),batch(256)),"normal historical large buffer survives real break and place");
        groups.add("oversized-normal-buffer-break-and-place");
        // Missing item metadata remains recoverable as raw data through the block item.
        var unknownPos=new BlockPos(2080,80,4);world.setBlockAndUpdate(unknownPos,block.defaultBlockState());var unknown=pipe(world,unknownPos);
        var unknownTag=unknown.saveWithoutMetadata(world.registryAccess());var unknownItem=(CompoundTag)batch(1).save(world.registryAccess());unknownItem.putString("id","missing_mod:r91_unknown");
        unknownTag.putString("scex_pipe_phase","insert");unknownTag.put("scex_pipe_uncertain",unknownItem);unknown.loadWithComponents(unknownTag,world.registryAccess());unknown.setChanged();
        var unknownPacked=dismantle(world,player,unknownPos,false);var unknownAt=unknownPos.south(6);placePacked(world,player,unknownAt,unknownPacked);
        var held=pipe(world,unknownAt);
        check(held.hasUncertainTransfer() && held.saveWithoutMetadata(world.registryAccess()).getCompound("scex_pipe_uncertain").equals(unknownItem),"unknown item data remains identical after replacement and placement");
        check(command(world,2,"mio_icif pipe resolve "+coords(unknownAt)+" "+held.getUncertainTransferId()+" 0")==0,"command cannot discard unknown item data");
        check(command(world,2,"mio_icif pipe inspect 1000000 80 1000000")==0 && world.getChunkSource().getChunkNow(62500,62500)==null,"inspection refuses an unloaded chunk without loading it");
        groups.add("unknown-item-custody-and-unloaded-command-boundary");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) throws Exception {
        if(tick==40)exercise(world);
        if(tick==210){
            for(var pos:restored){
                var chest=(ChestBlockEntity)world.getBlockEntity(pos.east());int count=0;
                for(int i=0;i<chest.getContainerSize();i++){var stack=chest.getItem(i);if(!stack.isEmpty()){check(ItemStack.isSameItemSameComponents(stack,batch(1)),"delivery retains item components");count+=stack.getCount();}}
                check(count==192 && pipe(world,pos).isEmpty() && !pipe(world,pos).hasUncertainTransfer(),"resolved pipe resumes actual ticking delivery with no duplication");
            }
            groups.add("resumed-world-transfer-after-reconciliation");
            Files.writeString(Path.of("pipe-recovery-result.json"),new Gson().toJson(Map.of("passed",true,"assertions",assertions,"groups",groups,
                "scope","real R90 world, registered commands, FakePlayer block placement and actual ticks; no connected client or named third-party release")));
        }
        return tick<41?null:Map.of("assertions",assertions,"groups",groups);
    }
}
