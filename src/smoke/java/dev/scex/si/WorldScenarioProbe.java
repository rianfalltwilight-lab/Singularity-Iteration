// SPDX-License-Identifier: Apache-2.0
// Vanilla commands and public saved-state observations on real world ticks.
package dev.scex.si;

import com.google.gson.Gson;
import dev.scex.si.energy.IndependentSiEnergy;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class WorldScenarioProbe {
    private final MinecraftServer server;
    private final Gson gson=new Gson();
    private final List<BlockPos> positions=new ArrayList<>();
    private final Map<Integer,List<String>> commands=new TreeMap<>();
    private BufferedWriter output;
    private int tick,observations,executed;
    private boolean finished;
    private final boolean observeWorldTime=Files.exists(Path.of("world-time-observation.json"));
    private final boolean observeEndpointSurface=Files.exists(Path.of("endpoint-surface.json"));
    private int phaseFrame=-1,phaseFrom=-1,phaseTo=-1;
    public WorldScenarioProbe(MinecraftServer server) throws Exception {
        this.server=server;
        for(String line:Files.readAllLines(Path.of("positions.tsv"))) {
            if(line.isBlank() || line.startsWith("#")) continue;
            String[] xyz=line.trim().split("\\s+");
            positions.add(new BlockPos(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2])));
        }
        if(positions.isEmpty() || positions.size()>512) throw new IllegalArgumentException("Invalid sample count");
        for(String line:Files.readAllLines(Path.of("commands.tsv"))) {
            if(line.isBlank() || line.startsWith("#")) continue;
            String[] row=line.split("\t",2);
            commands.computeIfAbsent(Integer.parseInt(row[0]),n->new ArrayList<>()).add(row[1]);
        }
        output=Files.newBufferedWriter(Path.of("observations.jsonl"));
        record("fixture-runtime",Map.of("si_code_source",
            com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container.class
                .getProtectionDomain().getCodeSource().getLocation().toString()));
        if(Boolean.getBoolean("scex.independent.energy")) {
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(Path.of("runtime-overlay.json"))).getAsJsonObject();
            var hashes=new ArrayList<Map<String,String>>();
            for(var item:manifest.getAsJsonArray("classes")) {
                var entry=item.getAsJsonObject();String name=entry.get("path").getAsString();
                if(name.contains("/energy/grid/") || name.contains("/api/energy/") || name.contains("/energy/leg/"))
                    throw new IllegalArgumentException("Unresolved implementation in runtime patch manifest");
                try(var data=IndependentSiEnergy.class.getClassLoader().getResourceAsStream(name)) {
                    if(data==null) throw new IllegalStateException("Missing runtime patch class: "+name);
                    String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data.readAllBytes()));
                    if(!actual.equals(entry.get("sha256").getAsString())) throw new IllegalStateException("Runtime patch class hash mismatch: "+name
                        +" expected="+entry.get("sha256").getAsString()+" actual="+actual
                        +" resource="+IndependentSiEnergy.class.getClassLoader().getResource(name));
                    hashes.add(Map.of("path",name,"sha256",actual));
                }
            }
            record("runtime-patch-hashes",Map.of("passed",true,"classes",hashes));
        }
        if(Files.exists(Path.of("independent-core.json"))) {
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(Path.of("independent-core.json"))).getAsJsonObject();
            var entries=manifest.getAsJsonArray("classes");
            if(entries.isEmpty() || entries.size()>256) throw new IllegalArgumentException("Invalid independent core class count");
            for(var item:entries) {
                var entry=item.getAsJsonObject();String name=entry.get("path").getAsString();
                if(!name.startsWith("dev/scex/energy/") || !name.endsWith(".class") || name.contains(".."))
                    throw new IllegalArgumentException("Invalid independent class path");
                try(var input=dev.scex.energy.DomainDistributor.class.getClassLoader().getResourceAsStream(name)) {
                    if(input==null) throw new IllegalStateException("Missing independent class: "+name);
                    String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                    if(!actual.equals(entry.get("sha256").getAsString())) throw new IllegalStateException("Independent core mismatch: "+name);
                }
            }
            record("independent-core-hashes",Map.of("passed",true,"classes",entries.size(),"artifact_sha256",manifest.get("jar_sha256").getAsString()));
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(this::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(this::onExplosionStart);
        NeoForge.EVENT_BUS.addListener(this::onExplosionDetonate);
        if(Boolean.getBoolean("scex.independent.energy")) {
            var observed=IndependentSiEnergy.current(server);
            if(observed==null) throw new IllegalStateException("Independent engine not attached before scenario");
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event)-> {
                    if(event.getServer()!=server) return;
                    try {
                        var metrics=observed.metrics();
                        Files.writeString(Path.of("independent-stop.json"),gson.toJson(Map.of("passed",
                            metrics.closed() && metrics.endpoints()==0 && metrics.dimensions()==0
                                && IndependentSiEnergy.current(server)==null,"metrics",metrics)));
                    } catch(Exception error){error.printStackTrace();}
                });
        }
        if(Files.exists(Path.of("phase-observation.json"))) {
            var window=com.google.gson.JsonParser.parseString(Files.readString(Path.of("phase-observation.json"))).getAsJsonObject();
            phaseFrom=window.get("from").getAsInt();phaseTo=window.get("to").getAsInt();
            if(phaseFrom<0 || phaseTo<phaseFrom || phaseTo-phaseFrom>80) throw new IllegalArgumentException("Bounded phase window required");
            var first=net.neoforged.bus.api.EventPriority.HIGHEST;var last=net.neoforged.bus.api.EventPriority.LOWEST;
            NeoForge.EVENT_BUS.addListener(first,(ServerTickEvent.Pre event)->{if(event.getServer()==server && !finished){phaseFrame++;phaseSample("server-START-first");}});
            NeoForge.EVENT_BUS.addListener(last,(ServerTickEvent.Pre event)->{if(event.getServer()==server)phaseSample("server-START-last");});
            NeoForge.EVENT_BUS.addListener(first,(ServerTickEvent.Post event)->{if(event.getServer()==server)phaseSample("server-END-first");});
            NeoForge.EVENT_BUS.addListener(last,(ServerTickEvent.Post event)->{if(event.getServer()==server)phaseSample("server-END-last");});
            NeoForge.EVENT_BUS.addListener(first,(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event)->{if(event.getLevel()==server.overworld())phaseSample("world-START-first");});
            NeoForge.EVENT_BUS.addListener(last,(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event)->{if(event.getLevel()==server.overworld())phaseSample("world-START-last");});
            NeoForge.EVENT_BUS.addListener(first,(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event)->{if(event.getLevel()==server.overworld())phaseSample("world-END-first");});
            NeoForge.EVENT_BUS.addListener(last,(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event)->{if(event.getLevel()==server.overworld())phaseSample("world-END-last");});
        }
    }
    private void phaseSample(String phase) {
        if(finished || phaseFrame<phaseFrom || phaseFrame>phaseTo) return;
        try {
            var level=server.overworld();
            for(var at:positions) {
                var chunk=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
                if(chunk==null) continue;
                var tile=chunk.getBlockEntity(at,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);
                if(tile==null) continue;
                record("phase-tile-save",Map.of("frame",phaseFrame,"phase",phase,"x",at.getX(),"y",at.getY(),"z",at.getZ(),
                    "nbt",tile.saveWithFullMetadata(server.registryAccess()).toString()));
            }
        }catch(Throwable error){error.printStackTrace();finish(false);}
    }
    private void onExplosionStart(net.neoforged.neoforge.event.level.ExplosionEvent.Start event) {
        recordExplosion("explosion-start",event);
    }
    private void onExplosionDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        recordExplosion("explosion-detonate",event);
    }
    private void recordExplosion(String kind,net.neoforged.neoforge.event.level.ExplosionEvent event) {
        if(finished || event.getLevel()!=server.overworld()) return;
        var explosion=event.getExplosion();
        var center=explosion.center();
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("x",center.x);row.put("y",center.y);row.put("z",center.z);
        row.put("radius",explosion.radius());
        row.put("interaction",explosion.getBlockInteraction().name());
        // Identify only our maintained wrapper; do not inspect other implementations.
        row.put("via_custom_storage",StackWalker.getInstance().walk(frames->frames.anyMatch(frame->
            frame.getClassName().equals("com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage")
                && frame.getMethodName().equals("triggerOverloadExplosion"))));
        row.put("affected_sample_positions",explosion.getToBlow().stream().filter(positions::contains)
            .map(p->List.of(p.getX(),p.getY(),p.getZ())).toList());
        try{record(kind,row);}
        catch(Exception error){error.printStackTrace();finish(false);}
    }
    private void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        recordChunkEvent("chunk-load-event",event);
    }
    private void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        recordChunkEvent("chunk-unload-event",event);
    }
    private void recordChunkEvent(String kind,net.neoforged.neoforge.event.level.ChunkEvent event) {
        if(finished || event.getLevel()!=server.overworld()) return;
        var cp=event.getChunk().getPos();
        if(positions.stream().noneMatch(p->(p.getX()>>4)==cp.x && (p.getZ()>>4)==cp.z)) return;
        try{record(kind,Map.of("chunk_x",cp.x,"chunk_z",cp.z));}
        catch(Exception error){error.printStackTrace();finish(false);}
    }
    private void record(String kind,Object value) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();row.put("tick",tick);row.put("kind",kind);row.put("value",value);
        output.write(gson.toJson(row));output.newLine();observations++;
    }
    private void onTick(ServerTickEvent.Post event) {
        if(finished || event.getServer()!=server) return;
        try {
            var world=server.overworld();
            if(observeWorldTime) record("world-game-time",world.getGameTime());
            if(Boolean.getBoolean("scex.independent.energy")) {
                var engine=IndependentSiEnergy.current(server);
                if(engine==null || !engine.metrics().failure().isEmpty()) throw new IllegalStateException("Independent engine missing or failed");
                record("independent-energy",engine.metrics());
                if(Files.exists(Path.of("transformer-factory.json"))) record("transformer-factory",TransformerFactoryProbe.metrics());
                if(tick==18 && Boolean.getBoolean("scex.independent.commitTests"))
                    record("commit-boundaries",NetworkCommitProbe.run(world));
            }
            for(BlockPos at:positions) {
                Map<String,Object> row=new LinkedHashMap<>();row.put("x",at.getX());row.put("y",at.getY());row.put("z",at.getZ());
                row.put("block_ticking",world.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(at)));
                var chunk=world.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
                if(chunk==null){record("chunk-unloaded",row);continue;}
                // World-level reads can renew a temporary chunk ticket. Observe
                // the already-loaded chunk directly so this probe permits unload.
                row.put("state",chunk.getBlockState(at).toString());record("block-state",row);
                var tile=chunk.getBlockEntity(at,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);
                if(tile!=null) {
                    row=new LinkedHashMap<>(row);row.remove("state");
                    row.put("nbt",tile.saveWithFullMetadata(server.registryAccess()).toString());record("tile-save",row);
                    if(observeEndpointSurface && (tick==20 || tick==40 || tick==70)) {
                        var surface=new LinkedHashMap<String,Object>();
                        surface.put("x",at.getX());surface.put("y",at.getY());surface.put("z",at.getZ());
                        surface.put("class",tile.getClass().getName());
                        surface.put("superclass",tile.getClass().getSuperclass().getName());
                        surface.put("independent_controlled",IndependentSiEnergy.controls(tile.getBlockState()));
                        boolean energyBase=tile instanceof com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
                        surface.put("energy_base",energyBase);
                        if(energyBase) {
                            var energy=(com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block)tile;
                            var storage=energy.getEnergyStorageInternal();
                            var quote=storage.scexNetworkQuote();
                            surface.put("amount",quote.amount());surface.put("output_enabled",quote.outputEnabled());
                            surface.put("effective_capacity",energy.getEffectiveCapacity());
                            surface.put("storage_class",storage.getClass().getName());
                        }
                        record("endpoint-surface",surface);
                    }
                }
            }
            for(String command:commands.getOrDefault(tick,List.of())) {
                var result=new int[]{Integer.MIN_VALUE};
                if(command.startsWith("@explode ")) {
                    String[] parts=command.split(" ");
                    if(parts.length!=5) throw new IllegalArgumentException("Invalid isolated blast control");
                    var center=new BlockPos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));
                    float radius=Float.parseFloat(parts[4]);
                    if(!positions.contains(center)||!Float.isFinite(radius)||radius<=0||radius>4
                        ||server.overworld().getChunkSource().getChunkNow(center.getX()>>4,center.getZ()>>4)==null)
                        throw new IllegalArgumentException("Blast control outside declared loaded fixture");
                    server.overworld().removeBlock(center,false);
                    server.overworld().explode(null,center.getX()+0.5,center.getY()+0.5,center.getZ()+0.5,radius,
                        net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
                    result[0]=1;
                } else {
                    var source=server.createCommandSourceStack().withSuppressedOutput().withCallback((success,value)->result[0]=success?value:-1);
                    server.getCommands().performPrefixedCommand(source,command);
                }
                executed++;
                record("command",Map.of("command",command,"result",result[0]));
                if(result[0]<0) throw new IllegalStateException("Scenario command failed: "+command);
            }
            output.flush();
            if(++tick>=Integer.getInteger("scex.scenario.ticks",150)) finish(true);
        }catch(Throwable error){error.printStackTrace();finish(false);}
    }
    private void finish(boolean passed) {
        finished=true;
        try {
            output.close();
            Files.writeString(Path.of(System.getProperty("scex.smoke.result")),gson.toJson(Map.of(
                "passed",passed && observations>0,"observations",observations,"commands",executed,"ticks",tick)));
        }catch(Exception error){error.printStackTrace();}
        server.halt(false);
    }
}
