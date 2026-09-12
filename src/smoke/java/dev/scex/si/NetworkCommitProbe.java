// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** Exercises the actual commit entry on three real, disconnected SI block entities. */
public final class NetworkCommitProbe {
    private NetworkCommitProbe() { }
    private int assertions;
    private void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    public static Map<String,Object> run(ServerLevel level) {
        return new NetworkCommitProbe().exercise(level);
    }
    private Map<String,Object> exercise(ServerLevel level) {
        CustomEUEnergyStorage[] stores=new CustomEUEnergyStorage[3];
        for(int i=0;i<3;i++) {
            var at=new BlockPos(4096+i*2,80,0);
            var tile=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4).getBlockEntity(at,LevelChunk.EntityCreationType.CHECK);
            check(tile instanceof mio_icif_Energy_Block,"real-control-block");
            stores[i]=((mio_icif_Energy_Block)tile).getEnergyStorageInternal();
            check(stores[i].scexNetworkControlled(),"explicit-engine-ownership");
        }
        check(stores[0].getAmount()==100 && stores[1].getAmount()==0 && stores[2].getAmount()==0,"initial-disconnected-control-balances");
        var a=stores[0];var b=stores[1];var c=stores[2];
        var good=List.of(new CustomEUEnergyStorage.NetworkWrite(a,a.scexNetworkQuote(),68),
            new CustomEUEnergyStorage.NetworkWrite(b,b.scexNetworkQuote(),32));
        check(!CustomEUEnergyStorage.scexCommitNetwork(good,0,()->false),"invalid-world-lease-rejected");
        check(a.getAmount()==100 && b.getAmount()==0,"guard-rejection-keeps-both-balances");
        b.setEnergy(1);
        check(!CustomEUEnergyStorage.scexCommitNetwork(good,0,()->true),"stale-receiver-quote-rejected");
        check(a.getAmount()==100 && b.getAmount()==1,"stale-rejection-does-not-debit-source");
        b.setEnergy(0);
        boolean bad=false;
        try { CustomEUEnergyStorage.scexCommitNetwork(good,1,()->true); }
        catch(IllegalArgumentException expected){bad=true;}
        check(bad && a.getAmount()==100 && b.getAmount()==0,"nonconserving-batch-rejected-without-write");
        bad=false;
        try {CustomEUEnergyStorage.scexCommitNetwork(List.of(good.getFirst(),good.getFirst()),64,()->true);}
        catch(IllegalArgumentException expected){bad=true;}
        check(bad && a.getAmount()==100,"duplicate-storage-rejected");
        check(CustomEUEnergyStorage.scexCommitNetwork(good,0,()->true),"valid-batch-committed");
        check(a.getAmount()==68 && b.getAmount()==32,"exact-balanced-receipt");
        check(!CustomEUEnergyStorage.scexCommitNetwork(good,0,()->true),"replay-of-stale-balances-rejected");
        a.setEnergy(100); b.setEnergy(b.getCapacity()-10);
        var over=List.of(new CustomEUEnergyStorage.NetworkWrite(a,a.scexNetworkQuote(),36),
            new CustomEUEnergyStorage.NetworkWrite(b,b.scexNetworkQuote(),b.getCapacity()+54));
        check(CustomEUEnergyStorage.scexCommitNetwork(over,0,()->true),"planned-over-capacity-credit-commits");
        check(a.getAmount()==36 && b.getAmount()==b.getCapacity()+54,"over-capacity-credit-not-silently-clamped");
        check(b.consumeEnergyInternal(2,false)==2 && b.getAmount()==b.getCapacity()+52,"internal-use-spends-only-requested-energy");
        check(b.receive(1,false)==0 && b.generateEnergyInternal(1,false)==0 && b.getAmount()==b.getCapacity()+52,"full-store-rejects-positive-input-without-negative-receipt");
        var empty=List.of(new CustomEUEnergyStorage.NetworkWrite(c,c.scexNetworkQuote(),0));
        var oldLevel=level;var oldPos=new BlockPos(4100,80,0);
        check(!CustomEUEnergyStorage.scexCommitNetwork(empty,0,()->{c.setBlockContext(oldLevel,oldPos.above());return true;}),"changed-storage-context-rejected");
        c.setBlockContext(oldLevel,oldPos);
        a.setEnergy(100); b.setEnergy(0); c.setEnergy(0);
        check(a.getAmount()==100 && b.getAmount()==0 && c.getAmount()==0,"control-balances-restored");
        return Map.of("passed",true,"checks",assertions,"scope","Actual main-thread commit, guard/stale/duplicate/conservation/replay/over-capacity/context boundaries");
    }
}
