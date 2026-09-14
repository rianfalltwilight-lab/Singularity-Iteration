// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;

/** Opt-in private integrated-client sound output observer; no runtime effects. */
@EventBusSubscriber(modid="scex_si_smoke",value=Dist.CLIENT)
public final class ClientAudioProbe {
    private static final Gson GSON=new Gson();
    private static final List<String> images=new ArrayList<>();
    private static int phase,frames,entered=-1,finished=-1,sounds;
    private static String capture;
    private static boolean done;
    private static java.io.BufferedWriter output;
    private ClientAudioProbe() {}
    private static synchronized void record(String kind,Object value)throws Exception {
        if(output==null || done)return;
        output.write(GSON.toJson(Map.of("client_frame",frames,"nano_time",System.nanoTime(),"kind",kind,"value",value)));output.newLine();output.flush();
    }
    @SubscribeEvent public static void played(PlaySoundSourceEvent event) {
        if(!Boolean.getBoolean("scex.client.audio") || done)return;
        try {
            var sound=event.getSound();if(sound==null)return;
            if(++sounds>10000)throw new IllegalStateException("Client sound bound reached");
            var value=new LinkedHashMap<String,Object>();value.put("sound",sound.getLocation().toString());value.put("category",sound.getSource().toString());value.put("volume",sound.getVolume());value.put("pitch",sound.getPitch());value.put("x",sound.getX());value.put("y",sound.getY());value.put("z",sound.getZ());record("play-source",value);
        } catch(Exception error) {fail(error);}
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("scex.client.audio") || done)return;
        var mc=Minecraft.getInstance();
        try {
            if(output==null) {
                output=Files.newBufferedWriter(Path.of("client-audio.jsonl"));
                mc.options.pauseOnLostFocus=false;mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.options.renderDistance().set(4);mc.options.simulationDistance().set(5);mc.options.framerateLimit().set(30);
                String renderer=org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
                String vendor=org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
                record("opengl",Map.of("renderer",String.valueOf(renderer),"vendor",String.valueOf(vendor)));
                System.out.println("SCEX_CLIENT_OPENGL renderer="+renderer+" vendor="+vendor);
            }
            frames++;
            if(phase==0 && mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen && mc.getOverlay()==null) {
                mc.options.onboardingAccessibilityFinished();mc.setScreen(new TitleScreen());
            }
            if(phase==0 && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {capture="r25-01-title.png";phase=1;}
            else if(phase==2) {
                phase=3;
                var settings=new LevelSettings("SCEX independent overvoltage audio",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("r25-audio-world",settings,new WorldOptions(252525L,false,false),registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),new TitleScreen());
            }
            if(mc.level!=null && mc.player!=null && mc.screen==null && entered<0) {entered=frames;record("player-entered",true);}
            if(entered>=0 && frames==entered+40)capture="r25-02-world.png";
            if(entered>=0 && frames==entered+310)capture="r25-03-experiment.png";
            if(Files.isRegularFile(Path.of("probe-result.json"))) {
                if(finished<0)finished=frames;
                if(frames>=finished+40) {
                    var result=Map.of("completed",true,"frames",frames,"entered_frame",entered,"sound_events",sounds,"screenshots",List.copyOf(images));
                    Files.writeString(Path.of("client-audio-result.json"),GSON.toJson(result));record("finished",result);done=true;output.close();mc.stop();
                }
            }
            if(frames>5000)throw new IllegalStateException("Client observation bound reached");
        } catch(Exception error) {fail(error);}
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if(capture==null || done)return;
        try(var picture=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            Files.createDirectories(Path.of("screenshots"));picture.writeToFile(Path.of("screenshots",capture));images.add(capture);record("screenshot",capture);capture=null;if(phase==1)phase=2;
        } catch(Exception error) {fail(error);}
    }
    private static synchronized void fail(Exception error) {
        if(done)return;done=true;error.printStackTrace();
        try {Files.writeString(Path.of("client-audio-failure.txt"),error.toString());if(output!=null)output.close();}catch(Exception ignored) {error.addSuppressed(ignored);}
        Minecraft.getInstance().stop();
    }
}
