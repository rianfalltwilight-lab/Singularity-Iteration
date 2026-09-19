// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.singularity_iteration.mio_icif.Blocks.Crop.mio_icif_crop_stick;
import com.singularity_iteration.mio_icif.Blocks.entity.crop.mio_icif_crop_entity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Opt-in R201 dedicated-server connection and block-entity sync observer. */
@EventBusSubscriber(modid = "scex_si_smoke", value = Dist.CLIENT)
public final class ConnectedClientProbe {
    private static final Gson GSON = new Gson();
    private static final Path MARKER = Path.of("connected-client-r201.json");
    private static final Path RESULT = Path.of("r201-connected-client-result.json");
    private static final Path FAILURE = Path.of("r201-connected-client-failure.json");
    private static final BlockPos CROP_POS = new BlockPos(2600, 90, 0);
    private static final String REVISION = "R201";
    private static final String RUN_NONCE = "r201-connected-client-01-20260918";
    private static final String SERVER_ADDRESS = "127.0.0.1:25591";
    private static final String PLANT_ID = "mio_icif:wheat";
    private static final String INITIAL_BARRIER = "r201-client-initial.json";
    private static final String UPDATED_BARRIER = "r201-client-updated.json";
    private static final int MAX_CLIENT_FRAMES = 5000;

    private static final List<String> screenshots = new ArrayList<>();
    private static JsonObject initialExpected;
    private static JsonObject updatedExpected;
    private static Path sharedRoot;
    private static String renderer;
    private static String vendor;
    private static String remoteAddress = "";
    private static String playerName = "";
    private static Map<String, Object> initialObserved;
    private static Map<String, Object> updatedObserved;
    private static Map<String, Object> lastObserved;
    private static String capture;
    private static int phase;
    private static int frames;
    private static int disconnectFrame = -1;
    private static boolean configured;
    private static boolean done;

