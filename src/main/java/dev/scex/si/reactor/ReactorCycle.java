// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.reactor;

import java.util.Arrays;
import java.util.Objects;

/**
 * Independent finite reactor model from ordinary R116 game observations.
 * No game dependency or access to an earlier reactor implementation.
 * One call is a 20-tick operation, including cooling when fuel is disabled.
 */
public final class ReactorCycle {
    private ReactorCycle() {}
    public enum Kind { FUEL, CELL, CONDENSATOR, VENT, COMPONENT_VENT, EXCHANGER, REFLECTOR, PLATING, INERT }
    public record Profile(Kind kind,int cells,boolean mox,int capacity,int selfCooling,
                          int hullCooling,int adjacentExchange,int hullExchange,int extraHull) {
        public Profile {
            Objects.requireNonNull(kind);
            if (cells<0||cells>4||capacity<0||selfCooling<0||hullCooling<0
                    ||adjacentExchange<0||hullExchange<0||extraHull<0) throw new IllegalArgumentException("Invalid reactor profile");
            if(kind==Kind.FUEL&&cells!=1&&cells!=2&&cells!=4)throw new IllegalArgumentException("Fuel cells must be 1, 2 or 4");
        }
        public static Profile fuel(int cells,boolean mox){return new Profile(Kind.FUEL,cells,mox,0,0,0,0,0,0);}
        public static Profile cell(int capacity){return thermal(Kind.CELL,capacity,0,0,0,0);}
        public static Profile condensator(int capacity){return thermal(Kind.CONDENSATOR,capacity,0,0,0,0);}
        public static Profile vent(int capacity,int self,int hull){return thermal(Kind.VENT,capacity,self,hull,0,0);}
        public static Profile componentVent(){return new Profile(Kind.COMPONENT_VENT,0,false,0,4,0,0,0,0);}
        public static Profile exchanger(int capacity,int adjacent,int hull){return thermal(Kind.EXCHANGER,capacity,0,0,adjacent,hull);}
        public static Profile reflector(int life){return new Profile(Kind.REFLECTOR,0,false,life,0,0,0,0,0);}
        public static Profile plating(int extra){return new Profile(Kind.PLATING,0,false,0,0,0,0,0,extra);}
        private static Profile thermal(Kind kind,int capacity,int self,int hull,int adjacent,int exchange){return new Profile(kind,0,false,capacity,self,hull,adjacent,exchange,0);}
        public boolean storesHeat(){return kind==Kind.CELL||kind==Kind.CONDENSATOR||kind==Kind.VENT||kind==Kind.EXCHANGER;}
    }
    /** Stored is heat for cooling components, wear for reflectors. Fuel uses remaining cycles. */
    public record Part(Profile profile,int stored,int remaining) {
        public Part {
            Objects.requireNonNull(profile);
            if(stored<0||remaining<0)throw new IllegalArgumentException("Negative component state");
            if(profile.storesHeat()&&stored>profile.capacity())throw new IllegalArgumentException("Component heat exceeds capacity");
            if(profile.kind()==Kind.REFLECTOR&&profile.capacity()>0&&stored>=profile.capacity())throw new IllegalArgumentException("Expired reflector");
        }
        public Part withStored(int amount){return new Part(profile,amount,remaining);}
        public Part withRemaining(int amount){return new Part(profile,stored,amount);}
    }
    public record Result(Part[] parts,long hullHeat,long maxHullHeat,long emittedHeat,
                         long generatedHeat,double euPerTick,boolean[] depletedFuel) {
        public Result {parts=parts.clone();depletedFuel=depletedFuel.clone();}
        @Override public Part[] parts(){return parts.clone();}
        @Override public boolean[] depletedFuel(){return depletedFuel.clone();}
    }
    public static Result step(Part[] input,int columns,long heat,boolean enabled){
        return step(input,columns,heat,enabled,false);
    }
    public static Result step(Part[] input,int columns,long heat,boolean enabled,boolean fluidMode){
        if(input.length!=54||columns<3||columns>9||heat<0)throw new IllegalArgumentException("Invalid reactor state");
        return new Work(input,columns,heat).run(enabled,fluidMode);
    }
    public static int selfPulses(int cells){return switch(cells){case 1->1;case 2->2;case 4->3;default->throw new IllegalArgumentException("Invalid cell count");};}
    public static int fuelHeat(int cells,int pulses){if(pulses<0||pulses>7)throw new IllegalArgumentException("Invalid pulse count");return Math.multiplyExact(2*cells,Math.multiplyExact(pulses,pulses+1));}
    public static double fuelEnergy(int cells,int pulses,boolean mox,long heat,long maxHeat){
        if(heat<0||maxHeat<=0||pulses<0||pulses>7)throw new IllegalArgumentException("Invalid fuel energy inputs");
        float factor=mox?1.0f+((float)heat/(float)maxHeat)*4.0f:1.0f;
        float sum=0;for(int i=0;i<cells*pulses;i++)sum+=factor;
        return sum*5.0f;
    }
    private static final class Work {
        final Part[] parts;final int columns;final boolean[] depleted=new boolean[54];
        long hull,max=10000,emitted,generated;
        Work(Part[] input,int columns,long heat){this.parts=input.clone();this.columns=columns;this.hull=heat;
            for(int i=0;i<54;i++)if(active(i)&&parts[i]!=null)max=Math.addExact(max,parts[i].profile().extraHull());}
        boolean active(int i){return i>=0&&i<54&&i%9<columns;}
        int[] adjacent(int slot){int[] a=new int[4];int count=0;
            for(int next:new int[]{slot-9,slot-1,slot+1,slot+9})
                if(active(next)&&Math.abs(next/9-slot/9)+Math.abs(next%9-slot%9)==1)a[count++]=next;
            return Arrays.copyOf(a,count);}
        boolean fuel(int slot){Part p=parts[slot];return p!=null&&p.profile().kind()==Kind.FUEL&&p.remaining()>0;}
        int pulses(int slot){int n=selfPulses(parts[slot].profile().cells());
            for(int a:adjacent(slot))if(parts[a]!=null&&(fuel(a)||parts[a].profile().kind()==Kind.REFLECTOR))n++;
            return n;}
        Result run(boolean enabled,boolean fluidMode){
            // Apply component operations in visible row order; fuel expiry is committed after electrical output.
            for(int i=0;i<54;i++){
                if(!active(i)||parts[i]==null)continue;Part p=parts[i];Profile profile=p.profile();
                switch(profile.kind()){
                    case FUEL -> {if(enabled&&fuel(i)){
                        int amount=0;
                        for(int cell=0;cell<profile.cells();cell++){
                            int pulses=selfPulses(profile.cells());
                            for(int a:adjacent(i))if(parts[a]!=null){
                                if(fuel(a))pulses++;
                                else if(parts[a].profile().kind()==Kind.REFLECTOR){
                                    pulses++;Part reflector=parts[a];int limit=reflector.profile().capacity();
                                    if(limit>0)parts[a]=reflector.stored()+1>=limit?null:reflector.withStored(reflector.stored()+1);
                                }
                            }
                            amount+=2*pulses*(pulses+1);
                        }
                        if(fluidMode&&profile.mox()&&hull>max/2)amount=Math.multiplyExact(amount,2);
                        generated+=amount;
                        while(amount>0){
                            int[] targets=Arrays.stream(adjacent(i)).filter(a->parts[a]!=null&&parts[a].profile().storesHeat()&&parts[a].profile().capacity()>0
                                &&(parts[a].profile().kind()!=Kind.CONDENSATOR||parts[a].stored()<parts[a].profile().capacity())).toArray();
                            if(targets.length==0){hull=Math.addExact(hull,amount);break;}
                            int share=amount/targets.length,remainder=amount%targets.length;amount=0;
                            for(int a:targets)amount+=add(a,share+(remainder-->0?1:0));
                        }
                    }}
                    case VENT -> {
                        int pull=(int)Math.min(hull,profile.hullCooling());hull-=pull;add(i,pull);
                        if(parts[i]!=null)emitted+=remove(i,profile.selfCooling());
                    }
                    case COMPONENT_VENT -> {for(int a:adjacent(i))if(parts[a]!=null&&parts[a].profile().storesHeat()
                            &&parts[a].profile().kind()!=Kind.CONDENSATOR)emitted+=remove(a,profile.selfCooling());}
                    case EXCHANGER -> {
                        long ownRatio=ratio(p.stored(),profile.capacity());
                        for(int a:adjacent(i))if(parts[a]!=null&&parts[a].profile().storesHeat()
                                &&parts[a].profile().kind()!=Kind.CONDENSATOR&&parts[a].profile().capacity()>0)exchange(i,a,profile.adjacentExchange(),ownRatio);
                        exchangeHull(i,profile.hullExchange(),ownRatio);
                    }
                    default -> { }
                }
            }
            float units=0;
            if(enabled){
                for(int i=0;i<54;i++)if(active(i)&&fuel(i)){
                    Part p=parts[i];float factor=factor(p);
                    for(int cell=0;cell<p.profile().cells();cell++){
                        for(int pulse=0;pulse<selfPulses(p.profile().cells());pulse++)units+=factor;
                        for(int a:adjacent(i))if(parts[a]!=null){
                            if(fuel(a))units+=factor(parts[a]);
                            else if(parts[a].profile().kind()==Kind.REFLECTOR)units+=factor;
                        }
                    }
                    depleted[i]=p.remaining()==1;parts[i]=p.withRemaining(p.remaining()-1);
                }
            }
            return new Result(parts,hull,max,emitted,generated,fluidMode?0:units*5.0f,depleted);
        }
        float factor(Part p){return p.profile().mox()?1.0f+((float)hull/(float)max)*4.0f:1.0f;}
        /** Return unabsorbed heat only for saturating condensators. Meltable components take their heat with them. */
        int add(int slot,int amount){if(amount<=0)return 0;Part p=parts[slot];if(p==null)return amount;
            long next=(long)p.stored()+amount;int capacity=p.profile().capacity();
            if(p.profile().kind()==Kind.CONDENSATOR){int accepted=Math.min(amount,Math.max(0,capacity-p.stored()));parts[slot]=p.withStored(p.stored()+accepted);return amount-accepted;}
            parts[slot]=next>capacity?null:p.withStored((int)next);return 0;
        }
        int remove(int slot,int amount){Part p=parts[slot];int accepted=Math.min(p.stored(),Math.max(0,amount));parts[slot]=p.withStored(p.stored()-accepted);return accepted;}
        long ratio(long amount,long capacity){return Math.round(1000.0*amount/capacity);}
        void exchange(int own,int other,int rate,long ownRatio){if(rate==0||parts[own]==null)return;Part a=parts[own],b=parts[other];
            long compare=ownRatio-ratio(b.stored(),b.profile().capacity());
            if(compare<0){int moved=Math.min(rate,Math.min(b.stored(),a.profile().capacity()-a.stored()));remove(other,moved);add(own,moved);}
            if(compare>0){int moved=Math.min(rate,Math.min(a.stored(),b.profile().capacity()-b.stored()));remove(own,moved);add(other,moved);}
        }
        void exchangeHull(int own,int rate,long ownRatio){if(rate==0||parts[own]==null)return;Part p=parts[own];
            long hullRatio=ratio(hull,max);
            if(ownRatio<hullRatio){int moved=(int)Math.min(rate,Math.min(hull,p.profile().capacity()-p.stored()));hull-=moved;add(own,moved);}
            if(ownRatio>hullRatio){int moved=Math.min(rate,p.stored());remove(own,moved);hull=Math.addExact(hull,moved);}
        }
    }
}
