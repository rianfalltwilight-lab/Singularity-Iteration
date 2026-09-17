// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.processing;

import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_scanner_elc;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Owned one-item pattern. Costs are supplied by a caller, never computed here. */
public final class StoredPattern {
    private final ItemStack item;
    private final double buckets;
    private final long energy;
    public StoredPattern(ItemStack item,double buckets,long energy) {
        if(!valid(item,buckets,energy))throw new IllegalArgumentException("Invalid one-item scan pattern");
        this.item=item.copy();this.buckets=buckets;this.energy=energy;
    }
    public static boolean valid(ItemStack item,double buckets,long energy) {
        return item!=null&&!item.isEmpty()&&item.getCount()==1&&validCosts(buckets,energy);
    }
    public static boolean validCosts(double buckets,long energy) {
        return Double.isFinite(buckets)&&buckets>0&&buckets<Long.MAX_VALUE/1000.0&&energy>=0;
    }
    public static StoredPattern from(mio_icif_scanner_elc.ScanResult value) {
        return value==null||!valid(value.item,value.uuMatterCostBuckets,value.energyCost)?null:
            new StoredPattern(value.item,value.uuMatterCostBuckets,value.energyCost);
    }
    public ItemStack item(){return item.copy();}
    public double buckets(){return buckets;}
    public long energy(){return energy;}
    public boolean sameItem(ItemStack other){return ItemStack.isSameItemSameComponents(item,other);}
    public boolean same(StoredPattern other){return other!=null&&sameItem(other.item)&&Double.compare(buckets,other.buckets)==0&&energy==other.energy;}
    /** Preserve the old SI public return type without retaining caller-owned stacks. */
    public mio_icif_scanner_elc.ScanResult legacyView(){return new mio_icif_scanner_elc.ScanResult(item.copy(),buckets,energy);}
    public CompoundTag save(HolderLookup.Provider registries) {
        var tag=new CompoundTag();tag.putInt("scex_pattern_version",1);tag.put("item",item.save(registries));
        tag.putDouble("uu_matter_cost_buckets",buckets);tag.putLong("energy_cost",energy);return tag;
    }
    /** The unversioned keys were observed through the SI R5 public saved-NBT boundary. */
    public static StoredPattern load(CompoundTag tag,HolderLookup.Provider registries) {
        for(var key:tag.getAllKeys())if(!java.util.Set.of("scex_pattern_version","item","uu_matter_cost_buckets","energy_cost").contains(key))return null;
        if(tag.contains("scex_pattern_version")&&(!tag.contains("scex_pattern_version",Tag.TAG_INT)||tag.getInt("scex_pattern_version")!=1))return null;
        if(!tag.contains("item",Tag.TAG_COMPOUND)||!tag.contains("uu_matter_cost_buckets",Tag.TAG_DOUBLE)||!tag.contains("energy_cost",Tag.TAG_LONG))return null;
        try {
            var item=ItemStack.parseOptional(registries,tag.getCompound("item"));
            double buckets=tag.getDouble("uu_matter_cost_buckets");long energy=tag.getLong("energy_cost");
            return valid(item,buckets,energy)?new StoredPattern(item,buckets,energy):null;
        }catch(RuntimeException invalid){return null;}
    }
}
