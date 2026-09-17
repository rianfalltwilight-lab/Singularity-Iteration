// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.pipe.mio_icif_pipe_item;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Adversarial inventories exist only in this test mod, attached to ordinary banner block entities. */
public final class PipeFailureWorldProbe {
    private static final BlockPos ROUTE = new BlockPos(2004, 80, 4);
    private static final List<BlockPos> COMMITS = List.of(new BlockPos(2020,80,4),new BlockPos(2030,80,4),
        new BlockPos(2040,80,4),new BlockPos(2050,80,4),new BlockPos(2060,80,4));
    private final boolean restart;
    private final List<String> groups = new ArrayList<>();
    private int assertions;
    public PipeFailureWorldProbe() throws Exception {
        restart = JsonParser.parseString(Files.readString(Path.of("pipe-failure-world.json"))).getAsJsonObject().get("phase").getAsString().equals("restart");
    }
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BlockEntityType.BANNER, (tile, side) -> new ItemStackHandler(1) {
            private int mode() { return tile.getPersistentData().getInt("r90_mode"); }
            @Override public int getSlots() {
                if (mode() == 1) throw new IllegalStateException("r90 simulated inventory unavailable");
                return 1;
            }
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                var data = tile.getPersistentData();
                if (simulate) {
                    if (mode() == 0) throw new IllegalStateException("r90 simulation unavailable");
                    if (mode() == 2) {
                        int quotes = data.getInt("r90_quotes") + 1; data.putInt("r90_quotes", quotes); tile.setChanged();
                        return quotes % 2 == 0 ? stack : ItemStack.EMPTY;
                    }
                    return ItemStack.EMPTY;
                }
                data.putInt("r90_calls", data.getInt("r90_calls") + 1); tile.setChanged();
                if (mode() == 3) return stack;
                data.putInt("r90_received", data.getInt("r90_received") + stack.getCount());
                return switch (mode()) {
                    case 4 -> null;
                    case 5 -> new ItemStack(Items.EMERALD);
                    case 6 -> new ItemStack(Items.DIAMOND);
                    case 7 -> stack.copyWithCount(stack.getCount() + 1);
                    default -> throw new IllegalStateException("r90 already committed insert");
                };
            }
        });
    }
    private void check(boolean pass, String label) { assertions++; if (!pass) throw new AssertionError("R90 pipe: " + label); }
    private ItemStack batch(int count) { var stack = new ItemStack(Items.DIAMOND, count); stack.set(DataComponents.CUSTOM_NAME, Component.literal("R90 retained batch")); return stack; }
    private mio_icif_pipe_item pipe(ServerLevel world, BlockPos pos) { return (mio_icif_pipe_item)world.getBlockEntity(pos); }
    private BannerBlockEntity banner(ServerLevel world, BlockPos pos) { return (BannerBlockEntity)world.getBlockEntity(pos); }
    private void banner(ServerLevel world, BlockPos pos, int mode) {
        world.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(pos, Blocks.WHITE_BANNER.defaultBlockState());
        banner(world,pos).getPersistentData().putInt("r90_mode",mode); banner(world,pos).setChanged();
    }
    private void makePipe(ServerLevel world, BlockPos pos, int count) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mio_icif:pipe/block_pipe_item"));
        check(block != Blocks.AIR,"registered pipe");
        world.setBlockAndUpdate(pos, block.defaultBlockState());
        var tile = pipe(world,pos); var tag = tile.saveWithoutMetadata(world.registryAccess());
        var buffer = (CompoundTag)batch(Math.min(99,count)).save(world.registryAccess());
        if (count>99) buffer.putInt("scex_pipe_count",count);
        tag.put("buffer",buffer); tile.loadWithComponents(tag,world.registryAccess()); tile.setChanged();
    }
    private void setup(ServerLevel world) {
        // Avoid support placement through the tested pipe position.
        banner(world,ROUTE.below(),0);
        banner(world,ROUTE.north(),1);
        banner(world,ROUTE.south(),2);
        banner(world,ROUTE.west(),3);
        world.setBlockAndUpdate(ROUTE.east(),Blocks.CHEST.defaultBlockState());
        makePipe(world,ROUTE,16);
        for(int i=0;i<COMMITS.size();i++) { var pos=COMMITS.get(i); banner(world,pos.east(),4+i); makePipe(world,pos,256); }
    }
    private void verify(ServerLevel world) {
        var healthy=(ChestBlockEntity)world.getBlockEntity(ROUTE.east());
        check(pipe(world,ROUTE).isEmpty() && !pipe(world,ROUTE).hasUncertainTransfer(),"actual tick scheduling drains through healthy outlet");
        check(ItemStack.matches(healthy.getItem(0),batch(16)),"healthy inventory receives exact components once");
        check(banner(world,ROUTE.west()).getPersistentData().getInt("r90_calls")==1,"confirmed rejection is not retried before healthy outlet");
        for(int i=0;i<COMMITS.size();i++) {
            var pos=COMMITS.get(i); var tile=pipe(world,pos); var external=banner(world,pos.east()).getPersistentData();
            check(tile.hasUncertainTransfer(),"unknown execution is held for mode "+i);
            check(ItemStack.matches(tile.getBufferItem(),batch(192)),"unoffered oversized custody retained for mode "+i);
            check(external.getInt("r90_calls")==1 && external.getInt("r90_received")==64,"no duplicate external insert for mode "+i);
            var state=tile.saveWithoutMetadata(world.registryAccess());
            check(state.getString("scex_pipe_phase").equals("insert"),"direction survives actual save");
            check(ItemStack.matches(ItemStack.parse(world.registryAccess(),state.getCompound("scex_pipe_uncertain")).orElseThrow(),batch(64)),"held batch identity survives actual save");
        }
    }
    public Map<String,Object> inspect(ServerLevel world,int tick) throws Exception {
        if(tick==30 && !restart)setup(world);
        if(tick<31)return null;
        if(tick==150) { verify(world); groups.add(restart?"real-world-restart-custody":"actual-tick-outlet-progress-and-unknown-custody"); }
        if(tick==290) {
            verify(world); groups.add("later-ticks-do-not-replay");
            Files.writeString(Path.of("pipe-failure-result.json"),new Gson().toJson(Map.of("passed",true,"phase",restart?"restart":"initial",
                "assertions",assertions,"groups",groups,"cases",COMMITS.size()+1,"scope","registered capabilities and ticking world; adversarial test inventories, not named third-party releases")));
        }
        return Map.of("assertions",assertions,"phase",restart?"restart":"initial");
    }
}
