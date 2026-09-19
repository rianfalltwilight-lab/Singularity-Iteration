// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Crop.mio_icif_crop_stick;
import com.singularity_iteration.mio_icif.Blocks.entity.crop.mio_icif_crop_entity;
import com.singularity_iteration.mio_icif.Blocks.mio_icif_blocks;
import com.singularity_iteration.mio_icif.api.internal.crop.PlantRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

/** R202 dedicated-server fixture requiring two named real clients online together. */
public final class DualClientWorldProbe {
    private static final Gson GSON = new Gson();
    private static final Path MARKER = Path.of("dual-client-r202.json");
    private static final Path RESULT = Path.of("dual-client-r202-server-result.json");
    private static final BlockPos CROP_POS = new BlockPos(2600, 90, 0);
    private static final String REVISION = "R202";
    private static final String RUN_NONCE = "r202-dual-client-01-20260918";
    private static final String SERVER_ADDRESS = "127.0.0.1:25592";
    private static final String PLANT_ID = "mio_icif:wheat";
    private static final List<String> ROLES = List.of("A", "B");
    private static final Set<String> EXPECTED_NAMES = Set.of("R202ObserverA", "R202ObserverB");
    private static final int FIXTURE_SETUP_TICK = 20;
    private static final int MAX_SERVER_TICKS = 2400;

    private final MinecraftServer server;
    private final JsonObject marker;
    private final JsonObject initialExpected;
    private final JsonObject updatedExpected;
    private final Path sharedRoot;
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, Path> initialBarriers = new LinkedHashMap<>();
    private final Map<String, Path> updatedBarriers = new LinkedHashMap<>();
    private final Map<String, String> initialScreenshots = new LinkedHashMap<>();
    private final Map<String, String> updatedScreenshots = new LinkedHashMap<>();
    private final Map<String, String> playerUuids = new LinkedHashMap<>();
    private final Map<String, Object> initialObserved;
    private Map<String, Object> updatedObserved;
    private int phase;
    private int completedTick = -1;
    private int simultaneousTick = -1;
    private boolean fixtureReady;
    private boolean complete;

