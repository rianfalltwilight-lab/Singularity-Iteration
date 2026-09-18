// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.singularity_iteration.mio_icif.Blocks.entity.producer.mio_icif_future_elc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.storage.LevelResource;

/** Readback only. This must be installed before Minecraft opens any world files. */
public final class FutureTradeColdProbe {
    private static final Path INPUT=Path.of("future-trade-cold-r134.json"),OUTPUT=Path.of("future-trade-cold-r134-result.json");
    private static final BlockPos OWNER=new BlockPos(23408,80,100);
    private static Prepared prepared;
    private record Prepared(JsonObject input,Path worldRoot,ListTag inventory,Map<String,Object> seal) {}
    private final Prepared baseline;
    private final List<Map<String,Object>> samples=new ArrayList<>();
    private int assertions;
    private boolean finished;
    private static void require(boolean value,String message) {if(!value)throw new IllegalStateException("R134 future cold: "+message);}
    private void check(boolean value,String message) {assertions++;require(value,message);}

    /** Call from SmokeProbe's mod constructor; constructing this at ServerStarted is too late. */
    public static void installIfPresent() {
        if(!Files.exists(INPUT))return;
        try {
            require(prepared==null,"single early install");
            var input=JsonParser.parseString(Files.readString(checked(INPUT))).getAsJsonObject();
            require(input.get("schema").getAsInt()==1,"schema1");
            Path world=checked(Path.of(input.get("world_root").getAsString()));
            Path manifest=checked(Path.of(input.get("world_manifest").getAsString()));
            require(hash(manifest).equals(input.get("world_manifest_sha256").getAsString()),"world manifest hash");
            verifyTree(world,manifest);
            var stopPath=checked(Path.of(input.get("normal_stop_result").getAsString()));
            require(hash(stopPath).equals(input.get("normal_stop_sha256").getAsString()),"normal stop receipt hash");
            var stop=JsonParser.parseString(Files.readString(stopPath)).getAsJsonObject();
            require(stop.get("passed").getAsBoolean()&&stop.get("saved_after_stop").getAsBoolean()&&stop.get("process_exit").getAsInt()==0
                &&!stop.get("forced").getAsBoolean()&&!stop.get("timed_out").getAsBoolean(),"actual normal save and process exit");
            Path warmPath=checked(Path.of(input.get("warm_result").getAsString()));
            require(hash(warmPath).equals(input.get("warm_result_sha256").getAsString()),"warm observation hash");
            var warm=JsonParser.parseString(Files.readString(warmPath)).getAsJsonObject();
            require(warm.get("passed").getAsBoolean()&&warm.get("completed_matrix_rows").getAsInt()==53
                &&warm.get("status").getAsString().equals("PASS_SCOPED_WARM_53_PLUS_2_COLD_PENDING"),"53 real warm rows plus extensions");
            var current=ProcessHandle.current();long sourcePid=input.get("source_pid").getAsLong();
            Instant sourceStart=Instant.parse(input.get("source_start").getAsString());
            require(current.pid()!=sourcePid||!current.info().startInstant().orElseThrow().equals(sourceStart),"different real JVM identity");
            require(ProcessHandle.of(sourcePid).flatMap(p->p.info().startInstant()).map(t->!t.equals(sourceStart)).orElse(true),"old process identity no longer live on this host");
            Path relative=Path.of(input.get("player_file").getAsString());require(!relative.isAbsolute(),"relative player file");
            Path player=checked(world.resolve(relative));require(player.startsWith(world),"player file inside original world");
            require(hash(player).equals(input.get("player_file_sha256").getAsString()),"raw saved player file hash");
            var raw=NbtIo.readCompressed(player,NbtAccounter.create(32L*1024*1024));
            ListTag inventory=raw.getList("Inventory",10).copy();
            require(!inventory.isEmpty(),"actual saved test inventory");
            var expected=input.getAsJsonObject("expected_owner");var frozen=warm.getAsJsonObject("cold_baseline");
            require(expected.get("energy").getAsLong()==frozen.get("energy").getAsLong()
                &&expected.get("scex_energy_fraction").getAsLong()==frozen.get("fraction").getAsLong()
                &&expected.get("DailyVolume").getAsInt()==frozen.get("daily_volume").getAsInt(),"disk owner agrees with paid warm observation");
            var seal=new LinkedHashMap<String,Object>();seal.put("input_sha256",hash(INPUT));seal.put("world_manifest_sha256",hash(manifest));
            seal.put("source_pid",sourcePid);seal.put("source_start",sourceStart.toString());seal.put("current_pid",current.pid());seal.put("current_start",current.info().startInstant().orElseThrow().toString());
            seal.put("saved_player_inventory",inventory.toString());seal.put("world_root",world.toString());
            prepared=new Prepared(input,world,inventory,seal);
            Files.writeString(OUTPUT,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("passed",false,"status","PREPARED_RAW_WORLD_BEFORE_OPEN","seal",seal)));
        } catch(Exception failure) {
            try {Files.writeString(OUTPUT,new GsonBuilder().create().toJson(Map.of("passed",false,"status","FAIL_COLD_PREPARATION","failure",failure.toString())));}catch(IOException suppressed){failure.addSuppressed(suppressed);}
            throw new IllegalStateException("R134 future cold preparation failed",failure);
        }
    }
    public FutureTradeColdProbe() {require(prepared!=null,"early install actually ran before world open");baseline=prepared;}
    /** Netty invokes Connection.channelActive through the real pipeline, populating channel attributes. */
    private static Connection embeddedConnection() {
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
        if (connection.channel() != channel || !channel.isActive())
            throw new IllegalStateException("R134 embedded connection did not become active");
        return connection;
    }
    private static void releaseConnection(ServerPlayer player) {
        if (player.connection.getConnection().channel() instanceof io.netty.channel.embedded.EmbeddedChannel channel)
            channel.finishAndReleaseAll();
    }
    private static final class ReadConnection extends ServerGamePacketListenerImpl {
        ReadConnection(ServerLevel world,ServerPlayer player){super(world.getServer(),embeddedConnection(),player,CommonListenerCookie.createInitial(player.getGameProfile(),false));}
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet,PacketSendListener listener) {}
    }
    public Map<String,Object> inspect(ServerLevel world,int tick)throws Exception {
        if(finished||tick<20)return null;
        try {
            check(world.getServer().isSameThread(),"natural main-thread readback");
            check(world.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(baseline.worldRoot()),"same raw verified world opened");
            var chunk=world.getChunkSource().getChunkNow(OWNER.getX()>>4,OWNER.getZ()>>4);check(chunk!=null,"saved forced chunk loaded naturally");
            check(chunk.getBlockEntities().get(OWNER) instanceof mio_icif_future_elc,"naturally instantiated real future owner");
            var owner=(mio_icif_future_elc)chunk.getBlockEntities().get(OWNER);
            check(owner.getEnergyStorageInternal().scexNetworkControlled(),"production policy owns cold ledger");
            CompoundTag saved=owner.saveWithFullMetadata(world.registryAccess());var expected=baseline.input().getAsJsonObject("expected_owner");
            for(String key:List.of("energy","scex_energy_fraction","Page","SelectedIndex","TradeQuantity","CurrentDay","DailyVolume"))
                check(saved.getLong(key)==expected.get(key).getAsLong(),"cold scalar preserved "+key);
            for(String key:List.of("id","Category"))check(saved.getString(key).equals(expected.get(key).getAsString()),"cold identity preserved "+key);
            var row=new LinkedHashMap<String,Object>();row.put("tick",tick);row.put("game_time",world.getGameTime());row.put("saved_owner",saved.toString());
            if(samples.isEmpty()) {
                var player=new ServerPlayer(world.getServer(),world,new GameProfile(UUID.fromString(baseline.input().get("player_uuid").getAsString()),"SI_R134_Future"),ClientInformation.createDefault());
                player.connection=new ReadConnection(world,player);
                var loaded=world.getServer().getPlayerList().load(player);check(loaded.isPresent(),"public normal PlayerList.load read actual saved player");
                var actual=player.getInventory().save(new ListTag());check(actual.equals(baseline.inventory()),"all actual stored inventory items/counts/components survived new JVM");
                row.put("loaded_inventory",actual.toString());row.put("player_loaded_via","PlayerList.load, not probe setItem");player.getTextFilter().leave();releaseConnection(player);
            }
            samples.add(row);
            if(tick<40)return null;
            finished=true;var result=report(true,"PASS_SCOPED_NEW_JVM_READBACK_COMPLETES_MATRIX_ROW_54");
            Files.writeString(OUTPUT,new GsonBuilder().setPrettyPrinting().create().toJson(result));return result;
        } catch(Exception|AssertionError failure) {
            finished=true;var result=report(false,"FAIL_COLD_READBACK");result.put("failure",failure.toString());Files.writeString(OUTPUT,new GsonBuilder().setPrettyPrinting().create().toJson(result));throw failure;
        }
    }
    private Map<String,Object> report(boolean passed,String status) {
        var result=new LinkedHashMap<String,Object>();result.put("passed",passed);result.put("status",status);result.put("assertions",assertions);
        result.put("matrix_row","normal_save_and_cold_readback");result.put("matrix_total_accepted",passed?54:53);result.put("seal",baseline.seal());result.put("samples",samples);
        result.put("trade_calls",0);result.put("probe_balance_or_inventory_setter_calls",0);result.put("player_inventory_populated_by_normal_load",true);result.put("scope","Real new-JVM world and public player load; full mod, connected client, multiplayer and performance acceptance unchanged");return result;
    }
    private static void verifyTree(Path root,Path manifest)throws Exception {
        var json=JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();require(json.get("schema").getAsInt()==1,"tree schema");
        var expected=new TreeSet<String>();
        for(var entry:json.getAsJsonArray("files")) {
            var row=entry.getAsJsonObject();String rel=row.get("path").getAsString();Path path=Path.of(rel);
            require(!path.isAbsolute()&&!rel.isEmpty()&&rel.indexOf('\\')<0&&!rel.equals("session.lock"),"canonical relative file");
            for(Path part:path)require(!part.toString().equals(".")&&!part.toString().equals(".."),"no dot segments");
            Path file=checked(root.resolve(path));require(file.startsWith(root)&&expected.add(rel),"unique bounded original file");
            require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)==row.get("bytes").getAsLong()&&hash(file).equals(row.get("sha256").getAsString()),"full hash for "+rel);
        }
        var actual=new TreeSet<String>();var pending=new ArrayDeque<Path>();pending.add(root);
        while(!pending.isEmpty())try(var children=Files.newDirectoryStream(checked(pending.removeFirst()))) {
            for(Path child:children){Path path=checked(child);var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(attributes.isDirectory())pending.addLast(path);else{require(attributes.isRegularFile(),"regular raw world files only");String rel=root.relativize(path).toString().replace('\\','/');if(!rel.equals("session.lock"))actual.add(rel);}}
        }
        require(actual.equals(expected)&&expected.contains("level.dat"),"entire original world hash set, excluding session.lock only");
    }
    private static Path checked(Path input)throws IOException {
        Path absolute=input.toAbsolutePath();for(Path part:absolute)if(part.toString().equals(".."))throw new IOException("Parent traversal: "+input);
        absolute=absolute.normalize();Path at=absolute.getRoot();ordinary(at);for(Path part:absolute){at=at.resolve(part);ordinary(at);}return absolute;
    }
    private static void ordinary(Path path)throws IOException {
        var attributes=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(attributes.isSymbolicLink()||attributes.isOther())throw new IOException("Link/special path: "+path);
        if(System.getProperty("os.name","").startsWith("Windows")){Object mask=Files.getAttribute(path,"dos:attributes",LinkOption.NOFOLLOW_LINKS);if(!(mask instanceof Number number)||(number.intValue()&0x400)!=0)throw new IOException("Reparse path: "+path);}
    }
    private static String hash(Path path)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(checked(path))){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}return HexFormat.of().formatHex(digest.digest());
    }
}
