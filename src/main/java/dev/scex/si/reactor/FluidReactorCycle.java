// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.reactor;

/** Ordinary R119 tank and heat observations, including odd remaining millibuckets. */
public final class FluidReactorCycle {
    private FluidReactorCycle(){}
    public record Conversion(int millibuckets,long returnedHeat){}
    public record Result(ReactorCycle.Result cycle,int coolant,int hotCoolant,int converted,long returnedHeat){}
    public static Conversion convert(long emittedHeat,int coolant,int hotCoolant,int capacity){
        if(emittedHeat<0||coolant<0||hotCoolant<0||capacity<0||coolant>capacity||hotCoolant>capacity)
            throw new IllegalArgumentException("Invalid fluid reactor state");
        long available=Math.min(coolant,capacity-hotCoolant);
        int converted=(int)Math.min(Math.multiplyExact(emittedHeat,2),available);
        // Normal saves show one missing half-unit rounds down on return to the integer hull.
        return new Conversion(converted,emittedHeat-(converted+1L)/2);
    }
    public static Result step(ReactorCycle.Part[] parts,int columns,long heat,boolean enabled,int coolant,int hotCoolant,int capacity){
        var raw=ReactorCycle.step(parts,columns,heat,enabled,true);
        var converted=convert(raw.emittedHeat(),coolant,hotCoolant,capacity);
        var next=new ReactorCycle.Result(raw.parts(),Math.addExact(raw.hullHeat(),converted.returnedHeat()),raw.maxHullHeat(),
                raw.emittedHeat(),raw.generatedHeat(),0,raw.depletedFuel());
        return new Result(next,coolant-converted.millibuckets(),hotCoolant+converted.millibuckets(),converted.millibuckets(),converted.returnedHeat());
    }
}
