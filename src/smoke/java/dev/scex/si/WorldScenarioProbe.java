// SPDX-License-Identifier: Apache-2.0
// Vanilla commands and public saved-state observations on real world ticks.
package dev.scex.si;

import com.google.gson.Gson;
import dev.scex.si.energy.IndependentSiEnergy;
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
    private final boolean observeWorldTime=Files.exists(Path.of("world-time-observation.json"));
    private final boolean observeEndpointSurface=Files.exists(Path.of("endpoint-surface.json"));
    private final TransformerLifecycleProbe transformerLifecycle;
    private final TransformerModeProbe transformerMode;
    private final SpecialCableInteractionProbe specialCableInteraction;
    private final InteropWorldProbe interopWorld;
    private final InteropRestartProbe interopRestart;
    private final ItemEquipmentWorldProbe itemEquipment;
    private final EquipmentSourceReplacementWorldProbe equipmentSourceReplacement;
    private final FlightBaselineWorldProbe flightBaseline;
    private final FlightReplacementWorldProbe flightReplacement;
    private final CropBaselineWorldProbe cropBaseline;
    private final CropReplacementWorldProbe cropReplacement;
    private final PumpProductWorldProbe pumpProduct;
    private final SolarProductWorldProbe solarProduct;
    private final MixedOutputWorldProbe mixedOutput;
    private final PatternStorageWorldProbe patternStorage;
    private final F04WorldProbe f04;
    private final PipeFailureWorldProbe pipeFailures;
    private final PipeRecoveryWorldProbe pipeRecovery;
    private final ReplicatorWorldProbe replicator;
    private final UuPricingWorldProbe uuPricing;
    private final ScannerWorldProbe scanner;
    private final ScannerDeniedWorldProbe scannerDenied;
    private final PatternMigrationWorldProbe patternMigration;
    private final UuCatalogWorldProbe uuCatalog;
    private final UuChargeWorldProbe uuCharge;
    private final HeldItemStateProbe heldItemStates;
    private final HeldScanWorldProbe heldScan;
    private final KineticStateProbe kineticStates;
    private final BlastStateProbe blastStates;
    private final MatterStateProbe matterStates;
    private final ReactorStateProbe reactorStates;
    private final NuclearItemWorldProbe nuclearItems;
    private final ChamberWorldProbe chambers;
    private final ChamberColdProbe chamberCold;
    private final ChunkTicketWorldProbe chunkTickets;
    private final OrdinaryConsumerWorldProbe ordinaryConsumers;
    private final RemainingConsumerWorldProbe remainingConsumers;
    private final FutureTradeWorldProbe futureTrade;
    private final FutureTradeColdProbe futureTradeCold;
    private final MachinePaymentWorldProbe machinePayment;
    private final LegacyElectricAdapterDonorProbe electricDonor;
    private final TeslaPaymentWorldProbe teslaPayment;
    private final ChamberLifecycleProbe chamberLifecycle;
    private final LegacyUuStateProbe legacyUuStates;
    private final ArmorApiDefaultsProbe armorApiDefaults;
    private final UuLegacyAdmissionWorldProbe uuLegacyAdmission;
    private final LegacyElectricAdapterPublicProbe legacyElectricAdapter;
    private final ArmorApiCandidateProbe armorApiCandidate;
    private final ReactorWorldProbe reactorWorld;
    private final ReactorAccidentWorldProbe reactorAccidentWorld;
    private final ReactorAccidentEffectWorldProbe reactorAccidentEffectWorld;
    private final FluidReactorWorldProbe fluidReactorWorld;
    private final MatterWorldProbe matterWorld;
    private final BlastWorldProbe blastWorld;
    private final KineticWorldProbe kineticWorld;
    private final UuProcessingWorldProbe uuProcessing;
    private final HeaterCalibrationProbe heaterCalibration;
    private final HeaterWorldProbe heaterWorld;
    private final StirlingWorldProbe stirlingWorld;
    private final DefaultPlacementProbe defaultPlacement;
    private final EnergyDirectionWorldProbe energyDirection;
    private final UuDefaultIdentityWorldProbe uuDefaultIdentities;
    private final EnergyOwnershipWorldProbe energyOwnership;
    private int phaseFrame=-1,phaseFrom=-1,phaseTo=-1;
    public WorldScenarioProbe(MinecraftServer server) throws Exception {
        this.server=server;
        kineticWorld=Files.exists(Path.of("kinetic-world.json")) ? new KineticWorldProbe() : null;
        kineticStates=Files.exists(Path.of("kinetic-states.json")) ? new KineticStateProbe() : null;
        blastStates=Files.exists(Path.of("blast-states.json")) ? new BlastStateProbe() : null;
        matterStates=Files.exists(Path.of("matter-states.json")) ? new MatterStateProbe() : null;
        reactorStates=Files.exists(Path.of("reactor-states.json")) ? new ReactorStateProbe() : null;
        nuclearItems=Files.exists(Path.of("nuclear-item-world.json")) ? new NuclearItemWorldProbe() : null;
        chambers=Files.exists(Path.of("chamber-world.json")) ? new ChamberWorldProbe() : null;
        chamberCold=Files.exists(Path.of("chamber-cold.json")) ? new ChamberColdProbe(server) : null;
        chunkTickets=Files.exists(Path.of("chunk-ticket-world.json")) ? new ChunkTicketWorldProbe() : null;
        machinePayment=Files.exists(Path.of("machine-payment-world-r134.json")) ? new MachinePaymentWorldProbe() : null;
        electricDonor=Files.exists(Path.of("legacy-electric-donor-candidate-r135.json")) ? new LegacyElectricAdapterDonorProbe() : null;
        futureTradeCold=Files.exists(Path.of("future-trade-cold-r134.json")) ? new FutureTradeColdProbe() : null;
        futureTrade=Files.exists(Path.of("future-trade-world-r134.json")) ? new FutureTradeWorldProbe() : null;
        remainingConsumers=Files.exists(Path.of("remaining-consumer-world.json")) ? new RemainingConsumerWorldProbe() : null;
        ordinaryConsumers=Files.exists(Path.of("ordinary-consumer-world.json")) ? new OrdinaryConsumerWorldProbe() : null;
        teslaPayment=Files.exists(Path.of("tesla-payment-world.json")) ? new TeslaPaymentWorldProbe() : null;
        chamberLifecycle=Files.exists(Path.of("chamber-lifecycle.json")) ? new ChamberLifecycleProbe() : null;
        legacyUuStates=Files.exists(Path.of("legacy-uu-states.json")) ? new LegacyUuStateProbe() : null;
        uuLegacyAdmission=Files.exists(Path.of("uu-legacy-admission-world.json")) ? new UuLegacyAdmissionWorldProbe() : null;
        legacyElectricAdapter=Files.exists(Path.of("legacy-electric-adapter-candidate-r135.json")) ? new LegacyElectricAdapterPublicProbe() : null;
        armorApiCandidate=Files.exists(Path.of("armor-api-candidate.json")) ? new ArmorApiCandidateProbe() : null;
        armorApiDefaults=Files.exists(Path.of("armor-api-defaults.json")) ? new ArmorApiDefaultsProbe() : null;
        reactorWorld=Files.exists(Path.of("reactor-world.json")) ? new ReactorWorldProbe() : null;
        reactorAccidentWorld=Files.exists(Path.of("reactor-accident-world-r137.json")) ? new ReactorAccidentWorldProbe(server) : null;
        reactorAccidentEffectWorld=Files.exists(Path.of("reactor-accident-effect-r140.json")) ? new ReactorAccidentEffectWorldProbe(server) : null;
        fluidReactorWorld=Files.exists(Path.of("fluid-reactor-world.json")) ? new FluidReactorWorldProbe(server) : null;
        matterWorld=Files.exists(Path.of("matter-world.json")) ? new MatterWorldProbe() : null;
        blastWorld=Files.exists(Path.of("blast-world.json")) ? new BlastWorldProbe() : null;
        heldScan=Files.exists(Path.of("held-scan-world.json")) ? new HeldScanWorldProbe() : null;
        heldItemStates=Files.exists(Path.of("held-item-states.json")) ? new HeldItemStateProbe() : null;
        uuCharge=Files.exists(Path.of("uu-charge-world.json")) ? new UuChargeWorldProbe() : null;
        stirlingWorld=Files.exists(Path.of("stirling-world.json")) ? new StirlingWorldProbe() : null;
        heaterWorld=Files.exists(Path.of("heater-world.json")) ? new HeaterWorldProbe() : null;
        heaterCalibration=Files.exists(Path.of("heater-calibration.json")) ? new HeaterCalibrationProbe() : null;
        defaultPlacement=Files.exists(Path.of("default-placement.json")) ? new DefaultPlacementProbe() : null;
        uuProcessing=Files.exists(Path.of("uu-processing-world.json")) ? new UuProcessingWorldProbe() : null;
        scannerDenied=Files.exists(Path.of("scanner-denied-world.json")) ? new ScannerDeniedWorldProbe() : null;
        uuDefaultIdentities=Files.exists(Path.of("uu-default-identities-r101.json")) ? new UuDefaultIdentityWorldProbe() : null;
        energyDirection=Files.exists(Path.of("energy-direction-world.json")) ? new EnergyDirectionWorldProbe() : null;
        energyOwnership=Files.exists(Path.of("energy-ownership-world.json")) ? new EnergyOwnershipWorldProbe() : null;
        uuCatalog=Files.exists(Path.of("uu-catalog-world.json")) ? new UuCatalogWorldProbe() : null;
        patternMigration=Files.exists(Path.of("pattern-migration-world.json")) ? new PatternMigrationWorldProbe() : null;
        scanner=Files.exists(Path.of("scanner-world.json")) ? new ScannerWorldProbe() : null;
        uuPricing=Files.exists(Path.of("uu-pricing-world.json")) ? new UuPricingWorldProbe() : null;
        replicator=Files.exists(Path.of("replicator-world.json")) ? new ReplicatorWorldProbe() : null;
        pipeRecovery=Files.exists(Path.of("pipe-recovery-world.json")) ? new PipeRecoveryWorldProbe() : null;
        pipeFailures=Files.exists(Path.of("pipe-failure-world.json")) ? new PipeFailureWorldProbe() : null;
        patternStorage=Files.exists(Path.of("pattern-storage-world.json")) ? new PatternStorageWorldProbe() : null;
        f04=Files.exists(Path.of("f04-world.json")) ? new F04WorldProbe() : null;
        mixedOutput=Files.exists(Path.of("mixed-output-world.json")) ? new MixedOutputWorldProbe(server) : null;
        itemEquipment=Files.exists(Path.of("item-equipment-world.json")) ? new ItemEquipmentWorldProbe() : null;
        equipmentSourceReplacement=Files.exists(Path.of("equipment-source-r145.json")) ? new EquipmentSourceReplacementWorldProbe() : null;
        flightBaseline=Files.exists(Path.of("flight-baseline-r147.json")) ? new FlightBaselineWorldProbe() : null;
        flightReplacement=Files.exists(Path.of("flight-replacement-r148.json")) ? new FlightReplacementWorldProbe() : null;
        cropBaseline=Files.exists(Path.of("crop-baseline-r151.json")) ? new CropBaselineWorldProbe() : null;
        cropReplacement=Files.exists(Path.of("crop-replacement-r156.json")) ? new CropReplacementWorldProbe() : null;
        pumpProduct=Files.exists(Path.of("pump-product-r158.json")) ? new PumpProductWorldProbe() : null;
        solarProduct=Files.exists(Path.of("extended-solar-product-r159.json")) ? new SolarProductWorldProbe() : null;
        interopRestart=Files.exists(Path.of("interop-restart.json")) ? new InteropRestartProbe() : null;
        interopWorld=interopRestart==null && Files.exists(Path.of("interop-world.json")) ? new InteropWorldProbe() : null;
        transformerLifecycle=Files.exists(Path.of("transformer-lifecycle.json")) ? new TransformerLifecycleProbe() : null;
        transformerMode=Files.exists(Path.of("transformer-mode-probe.json")) ? new TransformerModeProbe() : null;
        specialCableInteraction=Files.exists(Path.of("special-cable-interaction.json")) ? new SpecialCableInteractionProbe() : null;
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
        if(Boolean.getBoolean("scex.independent.energy")) {
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(Path.of("runtime-overlay.json"))).getAsJsonObject();
            var hashes=new ArrayList<Map<String,String>>();
            for(var item:manifest.getAsJsonArray("classes")) {
                var entry=item.getAsJsonObject();String name=entry.get("path").getAsString();
                if(name.contains("/energy/grid/") || name.contains("/api/energy/") || name.contains("/energy/leg/"))
                    throw new IllegalArgumentException("Unresolved implementation in runtime patch manifest");
                try(var data=IndependentSiEnergy.class.getClassLoader().getResourceAsStream(name)) {
                    if(data==null) throw new IllegalStateException("Missing runtime patch class: "+name);
                    String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data.readAllBytes()));
                    if(!actual.equals(entry.get("sha256").getAsString())) throw new IllegalStateException("Runtime patch class hash mismatch: "+name
                        +" expected="+entry.get("sha256").getAsString()+" actual="+actual
                        +" resource="+IndependentSiEnergy.class.getClassLoader().getResource(name));
                    hashes.add(Map.of("path",name,"sha256",actual));
                }
            }
            record("runtime-patch-hashes",Map.of("passed",true,"classes",hashes));
        }
        if(Files.exists(Path.of("independent-core.json"))) {
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(Path.of("independent-core.json"))).getAsJsonObject();
            var entries=manifest.getAsJsonArray("classes");
            if(entries.isEmpty() || entries.size()>256) throw new IllegalArgumentException("Invalid independent core class count");
            for(var item:entries) {
                var entry=item.getAsJsonObject();String name=entry.get("path").getAsString();
                if(!name.startsWith("dev/scex/energy/") || !name.endsWith(".class") || name.contains(".."))
                    throw new IllegalArgumentException("Invalid independent class path");
                try(var input=dev.scex.energy.DomainDistributor.class.getClassLoader().getResourceAsStream(name)) {
                    if(input==null) throw new IllegalStateException("Missing independent class: "+name);
                    String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                    if(!actual.equals(entry.get("sha256").getAsString())) throw new IllegalStateException("Independent core mismatch: "+name);
                }
            }
            record("independent-core-hashes",Map.of("passed",true,"classes",entries.size(),"artifact_sha256",manifest.get("jar_sha256").getAsString()));
        }
        if(Files.exists(Path.of("independent-platform.json"))) {
            var manifest=com.google.gson.JsonParser.parseString(Files.readString(Path.of("independent-platform.json"))).getAsJsonObject();
            var entries=manifest.getAsJsonArray("classes");
            if(entries.isEmpty() || entries.size()>256)throw new IllegalArgumentException("Invalid platform class count");
            for(var item:entries) {
                var entry=item.getAsJsonObject();String name=entry.get("path").getAsString();
                if(!name.startsWith("dev/scex/energy/minecraft/") || !name.endsWith(".class") || name.contains(".."))throw new IllegalArgumentException("Invalid platform class path");
                try(var input=dev.scex.energy.minecraft.IndependentSpecialCableBlockEntity.class.getClassLoader().getResourceAsStream(name)) {
                    if(input==null)throw new IllegalStateException("Missing independent platform class: "+name);
                    String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                    if(!actual.equals(entry.get("sha256").getAsString()))throw new IllegalStateException("Independent platform mismatch: "+name);
                }
            }
            record("independent-platform-hashes",Map.of("passed",true,"classes",entries.size(),"artifact_sha256",manifest.get("jar_sha256").getAsString()));
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(this::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(this::onExplosionStart);
        NeoForge.EVENT_BUS.addListener(this::onExplosionDetonate);
        if(Boolean.getBoolean("scex.independent.energy")) {
            var observed=IndependentSiEnergy.current(server);
            if(observed==null) throw new IllegalStateException("Independent engine not attached before scenario");
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                (net.neoforged.neoforge.event.server.ServerStoppedEvent event)-> {
                    if(event.getServer()!=server) return;
                    try {
                        var metrics=observed.metrics();
                        Files.writeString(Path.of("independent-stop.json"),gson.toJson(Map.of("passed",
                            metrics.closed() && metrics.endpoints()==0 && metrics.dimensions()==0
                                && IndependentSiEnergy.current(server)==null,"metrics",metrics)));
                    } catch(Exception error){error.printStackTrace();}
                });
        }
        if(Files.exists(Path.of("phase-observation.json"))) {
            var window=com.google.gson.JsonParser.parseString(Files.readString(Path.of("phase-observation.json"))).getAsJsonObject();
            phaseFrom=window.get("from").getAsInt();phaseTo=window.get("to").getAsInt();
            if(phaseFrom<0 || phaseTo<phaseFrom || phaseTo-phaseFrom>80) throw new IllegalArgumentException("Bounded phase window required");
            var first=net.neoforged.bus.api.EventPriority.HIGHEST;var last=net.neoforged.bus.api.EventPriority.LOWEST;
            NeoForge.EVENT_BUS.addListener(first,(ServerTickEvent.Pre event)->{if(event.getServer()==server && !finished){phaseFrame++;phaseSample("server-START-first");}});
            NeoForge.EVENT_BUS.addListener(last,(ServerTickEvent.Pre event)->{if(event.getServer()==server)phaseSample("server-START-last");});
            NeoForge.EVENT_BUS.addListener(first,(ServerTickEvent.Post event)->{if(event.getServer()==server)phaseSample("server-END-first");});
            NeoForge.EVENT_BUS.addListener(last,(ServerTickEvent.Post event)->{if(event.getServer()==server)phaseSample("server-END-last");});
            NeoForge.EVENT_BUS.addListener(first,(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event)->{if(event.getLevel()==server.overworld())phaseSample("world-START-first");});
            NeoForge.EVENT_BUS.addListener(last,(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre event)->{if(event.getLevel()==server.overworld())phaseSample("world-START-last");});
            NeoForge.EVENT_BUS.addListener(first,(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event)->{if(event.getLevel()==server.overworld())phaseSample("world-END-first");});
            NeoForge.EVENT_BUS.addListener(last,(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event)->{if(event.getLevel()==server.overworld())phaseSample("world-END-last");});
        }
    }
    private void phaseSample(String phase) {
        if(finished || phaseFrame<phaseFrom || phaseFrame>phaseTo) return;
        try {
            var level=server.overworld();
            for(var at:positions) {
                var chunk=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
                if(chunk==null) continue;
                var tile=chunk.getBlockEntity(at,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);
                if(tile==null) continue;
                record("phase-tile-save",Map.of("frame",phaseFrame,"phase",phase,"x",at.getX(),"y",at.getY(),"z",at.getZ(),
                    "nbt",tile.saveWithFullMetadata(server.registryAccess()).toString()));
            }
        }catch(Throwable error){error.printStackTrace();finish(false);}
    }
    private void onExplosionStart(net.neoforged.neoforge.event.level.ExplosionEvent.Start event) {
        recordExplosion("explosion-start",event);
    }
    private void onExplosionDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        recordExplosion("explosion-detonate",event);
    }
    private void recordExplosion(String kind,net.neoforged.neoforge.event.level.ExplosionEvent event) {
        if(finished || event.getLevel()!=server.overworld()) return;
        var explosion=event.getExplosion();
        var center=explosion.center();
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("x",center.x);row.put("y",center.y);row.put("z",center.z);
        row.put("radius",explosion.radius());
        row.put("interaction",explosion.getBlockInteraction().name());
        // Identify only our maintained wrapper; do not inspect other implementations.
        row.put("via_custom_storage",StackWalker.getInstance().walk(frames->frames.anyMatch(frame->
            frame.getClassName().equals("com.singularity_iteration.mio_icif.energy.CustomEUEnergyStorage")
                && frame.getMethodName().equals("triggerOverloadExplosion"))));
        row.put("affected_sample_positions",explosion.getToBlow().stream().filter(positions::contains)
            .map(p->List.of(p.getX(),p.getY(),p.getZ())).toList());
        try{record(kind,row);}
        catch(Exception error){error.printStackTrace();finish(false);}
    }
    private void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        recordChunkEvent("chunk-load-event",event);
    }
    private void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        recordChunkEvent("chunk-unload-event",event);
    }
    private void recordChunkEvent(String kind,net.neoforged.neoforge.event.level.ChunkEvent event) {
        if(finished || event.getLevel()!=server.overworld()) return;
        var cp=event.getChunk().getPos();
        if(positions.stream().noneMatch(p->(p.getX()>>4)==cp.x && (p.getZ()>>4)==cp.z)) return;
        try{record(kind,Map.of("chunk_x",cp.x,"chunk_z",cp.z));}
        catch(Exception error){error.printStackTrace();finish(false);}
    }
    private void record(String kind,Object value) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();row.put("tick",tick);row.put("kind",kind);row.put("value",value);
        output.write(gson.toJson(row));output.newLine();observations++;
    }
    private void onTick(ServerTickEvent.Post event) {
        if(finished || event.getServer()!=server) return;
        try {
            var world=server.overworld();
            if(kineticWorld!=null){var result=kineticWorld.inspect(world,tick);if(result!=null)record("kinetic-world",result);}
            if(kineticStates!=null){var result=kineticStates.inspect(world,tick);if(result!=null)record("kinetic-states",result);}
            if(blastStates!=null){var result=blastStates.inspect(world,tick);if(result!=null)record("blast-states",result);}
            if(matterStates!=null){var result=matterStates.inspect(world,tick);if(result!=null)record("matter-states",result);}
            if(reactorStates!=null){var result=reactorStates.inspect(world,tick);if(result!=null)record("reactor-states",result);}
            if(nuclearItems!=null){var result=nuclearItems.inspect(world,tick);if(result!=null)record("nuclear-items",result);}
            if(chambers!=null){var result=chambers.inspect(world,tick);if(result!=null)record("chamber-world",result);}
            if(chamberCold!=null){var result=chamberCold.inspect(world,tick);if(result!=null)record("chamber-cold",result);}
            if(chunkTickets!=null){var result=chunkTickets.inspect(world,tick);if(result!=null)record("chunk-ticket-world",result);}
            if(machinePayment!=null){var result=machinePayment.inspect(world,tick);if(result!=null)record("machine-payment-world-r134",result);}
            if(electricDonor!=null){var result=electricDonor.inspect(world,tick);if(result!=null)record("legacy-electric-donor-candidate-r135",result);}
            if(futureTradeCold!=null){var result=futureTradeCold.inspect(world,tick);if(result!=null)record("future-trade-cold-r134",result);}
            if(futureTrade!=null){var result=futureTrade.inspect(world,tick);if(result!=null)record("future-trade-world-r134",result);}
            if(remainingConsumers!=null){var result=remainingConsumers.inspect(world,tick);if(result!=null)record("remaining-consumer-world",result);}
            if(ordinaryConsumers!=null){var result=ordinaryConsumers.inspect(world,tick);if(result!=null)record("ordinary-consumer-world",result);}
            if(teslaPayment!=null){var result=teslaPayment.inspect(world,tick);if(result!=null)record("tesla-payment-world",result);}
            if(chamberLifecycle!=null){var result=chamberLifecycle.inspect(world,tick);if(result!=null)record("chamber-lifecycle",result);}
            if(legacyUuStates!=null){var result=legacyUuStates.inspect(world,tick);if(result!=null)record("legacy-uu-states",result);}
            if(uuLegacyAdmission!=null){var result=uuLegacyAdmission.inspect(world,tick);if(result!=null)record("uu-legacy-admission-world",result);}
            if(legacyElectricAdapter!=null){var result=legacyElectricAdapter.inspect(world,tick);if(result!=null)record("legacy-electric-adapter-candidate-r135",result);}
            if(armorApiCandidate!=null){var result=armorApiCandidate.inspect(world,tick);if(result!=null)record("armor-api-candidate",result);}
            if(armorApiDefaults!=null){var result=armorApiDefaults.inspect(world,tick);if(result!=null)record("armor-api-defaults",result);}
            if(reactorWorld!=null){var result=reactorWorld.inspect(world,tick);if(result!=null)record("reactor-world",result);}
            if(reactorAccidentWorld!=null){var result=reactorAccidentWorld.inspect(world,tick);if(result!=null)record("reactor-accident-world-r137",result);}
            if(reactorAccidentEffectWorld!=null){var result=reactorAccidentEffectWorld.inspect(world,tick);if(result!=null)record("reactor-accident-effect-r140",result);}
            if(fluidReactorWorld!=null){var result=fluidReactorWorld.inspect(world,tick);if(result!=null)record("fluid-reactor-world",result);}
            if(matterWorld!=null){var result=matterWorld.inspect(world,tick);if(result!=null)record("matter-world",result);}
            if(blastWorld!=null){var result=blastWorld.inspect(world,tick);if(result!=null)record("blast-world",result);}
            if(heldScan!=null){var result=heldScan.inspect(world,tick);if(result!=null)record("held-scan-world",result);}
            if(heldItemStates!=null){var result=heldItemStates.inspect(world,tick);if(result!=null)record("held-item-states",result);}
            if(uuCharge!=null){var result=uuCharge.inspect(world,tick);if(result!=null)record("uu-charge-world",result);}
            if(energyDirection!=null) {
                var result=energyDirection.inspect(world,tick);
                if(result!=null)record("energyDirection",result);
            }
            if(uuDefaultIdentities!=null) {
                var result=uuDefaultIdentities.inspect(world,tick);
                if(result!=null)record("uuDefaultIdentities",result);
            }
            if(energyOwnership!=null) {
                var result=energyOwnership.inspect(world,tick);
                if(result!=null)record("energy-ownership-world",result);
            }
            if(tick==5 && Files.exists(Path.of("legacy-uu-absence.json"))) record("legacy-uu-absence",LegacyUuAbsenceProbe.verify());
            if(heaterWorld!=null){var result=heaterWorld.inspect(world,tick);if(result!=null)record("heater-world",result);}
            if(stirlingWorld!=null){var result=stirlingWorld.inspect(world,tick);if(result!=null)record("stirling-world",result);}
            if(heaterCalibration!=null){var result=heaterCalibration.inspect(world,tick);if(result!=null)record("heater-calibration",result);}
            if(defaultPlacement!=null){var result=defaultPlacement.inspect(world,tick);if(result!=null)record("default-placement",result);}
            if(uuProcessing!=null) {
                var result=uuProcessing.inspect(world,tick);
                if(result!=null)record("uu-processing-world",result);
            }
            if(uuCatalog!=null) {
                var result=uuCatalog.inspect(world,tick);
                if(result!=null)record("uu-catalog-world",result);
            }
            if(patternMigration!=null) {
                var result=patternMigration.inspect(world,tick);
                if(result!=null)record("pattern-migration-world",result);
            }
            if(scannerDenied!=null) {
                var result=scannerDenied.inspect(world,tick);
                if(result!=null)record("scanner-denied-world",result);
            }
            if(scanner!=null) {
                var result=scanner.inspect(world,tick);
                if(result!=null)record("scanner-world",result);
            }
            if(uuPricing!=null) {
                var result=uuPricing.inspect(world,tick);
                if(result!=null)record("uu-pricing-world",result);
            }
            if(replicator!=null) {
                var result=replicator.inspect(world,tick);
                if(result!=null)record("replicator-world",result);
            }
            if(pipeRecovery!=null) {
                var result=pipeRecovery.inspect(world,tick);
                if(result!=null)record("pipe-recovery-world",result);
            }
            if(pipeFailures!=null) {
                var result=pipeFailures.inspect(world,tick);
                if(result!=null)record("pipe-failure-world",result);
            }
            if(patternStorage!=null) {
                var result=patternStorage.inspect(world,tick);
                if(result!=null)record("pattern-storage-world",result);
            }
            if(f04!=null) {
                var result=f04.inspect(world,tick);
                if(result!=null)record("f04-world",result);
            }
            if(mixedOutput!=null) {
                var result=mixedOutput.inspect(world,tick);
                if(result!=null)record("mixed-output-world",result);
            }
            if(interopRestart!=null) {
                var result=interopRestart.inspect(world,tick);
                if(result!=null)record("interop-restart",result);
            }
            if(interopWorld!=null) {
                var result=interopWorld.inspect(world,tick);
                if(result!=null)record("interop-world",result);
            }
            if(itemEquipment!=null) {
                var result=itemEquipment.inspect(world,tick);
                if(result!=null)record("item-equipment-world",result);
            }
            if(equipmentSourceReplacement!=null) {
                var result=equipmentSourceReplacement.inspect(world,tick);
                if(result!=null)record("equipment-source-r145",result);
            }
            if(flightBaseline!=null) {
                var result=flightBaseline.inspect(world,tick);
                if(result!=null)record("flight-baseline-r147",result);
            }
            if(flightReplacement!=null) {
                var result=flightReplacement.inspect(world,tick);
                if(result!=null)record("flight-replacement-r148",result);
            }
            if(cropBaseline!=null) {
                var result=cropBaseline.inspect(world,tick);
                if(result!=null)record("crop-baseline-r151",result);
            }
            if(cropReplacement!=null) {
                var result=cropReplacement.inspect(world,tick);
                if(result!=null)record("crop-replacement-r156",result);
            }
            if(pumpProduct!=null) {
                var result=pumpProduct.inspect(world,tick);
                if(result!=null)record("pump-product-r158",result);
            }
            if(solarProduct!=null) {
                var result=solarProduct.inspect(world,tick);
                if(result!=null)record("solar-product-r159",result);
            }
            if(specialCableInteraction!=null) {
                var result=specialCableInteraction.inspect(world,tick);
                if(result!=null)record("special-cable-interaction",result);
            }
            if(transformerMode!=null) {
                var result=transformerMode.inspect(world,tick);
                if(result!=null) record("transformer-mode-checks",result);
            }
            if(transformerLifecycle!=null) {
                var result=transformerLifecycle.inspect(world,tick,!commands.isEmpty());
                if(result!=null) record("transformer-lifecycle",result);
            }
            if(observeWorldTime) record("world-game-time",world.getGameTime());
            if(Boolean.getBoolean("scex.independent.energy")) {
                var engine=IndependentSiEnergy.current(server);
                if(engine==null || !engine.metrics().failure().isEmpty()) throw new IllegalStateException("Independent engine missing or failed");
                record("independent-energy",engine.metrics());
                if (Files.exists(Path.of("startup-diagnostics.json")) && tick<=80)
                    record("startup-diagnostics",engine.startupDiagnostics(world));
                if(Files.exists(Path.of("transformer-factory.json"))) record("transformer-factory",TransformerFactoryProbe.metrics());
                if(tick==18 && Boolean.getBoolean("scex.independent.commitTests"))
                    record("commit-boundaries",NetworkCommitProbe.run(world));
            }
            for(BlockPos at:positions) {
                Map<String,Object> row=new LinkedHashMap<>();row.put("x",at.getX());row.put("y",at.getY());row.put("z",at.getZ());
                row.put("block_ticking",world.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(at)));
                var chunk=world.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
                if(chunk==null){record("chunk-unloaded",row);continue;}
                // World-level reads can renew a temporary chunk ticket. Observe
                // the already-loaded chunk directly so this probe permits unload.
                row.put("state",chunk.getBlockState(at).toString());record("block-state",row);
                var tile=chunk.getBlockEntity(at,net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);
                if(tile!=null) {
                    row=new LinkedHashMap<>(row);row.remove("state");
                    row.put("nbt",tile.saveWithFullMetadata(server.registryAccess()).toString());record("tile-save",row);
                    if(observeEndpointSurface && (tick==20 || tick==40 || tick==70)) {
                        var surface=new LinkedHashMap<String,Object>();
                        surface.put("x",at.getX());surface.put("y",at.getY());surface.put("z",at.getZ());
                        surface.put("class",tile.getClass().getName());
                        surface.put("superclass",tile.getClass().getSuperclass().getName());
                        surface.put("independent_controlled",IndependentSiEnergy.controls(tile.getBlockState()));
                        boolean energyBase=tile instanceof com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
                        surface.put("energy_base",energyBase);
                        if(energyBase) {
                            var energy=(com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block)tile;
                            var storage=energy.getEnergyStorageInternal();
                            var quote=storage.scexNetworkQuote();
                            surface.put("amount",quote.amount());surface.put("output_enabled",quote.outputEnabled());
                            surface.put("effective_capacity",energy.getEffectiveCapacity());
                            surface.put("storage_class",storage.getClass().getName());
                        }
                        record("endpoint-surface",surface);
                    }
                }
            }
            for(String command:commands.getOrDefault(tick,List.of())) {
                var result=new int[]{Integer.MIN_VALUE};
                if(command.startsWith("@entities ")) {
                    String[] parts=command.split(" ");
                    if(parts.length!=5)throw new IllegalArgumentException("entities x y z radius");
                    var center=new BlockPos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));
                    double radius=Double.parseDouble(parts[4]);
                    if(!positions.contains(center)||!Double.isFinite(radius)||radius<=0||radius>16
                        ||world.getChunkSource().getChunkNow(center.getX()>>4,center.getZ()>>4)==null)
                        throw new IllegalArgumentException("Entity observation outside declared loaded fixture");
                    var point=net.minecraft.world.phys.Vec3.atCenterOf(center);
                    var box=new net.minecraft.world.phys.AABB(point.x-radius,point.y-radius,point.z-radius,point.x+radius,point.y+radius,point.z+radius);
                    var found=world.getEntities((net.minecraft.world.entity.Entity)null,box);
                    if(found.size()>256)throw new IllegalStateException("Entity observation bound exceeded");
                    var snapshots=new ArrayList<Map<String,Object>>();
                    for(var entity:found)if(entity.distanceToSqr(point)<=radius*radius) {
                        var tag=new net.minecraft.nbt.CompoundTag();boolean saved=entity.save(tag);
                        snapshots.add(Map.of("x",entity.getX(),"y",entity.getY(),"z",entity.getZ(),"saved",saved,"nbt",saved?tag.toString():""));
                    }
                    record("entity-snapshot",Map.of("x",center.getX(),"y",center.getY(),"z",center.getZ(),"radius",radius,"entities",snapshots));
                    result[0]=1;
                } else if(command.startsWith("@explode ")) {
                    String[] parts=command.split(" ");
                    if(parts.length!=5) throw new IllegalArgumentException("Invalid isolated blast control");
                    var center=new BlockPos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));
                    float radius=Float.parseFloat(parts[4]);
                    if(!positions.contains(center)||!Float.isFinite(radius)||radius<=0||radius>4
                        ||server.overworld().getChunkSource().getChunkNow(center.getX()>>4,center.getZ()>>4)==null)
                        throw new IllegalArgumentException("Blast control outside declared loaded fixture");
                    server.overworld().removeBlock(center,false);
                    server.overworld().explode(null,center.getX()+0.5,center.getY()+0.5,center.getZ()+0.5,radius,
                        net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
                    result[0]=1;
                } else {
                    var source=server.createCommandSourceStack().withSuppressedOutput().withCallback((success,value)->result[0]=success?value:-1);
                    server.getCommands().performPrefixedCommand(source,command);
                }
                executed++;
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
