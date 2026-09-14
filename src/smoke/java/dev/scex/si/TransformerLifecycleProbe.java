// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import dev.scex.energy.NetworkCell;
import dev.scex.energy.minecraft.IndependentTransformerBlockEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Checks identity revocation after ordinary fixture commands on installed world entities. */
public final class TransformerLifecycleProbe {
    private final List<BlockPos> positions=new ArrayList<>();
    private final List<IndependentTransformerBlockEntity> captured=new ArrayList<>();
    private final List<IndependentTransformerBlockEntity.Snapshot> quotes=new ArrayList<>();
    private int checks;
    public TransformerLifecycleProbe() throws Exception {
        for(var c:JsonParser.parseString(Files.readString(Path.of("transformer-lifecycle.json"))).getAsJsonArray()) {
            var a=c.getAsJsonObject().getAsJsonArray("transformer");
            positions.add(new BlockPos(a.get(0).getAsInt(),a.get(1).getAsInt(),a.get(2).getAsInt()));
        }
        if(positions.size()!=4) throw new IllegalArgumentException("Expected four frozen lifecycle positions");
    }
    private void check(boolean ok,String label) {
        checks++;
        if(!ok) throw new AssertionError("Installed transformer lifecycle: "+label);
    }
    private IndependentTransformerBlockEntity entity(ServerLevel world,BlockPos at) {
        var chunk=world.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
        if(chunk==null || !(chunk.getBlockEntity(at) instanceof IndependentTransformerBlockEntity found))
            throw new AssertionError("Independent installed transformer missing at "+at);
        return found;
    }
    private void rejectedMixedWrite(ServerLevel world,int i) {
        var source=(mio_icif_Energy_Block)world.getBlockEntity(positions.get(i).below());
        var storage=source.getEnergyStorageInternal();var fresh=storage.scexNetworkQuote();
        check(fresh.amount()>0,"source has a real debit available");
        var old=quotes.get(i).energy();
        check(!CustomEUEnergyStorage.scexCommitNetwork(
            List.of(new CustomEUEnergyStorage.NetworkWrite(storage,fresh,fresh.amount()-1)),
            List.of(new NetworkCell.Write(old,old.amount()+1)),0,()->true),"stale participant rejects mixed transaction");
        check(storage.scexNetworkQuote().amount()==fresh.amount(),"rejected transaction preserves ordinary source");
    }
    public Map<String,Object> inspect(ServerLevel world,int tick,boolean exercise) {
        if(!exercise) {
            if(tick!=20) return null;
            for(var at:positions) {
                var e=entity(world,at);var q=e.snapshot(false).orElseThrow();
                check(e.savedMode()==1 && q.energy().amount()==q.limits().capacity(),"loaded nonzero saturated buffer and mode");
                check(!e.isRemoved(),"loaded identity live");
            }
        } else if(tick==45 || tick==82) {
            captured.clear();quotes.clear();
            for(var at:positions) {
                var e=entity(world,at);captured.add(e);quotes.add(e.snapshot(false).orElseThrow());
            }
        } else if(tick==46 || tick==83 || tick==110) {
            for(int i=0;i<positions.size();i++) {
                var e=captured.get(i);
                check(!e.isCurrent(quotes.get(i),e.savedMode()==0),"captured world quote revoked");
                if(tick==46) check(e.savedMode()==0,"ordinary command changed mode");
                if(tick==83) check(e.isRemoved() && world.getBlockState(positions.get(i)).isAir(),"ordinary removal retired identity");
                if(tick==110) check(entity(world,positions.get(i))!=e && e.isRemoved(),"replacement has distinct identity");
                rejectedMixedWrite(world,i);
            }
        } else return null;
        return Map.of("passed",true,"checks",checks,"tick",tick,"exercise",exercise,"positions",positions.size());
    }
}
