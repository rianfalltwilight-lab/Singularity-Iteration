// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.scex.energy.ConductorRegistry;
import dev.scex.energy.minecraft.PlatformTopology;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real commands/events and read-only physical-state checks. No SI implementation access. */
public final class TopologyScenarioProbe {
    private final MinecraftServer server;
    private final PlatformTopology topology;
    private final JsonObject fixture;
    private final String phase = System.getProperty("scex.topology.phase", "exercise");
    private final Gson gson = new Gson();
    private final Map<String, List<BlockPos>> lines = new LinkedHashMap<>();
    private final Map<ResourceLocation, Long> losses = new HashMap<>();
    private final Map<Integer, List<String>> commands = new TreeMap<>();
    private final Set<String> unloaded = new HashSet<>();
    private final BufferedWriter output;
    private ConductorRegistry.Snapshot previous;
    private Map<BlockPos, Long> lastPhysical = Map.of();
    private int tick, executed, assertions, settledTicks, staleChecks, checkpoints;
    private boolean finished;

    public TopologyScenarioProbe(MinecraftServer server) throws Exception {
        this.server = server;
        fixture = JsonParser.parseString(Files.readString(Path.of("topology-fixture.json"))).getAsJsonObject();
        fixture.getAsJsonObject("losses").entrySet().forEach(e -> losses.put(ResourceLocation.parse(e.getKey()), e.getValue().getAsLong()));
        for (var entry : fixture.getAsJsonArray("lines")) {
            var line = entry.getAsJsonObject(); var positions = new ArrayList<BlockPos>();
            for (int x = line.get("x").getAsInt(); x < line.get("x").getAsInt() + line.get("length").getAsInt(); x++) {
                positions.add(new BlockPos(x, line.get("y").getAsInt(), line.get("z").getAsInt()));
            }
            lines.put(line.get("id").getAsString(), List.copyOf(positions));
        }
        if (lines.values().stream().mapToInt(List::size).sum() > 512) { throw new IllegalArgumentException("Fixture too large"); }
        for (String row : Files.readAllLines(Path.of("commands.tsv"))) {
            if (row.isBlank()) { continue; }
            String[] parts = row.split("\t", 2);
            commands.computeIfAbsent(Integer.parseInt(parts[0]), key -> new ArrayList<>()).add(parts[1]);
        }
        output = Files.newBufferedWriter(Path.of("observations.jsonl"));
        topology = new PlatformTopology(server, losses, 100_000, 32, 65_536, 4096);
        // Registration before levels load is part of the restart test.
        NeoForge.EVENT_BUS.register(this);
        record("attached-before-levels", Map.of("phase", phase, "levels_present", server.getAllLevels().iterator().hasNext()));
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) { throw new AssertionError(label + " at tick " + tick + " phase " + phase); }
    }
    private static ConductorRegistry.Position position(BlockPos at) {
        return new ConductorRegistry.Position(at.getX(), at.getY(), at.getZ());
    }
    private void record(String kind, Object value) throws Exception {
        output.write(gson.toJson(Map.of("tick", tick, "kind", kind, "value", value))); output.newLine();
    }
    @SubscribeEvent
    public void chunkUnload(ChunkEvent.Unload event) {
        if (finished || event.getLevel() != server.overworld()) { return; }
        var at = event.getChunk().getPos();
        if (lines.values().stream().flatMap(List::stream).noneMatch(p -> (p.getX() >> 4) == at.x && (p.getZ() >> 4) == at.z)) { return; }
        unloaded.add(at.x + "," + at.z);
        try { record("chunk-unload-event", Map.of("chunk_x", at.x, "chunk_z", at.z)); }
        catch (Exception error) { fail(error); }
    }
    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        if (finished || event.getServer() != server) { return; }
        try {
            var world = server.overworld(); var physical = new HashMap<BlockPos, Long>();
            for (var line : lines.values()) {
                for (BlockPos at : line) {
                    var chunk = world.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                    if (chunk == null) { continue; }
                    Long loss = losses.get(BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(at).getBlock()));
                    if (loss != null) { physical.put(at, loss); }
                }
            }
            var metrics = topology.metrics();
            check(metrics.failure().isEmpty(), "adapter-fault: " + metrics.failure());
            boolean ready = topology.ready(world);
            var caseResults = new LinkedHashMap<String, Object>();
            if (ready) {
                settledTicks++;
                if (previous != null && !lastPhysical.equals(physical)) {
                    check(!topology.isCurrent(world, previous), "changed-physical-topology-rejects-old-lease"); staleChecks++;
                    boolean rejected = false;
                    try { previous.size(); } catch (IllegalStateException expected) { rejected = true; }
                    check(rejected, "invalid-lease-refuses-read");
                }
                var snapshot = topology.snapshot(world);
                check(snapshot.size() == physical.size(), "global-fixture-membership-size");
                for (var line : lines.values()) {
                    for (BlockPos at : line) { check(snapshot.contains(position(at)) == physical.containsKey(at), "physical-index-membership-" + at); }
                }
                for (var entry : lines.entrySet()) {
                    var line = entry.getValue(); int count = 0; long loss = 0;
                    for (var at : line) { if (physical.containsKey(at)) { count++; loss += physical.get(at); } }
                    // A simple straight fixture has exactly one path iff every cell exists.
                    boolean connected = count == line.size(); boolean route = false;
                    if (snapshot.contains(position(line.getFirst())) && snapshot.contains(position(line.getLast()))) {
                        var routes = snapshot.routesFrom(position(line.getFirst())); int target = snapshot.vertex(position(line.getLast()));
                        route = routes.reaches(target);
                        if (route) { check(routes.lossMilliTo(target) == loss, "physical-line-loss-" + entry.getKey()); }
                    }
                    check(route == connected, "physical-line-connectivity-" + entry.getKey());
                    caseResults.put(entry.getKey(), Map.of("count", count, "connected", connected, "loss", loss));
                }
                previous = snapshot; lastPhysical = Map.copyOf(physical);
            }
            for (var item : fixture.getAsJsonArray(phase + "_checkpoints")) {
                var cp = item.getAsJsonObject(); if (cp.get("tick").getAsInt() != tick) { continue; }
                check(ready, "checkpoint-topology-ready");
                var actual = gson.toJsonTree(caseResults.get(cp.get("line").getAsString())).getAsJsonObject();
                for (String field : List.of("count", "connected", "loss")) {
                    if (cp.has(field)) { check(actual.get(field).equals(cp.get(field)), "checkpoint-" + cp.get("line") + "-" + field); }
                }
                checkpoints++;
            }
            record("topology", Map.of("ready", ready, "metrics", metrics, "physical_count", physical.size(), "lines", caseResults));
            for (String command : commands.getOrDefault(tick, List.of())) {
                int[] result = {Integer.MIN_VALUE};
                var source = server.createCommandSourceStack().withSuppressedOutput().withCallback((success, value) -> result[0] = success ? value : -1);
                server.getCommands().performPrefixedCommand(source, command); executed++;
                record("command", Map.of("command", command, "result", result[0]));
                check(result[0] >= 0, "command-success-" + command);
            }
            output.flush();
            if (++tick >= fixture.get(phase + "_ticks").getAsInt()) {
                check(checkpoints == fixture.getAsJsonArray(phase + "_checkpoints").size(), "all-checkpoints-executed");
                check(settledTicks >= tick / 2, "majority-settled-samples");
                if (phase.equals("exercise")) {
                    check(staleChecks >= 5, "real-topology-changes-invalidate-leases");
                    for (var at : fixture.getAsJsonArray("required_unloads")) { check(unloaded.contains(at.getAsString()), "physical-chunk-unload-" + at); }
                }
                finish(true, "");
            }
        } catch (Throwable error) { fail(error); }
    }
    private void fail(Throwable error) { error.printStackTrace(); finish(false, error.toString()); }
    private void finish(boolean passed, String failure) {
        if (finished) { return; } finished = true;
        try {
            output.close();
            Files.writeString(Path.of("probe-result.json"), gson.toJson(Map.of(
                "passed", passed, "failure", failure, "ticks", tick, "commands", executed, "assertions", assertions,
                "settled_ticks", settledTicks, "stale_checks", staleChecks, "checkpoints", checkpoints, "physical_unloads", unloaded)));
        } catch (Exception error) { error.printStackTrace(); }
        server.halt(false);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void stopped(ServerStoppedEvent event) {
        if (event.getServer() != server) { return; }
        var metrics = topology.metrics(); boolean leaseRejected = previous == null;
        if (previous != null) {
            try { previous.size(); } catch (IllegalStateException expected) { leaseRejected = true; }
        }
        try {
            Files.writeString(Path.of("topology-stop.json"), gson.toJson(Map.of(
                "passed", metrics.closed() && metrics.dimensions() == 0 && metrics.queued() == 0 && leaseRejected,
                "metrics", metrics, "old_lease_rejected", leaseRejected)));
        } catch (Exception error) { error.printStackTrace(); }
        NeoForge.EVENT_BUS.unregister(this);
    }
}
