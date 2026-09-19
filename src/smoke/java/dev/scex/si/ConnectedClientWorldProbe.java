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
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** R201 dedicated-server fixture paired with {@link ConnectedClientProbe}. */
public final class ConnectedClientWorldProbe {
    private static final Gson GSON = new Gson();
    private static final Path MARKER = Path.of("connected-client-r201.json");
    private static final Path RESULT = Path.of("connected-client-r201-server-result.json");
    private static final BlockPos CROP_POS = new BlockPos(2600, 90, 0);
    private static final String REVISION = "R201";
    private static final String RUN_NONCE = "r201-connected-client-01-20260918";
    private static final String SERVER_ADDRESS = "127.0.0.1:25591";
    private static final String PLANT_ID = "mio_icif:wheat";
    private static final String INITIAL_BARRIER = "r201-client-initial.json";
    private static final String UPDATED_BARRIER = "r201-client-updated.json";
    private static final int FIXTURE_SETUP_TICK = 20;
    private static final int MAX_SERVER_TICKS = 1800;

    private final MinecraftServer server;
    private final Path initialBarrier;
    private final Path updatedBarrier;
    private final JsonObject initialExpected;
    private final JsonObject updatedExpected;
    private final Map<String, Object> initialObserved;
    private Map<String, Object> updatedObserved;
    private int phase;
    private int completedTick = -1;
    private String playerName = "";
    private String playerUuid = "";
    private boolean fixtureReady;
    private boolean complete;

    public ConnectedClientWorldProbe(MinecraftServer server) throws Exception {
        this.server = server;
        JsonObject marker = JsonParser.parseString(Files.readString(MARKER, StandardCharsets.UTF_8)).getAsJsonObject();
        require(marker.get("schema_version").getAsInt() == 2, "schema_version");
        require(marker.get("revision").getAsString().equals(REVISION), "revision");
        require(marker.get("run_nonce").getAsString().equals(RUN_NONCE), "run_nonce");
        require(marker.get("server_address").getAsString().equals(SERVER_ADDRESS), "server_address");
        String clientUsername = marker.get("client_username").getAsString();
        require(clientUsername.equals("R201Observer"), "client_username");
        require(clientUsername.chars().allMatch(value -> value < 128), "client_username.ascii");
        require(marker.get("client_username_ascii_length").getAsInt() == clientUsername.length()
            && clientUsername.length() == 12, "client_username_ascii_length");
        require(marker.get("client_username_protocol_limit").getAsInt() == 16
            && clientUsername.length() <= 16, "client_username_protocol_limit");
        require(marker.get("plant_id").getAsString().equals(PLANT_ID), "plant_id");
        requireStringArray(marker, "expected_screenshots", List.of("r201-01-initial.png", "r201-02-updated.png"));
        requireStringArray(marker, "expected_result_files",
            List.of("r201-connected-client-result.json", "connected-client-r201-server-result.json"));
        require(marker.get("ticks").getAsInt() == MAX_SERVER_TICKS, "ticks");
        require(marker.get("fixture_setup_tick").getAsInt() == FIXTURE_SETUP_TICK, "fixture_setup_tick");
        requireVector(marker, "block_position", new double[] {2600, 90, 0});
        requireVector(marker, "player_position", new double[] {2600.5, 91, 3.5});
        initialExpected = marker.getAsJsonObject("initial");
        updatedExpected = marker.getAsJsonObject("updated");
        requireExpected(initialExpected, 2, 100, 2, "initial");
        requireExpected(updatedExpected, 6, 77, 6, "updated");
        JsonObject barriers = marker.getAsJsonObject("barrier_files");
        require(barriers.get("initial").getAsString().equals(INITIAL_BARRIER), "barrier_files.initial");
        require(barriers.get("updated").getAsString().equals(UPDATED_BARRIER), "barrier_files.updated");
        JsonObject bounds = marker.getAsJsonObject("bounded_timeouts");
        require(bounds.get("client_frames").getAsInt() == 5000, "bounded_timeouts.client_frames");
        require(bounds.get("server_ticks").getAsInt() == MAX_SERVER_TICKS, "bounded_timeouts.server_ticks");

        String configuredRoot = System.getProperty("scex.connected.sharedRoot", "");
        require(!configuredRoot.isBlank(), "scex.connected.sharedRoot property");
        Path sharedRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
        require(Files.isDirectory(sharedRoot), "shared root directory");
        initialBarrier = sharedRoot.resolve(INITIAL_BARRIER);
        updatedBarrier = sharedRoot.resolve(UPDATED_BARRIER);
        require(!Files.exists(initialBarrier), "initial barrier must not pre-exist");
        require(!Files.exists(updatedBarrier), "updated barrier must not pre-exist");
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
            throw new IllegalStateException("R201 dedicated client handshake exceeded 1800-tick bound phase=" + phase
                + " players=" + server.getPlayerList().getPlayers().size());
        }

