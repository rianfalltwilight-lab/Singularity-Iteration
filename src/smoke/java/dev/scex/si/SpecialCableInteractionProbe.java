// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.scex.energy.minecraft.IndependentSpecialCableBlockEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Real survival mining/placement paths and physical chunk re-instantiation in a private fixture. */
public final class SpecialCableInteractionProbe {
    private final List<BlockPos> positions=new ArrayList<>();
    private final List<ResourceLocation> blocks=new ArrayList<>();
    private final List<IndependentSpecialCableBlockEntity> previous=new ArrayList<>();
    private int checks;
    public SpecialCableInteractionProbe() throws Exception {
        for(var row:JsonParser.parseString(Files.readString(Path.of("special-cable-interaction.json"))).getAsJsonArray()) {
            var entry=row.getAsJsonObject();var p=entry.getAsJsonArray("position");
            positions.add(new BlockPos(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt()));
            blocks.add(ResourceLocation.parse(entry.get("block").getAsString()));
        }
        if(positions.size()!=2)throw new IllegalArgumentException("Two declared special-wire controls required");
    }
    private void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private IndependentSpecialCableBlockEntity entity(ServerLevel level,BlockPos at) {
        var chunk=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
        if(chunk==null || !(chunk.getBlockEntity(at) instanceof IndependentSpecialCableBlockEntity cable))throw new AssertionError("Independent special cable missing");
        return cable;
    }
    public Map<String,Object> inspect(ServerLevel level,int tick) {
        if(tick==40) {
            for(var at:positions)previous.add(entity(level,at));
        } else if(tick==50 || tick==60) {
            var player=FakePlayerFactory.get(level,new GameProfile(UUID.fromString("af5539c3-4c03-43ec-8438-986cdb320625"),"ScexWire25"));
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);player.setShiftKeyDown(true);
            for(int i=0;i<positions.size();i++) {
                var at=positions.get(i);player.setPos(at.getX()+0.5,at.getY()+1,at.getZ()+2);
                if(tick==50) {
                    player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    check(player.gameMode.destroyBlock(at),"survival wire mining accepted");
                    check(level.getBlockState(at).isAir() && previous.get(i).isRemoved(),"mined special entity removed");
                } else {
                    var item=new ItemStack(BuiltInRegistries.BLOCK.get(blocks.get(i)).asItem());
                    check(!item.isEmpty(),"registered special cable item exists");player.setItemInHand(InteractionHand.MAIN_HAND,item);
                    var hit=new BlockHitResult(Vec3.atCenterOf(at.below()).add(0,0.5,0),Direction.UP,at.below(),false);
                    var result=player.gameMode.useItemOn(player,level,item,InteractionHand.MAIN_HAND,hit);
                    check(result.consumesAction() && player.getMainHandItem().isEmpty(),"survival placement consumed one item");
                    var current=entity(level,at);check(current!=previous.get(i),"new independently owned placed entity");previous.set(i,current);
                }
            }
        } else if(tick==410) {
            for(int i=0;i<positions.size();i++) {
                var current=entity(level,positions.get(i));check(current!=previous.get(i),"physical reload created a new special entity");
                check(current.getUpdatePacket()!=null,"special entity supplies update packet");
                var saved=current.saveWithoutMetadata(level.registryAccess());var update=current.getUpdateTag(level.registryAccess());
                check(saved.getBoolean("active")==update.getBoolean("active") && saved.getInt("scex_poll_age")==update.getInt("scex_poll_age"),"saved and client-update state agree");
            }
        } else return null;
        return Map.of("passed",true,"checks",checks,"tick",tick,"positions",positions.size(),"real_survival_interaction",true);
    }
}
