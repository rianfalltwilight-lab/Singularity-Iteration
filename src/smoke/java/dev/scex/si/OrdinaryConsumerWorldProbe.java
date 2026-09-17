// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.google.gson.Gson;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Block;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_Energy_Container;
import com.singularity_iteration.mio_icif.Blocks.entity.mio_icif_producer;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.MachineItemHandler;
import com.singularity_iteration.mio_icif.Blocks.entity.slot.SlotType;
import com.singularity_iteration.mio_icif.api.item.IBatteryItem;
import dev.scex.energy.EnergyAmount;
import dev.scex.energy.IndependentEnergyMode;
import dev.scex.si.energy.FeLedger;
import dev.scex.si.energy.IndependentSiEnergy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Actual registered consumers and ordinary public energy/inventory entrypoints.
 * Mount from ordinary-consumer-world.json; run280ticks and forceload20000 96 21151 111 (72chunks).
 * The smoke mod must also register ItemEquipmentWorldProbe.registerCapabilities for its existing FE test item.
 * Native measurements bracket LevelTick.Post LOW; machine work before that interval is not counted as network loss.
 */
public final class OrdinaryConsumerWorldProbe {
    private static final List<String> IDS = List.of(
        "producer/block_canner_elc", "producer/block_neutron_polymerizer",
        "producer/block_redstone_reactor_coolant_injector", "producer/block_lapis_reactor_coolant_injector",
        "producer/block_terra_elc", "producer/block_matron_elc", "producer/block_harvest_elc",
        "producer/block_teleporter_elc", "producer/block_sorter_elc",
        "producer/block_fluid_regulator_elc", "producer/block_batch_crafter");
    private static final List<String> ZERO_EU = List.of(
        "producer/block_fluid_distributor_elc", "producer/block_weighted_fluid_distributor_elc");
    private static final class Circuit {
        final String id;
        final Direction input;
        final BlockPos target, source;
        final mio_icif_producer machine;
        final mio_icif_Energy_Container box;
        int phase, fullFrames;
        long capturedFrame = Long.MIN_VALUE;
        EnergyAmount before;
        Circuit(String id, Direction input, BlockPos target, mio_icif_producer machine,
                mio_icif_Energy_Container box) {
            this.id = id; this.input = input; this.target = target;
            this.source = target.relative(input, 2); this.machine = machine; this.box = box;
        }
    }
    private final List<Circuit> circuits = new ArrayList<>();
    private final List<Map<String, Object>> nativeRows = new ArrayList<>(), mixedRows = new ArrayList<>(),
        exclusionRows = new ArrayList<>(), lifecycleRows = new ArrayList<>();
    private ServerLevel level;
    private int assertions, currentTick;
    private Throwable failure;
    private boolean registered, finished;

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError("R131 ordinary consumer: " + label);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.parse("mio_icif:" + path); }
    private BlockEntity loaded(BlockPos at) {
        var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        check(chunk != null && level.shouldTickBlocksAt(ChunkPos.asLong(at)), "fixture is already ticking " + at);
        return chunk.getBlockEntity(at);
    }
    private void place(BlockPos at, String path, Direction facing) {
        var block = BuiltInRegistries.BLOCK.get(id(path));
        check(block != Blocks.AIR, "registered block " + path);
        var state = block.defaultBlockState();
        var property = block.getStateDefinition().getProperty("facing");
        if (property instanceof DirectionProperty direction) {
            check(direction.getPossibleValues().contains(facing), "supported facing " + path + " " + facing);
            state = state.setValue(direction, facing);
        }
        check(level.setBlockAndUpdate(at, state), "new fixture placement " + at);
    }
    private EnergyAmount balance(mio_icif_Energy_Block machine) {
        return machine.getEnergyStorageInternal().scexExactAmount();
    }
    private IEnergyStorage port(mio_icif_Energy_Block machine, Direction side) {
        var capability = level.getCapability(Capabilities.EnergyStorage.BLOCK, machine.getBlockPos(), side);
        check(capability != null, "actual registered FE capability " + machine.getBlockPos() + " " + side);
        return capability;
    }
    private void closedLegacy(mio_icif_Energy_Block machine) {
        var storage = machine.getEnergyStorageInternal();
        var before = storage.scexExactAmount();
        check(storage.scexNetworkControlled() && IndependentSiEnergy.controls(machine.getBlockState()), "single engine ownership");
        check(machine.getDemandedEnergy() == 0 && machine.getOfferedEnergy() == 0, "legacy demand and offer both zero");
        for (var side : Direction.values()) {
            check(!machine.acceptsEnergyFrom(null, side) && !machine.emitsEnergyTo(null, side), "legacy faces closed");
            check(machine.injectEnergy(side, 17.5, 32) == 17.5, "legacy injection returned in full");
        }
        machine.drawEnergy(17.5);
        check(storage == machine.getEnergyStorageInternal() && before.equals(balance(machine)), "legacy calls cannot change existing ledger");
    }
    private void setup(ServerLevel world) {
        level = world;
        check(IndependentEnergyMode.enabled() && IndependentEnergyMode.feature("processingMachines"), "fixed independent processing flags");
        check(IndependentSiEnergy.current(world.getServer()) != null, "normal independent engine is installed");
        int index = 0;
        for (String path : IDS) for (var side : Direction.values()) {
            var at = new BlockPos(20004 + 16 * index++, 80, 100);
            // All blocks stay inside the declared forced rectangle; these are empty test-world cells.
            check(world.getBlockState(at).isAir() && world.getBlockState(at.relative(side)).isAir()
                && world.getBlockState(at.relative(side, 2)).isAir(), "empty isolated circuit cells");
            place(at, path, Direction.NORTH);
            place(at.relative(side), "wiring/cable/block_glass_cable", Direction.NORTH);
            place(at.relative(side, 2), "wiring/block_bat_box", side.getOpposite());
            check(loaded(at) instanceof mio_icif_producer, "actual ordinary producer " + path);
            check(loaded(at.relative(side, 2)) instanceof mio_icif_Energy_Container, "actual source factory");
            var machine = (mio_icif_producer) loaded(at);
            var box = (mio_icif_Energy_Container) loaded(at.relative(side, 2));
            check(BuiltInRegistries.BLOCK.getKey(machine.getBlockState().getBlock()).equals(id(path)), "exact consumer ID");
            check(box.canProvidePowerFromSide(side.getOpposite()), "source output faces one glass link");
            check(machine.getEnergyStorageInternal().getMaxReceive() > 0
                && machine.getEnergyStorageInternal().getMaxExtract() == 0, "consumer input without export");
            check(balance(machine).isZero() && balance(box).isZero(), "fresh placed ledger has no manufactured energy");
            closedLegacy(machine);
            circuits.add(new Circuit(path, side, at, machine, box));
        }
        check(circuits.size() == 66, "eleven registrations times six native input faces");
        check(!IndependentSiEnergy.controls(Blocks.STONE.defaultBlockState()), "plain stone excluded");
        for (int i = 0; i < ZERO_EU.size(); i++) {
            String path = ZERO_EU.get(i); var at = new BlockPos(21124 + i * 16, 80, 100);
            place(at, path, Direction.NORTH);
            check(loaded(at) instanceof mio_icif_producer, "actual zero-EU logistics factory");
            var machine = (mio_icif_producer) loaded(at);
            var storage = machine.getEnergyStorageInternal();
            check(!IndependentSiEnergy.controls(machine.getBlockState()) && !storage.scexNetworkControlled(), "zero-EU logistics is not admitted as a consumer");
            check(storage.getCapacity() == 0 && storage.getMaxReceive() == 0 && balance(machine).isZero(), "zero-EU logistics has no positive account");
            var handler = (MachineItemHandler) machine.getItemHandler();
            check(handler.getLayout().getStart(SlotType.BATTERY) < 0, "zero-EU logistics has no battery slot");
            var fe = world.getCapability(Capabilities.EnergyStorage.BLOCK, at, Direction.UP);
            check(fe == null || !fe.canReceive() && fe.receiveEnergy(4, false) == 0, "zero-EU logistics rejects FE");
            exclusionRows.add(Map.of("id", id(path).toString(), "class", machine.getClass().getName(), "capacity", storage.getCapacity()));
        }
        NeoForge.EVENT_BUS.register(this); registered = true;
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void beforeNative(LevelTickEvent.Post event) {
        if (event.getLevel() != level || finished || failure != null || currentTick < 60) return;
        try {
            for (var circuit : circuits) {
                if (circuit.phase == 3) continue;
                check(loaded(circuit.target) == circuit.machine && loaded(circuit.source) == circuit.box, "same live circuit owners");
                var store = circuit.machine.getEnergyStorageInternal();
                long capacity = circuit.machine.getEffectiveCapacity();
                check(capacity > 64 && store.getCapacity() == capacity, "live effective capacity shares store");
                // Seed each explicitly independent numerical scenario after machine tick, before actual network settlement.
                store.setEnergy(circuit.phase == 0 ? 0 : circuit.phase == 1 ? capacity : capacity - 1);
                circuit.box.getEnergyStorageInternal().setEnergy(32);
                circuit.before = balance(circuit.machine);
                circuit.capturedFrame = level.getGameTime();
            }
        } catch (Throwable error) { failure = error; }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterNative(LevelTickEvent.Post event) {
        if (event.getLevel() != level || finished || failure != null || currentTick < 60) return;
        try {
            for (var circuit : circuits) {
                if (circuit.phase == 3 || circuit.capturedFrame != level.getGameTime()) continue;
                var debit = EnergyAmount.of(32).subtract(balance(circuit.box));
                var credit = balance(circuit.machine).subtract(circuit.before);
                check(debit.equals(credit), "native source debit equals actual consumer credit on one glass link");
                if (circuit.phase == 1) {
                    check(debit.isZero(), "full consumer does not debit native source");
                    if (++circuit.fullFrames == 3) {
                        nativeRows.add(nativeRow(circuit, debit, credit)); circuit.phase++;
                    }
                } else if (!debit.isZero()) {
                    check(debit.equals(EnergyAmount.of(circuit.phase == 0 ? 32 : 1)), "exact empty or one-EU-room admission");
                    nativeRows.add(nativeRow(circuit, debit, credit));
                    if (circuit.phase == 0 && circuit.input == Direction.DOWN) mixedInputs(circuit);
                    circuit.phase++;
                }
                // Do not leave a seed packet available to START catch-up outside the observed interval.
                circuit.box.getEnergyStorageInternal().setEnergy(0);
                circuit.capturedFrame = Long.MIN_VALUE;
            }
        } catch (Throwable error) { failure = error; }
    }
    private Map<String, Object> nativeRow(Circuit circuit, EnergyAmount debit, EnergyAmount credit) {
        return Map.of("id", id(circuit.id).toString(), "face", circuit.input.toString(),
            "phase", List.of("empty", "full", "one-eu-room").get(circuit.phase),
            "frame", level.getGameTime(), "source_debit", debit, "sink_credit", credit,
            "full_frames", circuit.fullFrames);
    }
    private ItemStack siBattery(int count) {
        var item = BuiltInRegistries.ITEM.get(id("normal/item_bat_lev0"));
        check(item != Items.AIR && item instanceof IBatteryItem, "actual registered SI battery");
        var stack = new ItemStack(item, count);
        ((IBatteryItem) item).setEnergy(stack, 200);
        check(((IBatteryItem) item).getEnergy(stack) == 200, "explicit test battery budget");
        return stack;
    }
    private ItemStack feBattery(int count) {
        var stack = new ItemStack(Items.NAUTILUS_SHELL, count);
        var data = new net.minecraft.nbt.CompoundTag(); data.putInt("r63_fe", 257);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        var capability = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        check(capability != null && capability.canExtract() && capability.getEnergyStored() == 257,
            "existing standard FE test item capability registered");
        return stack;
    }
    private int itemFe(ItemStack stack) {
        var capability = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        check(capability != null, "owned FE item still exposes capability");
        return capability.getEnergyStored();
    }
    private void snapshotFactory(mio_icif_producer machine) {
        var saved = machine.saveWithFullMetadata(level.registryAccess());
        var copy = BlockEntity.loadStatic(machine.getBlockPos(), machine.getBlockState(), saved, level.registryAccess());
        check(copy instanceof mio_icif_producer && copy != machine && copy.getClass() == machine.getClass()
            && copy.getType() == machine.getType() && copy.getLevel() == null, "ordinary saved factory preserves exact type off-world");
        var restored = (mio_icif_producer) copy;
        check(restored.getEnergyStorageInternal() != machine.getEnergyStorageInternal()
            && restored.getEnergyStorageInternal().scexNetworkControlled(), "restore has its own owned store, never another live balance");
        check(balance(restored).equals(balance(machine)), "saved whole and quarter-EU fraction restored exactly");
        check(saved.getLong("energy") == balance(machine).whole()
            && saved.getLong("scex_energy_fraction") == balance(machine).fraction(), "existing authoritative saved energy fields");
        closedLegacy(restored);
    }
    private void mixedInputs(Circuit circuit) {
        var machine = circuit.machine; var storage = machine.getEnergyStorageInternal();
        check(balance(machine).equals(EnergyAmount.of(32)), "mixed input begins with actual native32 EU");
        long inputBudget = storage.getMaxReceive();
        check(inputBudget > 0 && inputBudget <= Integer.MAX_VALUE / 4L
            && storage.getCapacity() >= 32 + inputBudget, "fixture capacity admits native packet plus complete item/FE allowance");
        var faces = new ArrayList<IEnergyStorage>();
        for (var side : Direction.values()) {
            var fe = port(machine, side); faces.add(fe);
            check(fe.canReceive() && !fe.canExtract() && fe.extractEnergy(1, false) == 0, "FE consumer faces never export");
            check(fe.receiveEnergy(1, true) == 1 && balance(machine).equals(EnergyAmount.of(32)), "FE simulation leaves native balance unchanged");
        }
        check(faces.getFirst().receiveEnergy(1, false) == 1
            && balance(machine).equals(new EnergyAmount(32, EnergyAmount.UNITS / 4)), "one actual FE publishes one quarterEU to native store");
        check(CompletableFuture.supplyAsync(() -> faces.getFirst().receiveEnergy(1, false)).join() == 0, "off-thread FE admission rejected");
        var handler = (MachineItemHandler) machine.getItemHandler();
        int slot = handler.getLayout().getStart(SlotType.BATTERY);
        check(slot >= 0 && handler.getStackInSlot(slot).isEmpty(), "actual machine battery slot is empty");
        var badSi = siBattery(2); handler.setStackInSlot(slot, badSi);
        var before = balance(machine);
        machine.scexFeBridge().discharge(handler, slot);
        check(ItemStack.matches(handler.getStackInSlot(slot), badSi) && balance(machine).equals(before), "stacked SI battery does not debit or credit");
        var single = siBattery(1); check(handler.isItemValid(slot, single), "real machine slot admits SI battery");
        handler.setStackInSlot(slot, single);
        machine.scexFeBridge().discharge(handler, slot);
        long remaining = ((IBatteryItem) single.getItem()).getEnergy(handler.getStackInSlot(slot));
        long siDebit = 200 - remaining;
        check(siDebit > 0 && balance(machine).equals(before.add(EnergyAmount.of(siDebit))), "actual SI item decrement equals same ledger credit");
        snapshotFactory(machine);
        var badFe = feBattery(2); handler.setStackInSlot(slot, badFe); before = balance(machine);
        machine.scexFeBridge().discharge(handler, slot);
        check(ItemStack.matches(handler.getStackInSlot(slot), badFe) && balance(machine).equals(before), "stacked FE item does not debit or credit");
        var singleFe = feBattery(1); check(handler.isItemValid(slot, singleFe), "real machine slot admits standard FE item");
        handler.setStackInSlot(slot, singleFe);
        long remainingBudgetFe = 4L * inputBudget - 1 - 4L * siDebit;
        check(remainingBudgetFe > 0, "SI item leaves FE input allowance for this mixed case");
        int offeredFe = (int) Math.min(remainingBudgetFe, FeLedger.fe(balance(machine).roomBelow(storage.getCapacity())));
        var itemCapability = singleFe.getCapability(Capabilities.EnergyStorage.ITEM);
        check(itemCapability != null, "actual FE source capability for quote");
        var beforeQuote = singleFe.copy();
        int expectedFeDebit = itemCapability.extractEnergy(offeredFe, true);
        check(expectedFeDebit > 0 && expectedFeDebit <= Math.min(257, offeredFe)
            && ItemStack.matches(beforeQuote, handler.getStackInSlot(slot)) && before.equals(balance(machine)),
            "actual FE item quote is bounded by stored energy, remaining allowance and capacity without mutation");
        machine.scexFeBridge().discharge(handler, slot);
        int feDebit = 257 - itemFe(handler.getStackInSlot(slot));
        check(feDebit == expectedFeDebit && balance(machine).equals(before.add(FeLedger.eu(feDebit))), "actual FE item decrement matches its public quote and shared ledger credit");
        int rest = faces.getLast().receiveEnergy(Integer.MAX_VALUE, false);
        check(rest >= 0 && 1L + 4L * siDebit + feDebit + rest == 4L * inputBudget,
            "all FE faces and both item kinds share one per-tick input allowance");
        check(balance(machine).equals(EnergyAmount.of(32 + inputBudget)), "native packet and item/FE inputs share one actual balance");
        check(faces.getFirst().receiveEnergy(1, false) == 0, "same-tick FE allowance cannot restart at another face");
        var exhausted = handler.getStackInSlot(slot).copy(); before = balance(machine);
        machine.scexFeBridge().discharge(handler, slot);
        check(ItemStack.matches(exhausted, handler.getStackInSlot(slot)) && before.equals(balance(machine)), "exhausted shared budget cannot debit item again");
        check(storage == machine.getEnergyStorageInternal(), "native FE and items keep original storage object");
        for (var fe : faces) check(fe.getEnergyStored() == FeLedger.fe(balance(machine)), "each capability reports same balance");
        check(machine.useEnergy(7, true) == 7 && before.equals(balance(machine)), "public internal-work payment simulation");
        check(machine.useEnergy(7, false) == 7 && balance(machine).equals(before.subtract(EnergyAmount.of(7))), "public internal-work payment debits that same balance");
        closedLegacy(machine);
        mixedRows.add(Map.of("id", id(circuit.id).toString(), "native_credit_eu", 32, "si_item_debit_eu", siDebit,
            "fe_item_debit", feDebit, "fe_item_simulated_quote", expectedFeDebit,
            "fe_direct_received", 1 + rest, "fe_item_shared_limit_eu", inputBudget,
            "after_internal_payment", balance(machine), "slot", slot, "saved", machine.saveWithFullMetadata(level.registryAccess()).toString()));
        handler.setStackInSlot(slot, ItemStack.EMPTY);
    }
    private void lifecycle() {
        for (var circuit : circuits) {
            if (circuit.input != Direction.DOWN) continue;
            var old = circuit.machine; var cached = port(old, Direction.UP); var prior = balance(old);
            old.setRemoved();
            check(cached.receiveEnergy(1, false) == 0 && balance(old).equals(prior), "removed owner closes cached FE port");
            closedLegacy(old); old.clearRemoved();
            check(loaded(circuit.target) == old && balance(old).equals(prior), "clearRemoved preserves same account");
            check(level.removeBlock(circuit.target, false) && old.isRemoved(), "ordinary removal retires actual owner");
            place(circuit.target, circuit.id, Direction.NORTH);
            check(loaded(circuit.target) instanceof mio_icif_producer, "replacement producer placed normally");
            var replacement = (mio_icif_producer) loaded(circuit.target);
            check(replacement != old && balance(replacement).isZero(), "same-position replacement receives no old stored balance");
            check(cached.receiveEnergy(1, false) == 0 && balance(old).equals(prior)
                && balance(replacement).isZero(), "stale port cannot charge replacement or removed account");
            var fresh = port(replacement, Direction.UP);
            check(fresh.receiveEnergy(1, false) == 1
                && balance(replacement).equals(new EnergyAmount(0, EnergyAmount.UNITS / 4)), "replacement exposes only its fresh ledger");
            snapshotFactory(replacement); closedLegacy(replacement);
            lifecycleRows.add(Map.of("id", id(circuit.id).toString(), "old_balance", prior, "new_balance", balance(replacement)));
        }
    }
    private void unregister() {
        if (registered) { NeoForge.EVENT_BUS.unregister(this); registered = false; }
    }
    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) {
        if (level != null && event.getServer() == level.getServer()) unregister();
    }
    public Map<String, Object> inspect(ServerLevel world, int tick) throws Exception {
        currentTick = tick;
        if (failure != null) {
            unregister();
            Files.writeString(Path.of("ordinary-consumer-world-result.json"), new Gson().toJson(Map.of(
                "passed", false, "assertions", assertions, "failure", failure.toString(), "native_rows", nativeRows, "mixed_rows", mixedRows)));
            throw new AssertionError("Ordinary consumer world probe failed", failure);
        }
        if (tick == 20) setup(world);
        if (tick == 240) {
            check(circuits.size() == 66 && circuits.stream().allMatch(c -> c.phase == 3), "all native scenarios completed before deadline");
            check(nativeRows.size() == 198 && mixedRows.size() == 11, "every native face and every actual item/FE consumer checked");
            lifecycle();
        }
        if (tick != 260) return null;
        finished = true; unregister();
        check(lifecycleRows.size() == 11 && exclusionRows.size() == 2, "all ownership and exclusion cases completed");
        var groups = List.of("66-six-face-native-empty-full-one-eu-room", "11-native-fe-si-item-fe-item-shared-ledgers",
            "11-shared-item-fe-budget-and-stacked-source-rejection", "existing-whole-and-fraction-save-factory-restore",
            "closed-legacy-demand-offer-injection", "11-cached-fe-owner-removal-and-replacement", "two-zero-eu-logistics-exclusions");
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", true); result.put("assertions", assertions); result.put("groups", groups);
        result.put("group_count", groups.size()); result.put("registered_consumers", IDS.size()); result.put("native_circuits", circuits.size());
        result.put("native_rows", nativeRows); result.put("mixed_rows", mixedRows); result.put("lifecycle_rows", lifecycleRows); result.put("excluded_rows", exclusionRows);
        result.put("scope", "Actual placements and native engine settlement; public FE capability and actual owned inventory discharge bridge. Per-scenario seed balances are explicit test inputs. Warm loadStatic restoration only. Native input and FE/item input have separate limits but the same balance.");
        result.put("not_verified", List.of("complete machine processing or original-IC2 equivalence", "natural full work-cycle battery scheduling",
            "cold-JVM reload or actual chunk/dimension unload", "absence of legacy event registration", "full old-grid/API retirement", "foreign-mod item callbacks"));
        Files.writeString(Path.of("ordinary-consumer-world-result.json"), new Gson().toJson(result));
        return result;
    }
}