    public DualClientWorldProbe(MinecraftServer server) throws Exception {
        this.server = server;
        marker = JsonParser.parseString(Files.readString(MARKER, StandardCharsets.UTF_8)).getAsJsonObject();
        require(marker.get("schema_version").getAsInt() == 3, "schema_version");
        require(marker.get("revision").getAsString().equals(REVISION), "revision");
        require(marker.get("run_nonce").getAsString().equals(RUN_NONCE), "run_nonce");
        require(System.getProperty("scex.connected.runNonce", "").equals(RUN_NONCE), "run nonce property");
        require(marker.get("server_address").getAsString().equals(SERVER_ADDRESS), "server_address");
        require(marker.get("required_simultaneous_players").getAsInt() == 2, "required_simultaneous_players");
        require(marker.get("plant_id").getAsString().equals(PLANT_ID), "plant_id");
        require(marker.get("ticks").getAsInt() == MAX_SERVER_TICKS, "ticks");
        require(marker.get("fixture_setup_tick").getAsInt() == FIXTURE_SETUP_TICK, "fixture_setup_tick");
        requireVector(marker, "block_position", new double[] {2600, 90, 0});
        initialExpected = marker.getAsJsonObject("initial");
        updatedExpected = marker.getAsJsonObject("updated");
        requireExpected(initialExpected, 2, 100, 2, "initial");
        requireExpected(updatedExpected, 6, 77, 6, "updated");
        JsonObject clients = marker.getAsJsonObject("clients");
        for (String role : ROLES) {
            JsonObject client = clients.getAsJsonObject(role);
            String lower = role.toLowerCase(java.util.Locale.ROOT);
            String name = client.get("username").getAsString();
            require(name.equals("R202Observer" + role) && name.chars().allMatch(value -> value < 128), role + " username");
            require(client.get("username_ascii_length").getAsInt() == name.length() && name.length() == 13
                && name.length() <= marker.get("client_username_protocol_limit").getAsInt(), role + " username length");
            names.put(role, name);
            JsonObject barriers = client.getAsJsonObject("barrier_files");
            String initial = barriers.get("initial").getAsString();
            String updated = barriers.get("updated").getAsString();
            require(initial.equals("r202-client-" + lower + "-initial.json"), role + " initial barrier");
            require(updated.equals("r202-client-" + lower + "-updated.json"), role + " updated barrier");
            var screenshots = client.getAsJsonArray("expected_screenshots");
            require(screenshots.size() == 2, role + " screenshots length");
            initialScreenshots.put(role, "screenshots/" + screenshots.get(0).getAsString());
            updatedScreenshots.put(role, "screenshots/" + screenshots.get(1).getAsString());
        }
        require(Set.copyOf(names.values()).equals(EXPECTED_NAMES), "two exact unique usernames");
        JsonObject bounds = marker.getAsJsonObject("bounded_timeouts");
        require(bounds.get("client_frames").getAsInt() == 6500, "bounded_timeouts.client_frames");
        require(bounds.get("server_ticks").getAsInt() == MAX_SERVER_TICKS, "bounded_timeouts.server_ticks");
        String configuredRoot = System.getProperty("scex.connected.sharedRoot", "");
        require(!configuredRoot.isBlank(), "scex.connected.sharedRoot property");
        sharedRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
        require(Files.isDirectory(sharedRoot), "shared root directory");
        for (String role : ROLES) {
            JsonObject barriers = clients.getAsJsonObject(role).getAsJsonObject("barrier_files");
            initialBarriers.put(role, sharedRoot.resolve(barriers.get("initial").getAsString()));
            updatedBarriers.put(role, sharedRoot.resolve(barriers.get("updated").getAsString()));
            require(!Files.exists(initialBarriers.get(role)), role + " initial barrier must not pre-exist");
            require(!Files.exists(updatedBarriers.get(role)), role + " updated barrier must not pre-exist");
        }
        initialObserved = Map.of("stage", 2, "water", 100, "block_age", 2);
    }

    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        require(world == server.overworld(), "overworld identity");
        if (!fixtureReady) {
            if (tick < FIXTURE_SETUP_TICK) return null;
            require(tick == FIXTURE_SETUP_TICK, "fixture setup occurs exactly at tick 20");
            setup(world);
            fixtureReady = true;
            return Map.of("phase", "fixture-ready", "tick", tick, "position", CROP_POS.toShortString());
        }
        if (complete) return null;
        if (tick >= MAX_SERVER_TICKS - 1) {
            throw new IllegalStateException("R202 dual client handshake exceeded 2400-tick bound phase=" + phase
                + " players=" + currentPlayerNames());
        }
        Map<String, ServerPlayer> players = currentPlayers();
        require(players.size() <= 2, "at most two connected test players");
        require(EXPECTED_NAMES.containsAll(players.keySet()), "no unexpected player identity");
        if (phase == 0 && players.keySet().equals(EXPECTED_NAMES)) {
            simultaneousTick = tick;
            for (String role : ROLES) {
                ServerPlayer player = players.get(names.get(role));
                playerUuids.put(role, player.getUUID().toString());
                JsonObject position = marker.getAsJsonObject("clients").getAsJsonObject(role)
                    .getAsJsonObject("player_position");
                player.teleportTo(world, position.get("x").getAsDouble(), position.get("y").getAsDouble(),
                    position.get("z").getAsDouble(), 180.0F, 0.0F);
            }
            phase = 1;
            return Map.of("phase", "two-players-simultaneous", "tick", tick, "player_count", 2,
                "players", List.copyOf(EXPECTED_NAMES));
        }
        if (phase == 1) {
            require(players.keySet().equals(EXPECTED_NAMES), "both players remain online before initial acknowledgements");
            if (allExist(initialBarriers)) {
                for (String role : ROLES) validateBarrier(role, initialBarriers.get(role), "initial", initialExpected,
                    initialScreenshots.get(role));
                mio_icif_crop_entity crop = crop(world);
                crop.setGrowthStage(updatedExpected.get("stage").getAsInt());
                crop.setWater(updatedExpected.get("water").getAsInt());
                crop.updateState();
                updatedObserved = snapshot(world, crop);
                require(matches(updatedObserved, updatedExpected), "server updated crop state");
                phase = 2;
                return Map.of("phase", "server-update-sent-after-two-initial-acks", "tick", tick,
                    "player_count", 2, "state", updatedObserved);
            }
        }
        if (phase == 2) {
            require(players.keySet().equals(EXPECTED_NAMES), "both players remain online before updated acknowledgements");
            if (allExist(updatedBarriers)) {
                for (String role : ROLES) validateBarrier(role, updatedBarriers.get(role), "updated", updatedExpected,
                    updatedScreenshots.get(role));
                require(matches(snapshot(world, crop(world)), updatedExpected), "server retains updated crop state");
                phase = 3;
                return Map.of("phase", "two-client-update-acknowledged", "tick", tick, "player_count", 2);
            }
        }
        if (phase == 3 && players.isEmpty()) {
            completedTick = tick;
            writeResult();
            complete = true;
            phase = 4;
            return Map.of("phase", "both-clients-disconnected", "tick", tick, "normal_halt_ready", true);
        }
        return null;
    }

    public boolean isComplete() {
        return complete;
    }

    private void setup(ServerLevel world) {
        require(world.getChunkSource().getChunkNow(CROP_POS.getX() >> 4, CROP_POS.getZ() >> 4) != null,
            "crop chunk loaded before fixture setup");
        require(world.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(CROP_POS)),
            "crop chunk ticking before fixture setup");
        world.setDefaultSpawnPos(new BlockPos(2600, 91, 3), 180.0F);
        world.setBlockAndUpdate(CROP_POS.below(), Blocks.FARMLAND.defaultBlockState());
        world.setBlockAndUpdate(CROP_POS, mio_icif_blocks.CROP_STICK.get().defaultBlockState());
        mio_icif_crop_entity crop = crop(world);
        var wheat = PlantRegistry.instance.getPlant("mio_icif", "wheat");
        require(wheat != null, "registered wheat plant");
        crop.setPlant(wheat);
        crop.setGrowthStage(initialExpected.get("stage").getAsInt());
        crop.setGrowthSpeed(0);
        crop.setYield(0);
        crop.setResilience(0);
        crop.setNutrients(100);
        crop.setWater(initialExpected.get("water").getAsInt());
        crop.setScanLevel(2);
        crop.setProgress(0);
        crop.setHybridBase(false);
        crop.updateState();
        require(matches(snapshot(world, crop), initialExpected), "server initial crop state");
    }

    private Map<String, ServerPlayer> currentPlayers() {
        Map<String, ServerPlayer> players = new TreeMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayer prior = players.put(player.getGameProfile().getName(), player);
            require(prior == null, "unique player names");
        }
        return players;
    }

    private List<String> currentPlayerNames() {
        return server.getPlayerList().getPlayers().stream()
            .map(player -> player.getGameProfile().getName()).sorted().toList();
    }

    private static boolean allExist(Map<String, Path> paths) {
        return paths.values().stream().allMatch(Files::isRegularFile);
    }

    private mio_icif_crop_entity crop(ServerLevel world) {
        var blockEntity = world.getBlockEntity(CROP_POS);
        require(blockEntity instanceof mio_icif_crop_entity, "crop block entity at fixed position");
        return (mio_icif_crop_entity)blockEntity;
    }

    private Map<String, Object> snapshot(ServerLevel world, mio_icif_crop_entity crop) {
        require(crop.getPlant() != null, "crop plant present");
        require((crop.getPlant().getModId() + ":" + crop.getPlant().getTypeId()).equals(PLANT_ID), "wheat identity");
        var state = world.getBlockState(CROP_POS);
        require(state.hasProperty(mio_icif_crop_stick.AGE), "crop block age property");
        return Map.of("stage", crop.getGrowthStage(), "water", crop.getWater(),
            "block_age", state.getValue(mio_icif_crop_stick.AGE));
    }

    private void validateBarrier(String role, Path path, String expectedPhase, JsonObject expected,
            String screenshot) throws Exception {
        JsonObject barrier = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        require(barrier.get("revision").getAsString().equals(REVISION), role + " barrier revision");
        require(barrier.get("run_nonce").getAsString().equals(RUN_NONCE), role + " barrier nonce");
        require(barrier.get("passed").getAsBoolean(), role + " barrier passed");
        require(barrier.get("role").getAsString().equals(role), role + " barrier role");
        require(barrier.get("phase").getAsString().equals(expectedPhase), role + " barrier phase");
        require(barrier.get("server_address").getAsString().equals(SERVER_ADDRESS), role + " barrier address");
        require(barrier.get("dedicated").getAsBoolean() && !barrier.get("integrated_server").getAsBoolean(),
            role + " barrier dedicated only");
        require(barrier.get("player_name").getAsString().equals(names.get(role)), role + " barrier player");
        require(barrier.get("plant_id").getAsString().equals(PLANT_ID), role + " barrier plant");
        require(barrier.get("stage").getAsInt() == expected.get("stage").getAsInt(), role + " barrier stage");
        require(barrier.get("water").getAsInt() == expected.get("water").getAsInt(), role + " barrier water");
        require(barrier.get("block_age").getAsInt() == expected.get("block_age").getAsInt(), role + " barrier age");
        require(barrier.get("screenshot").getAsString().equals(screenshot), role + " barrier screenshot");
        require(barrier.get("client_frame").getAsInt() > 0 && barrier.get("client_frame").getAsInt() < 6500,
            role + " barrier frame bound");
    }

    private void writeResult() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", REVISION);
        result.put("run_nonce", RUN_NONCE);
        result.put("passed", true);
        result.put("server_address", SERVER_ADDRESS);
        result.put("required_simultaneous_players", 2);
        result.put("simultaneous_player_count", 2);
        result.put("simultaneous_players", List.of("R202ObserverA", "R202ObserverB"));
        result.put("player_uuids", playerUuids);
        result.put("simultaneous_tick", simultaneousTick);
        result.put("plant_id", PLANT_ID);
        result.put("initial", initialObserved);
        result.put("updated", updatedObserved);
        Map<String, Object> barriers = new LinkedHashMap<>();
        for (String role : ROLES) {
            barriers.put(role, Map.of("initial", initialBarriers.get(role).getFileName().toString(),
                "updated", updatedBarriers.get(role).getFileName().toString()));
        }
        result.put("barrier_files", barriers);
        result.put("both_clients_disconnected", true);
        result.put("ticks", MAX_SERVER_TICKS);
        result.put("fixture_setup_tick", FIXTURE_SETUP_TICK);
        result.put("completed_tick", completedTick);
        writeAtomic(RESULT, result);
    }

    private static boolean matches(Map<String, Object> observed, JsonObject expected) {
        return ((Number)observed.get("stage")).intValue() == expected.get("stage").getAsInt()
            && ((Number)observed.get("water")).intValue() == expected.get("water").getAsInt()
            && ((Number)observed.get("block_age")).intValue() == expected.get("block_age").getAsInt();
    }

    private static void requireVector(JsonObject marker, String key, double[] expected) {
        var actual = marker.getAsJsonArray(key);
        require(actual.size() == expected.length, key + " length");
        for (int i = 0; i < expected.length; i++) {
            require(Double.compare(actual.get(i).getAsDouble(), expected[i]) == 0, key + "[" + i + "]");
        }
    }

    private static void requireExpected(JsonObject actual, int stage, int water, int age, String label) {
        require(actual.get("stage").getAsInt() == stage, label + ".stage");
        require(actual.get("water").getAsInt() == water, label + ".water");
        require(actual.get("block_age").getAsInt() == age, label + ".block_age");
    }

    private static void writeAtomic(Path path, Object value) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void require(boolean value, String label) {
        if (!value) throw new IllegalStateException("R202 dual server assertion failed: " + label);
    }
}