        var players = server.getPlayerList().getPlayers();
        require(players.size() <= 1, "at most one connected test player");
        if (phase == 0 && players.size() == 1) {
            var player = players.getFirst();
            playerName = player.getGameProfile().getName();
            playerUuid = player.getUUID().toString();
            player.teleportTo(world, 2600.5, 91.0, 3.5, 180.0F, 0.0F);
            phase = 1;
            return Map.of("phase", "player-connected", "tick", tick, "player", playerName,
                "uuid", playerUuid, "player_count", 1);
        }
        if (phase == 1 && Files.isRegularFile(initialBarrier)) {
            validateBarrier(initialBarrier, "initial", initialExpected, "screenshots/r201-01-initial.png");
            mio_icif_crop_entity crop = crop(world);
            crop.setGrowthStage(updatedExpected.get("stage").getAsInt());
            crop.setWater(updatedExpected.get("water").getAsInt());
            crop.updateState();
            updatedObserved = snapshot(world, crop);
            require(matches(updatedObserved, updatedExpected), "server updated crop state");
            phase = 2;
            return Map.of("phase", "server-update-sent", "tick", tick, "state", updatedObserved);
        }
        if (phase == 2 && Files.isRegularFile(updatedBarrier)) {
            validateBarrier(updatedBarrier, "updated", updatedExpected, "screenshots/r201-02-updated.png");
            require(matches(snapshot(world, crop(world)), updatedExpected), "server retains updated crop state");
            phase = 3;
            return Map.of("phase", "client-update-acknowledged", "tick", tick);
        }
        if (phase == 3 && players.isEmpty()) {
            completedTick = tick;
            writeResult();
            complete = true;
            phase = 4;
            return Map.of("phase", "client-disconnected", "tick", tick, "normal_halt_ready", true);
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

    private void validateBarrier(Path path, String expectedPhase, JsonObject expected, String screenshot) throws Exception {
        JsonObject barrier = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        require(barrier.get("revision").getAsString().equals(REVISION), expectedPhase + " barrier revision");
        require(barrier.get("run_nonce").getAsString().equals(RUN_NONCE), expectedPhase + " barrier nonce");
        require(barrier.get("passed").getAsBoolean(), expectedPhase + " barrier passed");
        require(barrier.get("phase").getAsString().equals(expectedPhase), expectedPhase + " barrier phase");
        require(barrier.get("server_address").getAsString().equals(SERVER_ADDRESS), expectedPhase + " barrier address");
        require(barrier.get("dedicated").getAsBoolean(), expectedPhase + " barrier dedicated");
        require(!barrier.get("integrated_server").getAsBoolean(), expectedPhase + " barrier not integrated");
        require(barrier.get("player_name").getAsString().equals(playerName), expectedPhase + " barrier player");
        require(barrier.get("plant_id").getAsString().equals(PLANT_ID), expectedPhase + " barrier plant");
        require(barrier.get("stage").getAsInt() == expected.get("stage").getAsInt(), expectedPhase + " barrier stage");
        require(barrier.get("water").getAsInt() == expected.get("water").getAsInt(), expectedPhase + " barrier water");
        require(barrier.get("block_age").getAsInt() == expected.get("block_age").getAsInt(), expectedPhase + " barrier age");
        require(barrier.get("screenshot").getAsString().equals(screenshot), expectedPhase + " barrier screenshot");
        require(barrier.get("client_frame").getAsInt() > 0
            && barrier.get("client_frame").getAsInt() < 5000, expectedPhase + " barrier frame bound");
    }

    private void writeResult() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", REVISION);
        result.put("run_nonce", RUN_NONCE);
        result.put("passed", true);
        result.put("server_address", SERVER_ADDRESS);
        result.put("player_name", playerName);
        result.put("player_uuid", playerUuid);
        result.put("player_count", 1);
        result.put("plant_id", PLANT_ID);
        result.put("initial", initialObserved);
        result.put("updated", updatedObserved);
        result.put("barrier_files", Map.of("initial", INITIAL_BARRIER, "updated", UPDATED_BARRIER));
        result.put("client_disconnected", true);
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

    private static void requireStringArray(JsonObject marker, String key, List<String> expected) {
        var actual = marker.getAsJsonArray(key);
        require(actual.size() == expected.size(), key + " length");
        for (int i = 0; i < expected.size(); i++) {
            require(actual.get(i).getAsString().equals(expected.get(i)), key + "[" + i + "]");
        }
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
        if (!value) throw new IllegalStateException("R201 connected server assertion failed: " + label);
    }
}
