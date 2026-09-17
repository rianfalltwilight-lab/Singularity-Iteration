// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_chunk_loader;
import dev.scex.energy.EnergyAmount;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Draft v2: no pending-NBT promotion, no synthetic ticks or ticket calls. */
public final class ChunkTicketColdProbe {
    private static final Path INPUT = Path.of("chunk-ticket-cold.json");
    private static final Path OUTPUT = Path.of("chunk-ticket-cold-result.json");
    private static final BlockPos OWNER = new BlockPos(18032, 80, 100);
    private static final ChunkPos SELF = new ChunkPos(OWNER);
    private static final String CONTROLLER = "mio_icif:paid_chunk_loader";
    private static final long NBT_LIMIT = 64L * 1024 * 1024;
    private static boolean installed;
    private final Path worldRoot;
    private final String manifestHash;
    private final JsonObject input;
    private final CompoundTag savedOwner;
    private final CompoundTag savedTickets;
    private final EnergyAmount savedEnergy;
    private final Set<Long> savedSelection;
    private final Set<Long> retained;
    private final long savedGameTime;
    private final long bill;
    private final int observeTicks;
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final Map<String, Object> baseline = new LinkedHashMap<>();
    private MinecraftServer server;
    private ServerLevel world;
    private mio_icif_chunk_loader owner;
    private int assertions, loadEvents, naturalTicks, observedTicks, paidNaturalTicks;
    private boolean beforeReady;
    private long startupTime, beforeTime;
    private EnergyAmount beforeEnergy;
    private boolean started, beforePending, done;

