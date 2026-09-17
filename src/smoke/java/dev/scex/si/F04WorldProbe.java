// SPDX-License-Identifier: Apache-2.0
// Black-box world probe for the public scanner/replicator ABI.
package dev.scex.si;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import com.mojang.authlib.GameProfile;
import java.util.UUID;

/**
 * Exercises only public registration, menu, energy and saved-state surfaces.
 * It deliberately does not inspect or reproduce scanner/replicator algorithms.
 */
public final class F04WorldProbe {
    private static final BlockPos SCANNER=new BlockPos(1740,80,4);
    private static final BlockPos REPLICATOR=new BlockPos(1750,80,4);
    private final List<String> groups=new ArrayList<>();
    private int assertions;
    private FakePlayer player;

    private static void check(boolean ok,String label){if(!ok)throw new AssertionError("F04 "+label);}
    private void assertAndCount(boolean ok,String label){assertions++;check(ok,label);}
    private void done(String group){assertAndCount(!groups.contains(group),"unique "+group);groups.add(group);}
    private static BlockPos base(BlockPos pos){return pos.below();}

    private static Object invoke(Object target,String name,Object... args) throws Exception {
        Class<?> c=target.getClass();
        while(c!=null){
            for(var m:c.getMethods()) if(m.getName().equals(name) && m.getParameterCount()==args.length){
                try{return m.invoke(target,args);}catch(IllegalArgumentException ignored){}
            }
            c=c.getSuperclass();
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
    private static Object optional(Object target,String name,Object... args){try{return invoke(target,name,args);}catch(Exception e){return null;}}
    private static long number(Object value){return value instanceof Number n?n.longValue():0L;}
    private static BlockEntity place(ServerLevel world,BlockPos pos,String id){
        var block=BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(id));
        check(block!=Blocks.AIR,"registered "+id);
        world.setBlockAndUpdate(base(pos),Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(pos,block.defaultBlockState());
        var tile=world.getBlockEntity(pos);
        check(tile!=null,"block entity created "+id);
        return tile;
    }
    private static Map<String,Object> nbt(ServerLevel world,BlockEntity tile){
        var out=new LinkedHashMap<String,Object>();
        out.put("class",tile.getClass().getName());
        out.put("nbt",tile.saveWithFullMetadata(world.registryAccess()).toString());
        return out;
    }
    private void setup(ServerLevel world) throws Exception {
        var scanner=place(world,SCANNER,"mio_icif:producer/block_scanner_elc");
        var replicator=place(world,REPLICATOR,"mio_icif:producer/block_replicator_elc");
        assertAndCount(scanner.getClass().getName().contains("scanner"),"scanner registration surface");
        assertAndCount(replicator.getClass().getName().contains("replicator"),"replicator registration surface");
        player=new FakePlayer(world,new GameProfile(UUID.fromString("00000000-0000-0000-0000-00000000f004"),"SI_F04"));
        player.setPos(SCANNER.getX()+0.5,SCANNER.getY()+1,SCANNER.getZ()+0.5);
        for(var pair:List.of(new Object[]{scanner,1},new Object[]{replicator,2})){
            var provider=pair[0] instanceof MenuProvider p?p:null;
            assertAndCount(provider!=null,"menu provider exposed");
            AbstractContainerMenu menu=provider==null?null:provider.createMenu((Integer)pair[1],player.getInventory(),player);
            assertAndCount(menu!=null,"menu constructed");
            if(menu!=null)menu.removed(player);
        }
        var energyBefore=number(optional(scanner,"getEnergyStorageInternal") == null ? null : optional(optional(scanner,"getEnergyStorageInternal"),"scexExactAmount"));
        var storage=optional(scanner,"getEnergyStorageInternal");
        if(storage!=null){
            optional(storage,"setEnergy",4096L);
            var after=number(optional(storage,"scexExactAmount"));
            assertAndCount(after>=energyBefore,"public energy storage accepts a bounded charge");
            done("scanner-energy-surface");
        }
        done("registration-and-menus");
        Files.writeString(Path.of("f04-world-setup.json"),new Gson().toJson(Map.of("scanner",nbt(world,scanner),"replicator",nbt(world,replicator))));
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) throws Exception {
        if(tick==30)setup(world);
        if(tick<31)return null;
        var scanner=world.getBlockEntity(SCANNER);var replicator=world.getBlockEntity(REPLICATOR);
        assertAndCount(scanner!=null&&replicator!=null,"machines remain loaded");
        if(tick==40){
            var scannerNbt=scanner.saveWithFullMetadata(world.registryAccess());
            var replNbt=replicator.saveWithFullMetadata(world.registryAccess());
            assertAndCount(!scannerNbt.isEmpty()&&!replNbt.isEmpty(),"machine state serializes");
            done("save-state-surface");
        }
        if(tick==80){
            check(groups.size()>=3,"all F04 groups complete");
            Files.writeString(Path.of("f04-world-result.json"),new Gson().toJson(Map.of(
                "passed",true,"groups",groups,"assertions",assertions,
                "scope","public registration/menu/energy/save surface; connected client, full scan and replication parity NOT_RUN")));
        }
        return Map.of("assertions",assertions,"groups",groups,"scanner",scanner.getClass().getName(),"replicator",replicator.getClass().getName());
    }
}
