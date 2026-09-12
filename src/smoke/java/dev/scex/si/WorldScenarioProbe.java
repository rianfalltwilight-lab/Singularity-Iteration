// SPDX-License-Identifier: Apache-2.0
// Vanilla commands and public saved-state observations on real world ticks.
package dev.scex.si;

import com.google.gson.Gson;
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
        NeoForge.EVENT_BUS.addListener(this::onTick);
    }
    private void record(String kind,Object value) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();row.put("tick",tick);row.put("kind",kind);row.put("value",value);
        output.write(gson.toJson(row));output.newLine();observations++;
    }
    private void onTick(ServerTickEvent.Post event) {
        if(finished || event.getServer()!=server) return;
        try {
            var world=server.overworld();
            for(BlockPos at:positions) {
                Map<String,Object> row=new LinkedHashMap<>();row.put("x",at.getX());row.put("y",at.getY());row.put("z",at.getZ());
                if(!world.hasChunkAt(at)){record("chunk-unloaded",row);continue;}
                row.put("state",world.getBlockState(at).toString());record("block-state",row);
                var tile=world.getBlockEntity(at);
                if(tile!=null) {
                    row=new LinkedHashMap<>(row);row.remove("state");
                    row.put("nbt",tile.saveWithFullMetadata(server.registryAccess()).toString());record("tile-save",row);
                }
            }
            for(String command:commands.getOrDefault(tick,List.of())) {
                var result=new int[]{Integer.MIN_VALUE};
                var source=server.createCommandSourceStack().withSuppressedOutput().withCallback((success,value)->result[0]=success?value:-1);
                server.getCommands().performPrefixedCommand(source,command);executed++;
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
