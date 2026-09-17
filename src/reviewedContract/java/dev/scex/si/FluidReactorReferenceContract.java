// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;
import dev.scex.si.reactor.FluidReactorCycle;
import dev.scex.si.reactor.ReactorCycle.Part;
import dev.scex.si.reactor.ReactorCycle.Profile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Expected values come exclusively from frozen ordinary saves, never from the implementation. */
public final class FluidReactorReferenceContract {
    private FluidReactorReferenceContract(){}
    private static final Map<String,Profile> PROFILES=Map.of("U1",Profile.fuel(1,false),"U4",Profile.fuel(4,false),"M1",Profile.fuel(1,true),"V",Profile.vent(1000,6,0),"VA",Profile.vent(1000,12,0),"VO",Profile.vent(1000,20,36));
    private static Part[] parse(String text){Part[] p=new Part[54];if(text.isEmpty())return p;
        for(String entry:text.split(";")){String[] e=entry.split(",");p[Integer.parseInt(e[0])]=new Part(PROFILES.get(e[1]),Integer.parseInt(e[2]),Integer.parseInt(e[3]));}return p;}
    private static String encode(Part[] p){StringBuilder s=new StringBuilder();for(int i=0;i<p.length;i++)if(p[i]!=null){
        if(!s.isEmpty())s.append(';');String key="?";for(var e:PROFILES.entrySet())if(e.getValue().equals(p[i].profile())){key=e.getKey();break;}
        s.append(i).append(',').append(key).append(',').append(p[i].stored()).append(',').append(p[i].remaining());}return s.toString();}
    public static void main(String[] args)throws Exception{
        Part[] parts=new Part[54];long heat=0;int cold=0,hot=0,checks=0,cases=0,failures=0;String label="";
        for(String line:Files.readAllLines(Path.of(args[0]))){String[] s=line.split("\t",-1);
            if(s[0].equals("CASE")){label=s[1];heat=Long.parseLong(s[2]);cold=Integer.parseInt(s[3]);hot=Integer.parseInt(s[4]);parts=parse(s[5]);cases++;}
            if(s[0].equals("STEP")){
                var result=FluidReactorCycle.step(parts,9,heat,Boolean.parseBoolean(s[2]),cold,hot,10000);
                var cycle=result.cycle();parts=cycle.parts();heat=cycle.hullHeat();cold=result.coolant();hot=result.hotCoolant();
                String actual=encode(parts);boolean pass=heat==Long.parseLong(s[3])&&cold==Integer.parseInt(s[4])&&hot==Integer.parseInt(s[5])&&actual.equals(s[6])&&cycle.euPerTick()==0;checks+=5;
                if(!pass){if(failures<20)System.out.println("MISMATCH "+label+" tick="+s[1]+" heat="+heat+"/"+s[3]+" cold="+cold+"/"+s[4]+" hot="+hot+"/"+s[5]+" actual="+actual+" expected="+s[6]);failures++;}
            }
        }
        System.out.println("SCEX_FLUID_REACTOR_REFERENCE cases="+cases+" checks="+checks+" failures="+failures);
        if(failures>0)throw new AssertionError("Reference mismatches: "+failures);
    }
}
