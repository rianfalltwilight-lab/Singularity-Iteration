// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.slot.MachineItemHandler;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotLayout;
import dev.scex.si.energy.ContainerToTank;
import dev.scex.si.energy.OwnedHeatExchange;
import dev.scex.si.energy.ThermalOutput;
import dev.scex.si.processing.OwnedFluidConversion;
import com.singularity_iteration.mio_icif.energy.heat.HeatStorage;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** Vanilla registry bootstrap only; no Minecraft world, server, or mod-loading run. */
public final class FuelContainerContract {
    private static int assertions;
    private FuelContainerContract() { }
    private static void require(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
private static final class PipeTile53 extends com.singularity_iteration.mio_icif.Blocks.entity.pipe.mio_icif_pipe_item {
        private boolean live=true;
        PipeTile53() { super(net.minecraft.world.level.block.entity.BlockEntityType.FURNACE, net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState()); }
        @Override protected boolean canWork() { return live; }
        void put(ItemStack item) { bufferItem=item.copy(); }
        void watch(dev.scex.si.processing.ItemPipeRoute route) { observeRoute(route); }
        int send(net.neoforged.neoforge.items.IItemHandler target,int amount) {
            isProcessing=true;try{return deliver(target,0,amount);}finally{isProcessing=false;}
        }
        net.minecraft.nbt.CompoundTag saved(net.minecraft.core.HolderLookup.Provider provider) {
            var tag=new net.minecraft.nbt.CompoundTag();saveAdditional(tag,provider);return tag;
        }
    }
    private static void nativeOutput66() {
        var tier=com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV;
        var storage=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(1000,32,32,tier);
        storage.setEnergy(1000);long[] clock={0};boolean[] active={true};
        var ledger=new dev.scex.si.energy.FeLedger(storage,()->clock[0],()->active[0]);
        var quote=ledger.quoteNativeOutput();
        require(quote.allowance().equals(dev.scex.energy.EnergyAmount.of(32))&&storage.getAmount()==1000,
            "Native quote exposes the output allowance without spending it");
        require(ledger.extract(1,false)==1,"FE may spend a quarter EU before network planning");
        var debit=new dev.scex.si.energy.FeLedger.OutputDebit(quote,dev.scex.energy.EnergyAmount.of(32));
        require(!dev.scex.si.energy.FeLedger.commitNativeOutput(java.util.List.of(debit),java.util.function.BooleanSupplier::getAsBoolean)
            &&storage.scexExactAmount().equals(dev.scex.energy.EnergyAmount.fromDouble(999.75)),
            "FE mutation invalidates an older native allowance even before a balance commit");
        quote=ledger.quoteNativeOutput();var offered=quote.limitOffer(storage.scexExactAmount());
        var route=new dev.scex.energy.RouteCosts(){
            @Override public boolean reaches(int receiver){return receiver==0;}
            @Override public long lossMilliTo(int receiver){return 0;}
        };
        var domains=java.util.List.of(new dev.scex.energy.DomainDistributor.Domain(new int[]{0},java.util.List.of(route),new int[][]{{0}}));
        var round=dev.scex.energy.FractionalDistributor.allocateTraced(
            java.util.List.of(new dev.scex.energy.DomainDistributor.Source(offered.whole(),32,false)),java.util.List.of(offered),
            domains,new int[]{0},java.util.List.of(dev.scex.energy.EnergyAmount.of(1000)),java.util.List.of(),new java.util.Random(66));
        require(offered.equals(dev.scex.energy.EnergyAmount.fromDouble(31.75))&&round.debit(0).isZero(),
            "Insufficient mixed allowance defers a full native storage packet rather than lowering its voltage");
        require(ledger.extract(127,false)==127&&ledger.extractWholeEu(1,false)==0,
            "FE and slot output cannot exceed the shared 32 EU allowance");
        clock[0]++;quote=ledger.quoteNativeOutput();
        var source=new dev.scex.energy.NetworkCell(storage.scexExactAmount());
        var sink=new dev.scex.energy.NetworkCell(dev.scex.energy.EnergyAmount.ZERO);
        var sourceQuote=source.quote();var sinkQuote=sink.quote();
        var amount=new dev.scex.energy.EnergyAmount(7,dev.scex.energy.EnergyAmount.UNITS/8);
        var loss=dev.scex.energy.EnergyAmount.of(1);
        var writes=java.util.List.of(new dev.scex.energy.NetworkCell.Write(sourceQuote,sourceQuote.exactAmount().subtract(amount)),
            new dev.scex.energy.NetworkCell.Write(sinkQuote,amount.subtract(loss)));
        debit=new dev.scex.si.energy.FeLedger.OutputDebit(quote,amount);
        var debits=java.util.List.of(debit);
        require(!dev.scex.si.energy.FeLedger.commitNativeOutput(debits,guard->dev.scex.energy.NetworkCell.commit(writes,loss,()->false))
            &&ledger.extract(1000,true)==128&&source.quote()==sourceQuote&&sink.quote()==sinkQuote,
            "Rejected owned network transaction changes neither balances nor the allowance");
        require(dev.scex.si.energy.FeLedger.commitNativeOutput(debits,guard->dev.scex.energy.NetworkCell.commit(writes,loss,guard))
            &&source.quote().exactAmount().add(sink.quote().exactAmount()).add(loss).equals(sourceQuote.exactAmount()),
            "Native allowance publication follows a conserving NetworkCell transaction including cable loss");
        require(ledger.quoteNativeOutput().allowance().equals(dev.scex.energy.EnergyAmount.fromDouble(24.875))
            &&ledger.extract(1000,true)==99,
            "Native eighth-EU debit is retained exactly and FE rounds only its own offered output down");
        require(!dev.scex.si.energy.FeLedger.commitNativeOutput(debits,java.util.function.BooleanSupplier::getAsBoolean),
            "A committed native allowance ticket cannot be replayed");
        var old=ledger.quoteNativeOutput();clock[0]++;
        require(!dev.scex.si.energy.FeLedger.commitNativeOutput(java.util.List.of(new dev.scex.si.energy.FeLedger.OutputDebit(old,amount)),
            java.util.function.BooleanSupplier::getAsBoolean)&&ledger.extract(1000,true)==128,
            "An old-tick native ticket does not spend a new-tick allowance");
        quote=ledger.quoteNativeOutput();storage.setMaxExtract(16);
        require(!dev.scex.si.energy.FeLedger.commitNativeOutput(java.util.List.of(new dev.scex.si.energy.FeLedger.OutputDebit(quote,amount)),
            java.util.function.BooleanSupplier::getAsBoolean)&&ledger.extract(1000,true)==64,
            "An output limit change revokes the quote before numeric commit");
        storage.setOutputEnabled(false);
        require(ledger.quoteNativeOutput().allowance().isZero(),"Redstone closes native and FE outputs together");
        storage.setOutputEnabled(true);active[0]=false;
        require(ledger.quoteNativeOutput().allowance().isZero(),"Inactive owner offers no native output");
        active[0]=true;ledger.loadUncertainOutput(1);
        require(ledger.quoteNativeOutput().allowance().isZero()&&ledger.extract(1,false)==0,
            "Uncertain foreign commit blocks native output as well as FE replay");
        ledger.loadUncertainOutput(0);
        var target=new net.neoforged.neoforge.energy.EnergyStorage(1000){
            @Override public int receiveEnergy(int request,boolean simulate){
                require(ledger.quoteNativeOutput().allowance().isZero(),"Foreign FE callbacks cannot reenter through native output");
                return simulate?request:0;
            }
        };
        require(ledger.push(target)==0&&ledger.extract(1000,true)==64,
            "A confirmed rejected FE reservation refunds the exact allowance for native use");
        debit=new dev.scex.si.energy.FeLedger.OutputDebit(ledger.quoteNativeOutput(),amount);
        boolean duplicate=false;
        try{dev.scex.si.energy.FeLedger.commitNativeOutput(java.util.List.of(debit,debit),java.util.function.BooleanSupplier::getAsBoolean);}
        catch(IllegalArgumentException expected){duplicate=true;}
        require(duplicate&&ledger.extract(1000,true)==64,"Duplicate owners cannot double-publish an output ticket");
        var huge=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(Long.MAX_VALUE,0,Long.MAX_VALUE,tier);
        huge.setEnergy(Long.MAX_VALUE);var hugeLedger=new dev.scex.si.energy.FeLedger(huge,()->0,()->true);
        require(hugeLedger.extract(1,false)==1&&hugeLedger.quoteNativeOutput().allowance().equals(
            new dev.scex.energy.EnergyAmount(Long.MAX_VALUE-1,3*dev.scex.energy.EnergyAmount.UNITS/4)),
            "Full-range native allowance retains a prior FE fraction without long multiplication overflow");
        var hugeDebit=new dev.scex.si.energy.FeLedger.OutputDebit(hugeLedger.quoteNativeOutput(),dev.scex.energy.EnergyAmount.of(Long.MAX_VALUE-1));
        require(dev.scex.si.energy.FeLedger.commitNativeOutput(java.util.List.of(hugeDebit),java.util.function.BooleanSupplier::getAsBoolean)
            &&hugeLedger.extract(1000,true)==3,"Native whole-long output leaves only the exact remaining three FE");
    }
    private static final class PatternTile68 extends com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pattern_storage {
        boolean live=true;
        PatternTile68(){super(net.minecraft.core.BlockPos.ZERO,net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);}
        @Override protected boolean operational(){return live&&!hasUnresolvedPatterns();}
        @Override protected net.minecraft.core.HolderLookup.Provider patternRegistries(){return net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);}
        void energy(long value){energyStorage.setEnergy(value);}
        long energy(){return energyStorage.getAmount();}
        net.minecraft.nbt.CompoundTag snapshot(){var tag=new net.minecraft.nbt.CompoundTag();saveAdditional(tag,patternRegistries());return tag;}
    }
    private static final class CallbackMemory68 extends com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory {
        Runnable callback=()->{};
        CallbackMemory68(){super(new net.minecraft.world.item.Item.Properties());}
        @Override public boolean tryStoreData(ItemStack target,ItemStack item,double uu,long energy){boolean value=super.tryStoreData(target,item,uu,energy);callback.run();return value;}
    }
    private static void patternStorage68(com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory memory,CallbackMemory68 callbackMemory) {
        var registries=net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var input=new ItemStack(Items.DIAMOND_SWORD);input.set(DataComponents.CUSTOM_NAME,Component.literal("Pattern A"));input.setDamageValue(37);
        var custom=new net.minecraft.nbt.CompoundTag();custom.putLong("foreign_energy",4_000_000_003L);input.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(custom));
        var crystal=new ItemStack(memory);var foreign=new net.minecraft.nbt.CompoundTag();foreign.putString("third_party","keep");
        crystal.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(foreign));
        require(!memory.hasData(crystal),"Unrelated custom data does not falsely occupy a memory crystal");
        var original=input.copy();
        require(memory.tryStoreData(crystal,input,0.25,5_000_000_003L)&&memory.hasData(crystal),"A valid one-item pattern is stored in native item components");
        input.setDamageValue(999);var exposed=memory.getStoredItemStack(crystal);exposed.shrink(1);
        require(ItemStack.matches(original,memory.getStoredItemStack(crystal)),"Source and returned-stack mutations cannot alter the owned crystal pattern");
        var restored=ItemStack.parseOptional(registries,(net.minecraft.nbt.CompoundTag)crystal.save(registries));
        require(ItemStack.matches(original,memory.getStoredItemStack(restored))&&memory.getUuMatterCost(restored)==0.25&&memory.getEnergyCost(restored)==5_000_000_003L,
            "Registry-aware crystal save/load preserves item components and full long costs");
        // Registered item/component slot-codec coverage requires the real NeoForge registry bootstrap (R69).
        memory.clearData(crystal);
        require(!memory.hasData(crystal)&&!crystal.has(DataComponents.CONTAINER)&&crystal.get(DataComponents.CUSTOM_DATA).copyTag().getString("third_party").equals("keep"),
            "Clearing owned pattern fields preserves unrelated custom data");
        var before=crystal.copy();
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,0,Double.MAX_VALUE})
            require(!memory.tryStoreData(crystal,original,bad,42)&&ItemStack.matches(before,crystal),"Invalid UU cost is rejected without mutating a crystal");
        require(!memory.tryStoreData(crystal,original,0.25,-1)&&!memory.tryStoreData(crystal,original.copyWithCount(2),0.25,42)&&ItemStack.matches(before,crystal),
            "Negative energy and non-single-item patterns are rejected atomically");
        var stacked=crystal.copyWithCount(2);
        require(!memory.tryStoreData(stacked,original,0.25,42)&&stacked.getCount()==2,"Legacy stacked crystals are retained instead of writing multiple pattern copies");
        crystal.set(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(java.util.List.of(new ItemStack(Items.DIAMOND))));before=crystal.copy();
        require(!memory.tryStoreData(crystal,original,0.25,42)&&ItemStack.matches(before,crystal),"An unrelated container component is not overwritten");
        var legacy=new ItemStack(memory);var tag=foreign.copy();tag.putString("item_id","minecraft:stone");tag.putInt("item_count",1);tag.putDouble("uu_matter_cost_buckets",0.25);tag.putLong("energy_cost",42);
        legacy.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
        require(memory.getStoredItemStack(legacy).is(Items.STONE)&&memory.getEnergyCost(legacy)==42,"Known flat legacy crystal fields remain readable");
        tag.putInt("scex_pattern_container",99);legacy.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));before=legacy.copy();
        memory.clearData(legacy);
        require(memory.hasData(legacy)&&memory.getStoredItemStack(legacy).isEmpty()&&!memory.tryStoreData(legacy,original,0.25,42)&&ItemStack.matches(before,legacy),
            "Unknown crystal versions remain held rather than becoming free usable patterns or being overwritten");
        var bank=new PatternTile68();bank.energy(1000);
        var pattern=new com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc.ScanResult(original,0.25,5_000_000_003L);
        require(bank.storePattern(pattern)&&bank.energy()==900&&bank.getStoredCount()==1,"A stored pattern pays exactly one SI operation cost");
        pattern.item.setDamageValue(500);bank.getCurrentPattern().shrink(1);bank.getPattern(0).item.shrink(1);bank.getStoredPatterns().get(0).item.shrink(1);
        var detached=bank.getStoredPatterns();detached.clear();
        require(ItemStack.matches(bank.getCurrentPattern(),original)&&bank.getStoredCount()==1,"Every pattern-book read and write boundary owns its item stack");
        var different=original.copy();different.set(DataComponents.CUSTOM_NAME,Component.literal("Pattern B"));
        require(!bank.hasPattern(different)&&bank.storePattern(new com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc.ScanResult(different,0.5,99))&&bank.getStoredCount()==2,
            "Same item type with different components is a separate pattern");
        bank.setItem(0,new ItemStack(memory));
        require(bank.exportCurrentPattern()&&bank.energy()==700&&ItemStack.matches(memory.getStoredItemStack(bank.getItem(0)),original),"Export commits matching item data and one actual payment");
        before=bank.getItem(0).copy();
        require(!bank.exportCurrentPattern()&&bank.energy()==700&&ItemStack.matches(before,bank.getItem(0)),"Export refuses to overwrite an occupied crystal without charging");
        var receiver=new PatternTile68();receiver.energy(100);receiver.setItem(0,before);
        require(receiver.importMemoryPattern()&&receiver.energy()==0&&ItemStack.matches(receiver.getCurrentPattern(),original)&&ItemStack.matches(before,receiver.getItem(0)),
            "Import copies the pattern, preserves the crystal and pays once");
        require(receiver.importMemoryPattern()&&receiver.energy()==0&&receiver.getStoredCount()==1,"Repeated identical import is idempotent and cannot double charge");
        var saved=bank.snapshot();var loaded=new PatternTile68();loaded.loadAdditional(saved,registries);
        require(loaded.getStoredCount()==2&&ItemStack.matches(loaded.getCurrentPattern(),original)&&loaded.getCurrentEuCost()==5_000_000_003L,
            "Actual pattern book NBT preserves components, costs and selection");
        loaded.clearContent();require(loaded.isEmpty()&&loaded.getStoredCount()==2,"Dropping the memory Container does not clear the separate pattern book");
        var old=new net.minecraft.nbt.CompoundTag();var oldPatterns=new net.minecraft.nbt.ListTag();
        try{oldPatterns.add(net.minecraft.nbt.TagParser.parseTag("{energy_cost:42L,item:{components:{\"minecraft:custom_name\":'\"R68 saved-format input\"'},count:1,id:\"minecraft:stone\"},uu_matter_cost_buckets:0.25d}"));}catch(com.mojang.brigadier.exceptions.CommandSyntaxException impossible){throw new AssertionError(impossible);}
        old.put("patterns",oldPatterns);old.putInt("current_index",99);loaded.loadAdditional(old,registries);
        require(loaded.getStoredCount()==1&&loaded.getCurrentIndex()==0&&loaded.getCurrentEuCost()==42&&loaded.getCurrentPattern().getHoverName().getString().equals("R68 saved-format input"),
            "Frozen ordinary R5 pattern NBT migrates through the maintained reader");
        var invalid=old.copy();invalid.getList("patterns",10).getCompound(0).putDouble("uu_matter_cost_buckets",Double.NaN);loaded.loadAdditional(invalid,registries);loaded.energy(1000);
        require(loaded.hasUnresolvedPatterns()&&loaded.getStoredCount()==0&&!loaded.storePattern(bank.getPattern(0))&&loaded.energy()==1000,
            "Invalid legacy pattern data is retained and blocks new paid operations");
        var held=loaded.snapshot().getCompound("scex_unresolved_patterns").copy();
        for(int i=0;i<3;i++){var copy=loaded.snapshot();loaded.loadAdditional(copy,registries);require(held.equals(loaded.snapshot().getCompound("scex_unresolved_patterns")),"Held legacy data round-trips without nested growth or loss");}
        var full=new PatternTile68();full.energy(100000);
        for(int i=0;i<64;i++){var item=new ItemStack(Items.STONE);item.set(DataComponents.CUSTOM_NAME,Component.literal("key"+i));require(full.storePattern(new com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc.ScanResult(item,1,1)),"Bounded book admits an independent component identity");}
        var zero=full.getPattern(0);long energy=full.energy();
        require(full.isFull()&&full.storePattern(new com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc.ScanResult(zero.item,2,3))&&full.getStoredCount()==64&&full.energy()==energy-100,
            "A full book can update an existing identity before applying the capacity check");
        energy=full.energy();
        require(!full.storePattern(new com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc.ScanResult(new ItemStack(Items.DIAMOND),1,1))&&full.energy()==energy,
            "A full book rejects a new identity without charging");
        bank.setItem(0,new ItemStack(callbackMemory));energy=bank.energy();long originalEnergy=energy;
        callbackMemory.callback=()->bank.energy(originalEnergy-1);
        require(!bank.exportCurrentPattern()&&bank.energy()==originalEnergy-1&&!callbackMemory.hasData(bank.getItem(0)),"A staging callback changing the source balance aborts export without publishing or charging");
        callbackMemory.callback=()->bank.setItem(0,new ItemStack(memory));energy=bank.energy();
        require(!bank.exportCurrentPattern()&&bank.energy()==energy&&bank.getItem(0).is(memory),"A replaced memory slot survives the rejected staged export");
        callbackMemory.callback=()->{};bank.live=false;energy=bank.energy();
        require(!bank.exportCurrentPattern()&&!bank.removePattern(0)&&bank.energy()==energy,"Inactive book cannot perform paid actions");
        var data=bank.getContainerData();var wire=new net.minecraft.world.inventory.SimpleContainerData(dev.scex.si.processing.PatternMenuData.COUNT);
        for(int i=0;i<data.getCount();i++){
            var small=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try{
                var packet=new net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket(1,i,data.get(i));
                net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket.STREAM_CODEC.encode(small,packet);
                var decoded=net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket.STREAM_CODEC.decode(small);wire.set(decoded.getId(),decoded.getValue());
            }finally{small.release();}
        }
        require(dev.scex.si.processing.PatternMenuData.read(wire,dev.scex.si.processing.PatternMenuData.ENERGY,2)==bank.energy()
            &&dev.scex.si.processing.PatternMenuData.read(wire,dev.scex.si.processing.PatternMenuData.EU,4)==5_000_000_003L
            &&Double.longBitsToDouble(dev.scex.si.processing.PatternMenuData.read(wire,dev.scex.si.processing.PatternMenuData.UU,4))==0.25,
            "Actual signed-short menu packets preserve full energy and double cost bits");
    }

    private static void interop53() {
        nativeOutput66();
        itemEnergy59();
        cropSeed56();
        routeInvalidation55();
        var storage=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(10,2,2,com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV);
        long[] clock={0};boolean[] active={true}, input={true}, output={true};
        var ledger=new dev.scex.si.energy.FeLedger(storage,()->clock[0],()->active[0]);
        var north=ledger.port(()->input[0],()->output[0]);var south=ledger.port(()->input[0],()->output[0]);
        require(north.receiveEnergy(1,true)==1 && storage.scexExactAmount().isZero(),"FE simulation leaves the owned ledger unchanged");
        for(int i=0;i<8;i++)require(north.receiveEnergy(1,false)==1,"Single FE accepted without integer EU truncation");
        require(storage.getAmount()==2 && south.receiveEnergy(1,false)==0,"All FE faces share one per-tick allowance");
        require(north.extractEnergy(3,false)==3 && storage.scexExactAmount().equals(dev.scex.energy.EnergyAmount.fromDouble(1.25)),"Odd FE extraction retains quarter EU");
        storage.generateEnergyInternal(1,false);
        require(storage.scexExactAmount().equals(dev.scex.energy.EnergyAmount.fromDouble(2.25)),"Whole EU generation preserves existing FE remainder");
        storage.consumeEnergyInternal(1,false);
        require(south.extractEnergy(20,true)==5 && south.extractEnergy(20,false)==5 && storage.scexExactAmount().isZero(),"Extraction is shared, conservative and simulated");
        clock[0]++;input[0]=false;
        require(north.receiveEnergy(4,false)==0 && !north.canReceive(),"Disabled input rejects execution");
        input[0]=true;require(north.receiveEnergy(Integer.MAX_VALUE,false)==8,"FE receiving is rate bounded");
        storage.setOutputEnabled(false);require(north.extractEnergy(1,false)==0 && !north.canExtract(),"Redstone disables FE output");
        storage.setOutputEnabled(true);output[0]=false;require(north.extractEnergy(1,false)==0,"Input face cannot extract");
        output[0]=true;active[0]=false;require(north.receiveEnergy(1,false)==0 && north.extractEnergy(1,false)==0,"Removed or inactive owner cannot transfer");
        active[0]=true;clock[0]++;storage.setEnergy(10);
        require(north.receiveEnergy(1,false)==0,"Full FE receiver provides backpressure");
        require(dev.scex.si.energy.FeLedger.fe(dev.scex.energy.EnergyAmount.of(Long.MAX_VALUE))==Integer.MAX_VALUE,"Long EU capacity saturates FE display without multiplication overflow");
        var target=new net.neoforged.neoforge.energy.EnergyStorage(100,1,100);
        require(ledger.push(target)==1 && target.getEnergyStored()==1 && storage.scexExactAmount().equals(dev.scex.energy.EnergyAmount.fromDouble(9.75)),"Active output charges exact accepted FE");
        require(north.extractEnergy(100,false)==7,"Active sending and sided extraction share the output rate");
        clock[0]++;
        var reject=new net.neoforged.neoforge.energy.EnergyStorage(100) {
            @Override public int receiveEnergy(int offered,boolean simulate) { return simulate?offered:0; }
        };
        var before=storage.scexExactAmount();
        require(ledger.push(reject)==0 && storage.scexExactAmount().equals(before),"Execution rejection refunds the entire reservation");
        var reentrant=new net.neoforged.neoforge.energy.EnergyStorage(100) {
            @Override public int receiveEnergy(int offered,boolean simulate) {
                require(north.extractEnergy(1,false)==0 && north.receiveEnergy(1,false)==0,"External callback cannot reenter the FE ledger");
                return super.receiveEnergy(Math.min(offered,1),simulate);
            }
        };
        require(ledger.push(reentrant)==1,"Reentrant target receives only the offered unit");
        var faulty=new net.neoforged.neoforge.energy.EnergyStorage(100) {
            @Override public int receiveEnergy(int offered,boolean simulate) {
                int accepted=super.receiveEnergy(offered,simulate);if(!simulate)throw new IllegalStateException("after accept");return accepted;
            }
        };
        boolean threw=false;try{ledger.push(faulty);}catch(IllegalStateException expected){threw=true;}
        require(threw && ledger.uncertainOutput()>0 && ledger.push(target)==0,"Unknown external output is held and cannot be replayed");
        var restored=new dev.scex.si.energy.FeLedger(storage,()->clock[0],()->true);
        restored.loadUncertainOutput(ledger.uncertainOutput());clock[0]++;
        require(restored.extract(100,false)==0,"Persisted uncertain output remains held after reload");

        var registries=net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty());
        var pipe=new PipeTile53();var handler=pipe.getItemHandlerCapability(net.minecraft.core.Direction.NORTH);
        ItemStack named=new ItemStack(Items.STONE,60);named.set(DataComponents.CUSTOM_NAME,Component.literal("A"));
        require(handler==pipe.getItemHandlerCapability(net.minecraft.core.Direction.NORTH),"Pipe capability objects are reused");
        require(handler.insertItem(0,named,true).isEmpty() && pipe.isEmpty(),"Pipe insertion simulation performs no movement");
        require(handler.insertItem(0,named,false).isEmpty() && pipe.getBufferItem().getCount()==60,"Insertion stays in one owned buffer");
        require(handler.insertItem(0,named,false).getCount()==56 && pipe.getBufferItem().getCount()==64,"Pipe buffer has a bounded stack limit");
        require(handler.insertItem(0,new ItemStack(Items.STONE),false).getCount()==1,"Different components are not merged");
        var copy=handler.getStackInSlot(0);copy.setCount(1);require(pipe.getBufferItem().getCount()==64,"Capability reads cannot mutate the buffer");
        require(handler.extractItem(0,4,true).getCount()==4 && pipe.getBufferItem().getCount()==64,"Extraction simulation is read only");
        pipe.put(new ItemStack(Items.DIAMOND,16));
        var refusing=new net.neoforged.neoforge.items.ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot,ItemStack stack,boolean simulate){return stack;}
        };
        require(pipe.send(refusing,16)==0 && pipe.getBufferItem().getCount()==16,"Entire downstream rejection preserves every extracted item");
        var partial=new net.neoforged.neoforge.items.ItemStackHandler(1);
        partial.setStackInSlot(0,new ItemStack(Items.DIAMOND,60));
        require(pipe.send(partial,16)==4 && pipe.getBufferItem().getCount()==12 && partial.getStackInSlot(0).getCount()==64,"Partial downstream rejection preserves remainder");
        var saved=pipe.saved(registries);var pipe2=new PipeTile53();pipe2.loadAdditional(saved,registries);
        require(pipe2.getBufferItem().getCount()==12,"Pipe buffer survives normal NBT round trip");
        pipe.put(new ItemStack(Items.DIAMOND,256));
        var overflowTarget=new net.neoforged.neoforge.items.ItemStackHandler(1){ @Override public int getSlotLimit(int slot){return 1000;} };
        require(pipe.send(overflowTarget,256)==64 && pipe.getBufferItem().getCount()==192,"Historical oversized buffers drain at the new bounded transfer rate without truncation");
        require(handler.extractItem(0,256,true).getCount()==64,"Pipe extraction respects the item maximum stack size");
        pipe2.live=false;require(pipe2.getItemHandlerCapability(null).extractItem(0,1,false).isEmpty(),"Stale pipe capability rejects extraction");

        var positions=new java.util.HashSet<net.minecraft.core.BlockPos>();
        for(int i=0;i<=600;i++)positions.add(new net.minecraft.core.BlockPos(i,0,0));
        // A loop and dead end must not capture the item or cause recursive calls.
        positions.add(new net.minecraft.core.BlockPos(0,0,1));positions.add(new net.minecraft.core.BlockPos(1,0,1));
        var endpoint=new net.neoforged.neoforge.items.ItemStackHandler(4096);
        for(int i=0;i<4095;i++)endpoint.setStackInSlot(i,new ItemStack(Items.COBBLESTONE,64));
        long[] revision={0};
        var access=new dev.scex.si.processing.ItemPipeRoute.Access() {
            @Override public net.minecraft.core.BlockPos pipe(net.minecraft.core.BlockPos from,net.minecraft.core.Direction side){
                var next=from.relative(side);return positions.contains(from)&&positions.contains(next)?next:null;
            }
            @Override public net.neoforged.neoforge.items.IItemHandler inventory(net.minecraft.core.BlockPos from,net.minecraft.core.Direction side){
                return positions.contains(from)&&from.equals(new net.minecraft.core.BlockPos(600,0,0))&&side==net.minecraft.core.Direction.EAST?endpoint:null;
            }
            @Override public long revision(){return revision[0];}
        };
        var route=new dev.scex.si.processing.ItemPipeRoute(new net.minecraft.core.BlockPos(0,0,1),null,new ItemStack(Items.DIAMOND));
        dev.scex.si.processing.ItemPipeRoute.Target found=null;int iterations=0;
        while(found==null && !route.exhausted() && iterations++<400){
            long work=route.work();found=route.advance(access,32);require(route.work()-work<=32,"Large route consumes at most its assigned work budget");
        }
        require(found!=null && found.slot()==4095 && route.visited()==positions.size(),"Incremental routing escapes dead end and loop and reaches a distant large inventory");
        positions.remove(new net.minecraft.core.BlockPos(300,0,0));revision[0]++;
        found=null;iterations=0;
        while(!route.exhausted() && iterations++<400)found=route.advance(access,64);
        require(found==null && route.exhausted(),"Removed path invalidates a cached target and cannot teleport through the gap");
        positions.add(new net.minecraft.core.BlockPos(300,0,0));revision[0]++;
        found=null;iterations=0;
        while(found==null && iterations++<400)found=route.advance(access,64);
        require(found!=null,"Reconnected pipe path resumes delivery search");
        var excluded=new dev.scex.si.processing.ItemPipeRoute(net.minecraft.core.BlockPos.ZERO,new net.minecraft.core.BlockPos(601,0,0),new ItemStack(Items.DIAMOND));
        found=null;iterations=0;
        while(!excluded.exhausted() && iterations++<400)found=excluded.advance(access,64);
        require(found==null && excluded.exhausted(),"Input never returns an item to its source container");
    }
    private static void cropSeed56() {
        var bag=new ItemStack(Items.PAPER,64);
        var data=new net.minecraft.nbt.CompoundTag();data.putString("owner_note","retain me");
        var nested=new net.minecraft.nbt.CompoundTag();nested.putLong("credit",Long.MAX_VALUE);data.put("other_mod",nested);
        bag.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(data));
        bag.set(DataComponents.CUSTOM_NAME,Component.literal("seed bag"));
        var before=bag.copy();
        require(dev.scex.si.processing.CropSeedData.isEmptyBag(bag),"A bag with unrelated custom data remains usable");
        var filled=dev.scex.si.processing.CropSeedData.fillOne(bag,"missing_crop_provider","saved_crop",15,22,31);
        require(filled.getCount()==1 && ItemStack.matches(bag,before),"Filling one bag never transforms the other 63 or changes the source");
        var tag=dev.scex.si.processing.CropSeedData.read(filled);
        require(tag.getString("owner_note").equals("retain me") && tag.getCompound("other_mod").equals(nested)
            && filled.get(DataComponents.CUSTOM_NAME).equals(bag.get(DataComponents.CUSTOM_NAME)),"Filled seed retains unrelated data and components");
        require(!dev.scex.si.processing.CropSeedData.isEmptyBag(filled),"A saved identity stays occupied without its provider");
        require(com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.getPlantType(filled)==null,"Missing provider fixture has no registered crop");
        var unknownBefore=filled.copy();
        require(dev.scex.si.processing.CropSeedData.fillOne(filled,"mio_icif","another_crop",0,0,0).isEmpty()
            && ItemStack.matches(filled,unknownBefore),"Missing-mod seed cannot be overwritten as an empty bag");
        var registries=net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty());
        var reloaded=ItemStack.parse(registries,filled.save(registries)).orElseThrow();
        require(ItemStack.matches(filled,reloaded) && !dev.scex.si.processing.CropSeedData.isEmptyBag(reloaded),"Unknown crop identity and metadata survive item NBT round trip");
        for(String key:new String[]{"PlantModId","PlantId"}) {
            for(boolean wrongType:new boolean[]{false,true}) {
                var malformed=bag.copy();var malformedTag=dev.scex.si.processing.CropSeedData.read(malformed);
                if(wrongType)malformedTag.putInt(key,7);else malformedTag.putString(key,"");
                malformed.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(malformedTag));
                require(!dev.scex.si.processing.CropSeedData.isEmptyBag(malformed)
                    && dev.scex.si.processing.CropSeedData.fillOne(malformed,"mio_icif","wheat",0,0,0).isEmpty(),
                    "Partial or malformed saved crop identity is retained instead of overwritten");
            }
        }
        var bounded=com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.createSeedStack(Items.PAPER,"mio_icif","wheat",Integer.MAX_VALUE,-1,32);
        require(com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.getGrowthFromStack(bounded)==31
            && com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.getGainFromStack(bounded)==0
            && com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.getResistanceFromStack(bounded)==31,"New seeds honor the public planter trait range 0..31");
        var originalTag=dev.scex.si.processing.CropSeedData.read(filled);originalTag.putInt("ScanLevel",Integer.MAX_VALUE);originalTag.putInt("GrowthSpeed",-20);
        filled.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(originalTag));
        require(com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.getScanLevel(filled)==4
            && com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.getGrowthFromStack(filled)==0
            && dev.scex.si.processing.CropSeedData.read(filled).equals(originalTag),"Read-time bounds do not destroy stored legacy data");
        com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.incrementScanLevel(filled);
        require(com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.getScanLevel(filled)==4
            && dev.scex.si.processing.CropSeedData.read(filled).getString("PlantId").equals("saved_crop"),"Increment saturates scan level without overflow or identity loss");
        com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.setScanLevel(filled,-50);
        require(com.singularity_iteration.mio_icif.Items.Crop.CropSeedItem.getScanLevel(filled)==0
            && dev.scex.si.processing.CropSeedData.read(filled).getCompound("other_mod").equals(nested),"Scan updates preserve unrelated nested metadata");
        require(dev.scex.si.processing.CropSeedData.fillOne(ItemStack.EMPTY,"mio_icif","wheat",0,0,0).isEmpty()
            && dev.scex.si.processing.CropSeedData.fillOne(bag,"","wheat",0,0,0).isEmpty(),"Empty input and missing target identity do not mint a seed");
    }
    private static void routeInvalidation55() {
        var origin=net.minecraft.core.BlockPos.ZERO;
        var positions=new java.util.HashSet<net.minecraft.core.BlockPos>();
        var nodes=new java.util.HashMap<net.minecraft.core.BlockPos,PipeTile53>();
        for(int x=0;x<=600;x++){var pos=new net.minecraft.core.BlockPos(x,0,0);positions.add(pos);nodes.put(pos,new PipeTile53());}
        long[] global={0};
        var endpoint=new net.neoforged.neoforge.items.ItemStackHandler(1);
        var route=new dev.scex.si.processing.ItemPipeRoute(origin,null,new ItemStack(Items.DIAMOND));
        var current=new dev.scex.si.processing.ItemPipeRoute[]{route};
        var watch=new boolean[]{false};
        var scoped=new boolean[]{false};
        var access=new dev.scex.si.processing.ItemPipeRoute.Access() {
            private void observe(net.minecraft.core.BlockPos pos){if(watch[0] && nodes.containsKey(pos))nodes.get(pos).watch(current[0]);}
            @Override public net.minecraft.core.BlockPos pipe(net.minecraft.core.BlockPos from,net.minecraft.core.Direction side){
                var next=from.relative(side);observe(from);observe(next);return positions.contains(from)&&positions.contains(next)?next:null;
            }
            @Override public net.neoforged.neoforge.items.IItemHandler inventory(net.minecraft.core.BlockPos from,net.minecraft.core.Direction side){
                observe(from);return positions.contains(from)&&from.getX()==600&&side==net.minecraft.core.Direction.EAST?endpoint:null;
            }
            @Override public long revision(){return scoped[0]?0:global[0];}
        };
        dev.scex.si.processing.ItemPipeRoute.Target found=null;
        for(int i=0;i<200;i++){global[0]++;found=route.advance(access,64);}
        require(found==null && route.visited()<20,"R53 global revision reproduces long-route starvation under unrelated updates");
        route=new dev.scex.si.processing.ItemPipeRoute(origin,null,new ItemStack(Items.DIAMOND));current[0]=route;watch[0]=scoped[0]=true;
        var unrelated=new PipeTile53();int iterations=0;
        while(found==null && iterations++<200){
            global[0]++;unrelated.markForUpdate();long work=route.work();found=route.advance(access,64);
            require(route.work()-work<=64,"Scoped invalidation keeps the existing step budget");
        }
        require(found!=null && found.pipe().getX()==600,"Long route completes while disconnected pipe changes every simulated tick");
        // Changes to a node inspected by the route still invalidate its full path.
        var gap=new net.minecraft.core.BlockPos(300,0,0);positions.remove(gap);nodes.get(gap).markForUpdate();
        found=null;iterations=0;
        while(!route.exhausted() && iterations++<200)found=route.advance(access,64);
        require(found==null && route.exhausted(),"Observed broken node invalidates target and forbids delivery across a gap");
        positions.add(gap);nodes.get(gap.west()).markForUpdate();
        found=null;iterations=0;
        while(found==null && iterations++<200)found=route.advance(access,64);
        require(found!=null,"Observed reconnection reopens an exhausted route");
        // Public connection changes must invalidate observers even without a world callback.
        nodes.get(gap).setConnection(net.minecraft.core.Direction.EAST,true);
        require(route.advance(access,1)==null && route.visited()<3,"Direct connection changes invalidate watched route");
        // A search restart abandons the previous dependency generation. Old watches
        // cannot keep invalidating a fresh search that no longer visits that branch.
        var retired=new dev.scex.si.processing.ItemPipeRoute.Watch();retired.observe(route);
        var startWatch=new dev.scex.si.processing.ItemPipeRoute.Watch();startWatch.observe(route);startWatch.changed();
        require(route.advance(access,1)==null,"Watched change starts a new search generation");
        found=null;iterations=0;
        while(found==null && iterations++<200){retired.changed();found=route.advance(access,64);}
        require(found!=null,"Retired dependency generation cannot starve a restarted search");
        // The last accepting simulation is foreign code: it can invalidate a
        // previously checked path in the same call and must not return a target.
        var afterQuote=new dev.scex.si.processing.ItemPipeRoute(origin,null,new ItemStack(Items.DIAMOND));
        var quoteWatch=new dev.scex.si.processing.ItemPipeRoute.Watch();int[] quotes={0};
        var mutating=new net.neoforged.neoforge.items.ItemStackHandler(1){
            @Override public ItemStack insertItem(int slot,ItemStack stack,boolean simulate){
                if(++quotes[0]==2)quoteWatch.changed();return ItemStack.EMPTY;
            }
        };
        var oneNode=new dev.scex.si.processing.ItemPipeRoute.Access(){
            @Override public net.minecraft.core.BlockPos pipe(net.minecraft.core.BlockPos from,net.minecraft.core.Direction side){quoteWatch.observe(afterQuote);return null;}
            @Override public net.neoforged.neoforge.items.IItemHandler inventory(net.minecraft.core.BlockPos from,net.minecraft.core.Direction side){return side==net.minecraft.core.Direction.DOWN?mutating:null;}
            @Override public long revision(){return 0;}
        };
        require(afterQuote.advance(oneNode,1)==null,"Candidate discovery stays separate from final validation");
        require(afterQuote.advance(oneNode,1)==null && quotes[0]==2,"Topology mutation inside final simulation rejects the candidate");
        require(afterQuote.advance(oneNode,3)!=null,"Unchanged subsequent attempt can recover after rejected mutation");
    }
    private static final class Inventory extends MachineItemHandler {
        Runnable observe;
        Inventory() { this(2); }
        Inventory(int count) { super(SlotLayout.builder().extra(count).build()); }
        @Override protected void onContentsChanged(int slot) { if (observe != null) observe.run(); }
    }
    private static final class HeatTile extends com.singularity_iteration.mio_icif.Blocks.entity.HUEntity.mio_icif_HeatU_Block {
        HeatTile() {
            super(net.minecraft.world.level.block.entity.BlockEntityType.FURNACE, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(), 1000, 0, 100, 20, 1000, 0);
        }
        boolean reviewedStorage() { return heatStorage instanceof dev.scex.si.energy.PlatformHeatStorage; }
        void storageUpgrades(int count) {
            upgradeStats = upgradeStats(0, count, 0, java.util.List.of());
            applyHeatCapacityUpgrades();
        }
        net.minecraft.nbt.CompoundTag saved(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new net.minecraft.nbt.CompoundTag(); saveAdditional(tag, registries); return tag;
        }
    }
    private static final class KineticTile extends com.singularity_iteration.mio_icif.Blocks.entity.KUEntity.mio_icif_KineticU_Block {
        KineticTile() {
            super(net.minecraft.world.level.block.entity.BlockEntityType.FURNACE, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(), 1000, 0, 100, 10000, .005F);
        }
        boolean reviewedStorage() { return kineticStorage instanceof dev.scex.si.energy.PlatformKineticStorage; }
    }
    private static final class Battery59 extends com.singularity_iteration.mio_icif.api.item.AbstractBattery {
        Battery59() { super(new net.minecraft.world.item.Item.Properties(), Long.MAX_VALUE, 0, 100, 64); }
    }
    private static class Tool59 extends com.singularity_iteration.mio_icif.api.item.AbstractElectricTool {
        Tool59() { super(new net.minecraft.world.item.Item.Properties(), Long.MAX_VALUE, 0, 100, 10, 1); }
        @Override public long getEnergy(ItemStack stack) { return energy59(stack); }
        @Override public void setEnergy(ItemStack stack,long value) { setEnergy59(stack,value); }
        @Override public long getMaxEnergy(ItemStack stack) {
            var data=stack.get(DataComponents.CUSTOM_DATA);
            return data!=null&&data.contains("r59_capacity")?data.copyTag().getLong("r59_capacity"):Long.MAX_VALUE;
        }
    }
    private static final class PartialTool59 extends Tool59 {
        @Override public long extractEnergy(ItemStack stack,long amount) {
            long before=getEnergy(stack),removed=Math.min(before,Math.min(3,Math.max(0,amount)));
            setEnergy(stack,before-removed);stack.remove(DataComponents.CUSTOM_NAME);return removed;
        }
    }
    private static final class Armor59 extends com.singularity_iteration.mio_icif.api.item.AbstractElectricArmor {
        Armor59() { super(net.minecraft.world.item.ArmorMaterials.LEATHER,net.minecraft.world.item.ArmorItem.Type.CHESTPLATE,
            new net.minecraft.world.item.Item.Properties(),Long.MAX_VALUE,0,100,0,1,"scex_contract/test"); }
        @Override public long getEnergy(ItemStack stack) { return energy59(stack); }
        @Override public void setEnergy(ItemStack stack,long value) { setEnergy59(stack,value); }
    }
    private static final class NormalTool59 extends com.singularity_iteration.mio_icif.api.item.AbstractElectricTool {
        NormalTool59() { super(new net.minecraft.world.item.Item.Properties(),1000,0,100,10,1); }
    }
    private static final class ThrowingTool59 extends Tool59 {
        @Override public long addEnergy(ItemStack stack,long amount) {
            super.addEnergy(stack,amount);throw new IllegalStateException("R59 receiver failed after staging a credit");
        }
    }
    private static final class NormalArmor59 extends com.singularity_iteration.mio_icif.api.item.AbstractElectricArmor {
        NormalArmor59() { super(net.minecraft.world.item.ArmorMaterials.LEATHER,net.minecraft.world.item.ArmorItem.Type.CHESTPLATE,
            new net.minecraft.world.item.Item.Properties(),1000,0,100,0,1,"scex_contract/test"); }
    }
    private static final class PartialArmor60 extends com.singularity_iteration.mio_icif.api.item.AbstractElectricArmor {
        PartialArmor60() { super(net.minecraft.world.item.ArmorMaterials.LEATHER,net.minecraft.world.item.ArmorItem.Type.CHESTPLATE,
            new net.minecraft.world.item.Item.Properties(),1000,0,100,0,1,"scex_contract/test"); }
        @Override public long addEnergy(ItemStack stack,long amount) { return super.addEnergy(stack,Math.min(3,amount)); }
    }
    private static final class CallbackTool61 extends Tool59 {
        Runnable callback;
        @Override public long addEnergy(ItemStack stack,long amount) {
            long accepted=super.addEnergy(stack,amount);if(callback!=null)callback.run();return accepted;
        }
    }
    private static final class CallbackDischarge62 extends Tool59 {
        Runnable callback;
        @Override public long extractEnergy(ItemStack stack,long amount) {
            long removed=super.extractEnergy(stack,amount);if(callback!=null)callback.run();return removed;
        }
    }
    private static final class FeSource62 implements net.neoforged.neoforge.energy.IEnergyStorage {
        private final ItemStack stack;
        int limit=3;boolean unchanged,invalid;Runnable callback;
        FeSource62(ItemStack stack){this.stack=stack;}
        @Override public int receiveEnergy(int amount,boolean simulate){return 0;}
        @Override public int extractEnergy(int amount,boolean simulate){
            int removed=Math.min(Math.max(0,amount),Math.min(limit,getEnergyStored()));
            if(!simulate){if(!unchanged)setEnergy59(stack,getEnergyStored()-removed);if(callback!=null)callback.run();}
            return invalid?amount+1:removed;
        }
        @Override public int getEnergyStored(){return (int)energy59(stack);}
        @Override public int getMaxEnergyStored(){return 10000;}
        @Override public boolean canReceive(){return false;}
        @Override public boolean canExtract(){return true;}
    }
    private static long energy59(ItemStack stack) {
        var data=stack.get(DataComponents.CUSTOM_DATA);return data==null?0:data.copyTag().getLong("r59_energy");
    }
    private static void setEnergy59(ItemStack stack,long value) {
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        tag.putLong("r59_energy",value);stack.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
    }
    private static void itemEnergy59() {
        // Public NeoForge unfreeze entrypoint, followed by normal registry freeze; no reflection or field mutation.
        net.neoforged.neoforge.registries.GameData.unfreezeData();
        var battery=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","battery59"),new Battery59());
        var tool=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","tool59"),new Tool59());
        var partial=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","partial_tool59"),new PartialTool59());
        var armor=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","armor59"),new Armor59());
        var normalTool=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","normal_tool59"),new NormalTool59());
        var normalArmor=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","normal_armor59"),new NormalArmor59());
        var throwing=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","throwing_tool59"),new ThrowingTool59());
        var partialArmor=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","partial_armor60"),new PartialArmor60());
        var callbackTool=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","callback_tool61"),new CallbackTool61());
        var callbackDischarge=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","callback_discharge62"),new CallbackDischarge62());
        var advanced=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","advanced_helmet60"),new com.singularity_iteration.mio_icif.Items.Armor.mio_icif_advanced_solar_helmet(net.minecraft.world.item.ArmorMaterials.DIAMOND,new net.minecraft.world.item.Item.Properties()));
        var hybrid=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","hybrid_helmet60"),new com.singularity_iteration.mio_icif.Items.Armor.mio_icif_hybrid_solar_helmet(net.minecraft.world.item.ArmorMaterials.DIAMOND,new net.minecraft.world.item.Item.Properties()));
        var ultimate=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","ultimate_helmet60"),new com.singularity_iteration.mio_icif.Items.Armor.mio_icif_ultimate_solar_helmet(net.minecraft.world.item.ArmorMaterials.NETHERITE,new net.minecraft.world.item.Item.Properties()));
        var memory68=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","memory68"),new com.singularity_iteration.mio_icif.Items.Resource.mio_icif_memory(new net.minecraft.world.item.Item.Properties()));
        var callbackMemory68=net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("scex_contract","callback_memory68"),new CallbackMemory68());
        for(var registry:net.minecraft.core.registries.BuiltInRegistries.REGISTRY) registry.freeze();
        patternStorage68(memory68,callbackMemory68);
        solarHelmets60(java.util.List.of(advanced,hybrid,ultimate),normalTool,partialArmor,battery);
        storageCharging61(normalTool,partialArmor,battery,armor,throwing,callbackTool);
        machineInput62(battery,partial,callbackDischarge);
        var api=new com.singularity_iteration.mio_icif.api.item.ItemAPIImpl();
        for(var item:java.util.List.<com.singularity_iteration.mio_icif.api.item.IBatteryItem>of(normalTool,normalArmor)) {
            var stack=new ItemStack((net.minecraft.world.item.Item)item);
            require(item.getEnergy(stack)==0,"Default representable durability item is empty");
            item.setEnergy(stack,400);var before=stack.copy();
            require(item.addEnergy(stack,Long.MIN_VALUE)==0&&item.extractEnergy(stack,-1)==0&&ItemStack.matches(stack,before),"Actual durability-backed bases reject inverse requests without mutation");
            require(item.addEnergy(stack,Long.MAX_VALUE)==600&&item.getEnergy(stack)==1000,"Actual durability-backed bases saturate positive overflow safely");
            require(item.extractEnergy(stack,Long.MAX_VALUE)==1000&&item.getEnergy(stack)==0,"Actual durability-backed bases debit only their real energy");
            require(item.getEnergy(ItemStack.EMPTY)==0,"Empty stack is not a charged durable item");
        }
        for(var item:java.util.List.<com.singularity_iteration.mio_icif.api.item.IBatteryItem>of(battery,tool,armor)) {
            var stack=new ItemStack((net.minecraft.world.item.Item)item);item.setEnergy(stack,500);
            stack.set(DataComponents.CUSTOM_NAME,Component.literal("preserve name"));
            for(long request:new long[]{Long.MIN_VALUE,-1,0}) {
                var before=stack.copy();
                require(item.addEnergy(stack,request)==0 && item.extractEnergy(stack,request)==0,"Nonpositive direct item request does not invert energy");
                require(api.chargeBattery(stack,request,false)==0 && api.dischargeBattery(stack,request,false)==0,"Nonpositive public item request reports zero");
                require(ItemStack.matches(stack,before),"Rejected item operations leave all components unchanged");
            }
            require(item.addEnergy(stack,Long.MAX_VALUE)==Long.MAX_VALUE-500 && item.getEnergy(stack)==Long.MAX_VALUE,"Large addition subtracts capacity before adding, without signed overflow");
            var full=stack.copy();
            require(api.dischargeBattery(stack,Long.MAX_VALUE,true)==Long.MAX_VALUE && ItemStack.matches(stack,full),"Large discharge simulation is read only");
            require(api.dischargeBattery(stack,Long.MAX_VALUE,false)==Long.MAX_VALUE && item.getEnergy(stack)==0,"Battery API reports the complete actual long debit");
            require(api.chargeBattery(stack,Long.MAX_VALUE,false)==Long.MAX_VALUE && item.getEnergy(stack)==Long.MAX_VALUE,"Battery API reports actual long credit");
            require(stack.getHoverName().getString().equals("preserve name"),"Energy operations retain unrelated components");
            stack.setCount(2);var multiple=stack.copy();
            require(api.dischargeBattery(stack,1,false)==0&&api.chargeBattery(stack,1,false)==0&&item.extractEnergy(stack,1)==0,"Energy transfer does not multiply a per-item change across a stack");
            require(ItemStack.matches(stack,multiple),"Multiple-item stack remains intact for splitting");
        }
        var toolStack=new ItemStack(tool);tool.setEnergy(toolStack,Long.MAX_VALUE);
        require(api.dischargeElectricTool(toolStack,3_000_000_000L,false)==3_000_000_000L&&tool.getEnergy(toolStack)==Long.MAX_VALUE-3_000_000_000L,"Tool API no longer caps the executed debit to int while claiming a long");
        require(api.chargeElectricTool(toolStack,Long.MAX_VALUE,false)==3_000_000_000L,"Tool API returns its actual credit");
        var armorStack=new ItemStack(armor);armor.setEnergy(armorStack,Long.MAX_VALUE);
        require(api.dischargeElectricArmor(armorStack,4_000_000_000L,false)==4_000_000_000L&&armor.getEnergy(armorStack)==Long.MAX_VALUE-4_000_000_000L,"Armor API no longer claims more energy than it debits");
        require(api.chargeElectricArmor(armorStack,Long.MAX_VALUE,false)==4_000_000_000L,"Armor API returns its actual credit");
        tool.setEnergy(toolStack,10);var tag=toolStack.getOrDefault(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        tag.putLong("r59_capacity",15);toolStack.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
        require(api.getBatteryCapacity(toolStack)==15&&api.getElectricToolMaxEnergy(toolStack)==15,"Capacity queries honor the actual stack");
        require(api.chargeElectricTool(toolStack,Long.MAX_VALUE,false)==5&&tool.getEnergy(toolStack)==15,"Charging honors stack-specific capacity");
        require(api.isBatteryFull(toolStack)&&tool.isFull(toolStack),"Direct and public full checks agree with the actual stack capacity");
        var partialStack=new ItemStack(partial);partial.setEnergy(partialStack,20);partialStack.set(DataComponents.CUSTOM_NAME,Component.literal("must remain"));
        var original=partialStack.copy();
        require(!partial.consumeEnergy(partialStack,10)&&ItemStack.matches(partialStack,original),"Failed paid action does not commit a partial debit or component removal");
        require(partial.consumeEnergy(partialStack,3)&&partial.getEnergy(partialStack)==17&&!partialStack.has(DataComponents.CUSTOM_NAME),"Exact paid action commits all staged component changes including removal");
        require(api.dischargeElectricTool(partialStack,10,false)==3&&partial.getEnergy(partialStack)==14,"Public API returns a partial provider's actual debit");
        original=partialStack.copy();require(!partial.consumeEnergy(partialStack,-1)&&ItemStack.matches(partialStack,original),"Negative action cost is rejected");
        require(armor.consumeEnergy(armorStack,100)&&armor.getEnergy(armorStack)==Long.MAX_VALUE-100,"Armor paid action commits an exact debit");
        original=armorStack.copy();require(!armor.consumeEnergy(armorStack,-1)&&ItemStack.matches(armorStack,original),"Negative armor cost cannot add energy");
        var supply=new ItemStack(battery);battery.setEnergy(supply,1000);var destination=new ItemStack(normalArmor);
        require(dev.scex.si.energy.BatteryTransfer.isEquipment(destination)&&dev.scex.si.energy.BatteryTransfer.isEquipment(toolStack)
            &&!dev.scex.si.energy.BatteryTransfer.isEquipment(supply),"Manual charging includes electric armor as well as tools, without charging ordinary batteries");
        require(dev.scex.si.energy.BatteryTransfer.move(supply,battery,destination,normalArmor,120)==120
            &&battery.getEnergy(supply)==880&&normalArmor.getEnergy(destination)==120,"Manual equipment charging debits the source and credits armor equally");
        var sourceBefore=supply.copy();var targetBefore=destination.copy();supply.setCount(2);var multipleSupply=supply.copy();
        require(dev.scex.si.energy.BatteryTransfer.move(supply,battery,destination,normalArmor,100)==0
            &&ItemStack.matches(multipleSupply,supply)&&ItemStack.matches(targetBefore,destination),"Stacked source cannot give free power after rejected source extraction");
        supply.setCount(1);require(ItemStack.matches(sourceBefore,supply),"Splitting the source restores the same per-item state");
        var partialBefore=partialStack.copy();targetBefore=destination.copy();
        require(dev.scex.si.energy.BatteryTransfer.move(partialStack,partial,destination,normalArmor,10)==0
            &&ItemStack.matches(partialBefore,partialStack)&&ItemStack.matches(targetBefore,destination),"A source unable to supply the whole quoted charge leaves both original items unchanged");
        var faulty=new ItemStack(throwing);sourceBefore=supply.copy();var faultyBefore=faulty.copy();boolean failed=false;
        try {dev.scex.si.energy.BatteryTransfer.move(supply,battery,faulty,throwing,10);}
        catch(IllegalStateException expected) {failed=expected.getMessage().equals("R59 receiver failed after staging a credit");}
        require(failed&&ItemStack.matches(sourceBefore,supply)&&ItemStack.matches(faultyBefore,faulty),"Receiver failure after modifying its staged item does not mint power in the original items");
        var provider=net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var restored=ItemStack.parseOptional(provider,(net.minecraft.nbt.CompoundTag)toolStack.save(provider));
        require(tool.getEnergy(restored)==15&&api.getBatteryCapacity(restored)==15,"Exact item component values survive serialized round trip");
        var inventory=new ItemStack[258];java.util.Arrays.fill(inventory,ItemStack.EMPTY);
        inventory[0]=new ItemStack(Items.DIAMOND);inventory[200]=new ItemStack(Items.EMERALD,2);inventory[257]=new ItemStack(Items.GOLD_INGOT,3);
        var container=new ItemStack(Items.PAPER);container.set(DataComponents.CUSTOM_NAME,Component.literal("held inventory"));
        com.singularity_iteration.mio_icif.api.item.AbstractElectricTool.saveHandHeldInventory(container,inventory,provider);
        java.util.Arrays.fill(inventory,new ItemStack(Items.STONE,7));
        com.singularity_iteration.mio_icif.api.item.AbstractElectricTool.loadHandHeldInventory(container,inventory,provider);
        require(inventory[0].is(Items.DIAMOND)&&inventory[200].getCount()==2&&inventory[257].getCount()==3,"Handheld inventory retains wide slot indices");
        require(inventory[1].isEmpty()&&container.getHoverName().getString().equals("held inventory"),"Loading clears stale slots and preserves container components");
        var legacy=new net.minecraft.nbt.CompoundTag();var entries=new net.minecraft.nbt.ListTag();var entry=new net.minecraft.nbt.CompoundTag();
        entry.putByte("Slot",(byte)200);entry.put("Item",new ItemStack(Items.GOLD_INGOT).save(provider));entries.add(entry);legacy.put("HandHeldItems",entries);
        container.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(legacy));
        com.singularity_iteration.mio_icif.api.item.AbstractElectricTool.loadHandHeldInventory(container,inventory,provider);
        require(inventory[200].is(Items.GOLD_INGOT)&&inventory[0].isEmpty()&&inventory[257].isEmpty(),"Old unsigned byte slots load without stale contents");
        com.singularity_iteration.mio_icif.api.item.AbstractElectricTool.loadHandHeldInventory(ItemStack.EMPTY,inventory,provider);
        require(java.util.Arrays.stream(inventory).allMatch(ItemStack::isEmpty),"An empty source also clears a reused handheld array");
    }
    private static void machineInput62(Battery59 battery,PartialTool59 partial,CallbackDischarge62 callbackItem) {
        var machine=dev.scex.si.energy.MachineItemDischarging.InputKind.MACHINE;
        var box=dev.scex.si.energy.MachineItemDischarging.InputKind.STORAGE;
        var storage=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(100,10,10,com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV);
        long[] clock={0};boolean[] active={true};var ledger=new dev.scex.si.energy.FeLedger(storage,()->clock[0],()->active[0]);
        var inventory=new Inventory(1);var source=new ItemStack(battery);battery.setEnergy(source,100);inventory.setStackInSlot(0,source);
        require(ledger.receiveWholeEu(-1,false)==0&&ledger.receiveWholeEu(0,false)==0&&storage.getAmount()==0,"Nonpositive whole EU input does not create energy or spend quota");
        require(ledger.receive(1,false)==1&&ledger.receiveWholeEu(100,true)==9,"FE block input and whole EU item input share the receiving allowance");
        require(dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,machine)
            &&battery.getEnergy(inventory.getStackInSlot(0))==91&&storage.scexExactAmount().equals(dev.scex.energy.EnergyAmount.fromDouble(9.25)),"Machine publishes exactly the paid battery debit and retains FE fraction");
        require(ledger.receive(100,false)==3&&ledger.receiveWholeEu(1,false)==0&&storage.getAmount()==10,"Remaining fractional FE input cannot be rounded into another EU");
        clock[0]++;storage.setEnergy(0);source=new ItemStack(partial);partial.setEnergy(source,20);source.set(DataComponents.CUSTOM_NAME,Component.literal("paid"));inventory.setStackInSlot(0,source);
        require(dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,machine)
            &&partial.getEnergy(inventory.getStackInSlot(0))==17&&storage.getAmount()==3&&!inventory.getStackInSlot(0).has(DataComponents.CUSTOM_NAME),"Partial SI extraction credits only three EU and preserves the staged component removal");
        var before=inventory.getStackInSlot(0).copy();
        require(dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,box)
            &&ItemStack.matches(before,inventory.getStackInSlot(0))&&storage.getAmount()==3,"Storage keeps the existing SI tool and armor discharge exclusion");
        source=new ItemStack(battery,2);battery.setEnergy(source,100);inventory.setStackInSlot(0,source);before=source.copy();
        dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,machine);
        require(ItemStack.matches(before,inventory.getStackInSlot(0))&&storage.getAmount()==3,"Multiple source items cannot duplicate one paid battery debit");
        clock[0]++;storage.setEnergy(99);storage.scexLoadFraction(dev.scex.energy.EnergyAmount.UNITS*3/4);source=new ItemStack(battery);battery.setEnergy(source,100);inventory.setStackInSlot(0,source);
        dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,machine);
        require(battery.getEnergy(inventory.getStackInSlot(0))==100&&storage.scexExactAmount().equals(dev.scex.energy.EnergyAmount.fromDouble(99.75)),"Quarter EU free space cannot consume one whole EU from an SI battery");
        require(ledger.receive(1,false)==1&&storage.getAmount()==100,"The remaining quarter EU is still usable by FE");
        inventory.setStackInSlot(0,new ItemStack(Items.REDSTONE,2));clock[0]++;
        dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,machine);
        require(inventory.getStackInSlot(0).getCount()==2&&storage.getAmount()==100,"Full machine does not consume redstone");
        storage.setEnergy(99);dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,box);
        require(inventory.getStackInSlot(0).getCount()==1&&storage.getAmount()==100,"Existing partial-space redstone consumption is retained with confirmed credit");
        clock[0]++;storage.setEnergy(0);source=new ItemStack(callbackItem);callbackItem.setEnergy(source,100);inventory.setStackInSlot(0,source);
        callbackItem.callback=()->{throw new IllegalStateException("after staged debit");};boolean failed=false;
        try{dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,machine);}catch(IllegalStateException expected){failed=true;}
        require(failed&&callbackItem.getEnergy(inventory.getStackInSlot(0))==100&&storage.getAmount()==0&&ledger.receiveWholeEu(100,true)==10,"Failed SI preparation retains the original battery, machine and quota");
        callbackItem.callback=()->inventory.setStackInSlot(0,new ItemStack(Items.DIAMOND));
        dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,machine);
        require(inventory.getStackInSlot(0).is(Items.DIAMOND)&&storage.getAmount()==0,"Discharge slot replacement cannot publish unowned machine energy");
        source=new ItemStack(callbackItem);callbackItem.setEnergy(source,100);inventory.setStackInSlot(0,source);callbackItem.callback=()->active[0]=false;
        dev.scex.si.energy.MachineItemDischarging.dischargeSi(ledger,storage,inventory,0,machine);
        require(callbackItem.getEnergy(inventory.getStackInSlot(0))==100&&storage.getAmount()==0,"Owner becoming inactive during discharge rejects the pending debit");
        callbackItem.callback=null;active[0]=true;
        var large=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(4_000_000_000L,3_000_000_000L,0,com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV);
        var largeLedger=new dev.scex.si.energy.FeLedger(large,()->0,()->true);source=new ItemStack(battery);battery.setEnergy(source,4_000_000_000L);inventory.setStackInSlot(0,source);
        require(dev.scex.si.energy.MachineItemDischarging.dischargeSi(largeLedger,large,inventory,0,box)
            &&large.getAmount()==3_000_000_000L&&battery.getEnergy(inventory.getStackInSlot(0))==1_000_000_000L&&largeLedger.receive(1,false)==0,"Storage long SI input does not truncate at the int FE API limit");
        var feStorage=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(10,2,2,com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV);
        var feLedger=new dev.scex.si.energy.FeLedger(feStorage,()->clock[0],()->true);source=new ItemStack(Items.ECHO_SHARD);setEnergy59(source,37);inventory.setStackInSlot(0,source);
        for(int expected:new int[]{34,31,29}) {
            before=inventory.getStackInSlot(0).copy();var after=before.copy();
            require(dev.scex.si.energy.MachineItemDischarging.dischargeFe(feLedger,feStorage,inventory,0,before,after,new FeSource62(after))
                &&energy59(inventory.getStackInSlot(0))==expected&&dev.scex.si.energy.FeLedger.fe(feStorage.scexExactAmount())==37-expected,"Partial FE item debit and fractional machine credit remain equal");
        }
        before=inventory.getStackInSlot(0).copy();var after=before.copy();
        dev.scex.si.energy.MachineItemDischarging.dischargeFe(feLedger,feStorage,inventory,0,before,after,new FeSource62(after));
        require(ItemStack.matches(before,inventory.getStackInSlot(0))&&feStorage.getAmount()==2,"Repeated FE item calls cannot bypass one input allowance");
        clock[0]++;before=inventory.getStackInSlot(0).copy();after=before.copy();var unchanged=new FeSource62(after);unchanged.unchanged=true;
        dev.scex.si.energy.MachineItemDischarging.dischargeFe(feLedger,feStorage,inventory,0,before,after,unchanged);
        require(ItemStack.matches(before,inventory.getStackInSlot(0))&&feStorage.getAmount()==2&&feLedger.receive(100,true)==8,"FE result without a persistent item change cannot create machine energy");
        after=before.copy();var invalid=new FeSource62(after);invalid.invalid=true;
        dev.scex.si.energy.MachineItemDischarging.dischargeFe(feLedger,feStorage,inventory,0,before,after,invalid);
        require(ItemStack.matches(before,inventory.getStackInSlot(0))&&feStorage.getAmount()==2,"Invalid FE return amount discards staged item changes and machine credit");
        after=before.copy();var broken=new FeSource62(after);broken.callback=()->{throw new IllegalStateException("FE staging failed");};failed=false;
        try{dev.scex.si.energy.MachineItemDischarging.dischargeFe(feLedger,feStorage,inventory,0,before,after,broken);}catch(IllegalStateException expected){failed=true;}
        require(failed&&ItemStack.matches(before,inventory.getStackInSlot(0))&&feStorage.getAmount()==2,"FE exception after staged extraction leaves both owned originals unchanged");
        after=before.copy();var replacing=new FeSource62(after);replacing.callback=()->inventory.setStackInSlot(0,new ItemStack(Items.DIAMOND));
        dev.scex.si.energy.MachineItemDischarging.dischargeFe(feLedger,feStorage,inventory,0,before,after,replacing);
        require(inventory.getStackInSlot(0).is(Items.DIAMOND)&&feStorage.getAmount()==2,"FE staging callback cannot overwrite a replaced discharge slot");
    }
    private static void storageCharging61(NormalTool59 tool,PartialArmor60 partial,Battery59 ordinary,Armor59 wide,ThrowingTool59 throwing,CallbackTool61 callbackTool) {
        var storage=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(100,10,10,com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV);
        storage.setEnergy(100);long[] clock={0};boolean[] active={true};
        var ledger=new dev.scex.si.energy.FeLedger(storage,()->clock[0],()->active[0]);
        var inventory=new Inventory(1);inventory.setStackInSlot(0,new ItemStack(partial));
        require(ledger.extractWholeEu(0,false)==0&&ledger.extractWholeEu(-1,false)==0&&storage.getAmount()==100,"Nonpositive EU slot requests do not debit or consume allowance");
        require(ledger.extract(1,false)==1&&ledger.extractWholeEu(Long.MAX_VALUE,true)==9,"One FE already spent leaves only nine whole EU within a ten EU limit");
        require(dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0)
            &&partial.getEnergy(inventory.getStackInSlot(0))==3&&storage.scexExactAmount().equals(dev.scex.energy.EnergyAmount.fromDouble(96.75)),"Storage pays only the three EU actually accepted by partial armor");
        require(ledger.extract(Integer.MAX_VALUE,false)==27&&storage.getAmount()==90&&storage.scexSavedFraction()==0,"SI slot and FE faces consume one exact output allowance including quarter EU");
        var before=inventory.getStackInSlot(0).copy();
        dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0);
        require(ItemStack.matches(before,inventory.getStackInSlot(0))&&storage.getAmount()==90,"Exhausted shared allowance prevents another SI charge in the same tick");
        clock[0]++;inventory.setStackInSlot(0,new ItemStack(tool));
        require(dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0)
            &&tool.getEnergy(inventory.getStackInSlot(0))==10&&ledger.extract(1,false)==0&&storage.getAmount()==80,"SI-first ordering also prevents duplicate FE output allowance");
        clock[0]++;storage.setOutputEnabled(false);before=inventory.getStackInSlot(0).copy();
        dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0);
        require(ItemStack.matches(before,inventory.getStackInSlot(0))&&storage.getAmount()==80,"Redstone disables native SI charging without item mutation");
        storage.setOutputEnabled(true);active[0]=false;
        dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0);
        require(ItemStack.matches(before,inventory.getStackInSlot(0))&&storage.getAmount()==80,"Inactive storage owner cannot charge SI slots");
        active[0]=true;inventory.setStackInSlot(0,new ItemStack(ordinary,2));before=inventory.getStackInSlot(0).copy();
        require(dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0)
            &&ItemStack.matches(before,inventory.getStackInSlot(0))&&storage.getAmount()==80,"Stacked battery is handled as rejected without a fallback debit");
        var full=new ItemStack(tool);tool.setEnergy(full,1000);inventory.setStackInSlot(0,full);
        dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0);
        require(ledger.extract(100,true)==40&&storage.getAmount()==80,"Full SI item leaves all output allowance available to FE");
        inventory.setStackInSlot(0,new ItemStack(throwing));before=inventory.getStackInSlot(0).copy();
        boolean failed=false;try{dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0);}catch(IllegalStateException expected){failed=true;}
        require(failed&&ItemStack.matches(before,inventory.getStackInSlot(0))&&storage.getAmount()==80&&ledger.extractWholeEu(100,true)==10,"Staging exception leaves original item, source energy and allowance unchanged");
        inventory.setStackInSlot(0,new ItemStack(callbackTool));callbackTool.callback=()->inventory.setStackInSlot(0,new ItemStack(Items.DIAMOND));
        dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0);
        require(inventory.getStackInSlot(0).is(Items.DIAMOND)&&storage.getAmount()==80,"Slot replacement during staging is retained and no charge is published or debited");
        inventory.setStackInSlot(0,new ItemStack(callbackTool));callbackTool.callback=()->active[0]=false;
        dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0);
        require(callbackTool.getEnergy(inventory.getStackInSlot(0))==0&&storage.getAmount()==80,"Owner becoming inactive during staging rejects the prepared item");
        active[0]=true;callbackTool.callback=()->storage.consumeEnergyInternal(1,false);
        dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0);
        require(callbackTool.getEnergy(inventory.getStackInSlot(0))==0&&storage.getAmount()==79&&ledger.extractWholeEu(100,true)==10,"Source mutation during staging does not publish free item energy or consume the charger allowance");
        callbackTool.callback=null;inventory.setStackInSlot(0,new ItemStack(Items.STONE));
        require(!dev.scex.si.energy.StorageItemCharging.charge(ledger,storage,inventory,0),"Non-SI items remain eligible for the separate standard FE capability path");
        var large=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(4_000_000_000L,3_000_000_000L,3_000_000_000L,com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV);
        large.setEnergy(4_000_000_000L);var largeLedger=new dev.scex.si.energy.FeLedger(large,()->0,()->true);
        inventory.setStackInSlot(0,new ItemStack(wide));
        require(dev.scex.si.energy.StorageItemCharging.charge(largeLedger,large,inventory,0)
            &&wide.getEnergy(inventory.getStackInSlot(0))==3_000_000_000L&&large.getAmount()==1_000_000_000L&&largeLedger.extract(1,false)==0,"Exact long-component armor charge does not truncate at the FE int API limit");
        var huge=new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV);
        huge.setEnergy(Long.MAX_VALUE);var hugeLedger=new dev.scex.si.energy.FeLedger(huge,()->0,()->true);
        require(hugeLedger.extractWholeEu(Long.MAX_VALUE,false)==Long.MAX_VALUE&&hugeLedger.extract(1,false)==0,"Full long EU output allowance is exact without scaling it into an overflowing FE counter");
        var saved=inventory.serializeNBT(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY));var loaded=new Inventory(1);
        loaded.deserializeNBT(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY),saved);
        require(wide.getEnergy(loaded.getStackInSlot(0))==3_000_000_000L,"Charged owned item survives actual handler serialization");
    }
    private static void solarHelmets60(java.util.List<net.minecraft.world.item.Item> helmets,NormalTool59 receiver,PartialArmor60 partial,Battery59 ordinary) {
        int head=dev.scex.si.energy.SolarHelmetCharging.HEAD_SLOT;
        require(head==39,"Helmet scheduler uses pinned Minecraft inventory head-slot layout");
        var provider=net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        for(var item:helmets) {
            var battery=(com.singularity_iteration.mio_icif.api.item.IBatteryItem)item;
            var source=new ItemStack(item);battery.setEnergy(source,10000);var carried=source.copy();
            var inventory=new net.minecraft.world.SimpleContainer(41);inventory.setItem(head,source);inventory.setItem(0,carried);
            var cursor=new dev.scex.si.energy.SolarHelmetCharging.Cursor();
            var before=carried.copy();
            require(cursor.step(inventory,carried,battery,0,10,30)==0&&ItemStack.matches(before,carried)&&battery.getEnergy(source)==10000,"Carried identical helmet neither generates nor spends power");
            require(cursor.step(inventory,source,battery,0,10,30)==30&&battery.getEnergy(source)==9980&&battery.getEnergy(carried)==10030,"Only the worn stack generates and pays the receiver exactly");
            before=source.copy();var targetBefore=carried.copy();
            require(cursor.step(inventory,source,battery,0,10,30)==0&&ItemStack.matches(before,source)&&ItemStack.matches(targetBefore,carried),"Duplicate tick invocation cannot repeat generation or charging");
            require(cursor.step(inventory,source,battery,1,10,30)==30&&cursor.inventoryProbes()==0,"Cached receiving slot sustains the per-tick rate without rescanning the inventory");
            inventory.setItem(head,carried);inventory.setItem(0,source);
            require(cursor.step(inventory,source,battery,2,10,30)==0,"The former worn stack stops immediately after swapping");
            require(cursor.step(inventory,carried,battery,2,10,30)==30&&battery.getEnergy(source)+battery.getEnergy(carried)==20030,"Replacement worn helmet receives exactly one generation step");
            var restored=ItemStack.parseOptional(provider,(net.minecraft.nbt.CompoundTag)carried.save(provider));
            require(battery.getEnergy(restored)==battery.getEnergy(carried)&&restored.getDamageValue()==carried.getDamageValue(),"Existing helmet numeric damage/energy representation survives serialization unchanged");
            inventory=new net.minecraft.world.SimpleContainer(41);source=new ItemStack(item);battery.setEnergy(source,1000);inventory.setItem(head,source);
            var partialStack=new ItemStack(partial);inventory.setItem(38,partialStack);cursor=new dev.scex.si.energy.SolarHelmetCharging.Cursor();
            require(cursor.step(inventory,source,battery,0,0,100)==3&&partial.getEnergy(partialStack)==3&&battery.getEnergy(source)==997,"Armor receiving only three units causes exactly three units of helmet debit");
            var multiple=new ItemStack(ordinary,2);ordinary.setEnergy(multiple,0);inventory.setItem(38,ItemStack.EMPTY);inventory.setItem(0,multiple);
            before=source.copy();
            require(cursor.step(inventory,source,battery,1,0,100)==0&&ItemStack.matches(before,source),"Rejected multi-item receiver does not consume theoretical charge");
            inventory=new net.minecraft.world.SimpleContainer(41);source=new ItemStack(item);battery.setEnergy(source,10000);inventory.setItem(head,source);
            var distant=new ItemStack(receiver);inventory.setItem(35,distant);cursor=new dev.scex.si.energy.SolarHelmetCharging.Cursor();
            for(int tick=0;tick<3;tick++) {
                cursor.step(inventory,source,battery,tick,0,100);
                require(cursor.inventoryProbes()<=12&&cursor.targetAttempts()<=16,"Helmet inventory and target work is bounded per tick");
            }
            require(receiver.getEnergy(distant)==100&&battery.getEnergy(source)==9900,"A distant inventory slot is discovered within one bounded rotation");
            require(cursor.step(inventory,source,battery,3,0,100)==100&&cursor.inventoryProbes()==0&&receiver.getEnergy(distant)==200,"Repeated charging keeps full rate through the successful slot cache");
            inventory=new net.minecraft.world.SimpleContainer(41);source=new ItemStack(item);battery.setEnergy(source,1000);inventory.setItem(head,source);
            cursor=new dev.scex.si.energy.SolarHelmetCharging.Cursor();int probes=0;
            for(int tick=0;tick<120;tick++) {
                require(cursor.step(inventory,source,battery,tick,10,100)==0,"Empty inventory does not create an outgoing debit");
                probes+=cursor.inventoryProbes();
                require(cursor.inventoryProbes()<=12&&cursor.targetAttempts()<=16,"Idle scheduler still obeys the work budget");
            }
            require(battery.getEnergy(source)==2200&&probes>0&&probes<=300,"Idle backoff reduces inventory probes while generation continues every tick");
            var inserted=new ItemStack(receiver);inventory.setItem(38,inserted);
            for(int tick=120;tick<144;tick++)cursor.step(inventory,source,battery,tick,0,100);
            require(receiver.getEnergy(inserted)>0&&battery.getEnergy(source)+receiver.getEnergy(inserted)==2200,"New worn receiver resumes within idle deadline without loss or duplication");
            var rewoundSource=source.copy();long balance=battery.getEnergy(source);
            cursor.step(inventory,source,battery,-1,10,0);
            require(battery.getEnergy(source)==balance+10&&!ItemStack.matches(rewoundSource,source),"World clock rewind does not leave generation stuck behind stale timing state");
        }
    }
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.setProperty("scex.independent.thermalGenerators", "true");
        for (var type : new Class<?>[]{MachineItemHandler.class, ContainerToTank.class, OwnedHeatExchange.class, ThermalOutput.class, HeatStorage.class, dev.scex.si.energy.PlatformHeatStorage.class, dev.scex.si.energy.PlatformKineticStorage.class})
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(args[0]).toRealPath()), "Just-compiled candidate class origin");
        for (int initialFluid : new int[]{0, 8999, 9000, 9500, 9999, 10000}) {
            var inventory = new Inventory();
            inventory.setStackInSlot(0, new ItemStack(Items.WATER_BUCKET));
            var tank = new FluidTank(10000);
            tank.setFluid(new FluidStack(Fluids.WATER, initialFluid));
            boolean expected = initialFluid <= 9000;
            boolean result = ContainerToTank.transfer(inventory, 0, 1, tank, new FluidStack(Fluids.WATER, 1000), new ItemStack(Items.BUCKET));
            require(result == expected, "Whole-container capacity admission");
            require(tank.getFluidAmount() == initialFluid + (expected ? 1000 : 0), "No unpaid partial fill");
            require(inventory.getStackInSlot(0).getCount() == (expected ? 0 : 1), "Exactly one consumed container");
            require(inventory.getStackInSlot(1).getCount() == (expected ? 1 : 0), "Exactly one returned container");
        }
        var inventory = new Inventory();
        var tank = new FluidTank(10000);
        inventory.setStackInSlot(0, new ItemStack(Items.WATER_BUCKET));
        inventory.setStackInSlot(1, new ItemStack(Items.BUCKET, Items.BUCKET.getDefaultMaxStackSize()));
        require(!ContainerToTank.transfer(inventory, 0, 1, tank, new FluidStack(Fluids.WATER, 1000), new ItemStack(Items.BUCKET)), "Full return slot blocks all mutation");
        inventory.setStackInSlot(1, new ItemStack(Items.BUCKET));
        inventory.getStackInSlot(1).set(DataComponents.CUSTOM_NAME, Component.literal("Keep this component"));
        require(!ContainerToTank.transfer(inventory, 0, 1, tank, new FluidStack(Fluids.WATER, 1000), new ItemStack(Items.BUCKET)), "Do not merge unlike container components");
        inventory.setStackInSlot(1, ItemStack.EMPTY);
        inventory.observe = () -> {
            require(inventory.getStackInSlot(0).isEmpty(), "Observer sees committed input");
            require(inventory.getStackInSlot(1).is(Items.BUCKET), "Observer sees committed return slot");
            require(tank.getFluidAmount() == 750, "Observer sees committed exact cell contents");
        };
        require(ContainerToTank.transfer(inventory, 0, 1, tank, new FluidStack(Fluids.WATER, 750), new ItemStack(Items.BUCKET)), "Actual supplied cell amount");
        inventory.observe = null;
        var before = inventory.getStackInSlot(1).copy();
        require(!inventory.scexCommitSlots(new int[]{1, 1}, new ItemStack[]{before, before}, new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY}), "Duplicate slot transaction rejected");
        require(!inventory.scexCommitSlots(new int[]{1}, new ItemStack[]{ItemStack.EMPTY}, new ItemStack[]{ItemStack.EMPTY}), "Stale slot transaction rejected");
        require(ItemStack.matches(before, inventory.getStackInSlot(1)), "Rejected transaction leaves slots unchanged");
        for (int initial : new int[]{0, 999, 1000, 1500}) {
            var slots = new Inventory();
            slots.setStackInSlot(0, new ItemStack(Items.BUCKET));
            var fluid = new FluidTank(10000);
            fluid.setFluid(new FluidStack(Fluids.WATER, initial));
            boolean result = ContainerToTank.drainToContainer(slots, 0, 1, fluid, new FluidStack(Fluids.WATER, 1000), new ItemStack(Items.WATER_BUCKET));
            require(result == (initial >= 1000), "Only a full output container drains the tank");
            require(fluid.getFluidAmount() == initial - (result ? 1000 : 0), "Exact output fluid quantity");
            require(slots.getStackInSlot(0).getCount() == (result ? 0 : 1), "Output operation consumes one empty");
            require(slots.getStackInSlot(1).getCount() == (result ? 1 : 0), "Output operation yields one filled container");
        }
        for (int initialHeat : new int[]{0, 30000, 30001, 49999, 50000}) {
            var input = new FluidTank(2000); input.setFluid(new FluidStack(Fluids.WATER, 1000));
            var output = new FluidTank(2000);
            var heat = new dev.scex.si.energy.PlatformHeatStorage(50000, 0, 100); heat.setHeat(initialHeat);
            boolean result = OwnedHeatExchange.exchange(input, output, heat, 1000, new FluidStack(Fluids.LAVA, 1000), 20000);
            require(result == (initialHeat <= 30000), "All operation heat must fit");
            require(input.getFluidAmount() == (result ? 0 : 1000), "Input debit accompanies entire output");
            require(output.getFluidAmount() == (result ? 1000 : 0), "Exact output fluid");
            require(heat.getHeatStored() == initialHeat + (result ? 20000 : 0), "No truncated operation heat");
        }
        var hotInput = new FluidTank(2000); hotInput.setFluid(new FluidStack(Fluids.WATER, 1000));
        var incompatibleOutput = new FluidTank(2000); incompatibleOutput.setFluid(new FluidStack(Fluids.WATER, 1));
        var heat = new dev.scex.si.energy.PlatformHeatStorage(50000, 0, 100);
        require(!OwnedHeatExchange.exchange(hotInput, incompatibleOutput, heat, 1000, new FluidStack(Fluids.LAVA, 1000), 20000), "Incompatible output fluid rejects entire operation");
        require(hotInput.getFluidAmount() == 1000 && incompatibleOutput.getFluidAmount() == 1 && heat.getHeatStored() == 0, "Rejected mixed-fluid operation preserves all state");
        for (int receive : new int[]{0, 1, 7, 100}) {
            var heatSource = new dev.scex.si.energy.PlatformHeatStorage(1000, 0, 100); heatSource.setHeat(100);
            var heatTarget = new dev.scex.si.energy.PlatformHeatStorage(1000, receive, 0);
            long accepted = ThermalOutput.move(heatSource, heatTarget, 100);
            require(accepted == receive, "Actual bounded heat acceptance");
            require(heatSource.getHeatStored() == 100 - receive && heatTarget.getHeatStored() == receive, "Rejected heat returns to zero-input source");
        }
        var heatTile = new HeatTile();
        var kineticTile = new KineticTile();
        require(heatTile.reviewedStorage() && kineticTile.reviewedStorage(), "Actual candidate block constructors select reviewed storage");
        require(heatTile.getHeatStorage() == heatTile && kineticTile.getKineticStorage() == kineticTile, "Public capability accesses the block owner");
        heatTile.generateHeatInternal(100, false); kineticTile.generateKineticInternal(100, false);
        require(heatTile.consumeHeatInternal(-10, false) == 0 && kineticTile.generateKineticInternal(-10, false) == 0, "Block delegate rejects negative accounting");
        require(heatTile.getHeatStored() == 100 && kineticTile.getKineticStored() == 100, "Block quantity survives invalid request");
        for (int receive : new int[]{0, 7, 1000}) {
            long remaining = 400;
            long total = 0;
            for (int face = 0; face < 6; face++) {
                var target = new dev.scex.si.energy.PlatformKineticStorage(1000, receive, 0, 10000, 0);
                long accepted = dev.scex.si.energy.KineticOutput.offer(target, remaining, 5000);
                remaining -= accepted;
                total += target.getKineticStored();
                require(remaining >= 0 && total + remaining == 400, "Six faces share one KU budget");
            }
            require(total == (receive == 0 ? 0 : receive == 7 ? 42 : 400), "Bounded receiver rates across faces");
        }
        var stoppedTarget = new dev.scex.si.energy.PlatformKineticStorage(1000, 1000, 0, 10000, 0);
        stoppedTarget.setKinetic(600);
        require(dev.scex.si.energy.KineticOutput.offer(stoppedTarget, 400, 5000) == 0, "Candidate RPM gate");
        require(dev.scex.si.energy.KineticOutput.offer(stoppedTarget, -1, 10000) == 0 && stoppedTarget.getKineticStored() == 600, "Negative offer leaves KU unchanged");
        var recipeSlots = new Inventory(3);
        recipeSlots.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 2));
        recipeSlots.setStackInSlot(2, new ItemStack(Items.DIRT, 64));
        var products = java.util.List.of(new ItemStack(Items.STONE), new ItemStack(Items.DIRT));
        require(dev.scex.si.processing.RecipeSlots.prepare(recipeSlots, 0, 1, new int[]{1, 2}, products).isEmpty(), "Blocked second product rejects all results");
        require(recipeSlots.getStackInSlot(0).getCount() == 2 && recipeSlots.getStackInSlot(1).isEmpty(), "First output is not published before later output fits");
        recipeSlots.setStackInSlot(2, ItemStack.EMPTY);
        var prepared = dev.scex.si.processing.RecipeSlots.prepare(recipeSlots, 0, 1, new int[]{1, 2}, products).orElseThrow();
        recipeSlots.setStackInSlot(1, new ItemStack(Items.DIRT));
        require(!prepared.commit() && recipeSlots.getStackInSlot(0).getCount() == 2, "Stale output snapshot leaves input alone");
        recipeSlots.setStackInSlot(1, ItemStack.EMPTY);
        var washingWater = new FluidTank(2000); washingWater.setFluid(new FluidStack(Fluids.WATER, 999));
        var washing = dev.scex.si.processing.RecipeSlots.prepare(recipeSlots, 0, 1, new int[]{1, 2}, products).orElseThrow();
        require(!washing.commitWithFluid(washingWater, new FluidStack(Fluids.WATER, 1000)), "Water shortage rejects complete recipe");
        require(washingWater.getFluidAmount() == 999 && recipeSlots.getStackInSlot(0).getCount() == 2, "Short water and input both preserved");
        washingWater.setFluid(new FluidStack(Fluids.WATER, 1000));
        recipeSlots.observe = () -> {
            require(recipeSlots.getStackInSlot(0).getCount() == 1 && recipeSlots.getStackInSlot(1).is(Items.STONE)
                && recipeSlots.getStackInSlot(2).is(Items.DIRT) && washingWater.isEmpty(), "Callback sees all final recipe slots and water");
        };
        require(washing.commitWithFluid(washingWater, new FluidStack(Fluids.WATER, 1000)), "Complete washing operation");
        require(!washing.commit(), "Prepared recipe cannot be reused");
        recipeSlots.observe = null;
        require(dev.scex.si.processing.RecipeSlots.prepare(recipeSlots, 0, 1, new int[]{1, 1}, products).isEmpty(), "Duplicate recipe output rejected");
        require(dev.scex.si.processing.RecipeSlots.prepare(recipeSlots, 0, 1, new int[]{1}, java.util.List.of(new ItemStack(Items.STONE, 65))).isEmpty(), "Oversized recipe output rejected");
        verifyFluidConversion(args[0]);
        verifyFluidTransfers(args[0]);
        verifyFluidTransferFailures();
        verifyIndependentCondenser(args[0]);
        verifyIndependentPump(args[0]);
        verifyJoinedRecipes(args[0]);
        verifyMiningCustody(args[0]);
        verifyMiningPayment(args[0]);
        verifyBasicMiningRecovery(args[0]);
        verifyElectrolysis(args[0]);
        verifyUpgradeSnapshots(args[0]);
        interop53();
        PipeTransferContract.run(args[0]);
        System.out.println("SCEX_FUEL_CONTAINER assertions=" + assertions + " PASS scope=real_slots_and_tank_world_NOT_RUN");
    }

    private static void verifyFluidConversion(String candidatePath) throws Exception {
        for (var type : new Class<?>[]{OwnedFluidConversion.class,
                com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_fermenter_elc.class})
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(candidatePath).toRealPath()), "Fluid conversion uses newly compiled classes");
        for (int supply : new int[]{19, 20, 40}) for (int buffered : new int[]{1600, 1601, 2000})
                for (int items : new int[]{0, 63, 64}) {
            var input = new FluidTank(10000); input.setFluid(new FluidStack(Fluids.WATER, supply));
            var output = new FluidTank(2000); output.setFluid(new FluidStack(Fluids.LAVA, buffered));
            var inventory = new Inventory(); inventory.setStackInSlot(0, new ItemStack(Items.DIRT, items));
            var conversion = OwnedFluidConversion.prepare(input, new FluidStack(Fluids.WATER, 20), output,
                new FluidStack(Fluids.LAVA, 400), inventory, 0, new ItemStack(Items.DIRT));
            boolean expected = supply >= 20 && buffered <= 1600 && items < 64;
            require(conversion.isPresent() == expected, "Input, fluid output, and byproduct must all fit");
            if (conversion.isPresent()) {
                inventory.observe = () -> require(input.getFluidAmount() == supply - 20
                    && output.getFluidAmount() == buffered + 400 && inventory.getStackInSlot(0).getCount() == items + 1,
                    "Inventory observer sees complete fluid and byproduct operation");
                require(conversion.get().commit(), "Admitted conversion commits");
                require(!conversion.get().commit(), "Conversion cannot duplicate outputs by reuse");
            }
            require(input.getFluidAmount() == supply - (expected ? 20 : 0), "Rejected conversion keeps all input");
            require(output.getFluidAmount() == buffered + (expected ? 400 : 0), "Exact produced fluid");
            require(inventory.getStackInSlot(0).getCount() == items + (expected ? 1 : 0), "No lost byproduct");
        }
        var input = new FluidTank(10000); input.setFluid(new FluidStack(Fluids.WATER, 40));
        var output = new FluidTank(2000);
        var inventory = new Inventory();
        inventory.setStackInSlot(0, new ItemStack(Items.DIRT, 64));
        var noByproduct = OwnedFluidConversion.prepare(input, new FluidStack(Fluids.WATER, 20), output,
            new FluidStack(Fluids.LAVA, 400), inventory, 0, ItemStack.EMPTY).orElseThrow();
        require(noByproduct.commit() && inventory.getStackInSlot(0).getCount() == 64,
            "Full fertilizer slot allows operations that do not yet owe fertilizer");
        inventory.setStackInSlot(0, new ItemStack(Items.DIRT));
        inventory.getStackInSlot(0).set(DataComponents.CUSTOM_NAME, Component.literal("Preserve components"));
        require(OwnedFluidConversion.prepare(input, new FluidStack(Fluids.WATER, 20), output,
            new FluidStack(Fluids.LAVA, 400), inventory, 0, new ItemStack(Items.DIRT)).isEmpty(),
            "Unlike byproduct components reject conversion");
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        var stale = OwnedFluidConversion.prepare(input, new FluidStack(Fluids.WATER, 20), output,
            new FluidStack(Fluids.LAVA, 400), inventory, 0, new ItemStack(Items.DIRT)).orElseThrow();
        input.drain(1, IFluidHandler.FluidAction.EXECUTE);
        require(!stale.commit() && input.getFluidAmount() == 19 && output.getFluidAmount() == 400
            && inventory.getStackInSlot(0).isEmpty(), "Changed fluid snapshot rejects stale conversion");
        input.setFluid(new FluidStack(Fluids.WATER, 20));
        var partial = new FluidTank(2000) {
            @Override public int fill(FluidStack resource, IFluidHandler.FluidAction action) {
                return super.fill(action.execute() ? resource.copyWithAmount(resource.getAmount() / 2) : resource, action);
            }
        };
        var failed = OwnedFluidConversion.prepare(input, new FluidStack(Fluids.WATER, 20), partial,
            new FluidStack(Fluids.LAVA, 400), inventory, 0, new ItemStack(Items.DIRT)).orElseThrow();
        require(!failed.commit() && input.getFluidAmount() == 20 && partial.isEmpty()
            && inventory.getStackInSlot(0).isEmpty(), "Partial owned fill restores both fluids without publishing byproduct");
        require(OwnedFluidConversion.prepare(input, new FluidStack(Fluids.LAVA, 20), output,
            new FluidStack(Fluids.LAVA, 400), inventory, 0, ItemStack.EMPTY).isEmpty(), "Wrong input fluid rejected");
        require(OwnedFluidConversion.prepare(input, new FluidStack(Fluids.WATER, 20), input,
            new FluidStack(Fluids.WATER, 20), inventory, 0, ItemStack.EMPTY).isEmpty(), "Aliased tanks rejected");

        var registries = net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty());
        for (long paid : new long[]{0, 3999, 4000, Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
            var tile = new com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_fermenter_elc(
                net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putLong("scex_work_credit", paid); tag.putInt("progress", 17);
            tag.putLong("biomassProcessed", 480); tag.putLong("heat", 901);
            tile.loadAdditional(tag, registries);
            var saved = tile.getUpdateTag(registries);
            require(saved.getLong("scex_work_credit") == paid && tile.getProgress() == Math.min(4000, paid),
                "Real fermenter NBT keeps all paid work with bounded GUI progress");
            require(saved.getLong("biomassProcessed") == 480 && tile.getHeatStored() == 901,
                "Real fermenter NBT preserves fertilizer remainder and available heat separately");
            require(tile.getHeatStorageCapability(null) == tile, "Real fermenter heat capability uses owning block");
            tile.loadAdditional(saved, registries);
            require(tile.getUpdateTag(registries).getLong("scex_work_credit") == paid, "Paid work survives second load/save unchanged");
        }
        var legacy = new com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_fermenter_elc(
            net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),
            net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);
        var oldTag = new net.minecraft.nbt.CompoundTag(); oldTag.putInt("progress", Integer.MAX_VALUE);
        oldTag.putInt("biomassProcessed", 480);
        legacy.loadAdditional(oldTag, registries);
        require(legacy.getUpdateTag(registries).getLong("scex_work_credit") == Integer.MAX_VALUE,
            "Legacy positive progress is retained beyond one operation");
        oldTag.putInt("progress", -1); oldTag.putInt("biomassProcessed", -1);
        legacy.loadAdditional(oldTag, registries);
        require(legacy.getProgress() == 0 && legacy.getUpdateTag(registries).getLong("biomassProcessed") == 0,
            "Negative legacy counters cannot create paid work or fertilizer");
    }

    private static final class PartialTank extends FluidTank {
        int actualLimit;
        Runnable reenter;
        PartialTank(int limit) { super(5000); actualLimit = limit; }
        @Override public int fill(FluidStack resource, IFluidHandler.FluidAction action) {
            if (action.execute() && reenter != null) reenter.run();
            return super.fill(action.execute() ? resource.copyWithAmount(Math.min(resource.getAmount(), actualLimit)) : resource, action);
        }
    }

    private static final class InductionTile extends com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_induction_elc {
        boolean recipeAvailable = true;
        InductionTile() {
            super(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);
            energyStorage.scexSetNetworkControlled(true);
        }
        @Override protected boolean canWork() { return recipeAvailable; }
        void heatingTick() { onTick(); }
        void seedEnergy(long amount) { apiGenerateEnergy(amount, false); }
        long storedEnergy() { return apiGetStoredEnergy(); }
        net.minecraft.nbt.CompoundTag saved(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new net.minecraft.nbt.CompoundTag(); saveAdditional(tag, registries); return tag;
        }
    }

    private static void verifyJoinedRecipes(String candidatePath) throws Exception {
        for (var type : new Class<?>[]{dev.scex.si.processing.RecipeSlots.class,
                com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_induction_elc.class})
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(candidatePath).toRealPath()), "Joined recipe classes come from this build");
        var inventory = new Inventory(4);
        inventory.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 2));
        inventory.setStackInSlot(1, new ItemStack(Items.RAW_IRON, 2));
        var lane1 = dev.scex.si.processing.RecipeSlots.prepare(inventory, 0, 1, new int[]{2},
            java.util.List.of(new ItemStack(Items.STONE))).orElseThrow();
        var lane2 = dev.scex.si.processing.RecipeSlots.prepare(inventory, 1, 1, new int[]{3},
            java.util.List.of(new ItemStack(Items.IRON_INGOT))).orElseThrow();
        require(dev.scex.si.processing.RecipeSlots.combine(java.util.List.of(lane1, lane1)).isEmpty(), "Same lane cannot be counted twice");
        var together = dev.scex.si.processing.RecipeSlots.combine(java.util.List.of(lane1, lane2)).orElseThrow();
        inventory.observe = () -> require(inventory.getStackInSlot(0).getCount() == 1 && inventory.getStackInSlot(1).getCount() == 1
            && inventory.getStackInSlot(2).is(Items.STONE) && inventory.getStackInSlot(3).is(Items.IRON_INGOT),
            "Every inventory callback sees both finished lanes");
        require(together.commit(), "Two independent lanes commit together");
        require(!lane1.commit() && !lane2.commit() && !together.commit(), "Original and combined plans cannot replay a committed lane");
        inventory.observe = null;
        var consumed = dev.scex.si.processing.RecipeSlots.consumeOnly(inventory, 0, 1).orElseThrow();
        require(consumed.commit() && inventory.getStackInSlot(0).isEmpty() && inventory.getStackInSlot(2).getCount() == 1,
            "A no-product recipe consumes one input without inventing output");
        require(!consumed.commit() && dev.scex.si.processing.RecipeSlots.consumeOnly(inventory, 0, 1).isEmpty(),
            "No-product recipe cannot consume missing input twice");
        require(dev.scex.si.processing.RecipeSlots.consumeOnly(inventory, 1, -1).isEmpty(), "Negative consumption cannot produce input");
        var different = new Inventory(); different.setStackInSlot(0, new ItemStack(Items.COBBLESTONE));
        var foreign = dev.scex.si.processing.RecipeSlots.consumeOnly(different, 0, 1).orElseThrow();
        var local = dev.scex.si.processing.RecipeSlots.consumeOnly(inventory, 1, 1).orElseThrow();
        require(dev.scex.si.processing.RecipeSlots.combine(java.util.List.of(local, foreign)).isEmpty(), "Separate owners cannot form an owned transaction");

        var tile = new InductionTile();
        tile.getHeatStorage().setHeat(100);
        tile.heatingTick();
        require(tile.storedEnergy() == 0 && tile.getHeatStorage().getHeatStored() == 96, "Actual induction heater cools without EU");
        tile.seedEnergy(1); tile.heatingTick();
        require(tile.storedEnergy() == 0 && tile.getHeatStorage().getHeatStored() == 97, "Actual induction heater pays for its heat increment");
        tile.seedEnergy(1); tile.recipeAvailable = false; tile.heatingTick();
        require(tile.storedEnergy() == 1 && tile.getHeatStorage().getHeatStored() == 93, "Idle unpowered induction heater preserves EU and cools");
        tile.forceStartWork();
        require(tile.getProgress() == 1, "Actual induction progress uses the same field as its producer base");
        tile.forceStopWork();
        require(tile.getProgress() == 0, "Base progress reset reaches actual induction progress");
        var registries = net.minecraft.core.HolderLookup.Provider.create(java.util.stream.Stream.empty());
        var saved = new net.minecraft.nbt.CompoundTag();
        saved.putLong("heat", 4321); saved.putInt("heat_percent", 99);
        saved.putInt("progress", 12); saved.putInt("progress1", 99);
        tile.loadAdditional(saved, registries);
        require(tile.getHeatStorage().getHeatStored() == 4321 && tile.getProgress() == 12,
            "Canonical induction heat and progress win over legacy duplicate tags");
        var restored = tile.saved(registries); tile.loadAdditional(restored, registries);
        require(tile.getHeatStorage().getHeatStored() == 4321 && tile.getProgress() == 12, "Actual induction NBT is stable on a second round trip");
        var legacy = new net.minecraft.nbt.CompoundTag(); legacy.putInt("heat_percent", 25); legacy.putInt("progress1", 17);
        tile.loadAdditional(legacy, registries);
        require(tile.getHeatStorage().getHeatStored() == 2500 && tile.getProgress() == 17, "Legacy induction heat percentage and progress load");
    }

    private static void verifyMiningCustody(String candidatePath) throws Exception {
        for (var type : new Class<?>[]{dev.scex.si.processing.PendingDrops.class, dev.scex.si.processing.MiningLoot.class})
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(candidatePath).toRealPath()), "Mining helper origin belongs to this build");
        var oneSlot = new Inventory(1);
        var competing = java.util.List.of(new ItemStack(Items.STONE, 40), new ItemStack(Items.STONE, 40));
        require(dev.scex.si.processing.RecipeSlots.outputs(oneSlot, new int[]{0}, competing).isEmpty(),
            "Two drops cannot reserve the same empty slot independently");
        var slots = new Inventory(2);
        var packed = dev.scex.si.processing.RecipeSlots.outputs(slots, new int[]{0, 1}, competing).orElseThrow();
        require(packed.commit() && slots.getStackInSlot(0).getCount() == 64 && slots.getStackInSlot(1).getCount() == 16,
            "Joint output plan splits combined counts across real slot capacity");
        var named = new ItemStack(Items.STONE, 16); named.set(DataComponents.CUSTOM_NAME, Component.literal("Separate component"));
        require(dev.scex.si.processing.RecipeSlots.outputs(slots, new int[]{0, 1}, java.util.List.of(named)).isEmpty(),
            "Mining output cannot merge unlike components to make space");
        var queue = new dev.scex.si.processing.PendingDrops(() -> {});
        var supplied = new ItemStack(Items.DIRT, 2);
        require(queue.stage(java.util.List.of(supplied)), "Loot acquires owned custody");
        supplied.setCount(1);
        require(queue.first().getCount() == 2 && !queue.stage(java.util.List.of(new ItemStack(Items.STONE))),
            "Custody detaches loot and blocks a second block while pending");
        var receiving = new Inventory(2);
        receiving.observe = () -> {
            require(queue.isEmpty() && receiving.getStackInSlot(0).getCount() == 2, "Owned inventory callback sees loot custody already cleared");
            require(!queue.stage(java.util.List.of(new ItemStack(Items.STONE))), "Delivery callback cannot restage a second capture");
        };
        require(queue.commitOwned(receiving, new int[]{0, 1}), "Loot publishes atomically to owned slots");
        receiving.observe = null;
        require(queue.commitOwned(receiving, new int[]{0, 1}) && receiving.getStackInSlot(0).getCount() == 2,
            "Empty custody does not replay an earlier delivery");
        queue.stage(java.util.List.of(new ItemStack(Items.DIRT, 64)));
        oneSlot.setStackInSlot(0, new ItemStack(Items.DIRT, 63));
        require(queue.deliver(oneSlot, 1) == 1 && queue.first().getCount() == 63 && oneSlot.getStackInSlot(0).getCount() == 64,
            "Partial external acceptance retains exact undelivered loot");
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var saved = queue.save(registries);
        var resumed = new dev.scex.si.processing.PendingDrops(() -> {}); resumed.load(registries, saved);
        require(ItemStack.matches(queue.first(), resumed.first()), "Pending loot survives real item NBT round trip");
        var manySlots = new Inventory(100);
        for (int slot = 0; slot < 99; slot++) manySlots.setStackInSlot(slot, new ItemStack(Items.STONE, 64));
        require(resumed.deliverRange(manySlots, 0, 64) == 0 && resumed.first().getCount() == 63,
            "Bounded external scan keeps loot while later slots remain unchecked");
        require(resumed.deliverRange(manySlots, 64, 64) == 63 && resumed.isEmpty()
            && manySlots.getStackInSlot(99).getCount() == 63, "Next range reaches an available slot beyond the first 64");
        var opaque = new net.minecraft.nbt.ListTag(); var unresolved = new net.minecraft.nbt.CompoundTag();
        unresolved.putString("id", "missing_mod:retained_loot"); unresolved.putInt("count", 7); opaque.add(unresolved);
        resumed.load(registries, opaque);
        require(!resumed.isEmpty() && resumed.first().isEmpty() && resumed.save(registries).equals(opaque),
            "Unknown optional item remains encoded in custody instead of silently vanishing");
        var tooMany = new java.util.ArrayList<ItemStack>();
        for (int i = 0; i <= dev.scex.si.processing.PendingDrops.MAX_STACKS; i++) tooMany.add(new ItemStack(Items.DIRT));
        var bounded = new dev.scex.si.processing.PendingDrops(() -> {});
        require(!bounded.stage(tooMany) && bounded.isEmpty(), "Oversized loot is rejected before source-world removal");
    }

    private static long toolEu(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getLong("test_eu");
    }
    private static void toolEu(ItemStack stack, long energy) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        tag.putLong("test_eu", energy);
        stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
    }
    private static com.singularity_iteration.mio_icif.api.item.IItemAPI toolApi(long rate, boolean wrongChargeReport) {
        return (com.singularity_iteration.mio_icif.api.item.IItemAPI) java.lang.reflect.Proxy.newProxyInstance(
            FuelContainerContract.class.getClassLoader(), new Class<?>[]{com.singularity_iteration.mio_icif.api.item.IItemAPI.class},
            (proxy, method, args) -> {
                var stack = (ItemStack) args[0];
                return switch (method.getName()) {
                    case "isElectricTool" -> stack.is(Items.PAPER);
                    case "getElectricToolStored" -> toolEu(stack);
                    case "getElectricToolMaxEnergy" -> 1000L;
                    case "dischargeElectricTool" -> {
                        long amount = Math.min(toolEu(stack), (long) args[1]);
                        if (!(boolean) args[2]) toolEu(stack, toolEu(stack) - amount);
                        yield amount;
                    }
                    case "chargeElectricTool" -> {
                        long amount = Math.min(rate, Math.min(1000 - toolEu(stack), (long) args[1]));
                        if (!(boolean) args[2]) toolEu(stack, toolEu(stack) + amount);
                        yield amount + (wrongChargeReport ? 1L : 0L);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }
    private static com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage miningEnergy(long amount) {
        var energy = new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(10000, 128, 1,
            com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV);
        // No ServerLevel exists in this contract. Exercise the same owned debit methods in compatibility mode.
        energy.setEnergy(amount); return energy;
    }
    private static final class AdvancedMinerTile extends com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_advanced_miner_elc {
        AdvancedMinerTile() {
            super(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);
            energyStorage.scexSetNetworkControlled(true);
        }
        net.minecraft.nbt.CompoundTag saved(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new net.minecraft.nbt.CompoundTag(); saveAdditional(tag, registries); return tag;
        }
    }
    private static void verifyMiningPayment(String candidatePath) throws Exception {
        for (var type : new Class<?>[]{dev.scex.si.processing.MiningPayment.class, dev.scex.si.processing.MiningLayer.class,
                dev.scex.si.energy.ToolEnergy.class, com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_advanced_miner_elc.class})
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(candidatePath).toRealPath()), "Mining payment and scheduler use newly compiled classes");
        var api = toolApi(17, false);
        var controlled = miningEnergy(512); controlled.scexSetNetworkControlled(true);
        var guarded = new dev.scex.si.processing.MiningPayment(() -> {});
        boolean threadGuard = false;
        try {
            guarded.attempt(controlled, new Inventory(1), 0, api, 512, 0, permit -> permit.fund());
        } catch (IllegalStateException expected) { threadGuard = expected.getMessage().contains("server thread"); }
        require(threadGuard && controlled.getAmount() == 512 && guarded.machineCredit() == 0 && !guarded.isBusy(),
            "Controlled source still requires a real owning server thread; fixture does not bypass that gate");
        var inventory = new Inventory(1);
        var tool = new ItemStack(Items.PAPER); toolEu(tool, 100); inventory.setStackInSlot(0, tool);
        var energy = miningEnergy(511);
        var wallet = new dev.scex.si.processing.MiningPayment(() -> {});
        require(!wallet.attempt(energy, inventory, 0, api, 512, 64, permit -> {
            require(!permit.fund(), "Insufficient machine energy prevents complete payment"); return false;
        }) && energy.getAmount() == 511 && toolEu(inventory.getStackInSlot(0)) == 100,
            "Scanner energy is unchanged when machine energy is short");
        energy.setEnergy(512); toolEu(inventory.getStackInSlot(0), 63);
        require(!wallet.attempt(energy, inventory, 0, api, 512, 64, permit -> {
            require(!permit.fund(), "Insufficient tool energy prevents complete payment"); return false;
        }) && energy.getAmount() == 512 && wallet.machineCredit() == 0, "Machine energy is unchanged when scanner is short");
        toolEu(inventory.getStackInSlot(0), 100);
        require(!wallet.attempt(energy, inventory, 0, api, 512, 64, permit -> false)
            && energy.getAmount() == 512 && toolEu(inventory.getStackInSlot(0)) == 100,
            "Rejected world preflight does not invoke payment");
        var captured = new dev.scex.si.processing.MiningPayment.Permit[1];
        inventory.observe = () -> {
            require(wallet.machineCredit() == 512 && wallet.toolCredit() == 64 && energy.getAmount() == 0,
                "Inventory callbacks see both paid balances and the machine debit");
            require(!wallet.attempt(energy, inventory, 0, api, 512, 64, permit -> true), "Same payer cannot reenter");
            require(!captured[0].fund(), "Same permit cannot recursively fund during inventory publication");
        };
        require(!wallet.attempt(energy, inventory, 0, api, 512, 64, permit -> {
            captured[0] = permit;
            require(permit.fund(), "Full internal work payment ignores the one-EU outward packet limit");
            return false;
        }) && wallet.machineCredit() == 512 && wallet.toolCredit() == 64 && toolEu(inventory.getStackInSlot(0)) == 36,
            "A failed world removal retains its complete payment for retry");
        inventory.observe = null;
        require(!captured[0].fund(), "Expired permit cannot replay payment");
        var failingInventory = new Inventory(1);
        var failingTool = new ItemStack(Items.PAPER); toolEu(failingTool, 100); failingInventory.setStackInSlot(0, failingTool);
        var failingEnergy = miningEnergy(512);
        var failingWallet = new dev.scex.si.processing.MiningPayment(() -> {});
        failingInventory.observe = () -> { throw new IllegalStateException("intentional inventory callback failure"); };
        boolean callbackFailed = false;
        try { failingWallet.attempt(failingEnergy, failingInventory, 0, api, 512, 64, permit -> permit.fund()); }
        catch (IllegalStateException expected) { callbackFailed = expected.getMessage().contains("intentional inventory"); }
        require(callbackFailed && !failingWallet.isBusy() && failingEnergy.getAmount() == 0
            && toolEu(failingInventory.getStackInSlot(0)) == 36 && failingWallet.machineCredit() == 512 && failingWallet.toolCredit() == 64,
            "An exception after owned inventory publication retains both paid credits and releases the guard");
        var resumed = new dev.scex.si.processing.MiningPayment(() -> {}); resumed.load(wallet.save());
        toolEu(inventory.getStackInSlot(0), 0);
        require(resumed.attempt(energy, inventory, 0, api, 512, 64, permit -> {
            require(permit.fund(), "Saved payment can resume with an empty tool and machine"); permit.complete(); return true;
        }) && resumed.machineCredit() == 0 && resumed.toolCredit() == 0 && energy.getAmount() == 0,
            "Successful retry spends both credits once without charging again");
        var invalid = new net.minecraft.nbt.CompoundTag(); invalid.putLong("machine_eu", -1); invalid.putLong("tool_eu", -9);
        resumed.load(invalid);
        require(resumed.machineCredit() == 0 && resumed.toolCredit() == 0, "Negative saved balances cannot create credit");
        invalid.putLong("machine_eu", Long.MAX_VALUE); invalid.putLong("tool_eu", Long.MAX_VALUE); resumed.load(invalid);
        require(resumed.attempt(energy, inventory, 0, api, 512, 64, permit -> {
            require(permit.fund(), "Large positive paid balances remain representable"); permit.complete(); return true;
        }) && resumed.machineCredit() == Long.MAX_VALUE - 512 && resumed.toolCredit() == Long.MAX_VALUE - 64,
            "Positive saved credit is conserved without overflowing");
        energy.setEnergy(100); toolEu(inventory.getStackInSlot(0), 10);
        inventory.observe = () -> require(energy.getAmount() == 83 && toolEu(inventory.getStackInSlot(0)) == 27,
            "Tool charge callback sees exactly the paid increment");
        require(dev.scex.si.energy.ToolEnergy.charge(inventory, 0, energy, api) == 17,
            "Real partial tool acceptance charges only its actual amount");
        inventory.observe = null;
        require(dev.scex.si.energy.ToolEnergy.charge(inventory, 0, energy, toolApi(17, true)) == 0
            && energy.getAmount() == 83 && toolEu(inventory.getStackInSlot(0)) == 27,
            "Invalid adapter report only mutates a detached copy");
        inventory.getStackInSlot(0).setCount(2);
        require(dev.scex.si.energy.ToolEnergy.charge(inventory, 0, energy, api) == 0,
            "Ambiguous multi-tool stack cannot receive single-tool energy");
        var center = new net.minecraft.core.BlockPos(30, -20, 45);
        var small = dev.scex.si.processing.MiningLayer.positions(center, 1);
        var large = dev.scex.si.processing.MiningLayer.positions(center, 32);
        require(small.size() == 9 && small.getFirst().equals(center) && large.size() == 4225
            && new java.util.HashSet<>(large).size() == 4225 && large.subList(0, 9).equals(small),
            "Coordinate-only layer preserves center-first ring order without duplicates");
        require(large.stream().allMatch(pos -> dev.scex.si.processing.MiningLayer.contains(center, pos))
            && !dev.scex.si.processing.MiningLayer.contains(center, center.above())
            && !dev.scex.si.processing.MiningLayer.contains(center, new net.minecraft.core.BlockPos(Integer.MIN_VALUE, -20, 45)),
            "Layer bounds use wide arithmetic and exact depth");
        require(dev.scex.si.processing.MiningLayer.cycleBudget(0) == 5
            && dev.scex.si.processing.MiningLayer.cycleBudget(-1) == 5
            && dev.scex.si.processing.MiningLayer.cycleBudget(Integer.MAX_VALUE) == 4225,
            "Scan budget cannot wrap or exceed the layer");
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var miner = new AdvancedMinerTile();
        var saved = new net.minecraft.nbt.CompoundTag();
        var origin = net.minecraft.core.BlockPos.ZERO.below();
        saved.putLong("tipPos", origin.asLong()); saved.putInt("workTicker", Integer.MAX_VALUE);
        saved.putLongArray("oresInCurrentLayer", new long[]{origin.asLong()}); saved.putInt("currentOreIndex", -9);
        saved.put("scex_mining_payment", wallet.save()); miner.loadAdditional(saved, registries);
        var roundtrip = miner.saved(registries);
        require(roundtrip.getInt("workTicker") == 19 && roundtrip.getInt("currentOreIndex") == 0
            && roundtrip.getLongArray("oresInCurrentLayer").length == 1
            && roundtrip.getCompound("scex_mining_payment").getLong("machine_eu") == 512,
            "Actual advanced miner preserves paid credit and valid layer data while bounding counters");
        saved.putLongArray("oresInCurrentLayer", new long[]{origin.asLong(), origin.east(33).asLong()});
        miner.loadAdditional(saved, registries);
        require(miner.saved(registries).getLongArray("oresInCurrentLayer").length == 0,
            "Invalid derived layer coordinates request regeneration instead of remote mining");
        var filter = new ItemStack(Items.STONE, 64); miner.setFilterStack(0, filter);
        var detached = miner.getFilterStack(0); detached.setCount(30);
        require(miner.getFilterStack(0).getCount() == 1 && filter.getCount() == 64,
            "Ghost filters are single-item copies and their getters cannot edit machine state");
    }

    private static final class BasicMinerTile extends com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_miner_elc {
        BasicMinerTile() {
            super(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);
            energyStorage.scexSetNetworkControlled(true);
        }
        net.minecraft.nbt.CompoundTag saved(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new net.minecraft.nbt.CompoundTag(); saveAdditional(tag, registries); return tag;
        }
    }
    private static final class PipeWorld implements dev.scex.si.processing.PipeAdvance.WorldAccess {
        int placed, converted, tipCalls, oldCalls;
        boolean allowTip, allowOld;
        Runnable tipCallback;
        public dev.scex.si.processing.PipeAdvance.Outcome placeTip(net.minecraft.core.BlockPos target) {
            tipCalls++;
            if (tipCallback != null) tipCallback.run();
            if (!allowTip) return dev.scex.si.processing.PipeAdvance.Outcome.RETRY;
            placed++; return dev.scex.si.processing.PipeAdvance.Outcome.APPLIED;
        }
        public dev.scex.si.processing.PipeAdvance.Outcome replaceOldTip(net.minecraft.core.BlockPos previous) {
            oldCalls++;
            if (!allowOld) return dev.scex.si.processing.PipeAdvance.Outcome.RETRY;
            converted++; return dev.scex.si.processing.PipeAdvance.Outcome.APPLIED;
        }
    }
    private static void verifyBasicMiningRecovery(String candidatePath) throws Exception {
        for (var type : new Class<?>[]{dev.scex.si.processing.MiningRoute.class, dev.scex.si.processing.PipeAdvance.class,
                dev.scex.si.processing.MiningPlacement.class, com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_miner_elc.class})
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(Path.of(candidatePath).toRealPath()), "Basic mining recovery classes come from the current build");
        var origin = new net.minecraft.core.BlockPos(20, -30, 25);
        var target = origin.offset(6, 0, -6);
        var route = new dev.scex.si.processing.MiningRoute(() -> {});
        require(route.begin(origin, target, 700), "Start one billed horizontal route");
        require(!route.begin(origin, origin.below(), 100), "Cannot overwrite an active paid operation");
        var first = route.next(); route.advance(first); route.markPaid();
        var resumedRoute = new dev.scex.si.processing.MiningRoute(() -> {}); resumedRoute.load(route.save());
        require(resumedRoute.paid() && resumedRoute.cost() == 700 && resumedRoute.next().equals(origin.east(2)),
            "Route saves the exact paid budget and next path coordinate");
        int steps = 1;
        while (!resumedRoute.complete()) { resumedRoute.advance(resumedRoute.next()); steps++; }
        require(steps == 12 && resumedRoute.target().equals(target), "Maximum candidate tunnel ends after twelve unique steps");
        resumedRoute.finish(); require(!resumedRoute.active() && !resumedRoute.paid(), "Finished route cannot carry paid state into the next target");
        require(!resumedRoute.begin(origin, origin.east(7), 700) && !resumedRoute.begin(origin, origin.below(2), 700),
            "Route rejects out-of-range and multi-depth targets");
        var corrupt = route.save(); corrupt.putLong("cursor", origin.north().asLong());
        resumedRoute.load(corrupt); require(resumedRoute.invalid(), "Off-path persisted cursor is preserved as invalid instead of mining arbitrary positions");
        var restoredPaid = new dev.scex.si.processing.MiningRoute(() -> {}); restoredPaid.load(route.save());
        require(restoredPaid.belongsTo(origin.above(), -64, 320)
            && !restoredPaid.belongsTo(origin.above().east(), -64, 320), "Route is bound to the actual miner column");
        var descending = new dev.scex.si.processing.MiningRoute(() -> {});
        require(descending.begin(origin, origin.below(), 700) && descending.next().equals(origin.below()), "Vertical advance has exactly one target");
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var inventory = new Inventory(1); inventory.setStackInSlot(0, new ItemStack(Items.STICK, 2));
        var pipe = new dev.scex.si.processing.PipeAdvance(() -> {});
        var world = new PipeWorld();
        inventory.observe = () -> {
            require(pipe.active() && pipe.reserved().getCount() == 1 && inventory.getStackInSlot(0).getCount() == 1,
                "Inventory callback sees the reserved pipe and the corresponding debit");
            require(!pipe.advance(world), "Pipe begin cannot reenter placement through an inventory callback");
        };
        require(pipe.begin(inventory, 0, new ItemStack(Items.STICK), origin, origin.below()), "Reserve exactly one pipe for two world steps");
        inventory.observe = null;
        require(!pipe.advance(world) && world.placed == 0 && world.converted == 0 && pipe.reserved().getCount() == 1,
            "Rejected new tip retains the pipe and leaves the old tip alone");
        var resumed = new dev.scex.si.processing.PipeAdvance(() -> {}); resumed.load(pipe.save(registries), registries);
        world.allowTip = true;
        require(!resumed.advance(world) && world.placed == 1 && world.converted == 0 && resumed.reserved().isEmpty(),
            "New tip can complete while previous-tip conversion is temporarily denied");
        var secondRestart = new dev.scex.si.processing.PipeAdvance(() -> {}); secondRestart.load(resumed.save(registries), registries);
        require(!secondRestart.advance(world) && world.placed == 1 && inventory.getStackInSlot(0).getCount() == 1,
            "Restart retries only old-tip conversion without consuming another pipe or placing another tip");
        world.allowOld = true;
        require(secondRestart.advance(world) && secondRestart.complete() && world.placed == 1 && world.converted == 1,
            "Both world stages complete with one reserved pipe");
        require(secondRestart.advance(world) && world.placed == 1 && world.converted == 1,
            "Completed-but-unacknowledged advancement is idempotent");
        secondRestart.finish(); require(!secondRestart.active(), "Completion clears the pipe intent");
        var firstPipe = new dev.scex.si.processing.PipeAdvance(() -> {});
        require(firstPipe.begin(inventory, 0, new ItemStack(Items.STICK), null, origin.below()), "First tip reserves a pipe without an old tip");
        var firstWorld = new PipeWorld(); firstWorld.allowTip = true;
        require(firstPipe.advance(firstWorld) && firstWorld.placed == 1 && firstWorld.oldCalls == 0 && inventory.getStackInSlot(0).isEmpty(),
            "Initial placement needs only one world operation and consumes the final pipe once");
        var uncertain = new dev.scex.si.processing.PipeAdvance(() -> {});
        inventory.setStackInSlot(0, new ItemStack(Items.STICK));
        uncertain.begin(inventory, 0, new ItemStack(Items.STICK), origin, origin.below());
        var exceptionalWorld = new PipeWorld();
        exceptionalWorld.tipCallback = () -> { throw new IllegalStateException("intentional world callback failure"); };
        boolean failed = false;
        try { uncertain.advance(exceptionalWorld); } catch (IllegalStateException expected) { failed = true; }
        require(failed && uncertain.uncertain() && !uncertain.isBusy() && uncertain.reserved().getCount() == 1,
            "Unknown world outcome preserves its receipt and reserved item without replaying blindly");
        var uncertainReload = new dev.scex.si.processing.PipeAdvance(() -> {}); uncertainReload.load(uncertain.save(registries), registries);
        require(uncertainReload.uncertain() && !uncertainReload.advance(new PipeWorld()), "Uncertain world state survives restart and remains paused");
        var opaque = pipe.save(registries); opaque.getCompound("reserved").putString("id", "missing_mod:pipe");
        var missing = new dev.scex.si.processing.PipeAdvance(() -> {}); missing.load(opaque, registries);
        require(missing.uncertain() && missing.save(registries).equals(opaque), "Unknown reserved item NBT is retained byte-for-value");
        var basic = new BasicMinerTile();
        var saved = new net.minecraft.nbt.CompoundTag();
        var basicRoute = new dev.scex.si.processing.MiningRoute(() -> {});
        basicRoute.begin(net.minecraft.core.BlockPos.ZERO, net.minecraft.core.BlockPos.ZERO.below(), 1750);
        basicRoute.markPaid(); basicRoute.advance(basicRoute.next());
        saved.put("scex_mining_route", basicRoute.save()); saved.put("scex_pipe_advance", opaque);
        saved.putLong("scex_mining_cost", 1750); saved.putInt("CurrentOreIndex", -1); saved.putInt("OreCount", 0);
        basic.loadAdditional(saved, registries);
        var roundtrip = basic.saved(registries);
        require(roundtrip.getCompound("scex_mining_route").getBoolean("paid") && roundtrip.getLong("scex_mining_cost") == 1750
            && roundtrip.getCompound("scex_pipe_advance").equals(opaque) && roundtrip.getInt("CurrentOreIndex") == 0,
            "Actual basic miner retains completed paid route, cost, and unresolved pipe state through NBT");
    }

    private static final class ElectrolyzerTile extends com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_electrolyzer_elc {
        ElectrolyzerTile() {
            super(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);
            energyStorage.scexSetNetworkControlled(true);
        }
        @Override protected java.util.Optional<dev.scex.si.processing.RecipeSlots.Prepared> prepareCell() {
            // No SI item registration exists in this bootstrap. Exercise the actual machine with an owned vanilla vessel fixture.
            if (!itemHandler.getStackInSlot(INPUT_SLOT).is(Items.WATER_BUCKET)) return java.util.Optional.empty();
            return dev.scex.si.processing.RecipeSlots.prepare(itemHandler, INPUT_SLOT, 1, new int[]{OUTPUT_SLOT},
                java.util.List.of(new ItemStack(Items.BUCKET)));
        }
        void input(int count) { itemHandler.setStackInSlot(INPUT_SLOT, new ItemStack(Items.WATER_BUCKET, count)); }
        void output(ItemStack item) { itemHandler.setStackInSlot(OUTPUT_SLOT, item); }
        ItemStack input() { return itemHandler.getStackInSlot(INPUT_SLOT).copy(); }
        ItemStack output() { return itemHandler.getStackInSlot(OUTPUT_SLOT).copy(); }
        void seedEnergy(long amount) { apiSetEnergy(amount); }
        long storedEnergy() { return apiGetStoredEnergy(); }
        long paidCredit() { return energyAccumulator; }
        long legacyCredit() { return legacyCellCredit; }
        void step() { doWork(); updateProgress(); }
        long offer(com.singularity_iteration.mio_icif.energy.EnergyUnit.IEUEnergyStorage target, long limit) {
            return offerChemical(target, limit);
        }
        net.minecraft.nbt.CompoundTag saved(net.minecraft.core.HolderLookup.Provider registries) {
            var tag = new net.minecraft.nbt.CompoundTag(); saveAdditional(tag, registries); return tag;
        }
    }
    private static void verifyElectrolysis(String candidatePath) throws Exception {
        require(Path.of(com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_electrolyzer_elc.class
            .getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(candidatePath).toRealPath()),
            "Actual electrolyzer implementation comes from the current candidate");
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var machine = new ElectrolyzerTile(); machine.input(1); machine.seedEnergy(200);
        for (int tick = 0; tick < 19; tick++) machine.step();
        require(machine.getChemicalEnergy() == 0 && machine.paidCredit() == 190 && machine.input().getCount() == 1
            && machine.output().isEmpty() && machine.storedEnergy() == 10,
            "Before whole-cell completion, debit remains paid work and is not exportable chemical energy");
        machine.step();
        require(machine.getChemicalEnergy() == 200 && machine.paidCredit() == 0 && machine.input().isEmpty()
            && machine.output().is(Items.BUCKET) && machine.output().getCount() == 1 && machine.storedEnergy() == 0,
            "One complete cell publishes exactly its paid energy and preserves its return container");
        machine.step(); require(machine.getChemicalEnergy() == 200 && machine.output().getCount() == 1,
            "No input cannot replay the last completed cell");
        var outputBlocked = new ElectrolyzerTile(); outputBlocked.input(1); outputBlocked.seedEnergy(200);
        var named = new ItemStack(Items.BUCKET); named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep separate"));
        outputBlocked.output(named); outputBlocked.step();
        require(outputBlocked.storedEnergy() == 200 && outputBlocked.paidCredit() == 0 && outputBlocked.input().getCount() == 1,
            "Incompatible return container blocks all payment and input mutation");
        var partial = new ElectrolyzerTile(); partial.input(1); partial.seedEnergy(7); partial.step();
        require(partial.paidCredit() == 7 && partial.storedEnergy() == 0 && partial.getChemicalEnergy() == 0,
            "A small final machine balance is conserved as partial paid work");
        var resumed = new ElectrolyzerTile(); resumed.loadAdditional(partial.saved(registries), registries);
        require(resumed.paidCredit() == 7 && resumed.legacyCredit() == 0 && resumed.input().getCount() == 1,
            "Canonical paid work is distinct from legacy overlap during reload");
        for (long legacyProgress : new long[]{0, 150, 200, 400, Long.MAX_VALUE}) {
            var legacy = new ElectrolyzerTile();
            var tag = new net.minecraft.nbt.CompoundTag(); tag.putLong("ChemicalEnergy", 100); tag.putLong("EnergyAccumulator", legacyProgress);
            legacy.loadAdditional(tag, registries); legacy.input(1); legacy.seedEnergy(200);
            long oldUsed = Math.min(200, legacyProgress), fresh = 200 - oldUsed;
            for (int tick = 0; tick < 20; tick++) legacy.step();
            require(legacy.getChemicalEnergy() == 100 + fresh && legacy.storedEnergy() == 200 - fresh
                && legacy.legacyCredit() == legacyProgress - oldUsed && legacy.output().getCount() == 1,
                "Legacy already-exportable energy settles cell debt without a second chemical-energy credit");
            var again = new ElectrolyzerTile(); again.loadAdditional(legacy.saved(registries), registries);
            require(again.getChemicalEnergy() == legacy.getChemicalEnergy() && again.legacyCredit() == legacy.legacyCredit()
                && again.paidCredit() == legacy.paidCredit(), "Legacy conversion becomes canonical and idempotent");
        }
        var capacity = new ElectrolyzerTile(); var fullTag = new net.minecraft.nbt.CompoundTag();
        fullTag.putInt("scex_electrolysis_version", 1); fullTag.putLong("ChemicalEnergy", 300);
        capacity.loadAdditional(fullTag, registries); capacity.input(1); capacity.seedEnergy(200); capacity.step();
        require(capacity.getChemicalEnergy() == 300 && capacity.storedEnergy() == 200 && capacity.paidCredit() == 0,
            "Whole chemical-output capacity is admitted before accepting new paid work");
        var receiver = miningEnergy(0); receiver.setMaxReceive(7);
        require(machine.offer(receiver, 32) == 7 && machine.getChemicalEnergy() == 193 && receiver.getAmount() == 7,
            "Chemical output subtracts only actual partial receiver acceptance");
        var recursive = new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(1000, 32, 0,
                com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV) {
            @Override public long receive(long amount, boolean simulate) {
                require(machine.offer(receiver, 32) == 0, "Receiver callback cannot reenter chemical output");
                return super.receive(amount, simulate);
            }
        };
        require(machine.offer(recursive, 32) == 32 && machine.getChemicalEnergy() == 161 && recursive.getAmount() == 32,
            "Predebit protects chemical output across receiver callbacks");
        var broken = new com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage(1000, 32, 0,
                com.singularity_iteration.mio_icif.energy.EnergyUnit.CableTier.LV) {
            @Override public long receive(long amount, boolean simulate) { throw new IllegalStateException("intentional target failure"); }
        };
        boolean rejected = false; try { machine.offer(broken, 32); } catch (IllegalStateException expected) { rejected = true; }
        var receipt = machine.saved(registries);
        require(rejected && receipt.getLong("scex_uncertain_output") == 32 && machine.getChemicalEnergy() == 129,
            "Unknown receiver outcome retains a separate amount receipt instead of blindly refunding and replaying it");
        var held = new ElectrolyzerTile(); held.loadAdditional(receipt, registries);
        require(held.offer(receiver, 32) == 0 && held.saved(registries).getLong("scex_uncertain_output") == 32,
            "Uncertain transfer remains explicitly paused after reload");
        var negative = new ElectrolyzerTile(); var negativeTag = new net.minecraft.nbt.CompoundTag();
        negativeTag.putLong("ChemicalEnergy", -10); negativeTag.putLong("EnergyAccumulator", -50); negative.loadAdditional(negativeTag, registries);
        require(negative.getChemicalEnergy() == 0 && negative.legacyCredit() == 0 && negative.paidCredit() == 0,
            "Negative old counters cannot create output or paid work");
    }

    private static com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats upgradeStats(int speed, int capacity, int transformer,
            java.util.List<com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats.DirectionalUpgrade> directions) {
        return new com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats(speed, capacity, transformer,
            1, 0, 0, 0, false, directions, java.util.List.of(), java.util.List.of(), java.util.List.of());
    }
    private static void verifyUpgradeSnapshots(String candidatePath) throws Exception {
        require(Path.of(com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toRealPath().equals(Path.of(candidatePath).toRealPath()), "Upgrade statistics use the newly compiled owner");
        var directions = new java.util.ArrayList<com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats.DirectionalUpgrade>();
        directions.add(new com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats.DirectionalUpgrade(net.minecraft.core.Direction.NORTH, 1));
        var stats = upgradeStats(2, 3, 1, directions); directions.clear();
        require(stats.getEjectorDirections().equals(java.util.List.of(net.minecraft.core.Direction.NORTH)),
            "Caller mutation cannot alter a cached direction snapshot");
        stats.getEjectorDirections().clear();
        require(stats.getEjectorDirections().size() == 1, "Returned direction list cannot mutate the stored snapshot");
        directions.add(new com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats.DirectionalUpgrade(null, 0));
        directions.add(new com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats.DirectionalUpgrade(net.minecraft.core.Direction.SOUTH, -2));
        directions.add(null);
        require(upgradeStats(0, 0, 0, directions).getEjectorDirections().isEmpty(), "Empty and invalid directional entries cannot activate automation");
        var invalid = upgradeStats(-1, -2, -3, java.util.List.of());
        require(invalid.overclockerCount == 0 && invalid.energyStorageCount == 0 && invalid.transformerCount == 0
            && invalid.getEnergyCapacityBonus() == 0 && invalid.getEnergyUsageMultiplier() == 1,
            "Negative external statistics cannot reduce costs or create negative capacity");
        require(stats.getEnergyCapacityBonus() == 32000 && stats.getHeatCapacity(1000) == 31000,
            "Ordinary energy/HU upgrade values retain their separate bonuses");
        var huge = upgradeStats(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, java.util.List.of());
        require(huge.getHeatCapacity(1000) == 21474836471000L && huge.getHeatCapacity(Long.MAX_VALUE - 5) == Long.MAX_VALUE,
            "Heat capacity uses a wide sum and saturates only at the actual long boundary");
        var overclockApi = (com.singularity_iteration.mio_icif.api.item.IItemAPI) java.lang.reflect.Proxy.newProxyInstance(
            FuelContainerContract.class.getClassLoader(), new Class<?>[]{com.singularity_iteration.mio_icif.api.item.IItemAPI.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUpgradeType" -> "overclocker";
                case "getUpgradeDirection" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        var inventory = new Inventory(2);
        inventory.setStackInSlot(0, new ItemStack(Items.PAPER, Integer.MAX_VALUE));
        inventory.setStackInSlot(1, new ItemStack(Items.PAPER, 2));
        var overflow = com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats.fromInventory(inventory, 0, 2, overclockApi);
        require(overflow.overclockerCount == Integer.MAX_VALUE && inventory.getStackInSlot(0).getCount() == Integer.MAX_VALUE
            && inventory.getStackInSlot(1).getCount() == 2, "Overflow saturates the int summary without modifying any item stack");
        var cropped = com.singularity_iteration.mio_icif.Items.Upgrade.MachineUpgradeStats.fromInventory(inventory, 1, Integer.MAX_VALUE, overclockApi);
        require(cropped.overclockerCount == 2, "Huge slot range stops at the actual inventory boundary");
        var highTier = (com.singularity_iteration.mio_icif.api.energy.ICableTier) java.lang.reflect.Proxy.newProxyInstance(
            FuelContainerContract.class.getClassLoader(), new Class<?>[]{com.singularity_iteration.mio_icif.api.energy.ICableTier.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getPowerRating")) return Long.MAX_VALUE;
                throw new UnsupportedOperationException(method.getName());
            });
        require(huge.getEffectiveCableTier(highTier) == highTier, "Transformer upgrade cannot downgrade an addon tier above the registry maximum");
        var heat = new HeatTile(); heat.storageUpgrades(300000);
        require(heat.getMaxHeatStored() == 3000001000L, "Actual HU owner applies a capacity above signed-int range");
        heat.setHeat(2900000000L);
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var restored = new HeatTile(); restored.loadAdditional(heat.saved(registries), registries);
        require(restored.getHeatStored() == 2900000000L && restored.getMaxHeatStored() == 1000,
            "Load before upgrade-inventory restoration preserves the full existing heat balance");
        restored.storageUpgrades(300000);
        require(restored.getHeatStored() == 2900000000L && restored.getMaxHeatStored() == 3000001000L,
            "Later upgrade restoration changes admission capacity without changing saved heat");
        restored.storageUpgrades(0);
        require(restored.getHeatStored() == 2900000000L && restored.getMaxHeatStored() == 1000 && !restored.canReceiveHeat(),
            "Removing an upgrade preserves existing heat and closes further input above capacity");
        require(restored.extractHeat(100, false) == 100 && restored.getHeatStored() == 2899999900L,
            "Over-capacity saved heat remains available for ordinary output");
        restored.setHeat(-1); require(restored.getHeatStored() == 0, "Negative saved heat cannot create balance");
    }

    private static final class PumpTile extends com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pump_elc {
        final java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> cells = new java.util.HashMap<>();
        long clock;
        int reads, removed;
        boolean reject;
        Runnable duringRemoval = () -> {};
        PumpTile() {
            super(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);
            cells.put(net.minecraft.core.BlockPos.ZERO.below(), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        }
        @Override protected boolean operational() { return true; }
        @Override protected long currentTick() { return clock; }
        @Override protected net.minecraft.core.Direction getFacing() { return net.minecraft.core.Direction.DOWN; }
        @Override protected net.minecraft.world.level.block.state.BlockState sourceState(net.minecraft.core.BlockPos pos) {
            reads++; return cells.getOrDefault(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
        @Override protected boolean removeSource(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState expected,
                                                 net.minecraft.world.level.material.Fluid fluid) {
            duringRemoval.run();
            if (reject || cells.get(pos) != expected) return false;
            cells.remove(pos); removed++; return true;
        }
        void step() { clock++; tickProduction(); }
        void containerStep() { onTick(); }
        void inputChanged() { checkInputChanged(); }
        int batterySlot() { return getBatterySlot(); }
        void power(long amount) { energyStorage.setEnergy(amount); }
        void slot(int index, ItemStack stack) { itemHandler.setStackInSlot(index, stack); }
    }
    private static void verifyIndependentPump(String candidatePath) throws Exception {
        require(Path.of(com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_pump_elc.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
            .equals(Path.of(candidatePath).toRealPath()), "Pump contracts use the independently compiled replacement");
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var machine = new PumpTile(); machine.power(20);
        for (int tick = 1; tick <= 20; tick++) {
            machine.step();
            require(machine.getEnergyStorage().getAmount() == 20 - tick && machine.getProgress() == tick
                && machine.getFluidAmount() == 0 && machine.removed == 0,
                "R49 direct water trace: twenty paid steps, source and tank unchanged until next step");
        }
        machine.forceStartWork(); machine.inputChanged();
        var paid = new net.minecraft.nbt.CompoundTag(); machine.saveAdditional(paid, registries);
        var restored = new PumpTile(); restored.loadAdditional(paid, registries); restored.step();
        require(!restored.hasUnmappedLegacy() && restored.getEnergyStorage().getAmount() == 0 && restored.getProgress() == 0
            && restored.getFluidAmount() == 1000 && restored.removed == 1, "Saved complete payment yields one bucket on next step without a further EU debit");
        for (int i = 0; i < 25; i++) restored.step();
        require(restored.getFluidAmount() == 1000 && restored.removed == 1, "No duplicate fluid after the source is consumed");
        require(restored.getItemHandler().getSlots() == 7 && restored.getContainerData().getCount() == 7
            && restored.batterySlot() == 0, "Preserve SI pump slot and menu ABI");
        var port = restored.getFluidHandlerCapability(null);
        require(port.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE) == 0, "Output capability rejects external fill");
        var copy = port.getFluidInTank(0); copy.setAmount(1);
        require(restored.getFluidAmount() == 1000, "External inspection cannot mutate the owned tank");
        require(port.drain(1, IFluidHandler.FluidAction.SIMULATE).getAmount() == 1 && restored.getFluidAmount() == 1000,
            "Drain simulation is read-only");
        restored.slot(5, new ItemStack(Items.BUCKET));
        restored.slot(6, new ItemStack(Items.STONE)); restored.containerStep();
        require(restored.getFluidAmount() == 1000 && restored.getItemHandler().getStackInSlot(5).is(Items.BUCKET), "Blocked container output retains fluid and input");
        restored.slot(6, ItemStack.EMPTY); restored.containerStep();
        require(restored.getFluidAmount() == 0 && restored.getItemHandler().getStackInSlot(5).isEmpty()
            && restored.getItemHandler().getStackInSlot(6).is(Items.WATER_BUCKET), "Owned bucket transaction consumes one bucket and exact fluid");
        var starved = new PumpTile(); starved.power(7);
        for (int i = 0; i < 12; i++) starved.step();
        require(starved.getProgress() == 7 && starved.getFluidAmount() == 0 && starved.removed == 0, "Partial power cannot remove a source");
        int starvedReads = starved.reads;
        for (int i = 0; i < 30; i++) starved.step();
        require(starved.reads == starvedReads, "Power-starved partial operation does not keep rescanning its path");
        starved.forceStopWork(); starved.inputChanged();
        var partial = new net.minecraft.nbt.CompoundTag(); starved.saveAdditional(partial, registries);
        var resumed = new PumpTile(); resumed.loadAdditional(partial, registries); resumed.power(13);
        for (int i = 0; i < 13; i++) resumed.step();
        require(resumed.getProgress() == 20 && resumed.getFluidAmount() == 0, "Public display reset and container changes do not erase payment across save/load");
        resumed.step(); require(resumed.removed == 1 && resumed.getFluidAmount() == 1000, "Restored partial work needs exactly the remaining thirteen EU");
        var veto = new PumpTile(); veto.loadAdditional(paid, registries); veto.reject = true; veto.step();
        require(veto.getProgress() == 20 && veto.getFluidAmount() == 0 && veto.removed == 0, "Rejected removal retains completed payment and source");
        veto.reject = false;
        veto.duringRemoval = () -> {
            require(veto.getFluidHandler().drain(1000, IFluidHandler.FluidAction.EXECUTE).isEmpty()
                && !veto.injectFluid(Fluids.WATER, 1), "Reentrant tank calls cannot mutate a world removal transaction");
        };
        for (int i = 0; i < 25; i++) veto.step();
        require(veto.getProgress() == 0 && veto.getFluidAmount() == 1000 && veto.removed == 1, "Retry completes one held operation without charging again");
        var vanished = new PumpTile(); vanished.loadAdditional(paid, registries);
        vanished.cells.clear(); vanished.step();
        require(vanished.getProgress() == 20 && vanished.getFluidAmount() == 0, "Disappeared source cannot produce fluid");
        var fullTank = new FluidTank(16000); fullTank.setFluid(new FluidStack(Fluids.WATER, 15500));
        var fullTag = paid.copy(); fullTag.getCompound("scex_pump_v1").put("Tank", fullTank.writeToNBT(registries, new net.minecraft.nbt.CompoundTag()));
        var full = new PumpTile(); full.loadAdditional(fullTag, registries); full.step();
        require(full.getFluidAmount() == 15500 && full.getProgress() == 20 && full.removed == 0, "Whole-source output space must exist before removal");
        full.getFluidHandler().drain(500, IFluidHandler.FluidAction.EXECUTE); full.step();
        require(full.getFluidAmount() == 16000 && full.getProgress() == 0 && full.removed == 1, "Freeing exact space releases one paid bucket");
        var lava = new PumpTile(); lava.loadAdditional(paid, registries);
        lava.cells.put(net.minecraft.core.BlockPos.ZERO.below(), net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState()); lava.step();
        require(lava.getFluid().getFluid() == Fluids.LAVA && lava.getFluidAmount() == 1000, "Candidate lava path uses actual selected fluid identity");
        var heldTag = paid.copy(); heldTag.getCompound("scex_pump_v1").put("UncertainRemoval", new net.minecraft.nbt.CompoundTag());
        var held = new PumpTile(); held.loadAdditional(heldTag, registries); held.step();
        require(held.hasUncertainRemoval() && held.getProgress() == 20 && held.removed == 0, "Uncertain saved world removal is held instead of replayed");
        var legacy = new net.minecraft.nbt.CompoundTag(); legacy.putString("old_pump_fluid", "preserve-unmapped");
        held.loadAdditional(legacy, registries); held.step();
        var kept = new net.minecraft.nbt.CompoundTag(); held.saveAdditional(kept, registries);
        require(held.hasUnmappedLegacy() && kept.getCompound("scex_pump_v1").getCompound("UnmappedLegacy").equals(legacy), "Unknown pump saves are retained verbatim");
        for (int invalid : new int[]{-1, 21, Integer.MAX_VALUE}) {
            var broken = paid.copy(); broken.getCompound("scex_pump_v1").putInt("PaidWork", invalid);
            held.loadAdditional(broken, registries); held.step();
            require(held.hasUnmappedLegacy() && held.getFluidAmount() == 0, "Malformed work values cannot mint fluid");
        }
        var malformedHold = paid.copy(); malformedHold.getCompound("scex_pump_v1").putString("UncertainRemoval", "bad-type");
        held.loadAdditional(malformedHold, registries); held.step();
        require(held.hasUnmappedLegacy() && held.removed == 0, "Malformed uncertainty markers fail closed instead of resuming removal");
        var flow = new PumpTile(); flow.power(20); flow.cells.clear();
        var flowingWater = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 1);
        for (int x = 0; x < 12; x++) flow.cells.put(new net.minecraft.core.BlockPos(x, -1, 0), flowingWater);
        flow.cells.put(new net.minecraft.core.BlockPos(12, -1, 0), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        for (int i = 0; i < 30 && flow.getProgress() < 7; i++) flow.step();
        require(flow.getProgress() == 7 && flow.removed == 0 && flow.getEnergyStorage().getAmount() == 13,
            "Machine resumes incremental path search before paying exactly seven work steps");
        flow.cells.remove(new net.minecraft.core.BlockPos(6, -1, 0));
        for (int i = 0; i < 25; i++) flow.step();
        require(flow.getProgress() == 7 && flow.removed == 0 && flow.getEnergyStorage().getAmount() == 13,
            "Broken cached fluid connection prevents further payment and remote source removal");
        flow.cells.put(new net.minecraft.core.BlockPos(6, -1, 0), flowingWater);
        for (int i = 0; i < 65 && flow.removed == 0; i++) flow.step();
        require(flow.removed == 1 && flow.getFluidAmount() == 1000 && flow.getEnergyStorage().getAmount() == 0,
            "Restored flowing path completes one source with original paid work preserved");
        var idle = new PumpTile(); idle.cells.clear(); idle.power(20);
        for (int i = 0; i < 100; i++) idle.step();
        require(idle.reads <= 5 && idle.getEnergyStorage().getAmount() == 20, "Empty idle pump backs off source queries and does not consume power");
        var search = new dev.scex.si.processing.FluidSourceSearch(); search.begin(net.minecraft.core.BlockPos.ZERO);
        int[] queries = {0};
        for (int i = 0; i < 300 && search.active(); i++) {
            int before = queries[0];
            require(search.advance(pos -> { queries[0]++; return 1; }, Integer.MAX_VALUE).isEmpty()
                && queries[0] - before <= 16 && search.visitedCount() <= 2048, "Large fluid field search has bounded step and memory costs");
        }
        require(search.exhausted() && queries[0] <= 2048, "Source-free field ends within the configured search cap");
        search.begin(net.minecraft.core.BlockPos.ZERO);
        java.util.List<net.minecraft.core.BlockPos> route = java.util.List.of();
        for (int i = 0; i < 30 && route.isEmpty() && search.active(); i++) {
            route = search.advance(pos -> pos.getY() != 0 || pos.getZ() != 0 || pos.getX() < 0 || pos.getX() > 12 ? 0 : pos.getX() == 12 ? 2 : 1, 16);
        }
        require(route.size() == 13 && route.getFirst().equals(net.minecraft.core.BlockPos.ZERO)
            && route.getLast().equals(new net.minecraft.core.BlockPos(12, 0, 0)), "Incremental search resumes along a connected flowing path");
    }

    private static final class CondenserTile extends com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_condenser {
        CondenserTile() {
            super(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState(),
                net.minecraft.world.level.block.entity.BlockEntityType.FURNACE);
        }
        @Override protected net.minecraft.world.level.material.Fluid steamFluid() { return Fluids.WATER; }
        @Override protected net.minecraft.world.level.material.Fluid distilledFluid() { return Fluids.LAVA; }
        void step() { tickProduction(); }
        void inputChanged() { checkInputChanged(); }
    }
    private static void verifyIndependentCondenser(String candidatePath) throws Exception {
        require(Path.of(com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_condenser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
            .equals(Path.of(candidatePath).toRealPath()), "Newly compiled independent condenser body");
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var machine = new CondenserTile();
        machine.getSteamTank().setFluid(new FluidStack(Fluids.WATER, 10000));
        long energyBefore = machine.getEnergyStorage().getAmount();
        for (int tick = 1; tick <= 100; tick++) {
            machine.step();
            require(machine.getSteamTank().getFluidAmount() == 10000 - 100 * tick
                && machine.getReservedSteam() == 100L * tick && machine.getDistilledTank().isEmpty(),
                "R50 unaccelerated trace: source debit becomes saved steam credit before product exists");
        }
        require(machine.getProgress() == 10000 && machine.getMaxProgress() == 10000, "Progress reflects consumed steam units");
        var paid = new net.minecraft.nbt.CompoundTag(); machine.saveAdditional(paid, registries);
        var resumed = new CondenserTile(); resumed.loadAdditional(paid, registries);
        require(!resumed.hasUnmappedLegacy() && resumed.getReservedSteam() == 10000, "Complete steam credit survives actual machine save/load");
        resumed.step();
        require(resumed.getReservedSteam() == 0 && resumed.getDistilledTank().getFluidAmount() == 100
            && resumed.getSteamTank().isEmpty(), "R50 next step publishes exactly one 100 mB product batch");
        for (int i = 0; i < 3; i++) resumed.step();
        require(resumed.getDistilledTank().getFluidAmount() == 100 && resumed.getEnergyStorage().getAmount() == energyBefore,
            "No duplicate batch or unmeasured baseline energy consumption");
        require(resumed.getItemHandler().getSlots() == 8 && resumed.getContainerData().getCount() == 6,
            "Preserve existing SI slot and menu integration shape");
        var port = resumed.getFluidHandlerCapability(net.minecraft.core.Direction.NORTH);
        require(port.getTanks() == 2 && port.fill(new FluidStack(Fluids.LAVA, 100), IFluidHandler.FluidAction.EXECUTE) == 0,
            "External fill cannot insert fluid into output tank");
        require(port.fill(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE) == 250
            && port.drain(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE).isEmpty(),
            "External steam input is accepted but not exposed as drainable product");
        require(port.drain(37, IFluidHandler.FluidAction.EXECUTE).getAmount() == 37
            && resumed.getDistilledTank().getFluidAmount() == 63, "External drain returns actual finished product only");
        resumed.step(); resumed.inputChanged();
        require(resumed.getReservedSteam() == 100 && resumed.getProgress() == 100,
            "Unrelated input item changes do not erase consumed steam");
        resumed.getDistilledTank().setFluid(new FluidStack(Fluids.LAVA, 9950));
        int before = resumed.getSteamTank().getFluidAmount(); resumed.step();
        require(resumed.getReservedSteam() == 100 && resumed.getSteamTank().getFluidAmount() == before,
            "Insufficient complete-batch output space blocks new debit without erasing credit");
        var full = new CondenserTile(); full.loadAdditional(paid, registries);
        full.getDistilledTank().setFluid(new FluidStack(Fluids.LAVA, 10000)); full.step();
        require(full.getReservedSteam() == 10000 && full.getDistilledTank().getFluidAmount() == 10000,
            "Completed batch remains reserved while output is full");
        full.getDistilledTank().drain(100, IFluidHandler.FluidAction.EXECUTE); full.step();
        require(full.getReservedSteam() == 0 && full.getDistilledTank().getFluidAmount() == 10000,
            "Available space releases the held batch once");
        var partial = new CondenserTile(); partial.getSteamTank().setFluid(new FluidStack(Fluids.WATER, 37)); partial.step();
        var partialSave = new net.minecraft.nbt.CompoundTag(); partial.saveAdditional(partialSave, registries);
        var partialRestored = new CondenserTile(); partialRestored.loadAdditional(partialSave, registries);
        require(!partialRestored.hasUnmappedLegacy() && partialRestored.getReservedSteam() == 37
            && partialRestored.getSteamTank().isEmpty(), "Partial source amount stays as exact paid credit across reload");
        partialRestored.step();
        require(partialRestored.getReservedSteam() == 37 && partialRestored.getDistilledTank().isEmpty(), "Partial credit cannot create an unpaid product batch");
        var legacy = new net.minecraft.nbt.CompoundTag(); legacy.putString("unmappedOldTank", "retain-exactly");
        var held = new CondenserTile(); held.loadAdditional(legacy, registries); held.step();
        var heldSave = new net.minecraft.nbt.CompoundTag(); held.saveAdditional(heldSave, registries);
        require(held.hasUnmappedLegacy() && heldSave.getCompound("scex_condenser_v1").getCompound("UnmappedLegacy").equals(legacy),
            "Unknown legacy source data is retained verbatim instead of guessed or silently emptied");
        require(held.getFluidHandlerCapability(null).fill(new FluidStack(Fluids.WATER, 100), IFluidHandler.FluidAction.EXECUTE) == 0,
            "Held legacy state cannot accept replacement tank contents");
        var unknown = paid.copy(); unknown.getCompound("scex_condenser_v1").remove("SteamCredit");
        held.loadAdditional(unknown, registries); held.step();
        require(held.hasUnmappedLegacy() && held.getDistilledTank().isEmpty(), "Incomplete canonical save cannot normalize missing accounting into a fresh machine");
    }

    private static void verifyFluidTransferFailures() {
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        for (int scenario = 0; scenario < 7; scenario++) {
            final int mode = scenario;
            var source = new FluidTank(2000) {
                @Override public FluidStack drain(FluidStack requested, IFluidHandler.FluidAction action) {
                    if (action.execute() && mode == 0) throw new IllegalStateException("Before source debit");
                    var drained = super.drain(requested, action);
                    if (action.execute() && mode == 1) throw new IllegalStateException("After source debit");
                    if (action.execute() && mode == 4) return new FluidStack(Fluids.LAVA, drained.getAmount());
                    return drained;
                }
            };
            source.setFluid(new FluidStack(Fluids.WATER, 1000));
            var target = new FluidTank(2000) {
                @Override public int fill(FluidStack offered, IFluidHandler.FluidAction action) {
                    if (!action.execute() && mode == 5) return offered.getAmount() + 1;
                    if (!action.execute() && mode == 6) { offered.setAmount(5000); return 501; }
                    int accepted = super.fill(offered, action);
                    if (action.execute() && mode == 2) throw new IllegalStateException("After target acceptance");
                    return action.execute() && mode == 3 ? offered.getAmount() + 1 : accepted;
                }
            };
            var buffer = new dev.scex.si.processing.FluidTransferBuffer(() -> {});
            boolean failed = false;
            try { buffer.move(source, target, 500); }
            catch (IllegalStateException expected) { failed = true; }
            require(failed, "Fault-injected external fluid operation is reported");
            require(buffer.isBlocked() == (mode < 5), "Only an attempted external mutation leaves an uncertain hold");
            if (mode >= 5) {
                require(source.getFluidAmount() == 1000 && target.isEmpty() && buffer.pending().isEmpty(),
                    "Invalid simulation quote cannot debit a source");
                continue;
            }
            require(buffer.uncertainPhase().equals(mode == 2 || mode == 3 ? "fill" : "drain")
                && buffer.uncertain().getAmount() == 500, "Persist exact direction and reserved quantity of uncertain call");
            require(buffer.pending().isEmpty(), "Uncertain quantity is excluded from retryable output");
            var saved = (net.minecraft.nbt.CompoundTag) buffer.save(registries);
            var resumed = new dev.scex.si.processing.FluidTransferBuffer(() -> {});
            resumed.load(registries, saved);
            int left = source.getFluidAmount(), received = target.getFluidAmount();
            for (int retry = 0; retry < 3; retry++)
                require(resumed.move(source, target, 500) == 0, "An unresolved transfer is not replayed after load");
            require(source.getFluidAmount() == left && target.getFluidAmount() == received
                && resumed.isBlocked() && resumed.save(registries).equals(saved), "Retry preserves external state and recovery evidence");
            require(left == (mode == 0 ? 1000 : 500) && received == (mode == 2 || mode == 3 ? 500 : 0),
                "Observed external side effects are not inferred from exception alone");
            var exposed = resumed.uncertain(); exposed.setAmount(1);
            require(resumed.uncertain().getAmount() == 500, "Uncertain getter cannot change retained intent");
        }
        var source = new FluidTank(2000); source.setFluid(new FluidStack(Fluids.WATER, 1000));
        var target = new FluidTank(2000);
        var old = (net.minecraft.nbt.CompoundTag) new FluidStack(Fluids.WATER, 137).saveOptional(registries);
        var migrated = new dev.scex.si.processing.FluidTransferBuffer(() -> {});
        migrated.load(registries, old);
        require(!migrated.isBlocked() && migrated.move(source, target, 1000) == 137
            && source.getFluidAmount() == 1000 && target.getFluidAmount() == 137, "Legacy pending-fluid format migrates without re-extraction");
        var malformed = new net.minecraft.nbt.CompoundTag(); malformed.putInt("TransferVersion", 99); malformed.putString("Keep", "future-format");
        migrated.load(registries, malformed);
        require(migrated.isBlocked() && migrated.move(source, target, 1000) == 0
            && migrated.save(registries).equals(malformed), "Unknown save versions retain their full evidence without movement");
        var detachedSave = (net.minecraft.nbt.CompoundTag) migrated.save(registries); detachedSave.remove("Keep");
        require(migrated.save(registries).equals(malformed), "Opaque save data is defensively copied");
        var empty = new dev.scex.si.processing.FluidTransferBuffer(() -> {});
        var invalid = (net.minecraft.nbt.CompoundTag) empty.save(registries); invalid.putString("Phase", "fill");
        empty.load(registries, invalid);
        require(empty.isBlocked() && empty.save(registries).equals(invalid), "Missing reservation in an active phase cannot be normalized away");

        var snapshots = new java.util.ArrayList<net.minecraft.nbt.CompoundTag>();
        var holder = new dev.scex.si.processing.FluidTransferBuffer[1];
        holder[0] = new dev.scex.si.processing.FluidTransferBuffer(() -> snapshots.add((net.minecraft.nbt.CompoundTag) holder[0].save(registries)));
        var partial = new PartialTank(137);
        require(holder[0].move(source, partial, 500) == 137 && holder[0].pending().getAmount() == 363,
            "Successful partial transfer releases only rejected reservation back to pending output");
        require(snapshots.stream().anyMatch(t -> t.getString("Phase").equals("drain"))
            && snapshots.stream().anyMatch(t -> t.getString("Phase").equals("fill")), "Owner observes each intent before external mutation");
        for (var snapshot : snapshots) {
            if (snapshot.getString("Phase").isEmpty()) continue;
            var restarted = new dev.scex.si.processing.FluidTransferBuffer(() -> {}); restarted.load(registries, snapshot);
            require(restarted.isBlocked() && restarted.move(source, target, 1000) == 0,
                "A save taken by a callback cannot replay its in-flight external operation");
        }
        require(!holder[0].isBlocked(), "Valid response clears the temporary hold");
    }

    private static void verifyFluidTransfers(String candidatePath) throws Exception {
        require(Path.of(dev.scex.si.processing.FluidTransferBuffer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
            .equals(Path.of(candidatePath).toRealPath()), "Newly compiled transfer buffer origin");
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        for (int accepted : new int[]{0, 1, 137, 500}) {
            var source = new FluidTank(5000) {
                @Override public int fill(FluidStack resource, IFluidHandler.FluidAction action) { return 0; }
            };
            source.setFluid(new FluidStack(Fluids.WATER, 1000));
            var target = new PartialTank(accepted);
            int[] dirty = {0};
            var buffer = new dev.scex.si.processing.FluidTransferBuffer(() -> dirty[0]++);
            require(buffer.move(source, target, 500) == accepted, "Actual partial target acceptance");
            require(source.getFluidAmount() == 500 && target.getFluidAmount() == accepted
                && buffer.pending().getAmount() == 500 - accepted, "All extracted fluid is delivered or retained");
            require(dirty[0] > 0, "Retained transfer state marks its owner for saving");
            var save = (net.minecraft.nbt.CompoundTag) buffer.save(registries);
            var resumed = new dev.scex.si.processing.FluidTransferBuffer(() -> {});
            resumed.load(registries, save);
            require(FluidStack.matches(resumed.pending(), buffer.pending()), "Transfer remainder survives NBT serialization");
            if (accepted < 500) {
                target.actualLimit = 5000;
                require(resumed.move(source, target, 1000) == 500 - accepted && source.getFluidAmount() == 500,
                    "Resume remainder without another source debit or a refill-capable source");
                require(target.getFluidAmount() == 500 && resumed.pending().isEmpty(), "Resumed delivery consumes its remainder once");
            }
            var detached = buffer.pending();
            detached.setAmount(1);
            require(buffer.pending().getAmount() == 500 - accepted, "Pending stack getter cannot mutate accounting");
        }
        var undrainable = new FluidTank(2000) {
            @Override public FluidStack drain(int amount, IFluidHandler.FluidAction action) { return FluidStack.EMPTY; }
            @Override public FluidStack drain(FluidStack fluid, IFluidHandler.FluidAction action) { return FluidStack.EMPTY; }
        };
        undrainable.setFluid(new FluidStack(Fluids.WATER, 1000));
        var receiving = new FluidTank(2000);
        var buffer = new dev.scex.si.processing.FluidTransferBuffer(() -> {});
        require(buffer.move(undrainable, receiving, 1000) == 0 && receiving.isEmpty()
            && undrainable.getFluidAmount() == 1000, "A visible input reservoir that disallows extraction cannot duplicate fluid");
        var available = new FluidTank(2000); available.setFluid(new FluidStack(Fluids.WATER, 1000));
        var partial = new PartialTank(1);
        var other = new FluidTank(2000);
        partial.reenter = () -> require(buffer.move(available, other, 1000) == 0, "Same transfer buffer rejects reentrant movement");
        require(buffer.move(available, partial, 1000) == 1 && available.isEmpty()
            && partial.getFluidAmount() == 1 && other.isEmpty() && buffer.pending().getAmount() == 999,
            "Reentrant target preserves total fluid and one pending balance");
        require(buffer.move(available, other, 0) == 0 && buffer.pending().getAmount() == 999,
            "Zero budget leaves pending fluid untouched");
    }
}