    /** Call once from the smoke mod constructor, before any server level loads. */
    public static void installIfPresent() {
        if (!Files.exists(INPUT)) return;
        if (installed) throw new IllegalStateException("R132 cold probe installed twice");
        installed = true;
        try {
            var probe = new ChunkTicketColdProbe();
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                    (ChunkEvent.Load event) -> probe.guarded(() -> probe.loaded(event)));
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                    (ServerStartedEvent event) -> probe.guarded(() -> probe.started(event)));
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
                    (LevelTickEvent.Pre event) -> probe.guarded(() -> probe.before(event)));
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                    (LevelTickEvent.Post event) -> probe.guarded(() -> probe.after(event)));
        } catch (Exception error) {
            try {
                Files.writeString(OUTPUT, new GsonBuilder().setPrettyPrinting().create().toJson(
                        Map.of("status", "FAIL_COLD_PREPARATION", "passed", false,
                                "error", error.toString())));
            } catch (IOException suppressed) { error.addSuppressed(suppressed); }
            throw new IllegalStateException("R132 cold preparation failed", error);
        }
    }

    private ChunkTicketColdProbe() throws Exception {
        input = JsonParser.parseString(Files.readString(INPUT)).getAsJsonObject();
        require(input.get("schema_version").getAsInt() == 1, "schema 1");
        require(input.get("case").getAsString().equals("r131_paid_self"), "paid-self scope only");
        worldRoot = checkedOrdinaryPath(Path.of(input.get("world_root").getAsString()));
        require(dev.scex.energy.IndependentEnergyMode.enabled()
                && dev.scex.energy.IndependentEnergyMode.feature("processingMachines"),
                "startup ownership policy enabled before any owner constructor");
        observeTicks = input.get("observe_ticks").getAsInt();
        require(observeTicks >= 2 && observeTicks <= 100, "bounded natural observation");
        var source = input.getAsJsonObject("source_process");
        long sourcePid = source.get("pid").getAsLong();
        Instant sourceStart = Instant.parse(source.get("started_at").getAsString());
        var current = ProcessHandle.current();
        require(sourcePid != current.pid() || !sourceStart.equals(current.info().startInstant().orElseThrow()),
                "different actual JVM identity");
        require(ProcessHandle.of(sourcePid).flatMap(p -> p.info().startInstant())
                .map(t -> !t.equals(sourceStart)).orElse(true), "source process has exited");
        require(source.get("exit_code").getAsInt() == 0 && source.get("normal_stop").getAsBoolean(),
                "runner receipt says clean normal stop");
        Path stopReceipt = Path.of(source.get("receipt_path").getAsString());
        require(hash(stopReceipt).equals(source.get("receipt_sha256").getAsString()), "stop receipt hash");
        Path manifest = Path.of(input.get("world_manifest").getAsString());
        manifestHash = hash(manifest);
        require(manifestHash.equals(input.get("world_manifest_sha256").getAsString()), "original tree manifest hash");
        verifyWorldTree(manifest);
        savedOwner = readOwnerFromRegion(worldRoot);
        savedTickets = NbtIo.readCompressed(worldRoot.resolve("data/chunks.dat"),
                NbtAccounter.create(NBT_LIMIT)).getCompound("data");
        var level = NbtIo.readCompressed(worldRoot.resolve("level.dat"),
                NbtAccounter.create(NBT_LIMIT)).getCompound("Data");
        savedGameTime = level.getLong("Time");
        require(savedOwner.contains("energy", Tag.TAG_ANY_NUMERIC), "disk energy exists");
        savedEnergy = new EnergyAmount(savedOwner.getLong("energy"), savedOwner.getLong("scex_energy_fraction"));
        savedSelection = selection(savedOwner);
        var owned = tickets(savedTickets, true);
        retained = new TreeSet<>(owned);
        retained.retainAll(savedSelection);
        retained.removeIf(key -> !inRange(new ChunkPos(key)));
        require(savedSelection.equals(Set.of(SELF.toLong())) && retained.equals(Set.of(SELF.toLong())),
                "exact R131 c self-only selection and valid saved ticking ticket");
        require(owned.equals(retained) && tickets(savedTickets, false).isEmpty(), "no extra saved owner tickets");
        require(Arrays.stream(savedTickets.getLongArray("Forced")).noneMatch(x -> x == SELF.toLong()),
                "no vanilla force ticket masks this owner");
        bill = (long) Math.ceil(retained.size() * mio_icif_chunk_loader.DEFAULT_EU_PER_CHUNK);
        require(bill > 0 && savedEnergy.whole() >= Math.multiplyExact(bill, observeTicks + 2L),
                "positive measured-policy bill and enough cold balance");
        baseline.put("source_process", source);
        baseline.put("current_pid", current.pid());
        baseline.put("current_started_at", current.info().startInstant().orElseThrow().toString());
        baseline.put("world_root", worldRoot.toString());
        baseline.put("world_manifest_sha256", manifestHash);
        baseline.put("input_sha256", hash(INPUT));
        baseline.put("saved_owner_snbt", savedOwner.toString());
        baseline.put("saved_chunks_data_snbt", savedTickets.toString());
        baseline.put("saved_game_time", savedGameTime);
        baseline.put("valid_saved_ticket_count", retained.size());
        baseline.put("startup_bill", bill);
        write("PREPARED_RAW_DISK_BASELINE", false, null);
    }

    private void loaded(ChunkEvent.Load event) throws Exception {
        if (!(event.getLevel() instanceof ServerLevel w) || !event.getChunk().getPos().equals(SELF)
                || !w.dimension().location().toString().equals("minecraft:overworld")) return;
        require(!started && ++loadEvents == 1, "one natural owner load before ServerStarted");
        require(w.getServer().isSameThread() && w.getServer().getTickCount() == 0,
                "startup load on server thread before natural ticks");
        require(event.getChunk() instanceof LevelChunk, "ordinary LevelChunk Load");
        var chunk = (LevelChunk) event.getChunk();
        var found = chunk.getBlockEntities().get(OWNER); // No pending-NBT promotion.
        require(w.getGameTime() == savedGameTime, "saved time intact on natural load");
        require(!w.shouldTickBlocksAt(SELF.toLong()), "no unpaid runtime ticking before startup validation");
        if (found == null) {
            var pending = chunk.getBlockEntityNbt(OWNER); // Public map lookup only.
            require(pending != null, "actual original owner NBT pending without forced instantiation");
            var observed = pending.copy();
            require(observed.getString("id").equals(savedOwner.getString("id"))
                    && observed.getInt("x") == OWNER.getX() && observed.getInt("y") == OWNER.getY()
                    && observed.getInt("z") == OWNER.getZ(), "pending original owner identity");
            require(new EnergyAmount(observed.getLong("energy"), observed.getLong("scex_energy_fraction"))
                    .equals(savedEnergy), "pending raw energy equals original disk before validation");
            require(Objects.equals(observed.get("inventory"), savedOwner.get("inventory"))
                    && selection(observed).equals(savedSelection), "pending inventory and selection preserved");
            require(!chunk.getBlockEntities().containsKey(OWNER), "probe did not promote pending owner");
            var row = new LinkedHashMap<String, Object>();
            row.put("phase", "owner-natural-load-pending-before-validation");
            row.put("server_tick", w.getServer().getTickCount()); row.put("game_time", w.getGameTime());
            row.put("thread", Thread.currentThread().getName()); row.put("owner_instantiated", false);
            row.put("pending_owner_snbt", observed.toString()); row.put("expected_energy", savedEnergy);
            row.put("network_controlled_observed", "NOT_INSTANTIATED");
            row.put("runtime_should_tick", w.shouldTickBlocksAt(SELF.toLong()));
            samples.add(row);
        } else {
            require(found instanceof mio_icif_chunk_loader, "naturally instantiated original registered loader");
            owner = (mio_icif_chunk_loader) found;
            require(owner.getEnergyStorageInternal().scexExactAmount().equals(savedEnergy),
                    "disk energy intact while callback's owner load is still completing");
            checkStaticState(w);
            sample(w, "owner-natural-load-before-validation", savedEnergy);
        }
    }

    private void started(ServerStartedEvent event) throws Exception {
        require(!started && event.getServer().getTickCount() == 0, "ServerStarted before first tick");
        server = event.getServer(); world = server.overworld();
        require(server.isSameThread(), "server thread");
        require(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(worldRoot),
                "opened exact verified cold world");
        require(loadEvents == 1, "early listener actually observed natural owner load");
        var chunk = world.getChunkSource().getChunkNow(SELF.x, SELF.z);
        require(chunk != null, "real validation callback loaded owner chunk");
        var found = chunk.getBlockEntities().get(OWNER);
        require(found instanceof mio_icif_chunk_loader, "real validation callback instantiated owner");
        if (owner != null) require(found == owner, "same naturally existing owner identity");
        owner = (mio_icif_chunk_loader) found;
        requireOwnerPresent();
        startupTime = world.getGameTime();
        require(startupTime == savedGameTime, "no unobserved natural tick or time command");
        var expected = savedEnergy.subtract(EnergyAmount.of(bill));
        require(owner.getEnergyStorageInternal().scexExactAmount().equals(expected),
                "startup validation charged exactly valid saved tickets once");
        checkStaticState(world); checkTickets(false);
        sample(world, "server-started-after-validation-before-tick", expected);
        // active may have been reset by deferred onLoad; it is not permission evidence.
        started = true;
        write("OBSERVING_NATURAL_COLD_TICKS", false, null);
    }

    private void before(LevelTickEvent.Pre event) throws Exception {
        if (event.getLevel() != world) return;
        require(started && !beforePending, "one paired natural level Pre");
        requireOwnerPresent();
        beforeTime = world.getGameTime();
        beforeEnergy = owner.getEnergyStorageInternal().scexExactAmount();
        require(beforeTime == startupTime + observedTicks, "continuous natural world times");
        var expected = savedEnergy.subtract(EnergyAmount.of(Math.multiplyExact(bill, 1L + paidNaturalTicks)));
        require(beforeEnergy.equals(expected), "no between-tick second debit or free balance change");
        checkStaticState(world); checkTickets(naturalTicks > 0);
        sample(world, "level-pre-" + (naturalTicks + 1), expected);
        beforeReady = runtimeReady();
        beforePending = true;
    }

    private void after(LevelTickEvent.Post event) throws Exception {
        if (event.getLevel() != world) return;
        require(beforePending, "matching natural level Post");
        requireOwnerPresent();
        // This fixture requires normal running time; frozen/skipped ticks are not silently counted.
        require(world.getGameTime() == beforeTime + 1, "one natural game-time advance");
        var actual = owner.getEnergyStorageInternal().scexExactAmount();
        boolean charged = actual.equals(beforeEnergy.subtract(EnergyAmount.of(bill)));
        sample(world, "level-post-observed-" + (observedTicks + 1),
                beforeReady ? beforeEnergy.subtract(EnergyAmount.of(bill)) : beforeEnergy);
        if (beforeReady) {
            require(charged, "fully ticking owner pays exactly once for the natural tick");
            require(runtimeReady(), "paid owner retains full runtime eligibility");
            naturalTicks++;
        } else {
            require(naturalTicks == 0 && observedTicks < 20, "bounded initial platform propagation only");
            require(charged || actual.equals(beforeEnergy), "startup transition never overpays or changes fractions");
        }
        if (charged) paidNaturalTicks++;
        checkStaticState(world); checkTickets(naturalTicks > 0);
        if (charged) require(owner.isActive(), "paid natural work is active");
        beforePending = false; observedTicks++;
        if (naturalTicks == observeTicks) {
            done = true;
            write("PASS_SCOPED_COLD_PAID_OWNER", true, null);
        }
    }

    private boolean runtimeReady() {
        var chunk = world.getChunkSource().getChunkNow(SELF.x, SELF.z);
        return chunk != null && chunk.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING)
                && world.areEntitiesLoaded(SELF.toLong()) && world.shouldTickBlocksAt(SELF.toLong());
    }

    private void requireOwnerPresent() {
        require(server.isSameThread(), "main-thread observation");
        var chunk = world.getChunkSource().getChunkNow(SELF.x, SELF.z);
        require(chunk != null && chunk.getBlockEntities().get(OWNER) == owner
                && !owner.isRemoved(), "same loaded owner with no probe force-load or NBT promotion");
    }

    private void checkStaticState(ServerLevel w) {
        var now = owner.saveWithoutMetadata(w.registryAccess());
        require(Objects.equals(savedOwner.get("inventory"), now.get("inventory")), "exact inventory preserved");
        require(owner.getLoadedChunks().equals(savedSelection), "selection preserved as set, ignoring NBT order");
        require(owner.getEnergyStorageInternal().scexNetworkControlled(), "independent ledger active");
        require(owner.getEuPerChunk() == mio_icif_chunk_loader.DEFAULT_EU_PER_CHUNK, "same SI cost policy");
        for (int i = 0; i < owner.getItemHandler().getSlots(); i++)
            require(owner.getItemHandler().getStackInSlot(i).isEmpty(), "original c inventory empty, no battery input");
    }

    private void checkTickets(boolean runtimeReady) {
        var data = world.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");
        require(data != null, "actual persistent tracker exists");
        var now = data.save(new CompoundTag(), world.registryAccess());
        require(tickets(now, true).equals(retained) && tickets(now, false).isEmpty(), "exact paid owner tickets retained");
        require(!world.getForcedChunks().contains(SELF.toLong()), "no vanilla ticket introduced");
        // DistanceManager propagates the newly reinstated ticket on its next normal update.
        if (runtimeReady) require(world.shouldTickBlocksAt(SELF.toLong()), "paid runtime ticking eligibility restored after natural propagation");
    }

    private void sample(ServerLevel w, String phase, EnergyAmount expected) {
        var row = new LinkedHashMap<String, Object>();
        row.put("phase", phase); row.put("server_tick", w.getServer().getTickCount());
        row.put("game_time", w.getGameTime()); row.put("thread", Thread.currentThread().getName());
        row.put("owner_instantiated", true);
        row.put("network_controlled_observed", owner.getEnergyStorageInternal().scexNetworkControlled());
        row.put("actual_energy", owner.getEnergyStorageInternal().scexExactAmount());
        row.put("expected_energy", expected); row.put("active_observed", owner.isActive());
        row.put("runtime_should_tick", w.shouldTickBlocksAt(SELF.toLong()));
        var loadedChunk = w.getChunkSource().getChunkNow(SELF.x, SELF.z);
        row.put("full_status", loadedChunk == null ? "UNLOADED" : loadedChunk.getFullStatus().name());
        row.put("entities_loaded", w.areEntitiesLoaded(SELF.toLong()));
        row.put("owner_snbt", owner.saveWithoutMetadata(w.registryAccess()).toString());
        samples.add(row);
    }

    private void verifyWorldTree(Path manifest) throws Exception {
        var declared = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        require(declared.get("schema_version").getAsInt() == 1, "world manifest schema");
        var expected = new TreeSet<String>();
        for (var entry : declared.getAsJsonArray("files")) {
            var row = entry.getAsJsonObject(); String relative = row.get("path").getAsString();
            Path relativePath = Path.of(relative);
            require(!relativePath.isAbsolute() && !relative.isEmpty() && relative.indexOf('\\') < 0,
                    "manifest uses canonical relative forward-slash paths");
            for (Path segment : relativePath)
                require(!segment.toString().equals("..") && !segment.toString().equals("."), "no dot path segments");
            Path path = checkedOrdinaryPath(worldRoot.resolve(relativePath));
            require(path.startsWith(worldRoot) && !relative.equals("session.lock") && expected.add(relative),
                    "unique bounded manifest path");
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(path) == row.get("bytes").getAsLong()
                    && hash(path).equals(row.get("sha256").getAsString()), "cold raw file matches " + relative);
        }
        var actual = new TreeSet<String>();
        var directories = new ArrayDeque<Path>(); directories.add(checkedOrdinaryPath(worldRoot));
        while (!directories.isEmpty()) {
            Path directory = checkedOrdinaryPath(directories.removeFirst());
            // Validate BEFORE opening the directory; never walk through an unchecked junction.
            try (var children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    Path path = checkedOrdinaryPath(child);
                    var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isDirectory()) directories.addLast(path);
                    else {
                        require(attrs.isRegularFile(), "only regular cold-world files");
                        String relative = worldRoot.relativize(path).toString().replace('\\', '/');
                        if (!relative.equals("session.lock")) actual.add(relative);
                    }
                }
            }
        }
        require(actual.equals(expected), "entire cold world matches original manifest except session.lock");
        require(expected.contains("level.dat") && expected.contains("data/chunks.dat")
                && expected.contains("region/r.35.0.mca"), "actual saved world core files included");
    }

    /** Read raw region bytes only. Do not open RegionFile: its constructor may write headers. */
    private static CompoundTag readOwnerFromRegion(Path root) throws Exception {
        Path region = root.resolve("region/r." + (SELF.x >> 5) + "." + (SELF.z >> 5) + ".mca");
        CompoundTag chunk;
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            file.seek(4L * ((SELF.x & 31) + (SELF.z & 31) * 32));
            int location = file.readInt(), sector = location >>> 8, sectors = location & 255;
            if (sector < 2 || sectors == 0) throw new IOException("Missing original owner region entry");
            file.seek(sector * 4096L);
            int length = file.readInt(), compression = file.readUnsignedByte();
            if (length < 1 || length > sectors * 4096 - 4 || sector * 4096L + 4 + length > file.length())
                throw new IOException("Invalid bounded region entry");
            byte[] bytes = new byte[length - 1]; file.readFully(bytes);
            InputStream raw = new ByteArrayInputStream(bytes);
            InputStream decoded = switch (compression) {
                case 1 -> new GZIPInputStream(raw);
                case 2 -> new InflaterInputStream(raw);
                case 3 -> raw;
                default -> throw new IOException("Unsupported/external region compression: " + compression);
            };
            try (var data = new DataInputStream(decoded)) { chunk = NbtIo.read(data, NbtAccounter.create(NBT_LIMIT)); }
        }
        CompoundTag found = null;
        for (Tag tag : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
            var be = (CompoundTag) tag;
            if (be.getInt("x") == OWNER.getX() && be.getInt("y") == OWNER.getY() && be.getInt("z") == OWNER.getZ()) {
                if (found != null) throw new IOException("Duplicate original owner block entity");
                found = be.copy();
            }
        }
        if (found == null) throw new IOException("Original owner absent from saved region");
        return found;
    }

    private Set<Long> selection(CompoundTag tag) {
        var result = new TreeSet<Long>(); var selected = tag.getCompound("loadedChunks");
        int count = selected.getInt("count");
        require(count >= 0 && count <= mio_icif_chunk_loader.MAX_CHUNKS, "bounded original selection");
        for (int i = 0; i < count; i++) {
            require(selected.contains("c" + i, Tag.TAG_LONG), "exact long saved selection");
            require(result.add(selected.getLong("c" + i)), "unique original selection");
        }
        return result;
    }

    private static Set<Long> tickets(CompoundTag data, boolean ticking) {
        var result = new TreeSet<Long>(); String key = ticking ? "TickingBlocks" : "Blocks";
        for (Tag tag : data.getList("ModForced", Tag.TAG_COMPOUND)) {
            var controller = (CompoundTag) tag;
            if (!CONTROLLER.equals(controller.getString("Controller"))) continue;
            for (Tag entry : controller.getList("ModForced", Tag.TAG_COMPOUND)) {
                var ticket = (CompoundTag) entry;
                for (Tag ownerTag : ticket.getList(key, Tag.TAG_COMPOUND)) {
                    var pos = (CompoundTag) ownerTag;
                    if (pos.getInt("X") == OWNER.getX() && pos.getInt("Y") == OWNER.getY() && pos.getInt("Z") == OWNER.getZ())
                        result.add(ticket.getLong("Chunk"));
                }
            }
        }
        return result;
    }

    private static boolean inRange(ChunkPos p) {
        return Math.abs((long) p.x - SELF.x) <= mio_icif_chunk_loader.CHUNK_RADIUS
                && Math.abs((long) p.z - SELF.z) <= mio_icif_chunk_loader.CHUNK_RADIUS;
    }

    /** Reject traversal and every existing intermediate link/reparse point, including root ancestors. */
    private static Path checkedOrdinaryPath(Path input) throws IOException {
        Path absolute = input.toAbsolutePath();
        for (Path segment : absolute)
            if (segment.toString().equals("..")) throw new IOException("Parent traversal path rejected: " + input);
        absolute = absolute.normalize();
        Path current = absolute.getRoot();
        if (current == null) throw new IOException("Absolute root required: " + input);
        checkOrdinaryComponent(current);
        for (Path segment : absolute) {
            current = current.resolve(segment);
            checkOrdinaryComponent(current);
        }
        return absolute;
    }

    private static void checkOrdinaryComponent(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Link/special/reparse path rejected: " + path);
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            // Public Files API; JDK 21 Windows DOS view exposes the native attribute mask.
            // Fail closed if the provider does not expose it. No reflection/native helper.
            Object mask = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            if (!(mask instanceof Number number) || (number.intValue() & 0x400) != 0)
                throw new IOException("Windows reparse path rejected: " + path);
        }
    }

    private static String hash(Path path) throws Exception {
        path = checkedOrdinaryPath(path);
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void require(boolean ok, String message) {
        assertions++; if (!ok) throw new IllegalStateException("R132 cold: " + message);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private void guarded(Checked action) {
        if (done) return;
        try { action.run(); }
        catch (Throwable error) {
            done = true;
            try { write("FAIL_COLD_PAID_OWNER", false, error.toString()); }
            catch (IOException suppressed) { error.addSuppressed(suppressed); }
            throw new IllegalStateException("R132 actual cold observation failed", error);
        }
    }
    private void write(String status, boolean passed, String error) throws IOException {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status); result.put("passed", passed); result.put("assertions", assertions);
        result.put("cases", 1); result.put("natural_ticks", naturalTicks);
        result.put("observed_level_ticks", observedTicks); result.put("paid_natural_ticks", paidNaturalTicks);
        result.put("baseline", baseline); result.put("samples", samples);
        result.put("limits", List.of("Only original paid self owner; invalid owner and insufficient balance are separate candidates",
                "Final acceptance also requires this JVM's normal-stop and saved-world receipt",
                "active is observational before first natural tick; onLoad ordering is not assumed"));
        if (error != null) result.put("error", error);
        Files.writeString(OUTPUT, new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
