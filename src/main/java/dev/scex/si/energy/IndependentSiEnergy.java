// SPDX-License-Identifier: Apache-2.0
package dev.scex.si.energy;

import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage;
import dev.scex.energy.ConductorRegistry;
import dev.scex.energy.DeferredEntries;
import dev.scex.energy.DomainDistributor;
import dev.scex.energy.ReceiverOrder;
import dev.scex.energy.RouteCosts;
import dev.scex.energy.minecraft.PlatformTopology;
import dev.scex.energy.minecraft.ContactOrder;
import dev.scex.energy.minecraft.SmallBlastField;
import dev.scex.energy.minecraft.IndependentTransformerBlockEntity;
import dev.scex.energy.minecraft.IndependentSpecialCableBlockEntity;
import dev.scex.energy.NetworkCell;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Experimental SI-side bridge, independently written for numeric storage and
 * public world APIs. It does not implement or call any old grid/energy API.
 * This opt-in subset is an integration checkpoint, not the complete replacement.
 */
public final class IndependentSiEnergy implements PlatformTopology.Observer {
    private static final ResourceLocation BATBOX = id("wiring/block_bat_box");
    private static final ResourceLocation GENERATOR = id("generator/block_thermal_generator");
    private static final Set<ResourceLocation> TRANSFORMERS = Set.of(id("wiring/transformer_lv_mv"),
        id("wiring/transformer_mv_hv"), id("wiring/transformer_hv_ev"), id("wiring/transformer_ev_sc"));
    private static final Set<ResourceLocation> SPECIAL_CABLES = Set.of(id("wiring/block_eu_detector_cable"), id("wiring/block_eu_splitter_cable"));
    private static final Set<ResourceLocation> BASIC_PROCESSORS = Set.of(id("producer/block_furnace_elc"),
        id("producer/block_powder_elc"), id("producer/block_extractor_elc"), id("producer/block_compressor_elc"));
    // Packet sizes are explicit public-game observations, not old grid tiers.
    private static final Map<ResourceLocation, Long> STORAGE_PACKETS = Map.of(
        BATBOX, 32L, id("wiring/block_cesu"), 128L,
        id("wiring/block_mfe"), 512L, id("wiring/block_mfsu"), 2048L);
    private static final Set<ResourceLocation> ENDPOINTS = Set.of(BATBOX, GENERATOR, id("producer/block_furnace_elc"),
        id("wiring/block_cesu"), id("wiring/block_mfe"), id("wiring/block_mfsu"),
        id("producer/block_powder_elc"), id("producer/block_extractor_elc"), id("producer/block_compressor_elc"));
    private static final Map<ResourceLocation, Long> CONDUCTORS = Map.ofEntries(
        Map.entry(id("wiring/cable/block_cable"), 200L), Map.entry(id("wiring/cable/block_cable_o"), 200L),
        Map.entry(id("wiring/cable/block_tin_cable"), 200L), Map.entry(id("wiring/cable/block_tin_cable_1"), 200L),
        Map.entry(id("wiring/cable/block_gold_cable"), 400L), Map.entry(id("wiring/cable/block_gold_cable_1"), 400L),
        Map.entry(id("wiring/cable/block_iron_cable"), 800L), Map.entry(id("wiring/cable/block_iron_cable_1"), 800L),
        Map.entry(id("wiring/cable/block_glass_cable"), 25L),
        Map.entry(id("wiring/block_eu_detector_cable"), 500L), Map.entry(id("wiring/block_eu_splitter_cable"), 500L));
    // Only measured fuse thresholds are enabled. Iron/glass ultimate limits
    // remain open; the currently controlled sources offer at most 2048 EU.
    private static final Map<ResourceLocation, Long> FUSE_LIMITS = Map.of(
        id("wiring/cable/block_tin_cable"), 33L, id("wiring/cable/block_tin_cable_1"), 33L,
        id("wiring/cable/block_cable"), 129L, id("wiring/cable/block_cable_o"), 129L,
        id("wiring/cable/block_gold_cable"), 513L, id("wiring/cable/block_gold_cable_1"), 513L);
    private static final Set<ResourceLocation> MEASURED_BLAST_CABLES = Set.of(
        id("wiring/cable/block_tin_cable_1"), id("wiring/cable/block_cable"),
        id("wiring/cable/block_gold_cable_1"), id("wiring/cable/block_glass_cable"));
    private static final Map<MinecraftServer, IndependentSiEnergy> SERVERS = new IdentityHashMap<>();
    private static boolean installed;
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("mio_icif", path); }
    public static boolean controls(BlockState state) {
        var type = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return Boolean.getBoolean("scex.independent.energy") && (ENDPOINTS.contains(type)
            || Boolean.getBoolean("scex.independent.transformers") && TRANSFORMERS.contains(type)
            || Boolean.getBoolean("scex.independent.specialCables") && SPECIAL_CABLES.contains(type));
    }
    public static synchronized void install() {
        if (installed) return; installed = true;
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> {
            if (Boolean.getBoolean("scex.independent.energy")) attach(event.getServer());
        });
    }
    public static synchronized IndependentSiEnergy attach(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Attach on server thread");
        return SERVERS.computeIfAbsent(server, IndependentSiEnergy::new);
    }
    public static synchronized IndependentSiEnergy current(MinecraftServer server) { return SERVERS.get(server); }
    public static void changed(mio_icif_Energy_Block tile) {
        if (tile.getLevel() instanceof ServerLevel level) {
            var engine = current(level.getServer()); if (engine != null) engine.topology.changed(level, tile.getBlockPos());
        }
    }

    /** Server-authoritative mode selection through the ordinary block interaction event. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void interact(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server
                || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND
                || !event.getItemStack().isEmpty()
                || event.getEntity().isSpectator() || !event.getEntity().mayBuild()
                || !level.mayInteract(event.getEntity(), event.getPos())) return;
        var at = event.getPos();
        var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(at, LevelChunk.EntityCreationType.CHECK)
                instanceof IndependentTransformerBlockEntity transformer)) return;
        int next = transformer.savedMode();
        if (event.getEntity().isShiftKeyDown()) {
            next = Math.floorMod(next + 1, 3);
            transformer.setSavedMode(next);
            topology.changed(level, at);
        }
        String label = next == 0 ? "固定升压" : next == 1 ? "固定降压" : "红石自动";
        event.getEntity().displayClientMessage(net.minecraft.network.chat.Component.literal("变压器：" + label + "；蹲下空手右键切换"), true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private MinecraftServer server;
    private final long ownerThread = Thread.currentThread().threadId();
    private final PlatformTopology topology;
    private final Map<ServerLevel, WorldGrid> worlds = new HashMap<>();
    // Record the independent seed in probe metrics so this selection stream can
    // be replayed. It is not a seed or algorithm taken from the reference mod.
    private final long selectionSeed = Long.getLong("scex.independent.selectionSeed", ThreadLocalRandom.current().nextLong());
    private final SplittableRandom selectionRandom = new SplittableRandom(selectionSeed);
    // A separate independent stream keeps visible block effects from changing
    // receiver selection. This is not the reference game's PRNG or seed.
    private final SplittableRandom blockDropRandom = new SplittableRandom(selectionSeed ^ 0x5343455844524f50L);
    private long ticks, commits, rejected, debited, credited, dissipated;
    private long deliveryCount, deliveryWireVisits, fusedWires, destroyedReceivers, blastBlocks;
    private String failure = "";
    private boolean closed;
    /** Read-only public-platform diagnostics for bounded startup investigations. */
    public Map<String, Object> startupDiagnostics(ServerLevel level) {
        if (level.getServer() != server || !server.isSameThread()) throw new IllegalArgumentException("Wrong server context");
        var grid = worlds.get(level);
        return Map.of("topology", topology.metrics(), "ready", topology.ready(level),
            "world_grid", grid != null, "catch_up", grid != null && grid.catchUp,
            "entries", grid == null ? 0 : grid.machines.size());
    }
    public record Metrics(long ticks, long commits, long rejected, long debited, long credited, long dissipated,
                          int dimensions, int endpoints, boolean closed, String failure, long selectionSeed,
                          long deliveryCount, long deliveryWireVisits, long fusedWires, long destroyedReceivers, long blastBlocks) { }
    private record Port(BlockEntity tile, BlockPos position, BlockState state,
                        CustomEUEnergyStorage storage, CustomEUEnergyStorage.NetworkQuote quote,
                        long capacity, int inputs, int outputs, long packet, int packets,
                        IndependentTransformerBlockEntity.Snapshot transformer) { }
    private record Element(ResourceLocation type, long conductorLoss, BlockEntity tile, int routing) {
        @Override public boolean equals(Object other) {
            return other instanceof Element e && type.equals(e.type) && conductorLoss == e.conductorLoss && tile == e.tile && routing == e.routing;
        }
        @Override public int hashCode() { return 31 * type.hashCode() + Long.hashCode(conductorLoss) + System.identityHashCode(tile) + routing; }
    }
    private static final class WorldGrid implements AutoCloseable {
        final DeferredEntries<BlockPos, Element> entries = new DeferredEntries<>(104_096, 65_536);
        final ConductorRegistry conductors = new ConductorRegistry(100_000, 32);
        final ContactOrder contactOrder;
        WorldGrid(long constructionSeed) { contactOrder = new ContactOrder(104_096, 32, constructionSeed); }
        final Map<BlockPos, BlockEntity> machines = new LinkedHashMap<>();
        final Map<BlockPos, Integer> transformerRouting = new HashMap<>();
        final Map<BlockPos, IndependentSpecialCableBlockEntity> specialCables = new LinkedHashMap<>();
        final Map<BlockPos, Element> specialObserved = new HashMap<>(), specialPublished = new HashMap<>();
        final java.util.NavigableMap<Long, Map<BlockPos, Element>> specialPending = new java.util.TreeMap<>();
        final Map<BlockPos, Set<BlockPos>> initialGeneratorContacts = new HashMap<>();
        final Map<Long, Integer> conductorChunks = new HashMap<>();
        final Map<BlockPos, ResourceLocation> conductorTypes = new HashMap<>();
        boolean catchUp;
        long effectPauseFrame = -1;
        // Unlike ordinary physical add/remove coalescing, measured splitter
        // pulses preserve the observed off/on transitions in consecutive frames.
        void observeSpecial(long frame, BlockPos at, Element value) {
            if (java.util.Objects.equals(specialObserved.get(at), value)) return;
            if (value == null) specialObserved.remove(at); else specialObserved.put(at, value);
            if (specialObserved.size() > 100_000 || specialPending.size() > 4)
                throw new IllegalStateException("Special cable queue limit reached");
            specialPending.computeIfAbsent(frame + 1, ignored -> new LinkedHashMap<>()).put(at, value);
        }
        List<DeferredEntries.Change<BlockPos, Element>> advanceSpecial(long frame) {
            var result = new ArrayList<DeferredEntries.Change<BlockPos, Element>>();
            while (!specialPending.isEmpty() && specialPending.firstKey() <= frame) {
                for (var item : specialPending.pollFirstEntry().getValue().entrySet()) {
                    var at = item.getKey(); var before = specialPublished.get(at); var after = item.getValue();
                    if (after != null && after.conductorLoss >= 0) {
                        var current = specialObserved.get(at);
                        // A transient enabling pulse which ended before publication
                        // must not create a conducting entry for the START catch-up.
                        if (current == null || current.tile != after.tile || current.conductorLoss < 0) continue;
                    }
                    if (after == null) specialPublished.remove(at); else specialPublished.put(at, after);
                    if (!java.util.Objects.equals(before, after)) result.add(new DeferredEntries.Change<>(at, before, after));
                }
            }
            return result;
        }
        List<DeferredEntries.Change<BlockPos, Element>> forget(java.util.function.Predicate<BlockPos> predicate) {
            var result = new ArrayList<>(entries.forgetIf(predicate));
            var iterator = specialPublished.entrySet().iterator();
            while (iterator.hasNext()) {
                var item = iterator.next();
                if (predicate.test(item.getKey())) { result.add(new DeferredEntries.Change<>(item.getKey(), item.getValue(), null)); iterator.remove(); }
            }
            specialObserved.keySet().removeIf(predicate);
            specialPending.values().forEach(batch -> batch.keySet().removeIf(predicate));
            specialPending.values().removeIf(Map::isEmpty);
            return result;
        }
        void apply(List<DeferredEntries.Change<BlockPos, Element>> changes) {
            for (var change : changes) {
                var at = change.key(); long chunk = ChunkPos.asLong(at);
                contactOrder.remove(point(at));
                if (change.before() != null && change.before().conductorLoss >= 0) {
                    for (Direction side : Direction.values()) {
                        var initial = initialGeneratorContacts.get(at.relative(side));
                        if (initial != null) initial.remove(at);
                    }
                    conductors.remove(point(at));
                    conductorTypes.remove(at);
                    conductorChunks.compute(chunk, (key, count) -> count == 1 ? null : count - 1);
                }
                machines.remove(at);
                transformerRouting.remove(at);
                specialCables.remove(at);
                initialGeneratorContacts.remove(at);
                var after = change.after();
                if (after == null) continue;
                if (after.tile instanceof IndependentSpecialCableBlockEntity cable) specialCables.put(at, cable);
                if (after.conductorLoss >= 0) {
                    conductors.put(point(at), after.conductorLoss);
                    contactOrder.putConductor(point(at), after.conductorLoss);
                    conductorTypes.put(at, after.type);
                    conductorChunks.merge(chunk, 1, Integer::sum);
                } else if (!(after.tile instanceof IndependentSpecialCableBlockEntity)) {
                    if (machines.size() >= 4096) throw new IllegalStateException("Endpoint limit reached");
                    machines.put(at, after.tile);
                    contactOrder.putEndpoint(point(at));
                    if (after.tile instanceof IndependentTransformerBlockEntity) transformerRouting.put(at, after.routing);
                    if (after.type.equals(GENERATOR)) {
                        var initial = new HashSet<BlockPos>();
                        for (Direction side : Direction.values()) {
                            var neighbour = at.relative(side);
                            if (conductors.containsRegistered(point(neighbour))) initial.add(neighbour);
                        }
                        initialGeneratorContacts.put(at, initial);
                    }
                }
            }
        }
        @Override public void close() {
            entries.close(); conductors.close(); contactOrder.close(); machines.clear(); transformerRouting.clear(); specialCables.clear(); specialObserved.clear(); specialPublished.clear(); specialPending.clear(); initialGeneratorContacts.clear(); conductorChunks.clear(); conductorTypes.clear(); catchUp = false; effectPauseFrame = -1;
        }
    }
    private IndependentSiEnergy(MinecraftServer server) {
        this.server = server;
        topology = new PlatformTopology(server, CONDUCTORS, 100_000, 32, 65_536, 4096, this);
        NeoForge.EVENT_BUS.register(this);
    }
    public Metrics metrics() {
        if (Thread.currentThread().threadId() != ownerThread) throw new IllegalStateException("Read engine metrics on server thread");
        return new Metrics(ticks, commits, rejected, debited, credited, dissipated,
            worlds.size(), worlds.values().stream().mapToInt(grid -> grid.machines.size()).sum(), closed, failure, selectionSeed,
            deliveryCount, deliveryWireVisits, fusedWires, destroyedReceivers, blastBlocks);
    }
    @Override
    public void blockChanged(ServerLevel level, BlockPos at, BlockState before, BlockState after) {
        var type = BuiltInRegistries.BLOCK.getKey(after.getBlock());
        boolean recognized = controls(after) || CONDUCTORS.containsKey(type)
            && (!SPECIAL_CABLES.contains(type) || Boolean.getBoolean("scex.independent.specialCables"));
        var grid = worlds.get(level);
        if (recognized) {
            if (grid == null) { grid = new WorldGrid(selectionSeed ^ 0x5343455848495354L); worlds.put(level, grid); }
            grid.contactOrder.reservePlacement(point(at));
        } else if (grid != null) grid.contactOrder.cancelPlacement(point(at));
    }
    @Override
    public void position(ServerLevel level, LevelChunk chunk, BlockPos at) {
        var state = chunk.getBlockState(at); var type = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var loss = CONDUCTORS.get(type); Element element = null;
        if (SPECIAL_CABLES.contains(type) && !Boolean.getBoolean("scex.independent.specialCables")) loss = null;
        var grid = worlds.get(level);
        // The chunk is already FULL. This can materialize its saved block entity,
        // without loading another chunk or renewing a world lookup ticket.
        if (loss != null || controls(state)) {
            var tile = chunk.getBlockEntity(at, LevelChunk.EntityCreationType.IMMEDIATE);
            if (tile == null) throw new IllegalStateException("Electrical block entity missing at " + at);
            if (SPECIAL_CABLES.contains(type) && !(tile instanceof IndependentSpecialCableBlockEntity))
                throw new IllegalStateException("Special conductor lacks independent entity at " + at);
            if (loss == null && !(tile instanceof IndependentTransformerBlockEntity)
                    && (!(tile instanceof mio_icif_Energy_Block machine) || !machine.getEnergyStorageInternal().scexNetworkControlled()))
                throw new IllegalStateException("Controlled endpoint lacks the new storage boundary at " + at);
            int routing = 0;
            if (tile instanceof IndependentTransformerBlockEntity transformer) {
                routing = transformer.routingSignature();
            }
            if (tile instanceof IndependentSpecialCableBlockEntity cable) {
                // Sampling a topology entry is read-only. Visible state updates
                // occur at onLoad or the server world tick boundary.
                if (!cable.conductsNow()) loss = -2L;
            }
            element = new Element(type, loss == null ? -1 : loss, tile, routing);
            if (grid == null) { grid = new WorldGrid(selectionSeed ^ 0x5343455848495354L); worlds.put(level, grid); }
        }
        // A late server-post observation belongs to the next publication frame
        // if the world's END decision has already been made.
        if (grid != null) {
            long frame = Math.max(ticks + 1, grid.entries.metrics().advancedFrame() + 1);
            if (element != null && element.tile instanceof IndependentSpecialCableBlockEntity || grid.specialObserved.containsKey(at)) {
                grid.observeSpecial(frame, at.immutable(), element != null && element.tile instanceof IndependentSpecialCableBlockEntity ? element : null);
                if (element == null || element.tile instanceof IndependentSpecialCableBlockEntity) return;
            }
            grid.entries.observe(frame, at.immutable(), element);
        }
    }
    @Override
    public void chunkRemoved(ServerLevel level, int chunkX, int chunkZ) {
        var grid = worlds.get(level);
        if (grid != null) {
            grid.apply(grid.forget(at -> (at.getX() >> 4) == chunkX && (at.getZ() >> 4) == chunkZ));
            grid.contactOrder.forgetPlacements(at -> (at.x() >> 4) == chunkX && (at.z() >> 4) == chunkZ);
            grid.catchUp = false;
        }
    }
    @Override public void levelRemoved(ServerLevel level) { var grid = worlds.remove(level); if (grid != null) grid.close(); }
    @Override public void cleared() { worlds.values().forEach(WorldGrid::close); worlds.clear(); }
    @SubscribeEvent
    public void beforeLevel(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server || closed || !failure.isEmpty()) return;
        var grid = worlds.get(level);
        if (grid == null) return;
        for (var item : grid.specialCables.entrySet()) {
            var at = item.getKey(); var cable = item.getValue();
            if (level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) == null || cable.isRemoved()
                    || !level.shouldTickBlocksAt(ChunkPos.asLong(at))) continue;
            if (cable.refreshInput()) topology.changed(level, at);
        }
        for (var item : grid.machines.entrySet()) {
            if (!(item.getValue() instanceof IndependentTransformerBlockEntity transformer)) continue;
            var at = item.getKey();
            var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            if (chunk == null || transformer.isRemoved() || !level.shouldTickBlocksAt(ChunkPos.asLong(at))) continue;
            boolean changed = transformer.refreshMode();
            if (changed || !Integer.valueOf(transformer.routingSignature()).equals(grid.transformerRouting.get(at))) topology.changed(level, at);
        }
        if (!grid.catchUp) return;
        grid.catchUp = false;
        try {
            if (!topology.metrics().failure().isEmpty()) throw new IllegalStateException(topology.metrics().failure());
            settle(level, grid);
        } catch (RuntimeException error) { fail(error); }
    }
    @SubscribeEvent
    public void tick(ServerTickEvent.Post event) {
        if (event.getServer() != server || closed || !failure.isEmpty()) return;
        ticks++;
        if (!topology.metrics().failure().isEmpty()) fail(new IllegalStateException(topology.metrics().failure()));
    }
    @SubscribeEvent(priority = EventPriority.LOW)
    public void afterLevel(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server || closed || !failure.isEmpty()) return;
        try {
            if (!topology.metrics().failure().isEmpty()) throw new IllegalStateException(topology.metrics().failure());
            var grid = worlds.get(level);
            if (grid == null || !topology.ready(level)) return;
            for (var cable : grid.specialCables.values()) {
                var at = cable.getBlockPos();
                if (!cable.isRemoved() && level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null
                        && level.shouldTickBlocksAt(ChunkPos.asLong(at))) cable.sampleDetector();
            }
            var changes = new ArrayList<>(grid.entries.advance(ticks + 1));
            changes.addAll(grid.advanceSpecial(ticks + 1));
            boolean effectPause = grid.effectPauseFrame == ticks + 1;
            if (effectPause) grid.effectPauseFrame = -1;
            if (changes.isEmpty() && !effectPause) settle(level, grid);
            else {
                grid.apply(changes);
                // Public reference observations: a matured electrical edit
                // pauses END once, followed by START and END packets next tick.
                grid.catchUp = true;
            }
        } catch (RuntimeException error) { fail(error); }
    }
    private void fail(RuntimeException error) { failure = error.toString(); error.printStackTrace(); topology.close(); }
    private boolean accessible(ServerLevel level, WorldGrid grid) {
        if (worlds.get(level) != grid || server.getLevel(level.dimension()) != level) return false;
        // Delayed wire edits never authorize transfer through inaccessible chunks.
        for (long chunk : grid.conductorChunks.keySet()) {
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) == null
                || !level.shouldTickBlocksAt(chunk)) return false;
        }
        return true;
    }
    private void settle(ServerLevel level, WorldGrid grid) {
        if (!accessible(level, grid)) return;
        var ports = new ArrayList<Port>();
        for (var entry : grid.machines.entrySet()) {
            BlockPos at = entry.getKey(); var tile = entry.getValue();
            var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            if (chunk == null || !level.shouldTickBlocksAt(ChunkPos.asLong(at)) || tile.isRemoved()
                || chunk.getBlockEntity(at, LevelChunk.EntityCreationType.CHECK) != tile) continue;
            if (tile instanceof IndependentTransformerBlockEntity transformer) {
                if (!transformer.validMode()) continue;
                // Visible mode changes revoke old quotes immediately; the new sides
                // join only after the same delayed publication as electrical edits.
                if (!Integer.valueOf(transformer.routingSignature()).equals(grid.transformerRouting.get(at))) continue;
                var offered = transformer.snapshot(transformer.stepUpNow());
                if (offered.isEmpty()) continue;
                var saved = offered.orElseThrow(); var limits = saved.limits();
                var property = saved.state().getBlock().getStateDefinition().getProperty("facing");
                if (property == null || !(saved.state().getValue(property) instanceof Direction facing))
                    throw new IllegalStateException("Transformer facing missing");
                int high = 1 << facing.ordinal(), low = 63 ^ high;
                int input = saved.stepUp() ? low : high, output = saved.stepUp() ? high : low;
                var quote = new CustomEUEnergyStorage.NetworkQuote(level, at, saved.energy().amount(),
                    limits.capacity(), limits.inputLimit(), limits.outputPacket(), limits.outputPacket(), true, true, saved.energy());
                ports.add(new Port(tile, at, saved.state(), null, quote, limits.capacity(), input, output,
                    limits.outputPacket(), limits.outputPackets(), saved));
                continue;
            }
            var machine = (mio_icif_Energy_Block) tile;
            int inputs = 63, outputs = 0;
            var type = BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock());
            boolean generator = type.equals(GENERATOR);
            if (generator) { inputs = 0; outputs = 63; }
            if (tile instanceof mio_icif_Energy_Container storageBox && STORAGE_PACKETS.containsKey(type)) {
                inputs = 0;
                for (var side : Direction.values()) {
                    if (storageBox.canProvidePowerFromSide(side)) outputs |= 1 << side.ordinal();
                    if (storageBox.canConsumePowerFromSide(side)) inputs |= 1 << side.ordinal();
                }
            }
            var storage = machine.getEnergyStorageInternal(); var quote = storage.scexNetworkQuote();
            if (!quote.outputEnabled()) outputs = 0;
            // Original binary observations distinguish generator residual offers
            // from the BatBox full-packet reserve rule, including a 1 EU offer.
            long packet = generator ? Math.min(32, quote.amount()) : STORAGE_PACKETS.getOrDefault(type, 32L);
            ports.add(new Port(tile, at, tile.getBlockState(), storage, quote, machine.getEffectiveCapacity(), inputs, outputs, packet, 1, null));
        }
        var sources = ports.stream().filter(p -> p.outputs != 0 && p.packet > 0 && p.quote.amount() >= p.packet).toList();
        var sinks = ports.stream().filter(p -> p.inputs != 0).toList();
        if (sources.isEmpty() || sinks.isEmpty()) return;
        var snapshot = grid.conductors.snapshot(); long[] room = new long[sinks.size()];
        int[] receivers = new int[sinks.size()];
        for (int i = 0; i < room.length; i++) { room[i] = Math.max(0, sinks.get(i).capacity - sinks.get(i).quote.amount()); receivers[i] = i; }
        var quotes = sources.stream().map(source -> new DomainDistributor.Source(source.quote.amount(), source.packet,
            BuiltInRegistries.BLOCK.getKey(source.state.getBlock()).equals(GENERATOR), source.packets)).toList();
        var effectPaths = new ArrayList<List<ConductorRegistry.Path[]>>();
        var domains = domains(sources, sinks, snapshot, receivers, level.getGameTime(), grid, effectPaths);
        var shared = new ArrayList<DomainDistributor.SharedStorage>();
        var sinkIndices = new IdentityHashMap<Port, Integer>();
        for (int i = 0; i < sinks.size(); i++) sinkIndices.put(sinks.get(i), i);
        for (int i = 0; i < sources.size(); i++) {
            var source = sources.get(i); var receiver = sinkIndices.get(source);
            if (source.transformer != null && receiver != null)
                shared.add(new DomainDistributor.SharedStorage(i, receiver, source.capacity));
        }
        var round = DomainDistributor.allocateTraced(quotes, domains, receivers, room, shared, selectionRandom);
        var deltas = new IdentityHashMap<Port, Long>();
        long loss = round.dissipated(), debit = 0, credit = 0;
        for (int i = 0; i < sources.size(); i++) {
            long value = round.debit(i); debit = Math.addExact(debit, value);
            deltas.merge(sources.get(i), -value, Math::addExact);
        }
        for (int receiver = 0; receiver < sinks.size(); receiver++) {
            long value = round.credit(receiver); credit = Math.addExact(credit, value);
            deltas.merge(sinks.get(receiver), value, Math::addExact);
        }
        if (debit == 0) return;
        long traceDebit = 0, traceCredit = 0, traceLoss = 0;
        long[] wireVisits = {0};
        var fusePlan = new LinkedHashMap<BlockPos, ResourceLocation>();
        var receiverPlan = new LinkedHashMap<BlockPos, Port>();
        var detectorDeliveries = new HashSet<IndependentSpecialCableBlockEntity>();
        for (var delivery : round.deliveries()) {
            traceDebit = Math.addExact(traceDebit, delivery.sourceDebit());
            traceCredit = Math.addExact(traceCredit, delivery.credit());
            traceLoss = Math.addExact(traceLoss, delivery.pathLoss());
            var path = effectPaths.get(delivery.domain()).get(delivery.entry())[delivery.receiver()];
            long[] weakestMeasuredLimit = {Long.MAX_VALUE};
            if (path == null) {
                if (delivery.pathLoss() != 0) throw new IllegalStateException("Direct delivery has conductor loss");
            } else {
                if (path.lossMilli() / 1000 != delivery.pathLoss()) throw new IllegalStateException("Delivery path and quoted loss differ");
                path.visit(position -> {
                    wireVisits[0] = Math.incrementExact(wireVisits[0]);
                    var at = new BlockPos(position.x(), position.y(), position.z());
                    var type = grid.conductorTypes.get(at);
                    if (type == null) throw new IllegalStateException("Published conductor has no material identity");
                    var special = grid.specialCables.get(at);
                    if (special != null && special.detector()) detectorDeliveries.add(special);
                    Long limit = FUSE_LIMITS.get(type);
                    if (limit != null) {
                        weakestMeasuredLimit[0] = Math.min(weakestMeasuredLimit[0], limit);
                        if (delivery.sourceDebit() > limit) fusePlan.put(at, type);
                    }
                });
            }
            var sink = sinks.get(delivery.receiver());
            var receiverType = BuiltInRegistries.BLOCK.getKey(sink.state.getBlock());
            Long receiverLimit = STORAGE_PACKETS.get(receiverType);
            if (sink.transformer != null) receiverLimit = Long.valueOf(sink.transformer.limits().inputLimit());
            // The machine publishes its current upgraded limit in the same
            // quote as its balance/capacity. Revalidation rejects the whole
            // transaction if that limit changes before the common commit.
            // R26 normal-game voltage observations cover 0..3 upgrades.
            else if (BASIC_PROCESSORS.contains(receiverType)) receiverLimit = Long.valueOf(sink.quote.maxReceive());
            // Apply the independently measured single-packet receiver rule.
            // A fuse protects the receiver; the measured conductor boundary
            // can destroy it even when the credited energy fits its tier.
            // Mixed/multi-source effects and collateral blast remain unverified.
            boolean fused = delivery.sourceDebit() > weakestMeasuredLimit[0];
            boolean boundary = path != null && delivery.sourceDebit() == weakestMeasuredLimit[0];
            if (receiverLimit != null && !fused && delivery.sourceDebit() > receiverLimit
                    && (delivery.credit() > receiverLimit || boundary)) {
                receiverPlan.put(sink.position, sink);
            }
        }
        if (traceDebit != debit || traceCredit != credit || traceLoss != loss)
            throw new IllegalStateException("Delivery trace does not reconcile with the round");
        var writes = new ArrayList<CustomEUEnergyStorage.NetworkWrite>();
        var additional = new ArrayList<NetworkCell.Write>();
        for (var port : ports) {
            long delta = deltas.getOrDefault(port, 0L);
            long next = Math.addExact(port.quote.amount(), delta);
            if (port.transformer != null) additional.add(new NetworkCell.Write(port.transformer.energy(), next));
            else if (delta != 0) writes.add(new CustomEUEnergyStorage.NetworkWrite(port.storage, port.quote, next));
        }
        if (CustomEUEnergyStorage.scexCommitNetwork(writes, additional, loss, () -> valid(level, grid, snapshot, ports))) {
            detectorDeliveries.forEach(IndependentSpecialCableBlockEntity::delivered);
            for (var port : ports) if (port.transformer != null && deltas.getOrDefault(port, 0L) != 0)
                ((IndependentTransformerBlockEntity) port.tile).markNetworkChanged();
            commits++; debited = Math.addExact(debited, debit); credited = Math.addExact(credited, credit); dissipated = Math.addExact(dissipated, loss);
            deliveryCount = Math.addExact(deliveryCount, round.deliveries().size());
            deliveryWireVisits = Math.addExact(deliveryWireVisits, wireVisits[0]);
            var removedByEffects = new HashSet<BlockPos>();
            for (var fuse : fusePlan.entrySet()) {
                var at = fuse.getKey();
                var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                if (chunk == null || !BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(at).getBlock()).equals(fuse.getValue())) continue;
                if (level.removeBlock(at, false)) {
                    // Effects run after the numeric transaction. Revoke the
                    // conductor lease now so the destroyed wire cannot carry
                    // another packet while its publication event is pending.
                    grid.conductors.remove(point(at));
                    grid.contactOrder.remove(point(at));
                    topology.changed(level, at);
                    removedByEffects.add(at);
                    fusedWires = Math.incrementExact(fusedWires);
                }
            }
            for (var sink : receiverPlan.values()) {
                var at = sink.position;
                var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                if (chunk == null || chunk.getBlockEntity(at) != sink.tile || !chunk.getBlockState(at).equals(sink.state)) continue;
                if (level.removeBlock(at, false)) {
                    grid.machines.remove(at);
                    grid.contactOrder.remove(point(at));
                    grid.initialGeneratorContacts.remove(at);
                    topology.changed(level, at);
                    removedByEffects.add(at);
                    destroyedReceivers = Math.incrementExact(destroyedReceivers);
                    // Independent audible feedback uses the vanilla resource;
                    // no reference-mod sound asset is copied. Volume 1.2 gives
                    // a 19.2-block attenuation radius with the vanilla sound.
                    level.playSound(null, at, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                        net.minecraft.sounds.SoundSource.BLOCKS, 1.2F, 1.0F);
                    applySmallEntityBlast(level, at);
                    applySmallBlockBlast(level, grid, at, removedByEffects);
                }
            }
            if (!removedByEffects.isEmpty()) {
                // Our own completed removals are already known this frame.
                // Retire them together so a later observer echo cannot delay
                // the measured next-END pause by an additional frame.
                grid.apply(grid.forget(removedByEffects::contains));
                // A blast during a START catch-up still schedules the next
                // frame's END pause. Consuming a boolean in this same frame's
                // END had made later independent inputs one tick too early.
                grid.effectPauseFrame = ticks + 2;
            }
        } else rejected++;
    }
    private static void applySmallEntityBlast(ServerLevel level, BlockPos center) {
        var origin = net.minecraft.world.phys.Vec3.atCenterOf(center);
        var box = new net.minecraft.world.phys.AABB(origin.x - 6.5, origin.y - 6.5, origin.z - 6.5,
            origin.x + 6.5, origin.y + 6.5, origin.z + 6.5);
        var entities = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
            entity -> entity.isAlive() && !entity.isSpectator());
        if (entities.isEmpty()) return;
        // Sample before neighbour destruction: glass and stone shields both
        // obstruct the measured field even when the glass is then removed.
        var field = new SmallBlastField(origin.x, origin.y, origin.z, (x, y, z) -> {
            var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return chunk == null || !chunk.getBlockState(new BlockPos(x, y, z)).isAir();
        });
        var source = level.damageSources().explosion(null, null);
        for (var entity : entities) {
            int damage = field.damageAt(entity.getX(), entity.getY(), entity.getZ());
            if (damage == 0 || !entity.hurt(source, damage)) continue;
            var direction = entity.position().subtract(origin).normalize();
            entity.setDeltaMovement(entity.getDeltaMovement().add(direction.scale(damage * 0.021875)));
            entity.hurtMarked = true;
        }
    }
    private void applySmallBlockBlast(ServerLevel level, WorldGrid grid, BlockPos center, Set<BlockPos> removed) {
        // Independent block-only hypothesis from frozen material/position
        // observations: the 18 face/edge neighbours, evaluated individually.
        // The resistance cutoff matches the measured vanilla materials; values
        // between 1 and 1.5 and other modded blocks remain unverified.
        // R25's new272-block sample retained207dirt items, supporting the frozen
        // three-in-four drop hypothesis for this small blast. Vanilla loot
        // rules still decide the item, and doTileDrops remains authoritative.
        // Entity fields run first; sounds and other strengths remain separate.
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            int distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared == 0 || distanceSquared > 2) continue;
            var at = center.offset(dx, dy, dz);
            var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            if (chunk == null) continue;
            var state = chunk.getBlockState(at);
            if (state.isAir() || state.getDestroySpeed(level, at) < 0) continue;
            var type = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            float resistance = state.getBlock().getExplosionResistance();
            if (!MEASURED_BLAST_CABLES.contains(type)
                    && (!Float.isFinite(resistance) || resistance < 0 || resistance > 1.0F)) continue;
            var drops = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS)
                ? net.minecraft.world.level.block.Block.getDrops(state, level, at,
                    chunk.getBlockEntity(at, LevelChunk.EntityCreationType.CHECK), null, net.minecraft.world.item.ItemStack.EMPTY)
                : List.<net.minecraft.world.item.ItemStack>of();
            if (level.removeBlock(at, false)) {
                grid.conductors.remove(point(at));
                grid.contactOrder.remove(point(at));
                grid.machines.remove(at);
                grid.initialGeneratorContacts.remove(at);
                topology.changed(level, at);
                removed.add(at);
                blastBlocks = Math.incrementExact(blastBlocks);
                for (var drop : drops) if (blockDropRandom.nextInt(4) != 0)
                    net.minecraft.world.level.block.Block.popResource(level, at, drop);
            }
        }
    }
    private boolean valid(ServerLevel level, WorldGrid grid, ConductorRegistry.Snapshot snapshot, List<Port> ports) {
        // The independent published registry intentionally preserves one final
        // packet after a wire edit, as measured. Its lease is still current;
        // source/sink identity, all fresh quotes and chunk access remain mandatory.
        if (!accessible(level, grid) || !grid.conductors.isCurrent(snapshot)) return false;
        for (var port : ports) {
            var chunk = level.getChunkSource().getChunkNow(port.position.getX() >> 4, port.position.getZ() >> 4);
            if (chunk == null || !level.shouldTickBlocksAt(ChunkPos.asLong(port.position)) || port.tile.isRemoved()
                || chunk.getBlockEntity(port.position, LevelChunk.EntityCreationType.CHECK) != port.tile
                || chunk.getBlockState(port.position) != port.state) return false;
            if (port.transformer != null) {
                var transformer = (IndependentTransformerBlockEntity) port.tile;
                if (!transformer.validMode() || !transformer.isCurrent(port.transformer, transformer.stepUpNow())) return false;
            } else if (((mio_icif_Energy_Block) port.tile).getEffectiveCapacity() != port.capacity
                    || !port.storage.scexNetworkQuote().equals(port.quote)) return false;
        }
        return true;
    }
    private static ConductorRegistry.Position point(BlockPos at) { return new ConductorRegistry.Position(at.getX(), at.getY(), at.getZ()); }
    private List<DomainDistributor.Domain> domains(List<Port> sources, List<Port> sinks,
            ConductorRegistry.Snapshot graph, int[] receivers, long worldTime, WorldGrid grid,
            List<List<ConductorRegistry.Path[]>> effectPaths) {
        // Nonnegative keys identify physical wire components; negative keys identify
        // individual direct contacts. A receiver never joins separate wire runs.
        var grouped = new LinkedHashMap<Long, LinkedHashMap<Long, Map.Entry<long[], ConductorRegistry.Path[]>>>();
        for (int sourceId = 0; sourceId < sources.size(); sourceId++) {
            var source = sources.get(sourceId);
            var initial = grid.initialGeneratorContacts.getOrDefault(source.position, Set.of());
            int count = 0, componentId = -1;
            boolean sameComponent = true;
            for (Direction side : Direction.values()) {
                if ((source.outputs & (1 << side.ordinal())) == 0) continue;
                var at = source.position.relative(side); var position = point(at);
                if (!graph.contains(position)) continue;
                count++;
                if (!initial.contains(at)) sameComponent = false;
                int found = graph.componentOf(position);
                if (componentId < 0) componentId = found;
                else if (componentId != found) sameComponent = false;
            }
            // R18's two-contact, pre-existing conductor controls justify this
            // finite integration scope. Three contacts and merge history remain
            // unresolved; this does not identify an original internal algorithm.
            boolean separateContacts = initial.size() == 2 && count == 2 && sameComponent;
            for (Direction output : Direction.values()) {
                if ((source.outputs & (1 << output.ordinal())) == 0) continue;
                BlockPos start = source.position.relative(output);
                var origin = point(start);
                boolean wired = graph.contains(origin);
                var paths = wired ? graph.routesFrom(origin) : null;
                long component = wired ? graph.componentOf(origin) : -1;
                long emitter = 7L * sourceId + (separateContacts ? output.ordinal() : 6);
                for (int receiver = 0; receiver < sinks.size(); receiver++) {
                    var sink = sinks.get(receiver); if (source.tile == sink.tile) continue;
                    if (start.equals(sink.position) && (sink.inputs & (1 << output.getOpposite().ordinal())) != 0) {
                        long direct = -1L - (long) sourceId * sinks.size() - receiver;
                        recordRoute(grouped, direct, 7L * sourceId + 6, receiver, sinks.size(), 0, null, null, null);
                    }
                    if (!wired) continue;
                    for (Direction input : Direction.values()) {
                        if ((sink.inputs & (1 << input.ordinal())) == 0) continue;
                        var contact = point(sink.position.relative(input)); if (!graph.contains(contact)) continue;
                        int vertex = graph.vertex(contact);
                        if (paths.reaches(vertex)) recordRoute(grouped, component, emitter, receiver, sinks.size(), paths.lossMilliTo(vertex), graph, origin, contact);
                    }
                }
            }
        }
        var result = new ArrayList<DomainDistributor.Domain>();
        for (var domainItem : grouped.entrySet()) {
            var domain = domainItem.getValue();
            var selectedPaths = new ArrayList<ConductorRegistry.Path[]>();
            int[] ids = new int[domain.size()]; int[][] priorities = new int[domain.size()][];
            var routes = new ArrayList<RouteCosts>(); int index = 0; boolean shared = false;
            for (var entry : domain.entrySet()) {
                int source = (int) (entry.getKey() / 7); long[] losses = entry.getValue().getKey();
                selectedPaths.add(entry.getValue().getValue());
                boolean contactEntry = entry.getKey() % 7 != 6; shared |= contactEntry;
                RouteCosts costs = new RouteCosts() {
                    @Override public boolean reaches(int contact) { return losses[contact] >= 0; }
                    @Override public long lossMilliTo(int contact) {
                        if (!reaches(contact)) throw new IllegalArgumentException("Unreachable endpoint");
                        return losses[contact];
                    }
                };
                boolean[] eligible = new boolean[sinks.size()];
                for (int receiver = 0; receiver < eligible.length; receiver++) {
                    // Full connected receivers retain their random offset.
                    eligible[receiver] = costs.reaches(receiver) && costs.wholeLossTo(receiver) < sources.get(source).packet;
                }
                ids[index] = source; routes.add(costs);
                int[] registration = receivers;
                if (contactEntry) {
                    registration = Arrays.stream(receivers).boxed().sorted(java.util.Comparator.comparingLong(
                        receiver -> costs.reaches(receiver) ? costs.lossMilliTo(receiver) : Long.MAX_VALUE))
                        .mapToInt(Integer::intValue).toArray();
                }
                if (!contactEntry && domainItem.getKey() >= 0
                        && BuiltInRegistries.BLOCK.getKey(sources.get(source).state.getBlock()).equals(GENERATOR)) {
                    var sourcePort = sources.get(source);
                    ConductorRegistry.Position contact = null; int contacts = 0;
                    for (Direction side : Direction.values()) {
                        var near = point(sourcePort.position.relative(side));
                        if ((sourcePort.outputs & (1 << side.ordinal())) != 0 && graph.contains(near)
                                && graph.componentOf(near) == domainItem.getKey()) { contact = near; contacts++; }
                    }
                    if (contacts > 1) {
                        var order = grid.contactOrder.receivers(point(sourcePort.position), contact);
                        if (!order.isEmpty()) {
                            var ranks = new HashMap<ConductorRegistry.Position, Integer>();
                            for (int i = 0; i < order.size(); i++) ranks.put(order.get(i), i);
                            registration = Arrays.stream(receivers).boxed().sorted(java.util.Comparator.comparingInt(
                                receiver -> ranks.getOrDefault(point(sinks.get(receiver).position), Integer.MAX_VALUE)))
                                .mapToInt(Integer::intValue).toArray();
                        }
                    }
                }
                priorities[index++] = ReceiverOrder.create(registration, eligible, worldTime, selectionRandom);
            }
            result.add(shared ? DomainDistributor.Domain.withSharedSourceContacts(ids, routes, priorities)
                : new DomainDistributor.Domain(ids, routes, priorities));
            effectPaths.add(selectedPaths);
        }
        return result;
    }
    private static void recordRoute(Map<Long, LinkedHashMap<Long, Map.Entry<long[], ConductorRegistry.Path[]>>> domains, long domain,
            long source, int receiver, int count, long loss, ConductorRegistry.Snapshot graph,
            ConductorRegistry.Position origin, ConductorRegistry.Position target) {
        var selected = domains.computeIfAbsent(domain, key -> new LinkedHashMap<>()).computeIfAbsent(source, key -> {
            long[] values = new long[count]; Arrays.fill(values, -1); return Map.entry(values, new ConductorRegistry.Path[count]);
        });
        long[] losses = selected.getKey();
        if (losses[receiver] < 0 || loss < losses[receiver]) {
            losses[receiver] = loss;
            selected.getValue()[receiver] = graph == null ? null : graph.path(origin, target);
        }
    }
    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) {
        if (event.getServer() != server) return;
        topology.close(); cleared(); closed = true;
        synchronized (IndependentSiEnergy.class) { SERVERS.remove(server); }
        server = null;
        NeoForge.EVENT_BUS.unregister(this);
    }
}
