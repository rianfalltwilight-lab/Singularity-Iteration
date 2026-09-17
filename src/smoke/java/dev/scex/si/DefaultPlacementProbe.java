// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Default item use and ordinary saves only; no algorithm or canonical-charge inference. */
public final class DefaultPlacementProbe {
    private static final String[] BLOCKS={"producer/block_barrel","producer/block_pattern_storage","wiring/block_bat_box",
       "wiring/block_cesu","wiring/block_batbox_charger","wiring/block_cesu_charger","wiring/block_mfe_charger",
       "wiring/block_mfsu_charger","kugenerator/block_kinetic_generator_elc","wiring/block_mfe","wiring/block_mfsu",
       "producer/block_steam_kinetic_generator"};
    private static final String[] BOATS={"normal/entity_coal_boat","normal/entity_electric_boat","normal/entity_rubber_boat"};
    private static final String[] TYPES={"mio_icif:carbon_boat","mio_icif:electric_boat","mio_icif:rubber_boat"};
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private int checks;
    private void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    private Map<String,Object> item(ServerLevel world,ItemStack stack){
        var ops=world.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        return Map.of("stack_snbt",stack.save(world.registryAccess()).toString(),
           "all_components_snbt",DataComponentMap.CODEC.encodeStart(ops,stack.getComponents()).getOrThrow().toString());
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception{
        if(tick==20){
            var player=FakePlayerFactory.get(world,new GameProfile(UUID.fromString("73d11d64-36e7-4b18-9d8e-1c264b77bd0b"),"R104DefaultPlace"));
            player.getAbilities().instabuild=false;
            for(int i=0;i<BLOCKS.length;i++){
                var id=ResourceLocation.fromNamespaceAndPath("mio_icif",BLOCKS[i]);check(BuiltInRegistries.ITEM.containsKey(id),"Registered default item");
                var stack=new ItemStack(BuiltInRegistries.ITEM.get(id));check(stack.getItem() instanceof BlockItem,"Actual block item");
                var before=item(world,stack);var p=new BlockPos(4200+i*6,80,100);check(world.getBlockState(p).isAir(),"Unused default placement position");
                world.setBlockAndUpdate(p.below(),Blocks.OBSIDIAN.defaultBlockState());player.setPos(p.getX()+.5,p.getY(),p.getZ()+2.5);
                player.setItemInHand(InteractionHand.MAIN_HAND,stack);
                var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,new BlockHitResult(Vec3.atCenterOf(p.below()).add(0,.5,0),Direction.UP,p.below(),false));
                check(((BlockItem)stack.getItem()).place(context).consumesAction()&&stack.isEmpty(),"Default survival stack placed once");
                var tile=world.getBlockEntity(p);check(tile!=null,"Actual registered block entity");
                var saved=tile.saveWithFullMetadata(world.registryAccess());var loaded=BlockEntity.loadStatic(p,tile.getBlockState(),saved,world.registryAccess());
                check(loaded!=null&&loaded!=tile&&loaded.getLevel()==null,"Ordinary unbound factory copy");
                var row=new LinkedHashMap<String,Object>();row.put("target_item",id.toString());row.put("before",before);
                row.put("block_state",tile.getBlockState().toString());row.put("tile_type",BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(tile.getType()).toString());
                row.put("initial_nbt",saved.toString());row.put("factory_copy_nbt",loaded.saveWithFullMetadata(world.registryAccess()).toString());
                row.put("factory_save_equal",saved.equals(loaded.saveWithFullMetadata(world.registryAccess())));row.put("position",p.toShortString());
                if(tile instanceof com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block energy){
                    row.put("eu",energy.getEnergyStorageInternal().getAmount());row.put("eu_fraction",energy.getEnergyStorageInternal().scexSavedFraction());
                    row.put("eu_capacity",energy.getEnergyStorageInternal().getCapacity());
                }
                rows.add(row);
            }
            for(int i=0;i<BOATS.length;i++){
                var id=ResourceLocation.fromNamespaceAndPath("mio_icif",BOATS[i]);check(BuiltInRegistries.ITEM.containsKey(id),"Registered boat item");
                var stack=new ItemStack(BuiltInRegistries.ITEM.get(id));var before=item(world,stack);var p=new BlockPos(4300+i*8,80,100);
                world.setBlockAndUpdate(p.below(),Blocks.OBSIDIAN.defaultBlockState());
                player.setPos(p.getX()+.5,p.getY()+1,p.getZ()+.5);player.setYRot(0);player.setXRot(90);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
                var area=new AABB(p).inflate(3);check(world.getEntities(player,area).isEmpty(),"Empty vehicle placement area");
                var result=stack.getItem().use(world,player,InteractionHand.MAIN_HAND);
                check(result.getResult().consumesAction()&&stack.isEmpty(),"Ordinary vehicle use consumes exactly one stack");
                var found=world.getEntities(player,area);check(found.size()==1,"One actual spawned vehicle");var entity=found.getFirst();
                var actual=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();check(actual.equals(TYPES[i]),"Exact registered default vehicle type");
                var saved=new CompoundTag();check(entity.save(saved),"Ordinary entity save");
                rows.add(Map.of("target_item",id.toString(),"before",before,"entity_type",actual,"initial_nbt",saved.toString(),"position",p.toShortString()));
            }
            player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        }
        if(tick==30){
            var result=Map.<String,Object>of("observation_completed",true,"blocks",12,"vehicles",3,"checks",checks,"rows",rows,
               "quotes_activated",0,"scope","Normal default item placement/use and immediate save; admission requires separate semantic comparison. No charge normalization or full factory parity.");
            Files.writeString(Path.of("default-placement-result.json"),new Gson().toJson(result));return result;
        }
        return null;
    }
}
