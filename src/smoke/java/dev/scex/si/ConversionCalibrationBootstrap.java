// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Marker-gated smoke bootstrap; no edit of shared WorldScenarioProbe is needed. */
@EventBusSubscriber(modid="scex_si_smoke")
public final class ConversionCalibrationBootstrap {
    private static final Map<MinecraftServer,Session> SESSIONS=new IdentityHashMap<>();
    private static final class Session {
        final StirlingCalibrationProbe probe=new StirlingCalibrationProbe();
        int tick;
    }
    private ConversionCalibrationBootstrap() { }
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if(Files.exists(Path.of("stirling-calibration.json")))SESSIONS.put(event.getServer(),new Session());
    }
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        var session=SESSIONS.get(event.getServer());if(session==null)return;
        try {
            session.probe.inspect(event.getServer().overworld(),session.tick++);
            if(session.tick>162)SESSIONS.remove(event.getServer());
        } catch(Throwable failure) {
            SESSIONS.remove(event.getServer());failure.printStackTrace();
            try {
                Files.writeString(Path.of("stirling-calibration-result.json"),new Gson().toJson(Map.of(
                        "observation_completed",false,"tick",session.tick-1,"failure",failure.toString())));
            } catch(Exception reportingFailure) { reportingFailure.printStackTrace(); }
            event.getServer().halt(false);
        }
    }
    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { SESSIONS.remove(event.getServer()); }
}
