// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import dev.scex.energy.NetworkCell;
import dev.scex.energy.minecraft.IndependentTransformerBlockEntity;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Detached platform-entity serialization checks; never installs or replaces a world block entity. */
public final class TransformerStateProbe {
    private int checks;
    private TransformerStateProbe() { }
    private void check(boolean value) { checks++; if (!value) throw new AssertionError("Transformer state check " + checks); }
    public static Map<String,Object> run(ServerLevel world) { return new TransformerStateProbe().exercise(world); }
    private Map<String,Object> exercise(ServerLevel world) {
        var host=world.getBlockEntity(new BlockPos(4096,80,0));
        if (host==null) throw new IllegalStateException("Missing probe metadata host");
        for (long low : new long[]{32,128,512,2048}) {
            var entity=new IndependentTransformerBlockEntity(host.getType(),host.getBlockPos(),host.getBlockState(),low,1);
            entity.setLevel(world);
            var tag=new CompoundTag();tag.putDouble("buffer",73);tag.putInt("mode",1);
            entity.loadCustomOnly(tag,world.registryAccess());
            var quote=entity.snapshot(false).orElseThrow();
            check(quote.energy().amount()==73 && quote.limits().capacity()==8*low);
            check(quote.limits().outputPacket()==low && quote.limits().outputPackets()==4);
            check(entity.snapshot(true).orElseThrow().limits().outputPacket()==4*low);
            check(entity.isCurrent(quote,false) && !entity.isCurrent(quote,true));
            var supply=new NetworkCell(100);
            check(NetworkCell.commit(List.of(new NetworkCell.Write(supply.quote(),95),
                new NetworkCell.Write(quote.energy(),78)),0,()->entity.isCurrent(quote,false)));
            check(!entity.isCurrent(quote,false));
            var saved=entity.saveCustomOnly(world.registryAccess());
            check(saved.getDouble("buffer")==78 && saved.getInt("mode")==1);
            var beforeMode=entity.snapshot(false).orElseThrow();
            entity.setSavedMode(7);entity.setSavedMode(1);
            check(!entity.isCurrent(beforeMode,false));
            var beforeRemoval=entity.snapshot(false).orElseThrow();
            entity.setRemoved();check(entity.snapshot(false).isEmpty());
            entity.clearRemoved();check(entity.snapshot(false).orElseThrow().energy().amount()==78);
            check(!NetworkCell.commit(List.of(new NetworkCell.Write(beforeRemoval.energy(),78)),0,()->true));
            for (double unsupported : new double[]{0.5,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
                tag.putDouble("buffer",unsupported);entity.loadCustomOnly(tag,world.registryAccess());
                check(entity.snapshot(false).isEmpty());
                double retained=entity.saveCustomOnly(world.registryAccess()).getDouble("buffer");
                check(Double.doubleToLongBits(retained)==Double.doubleToLongBits(unsupported));
            }
            entity.loadCustomOnly(saved,world.registryAccess());
            check(entity.snapshot(false).orElseThrow().energy().amount()==78);
            entity.setRemoved();supply.retire();
        }
        return Map.of("passed",true,"checks",checks,"tiers",4,
            "scope","Detached independent entity; platform save/load and identity only. No factory, ticker, GUI or transformer world transfer acceptance.");
    }
}