    private ConnectedClientProbe() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("scex.client.connected") || done) return;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (!configured) configure(minecraft);
            frames++;
            if (frames >= MAX_CLIENT_FRAMES) {
                throw new IllegalStateException("R201 connected client exceeded 5000-frame bound phase=" + phase
                    + " last_observed=" + lastObserved);
            }

            if (phase == 0 && minecraft.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
                    && minecraft.getOverlay() == null) {
                minecraft.options.onboardingAccessibilityFinished();
                minecraft.setScreen(new TitleScreen());
            }
            if (phase == 0 && minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) {
                ServerData server = new ServerData("SCEX R201 dedicated probe", SERVER_ADDRESS, ServerData.Type.OTHER);
                phase = 1;
                System.out.println("SCEX_R201_CLIENT_CONNECT address=" + SERVER_ADDRESS);
                ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(SERVER_ADDRESS),
                    server, false, null);
                return;
            }

            if (phase == 1 && connectedToDedicatedServer(minecraft)) {
                Map<String, Object> observed = observeCrop(minecraft);
                if (matches(observed, initialExpected)) {
                    initialObserved = Map.copyOf(observed);
                    capture = "r201-01-initial.png";
                    phase = 2;
                }
            } else if (phase == 3 && connectedToDedicatedServer(minecraft)) {
                Map<String, Object> observed = observeCrop(minecraft);
                if (matches(observed, updatedExpected)) {
                    updatedObserved = Map.copyOf(observed);
                    capture = "r201-02-updated.png";
                    phase = 4;
                }
            } else if (phase == 5) {
                if (!connectedToDedicatedServer(minecraft)) {
                    throw new IllegalStateException("Dedicated connection disappeared before requested disconnect");
                }
                minecraft.disconnect(new TitleScreen());
                disconnectFrame = frames;
                phase = 6;
            } else if (phase == 6 && minecraft.level == null && minecraft.getConnection() == null
                    && minecraft.getSingleplayerServer() == null && frames >= disconnectFrame + 5) {
                writeFinalResult();
                done = true;
                System.out.println("SCEX_R201_CONNECTED_CLIENT passed=true frames=" + frames);
                minecraft.stop();
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("scex.client.connected") || done || capture == null) return;
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            Files.createDirectories(Path.of("screenshots"));
            image.writeToFile(Path.of("screenshots", capture));
            String relative = "screenshots/" + capture;
            screenshots.add(capture);
            if (phase == 2) {
                writeAtomic(sharedRoot.resolve(INITIAL_BARRIER), barrier("initial", initialObserved, relative));
                phase = 3;
            } else if (phase == 4) {
                writeAtomic(sharedRoot.resolve(UPDATED_BARRIER), barrier("updated", updatedObserved, relative));
                phase = 5;
            } else {
                throw new IllegalStateException("Unexpected R201 screenshot phase " + phase);
            }
            System.out.println("SCEX_R201_CLIENT_SCREENSHOT path=" + relative);
            capture = null;
        } catch (Throwable error) {
            fail(error);
        }
    }

    private static void configure(Minecraft minecraft) throws Exception {
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
        require(marker.get("ticks").getAsInt() == 1800, "ticks");
        require(marker.get("fixture_setup_tick").getAsInt() == 20, "fixture_setup_tick");
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
        require(bounds.get("client_frames").getAsInt() == MAX_CLIENT_FRAMES, "bounded_timeouts.client_frames");
        require(bounds.get("server_ticks").getAsInt() == 1800, "bounded_timeouts.server_ticks");

        String configuredRoot = System.getProperty("scex.connected.sharedRoot", "");
        require(!configuredRoot.isBlank(), "scex.connected.sharedRoot property");
        sharedRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
        require(Files.isDirectory(sharedRoot), "shared root directory");
        require(!Files.exists(sharedRoot.resolve(INITIAL_BARRIER)), "initial barrier must not pre-exist");
        require(!Files.exists(sharedRoot.resolve(UPDATED_BARRIER)), "updated barrier must not pre-exist");

        minecraft.options.pauseOnLostFocus = false;
        minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        minecraft.options.renderDistance().set(4);
        minecraft.options.simulationDistance().set(4);
        minecraft.options.framerateLimit().set(30);
        renderer = String.valueOf(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
        vendor = String.valueOf(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR));
        configured = true;
        System.out.println("SCEX_R201_CLIENT_OPENGL renderer=" + renderer + " vendor=" + vendor);
    }

    private static boolean connectedToDedicatedServer(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null
                || minecraft.screen != null) return false;
        require(minecraft.getSingleplayerServer() == null && !minecraft.hasSingleplayerServer(),
            "client must not have an integrated server");
        ServerData current = minecraft.getCurrentServer();
        require(current != null && SERVER_ADDRESS.equals(current.ip), "current dedicated server identity");
        require(minecraft.getConnection().getConnection().isConnected(), "network connection alive");
        remoteAddress = String.valueOf(minecraft.getConnection().getConnection().getRemoteAddress());
        playerName = minecraft.player.getGameProfile().getName();
        return true;
    }

    private static Map<String, Object> observeCrop(Minecraft minecraft) {
        var blockEntity = minecraft.level.getBlockEntity(CROP_POS);
        if (!(blockEntity instanceof mio_icif_crop_entity crop) || crop.getPlant() == null) return null;
        var state = minecraft.level.getBlockState(CROP_POS);
        int age = state.hasProperty(mio_icif_crop_stick.AGE) ? state.getValue(mio_icif_crop_stick.AGE) : -1;
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("plant_id", crop.getPlant().getModId() + ":" + crop.getPlant().getTypeId());
        observed.put("stage", crop.getGrowthStage());
        observed.put("water", crop.getWater());
        observed.put("block_age", age);
        lastObserved = Map.copyOf(observed);
        return observed;
    }

    private static boolean matches(Map<String, Object> observed, JsonObject expected) {
        return observed != null
            && PLANT_ID.equals(observed.get("plant_id"))
            && ((Number)observed.get("stage")).intValue() == expected.get("stage").getAsInt()
            && ((Number)observed.get("water")).intValue() == expected.get("water").getAsInt()
            && ((Number)observed.get("block_age")).intValue() == expected.get("block_age").getAsInt();
    }

    private static Map<String, Object> barrier(String stage, Map<String, Object> observed, String screenshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", REVISION);
        result.put("run_nonce", RUN_NONCE);
        result.put("passed", true);
        result.put("phase", stage);
        result.put("server_address", SERVER_ADDRESS);
        result.put("dedicated", true);
        result.put("integrated_server", false);
        result.put("remote_address", remoteAddress);
        result.put("player_name", playerName);
        result.put("plant_id", observed.get("plant_id"));
        result.put("stage", observed.get("stage"));
        result.put("water", observed.get("water"));
        result.put("block_age", observed.get("block_age"));
        result.put("screenshot", screenshot);
        result.put("client_frame", frames);
        return result;
    }

    private static void writeFinalResult() throws Exception {
        require(screenshots.equals(List.of("r201-01-initial.png", "r201-02-updated.png")),
            "two ordered screenshots");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", REVISION);
        result.put("run_nonce", RUN_NONCE);
        result.put("passed", true);
        result.put("server_address", SERVER_ADDRESS);
        result.put("dedicated", true);
        result.put("integrated_server", false);
        result.put("remote_address", remoteAddress);
        result.put("player_name", playerName);
        result.put("renderer", renderer);
        result.put("vendor", vendor);
        result.put("frames", frames);
        result.put("plant_id", PLANT_ID);
        result.put("initial", initialObserved);
        result.put("updated", updatedObserved);
        result.put("screenshots", List.copyOf(screenshots));
        result.put("barrier_files", Map.of("initial", INITIAL_BARRIER, "updated", UPDATED_BARRIER));
        result.put("normal_disconnect", true);
        writeAtomic(RESULT, result);
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
        if (!value) throw new IllegalStateException("R201 connected client assertion failed: " + label);
    }

    private static synchronized void fail(Throwable error) {
        if (done) return;
        done = true;
        error.printStackTrace();
        try {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("revision", REVISION);
            failure.put("run_nonce", RUN_NONCE);
            failure.put("passed", false);
            failure.put("phase", phase);
            failure.put("frames", frames);
            failure.put("error", error.toString());
            failure.put("last_observed", lastObserved == null ? Map.of() : lastObserved);
            writeAtomic(FAILURE, failure);
        } catch (Throwable suppressed) {
            error.addSuppressed(suppressed);
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.level != null || minecraft.getConnection() != null) minecraft.disconnect(new TitleScreen());
        } catch (Throwable suppressed) {
            error.addSuppressed(suppressed);
        }
        minecraft.stop();
    }
}
