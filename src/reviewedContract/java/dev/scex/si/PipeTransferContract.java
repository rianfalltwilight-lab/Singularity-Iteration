// SPDX-License-Identifier: Apache-2.0
package dev.scex.si;

import com.singularity_iteration.mio_icif.Blocks.entity.pipe.mio_icif_pipe_item;
import dev.scex.si.processing.ItemPipeRoute;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Real pipe and routing classes under vanilla registry bootstrap; no game world. */
public final class PipeTransferContract {
    private static int assertions;
    private static final HolderLookup.Provider REGISTRIES = HolderLookup.Provider.create(java.util.stream.Stream.empty());
    private PipeTransferContract() { }
    private static void require(boolean result, String label) {
        assertions++;
        if (!result) throw new AssertionError(label);
    }
    private static final class Pipe extends mio_icif_pipe_item {
        Pipe() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override protected boolean canWork() { return true; }
        int paidExtractions;
        @Override protected void extracted(int count) { paidExtractions += count; }
        void put(ItemStack stack) { bufferItem = stack.copy(); }
        int send(IItemHandler target, int amount) {
            isProcessing = true;
            try { return deliver(target, 0, amount); }
            finally { isProcessing = false; }
        }
        CompoundTag saved() { var tag = new CompoundTag(); saveAdditional(tag, REGISTRIES); return tag; }
    }
    private static ItemStack named(int count) {
        var stack = new ItemStack(Items.DIAMOND, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("retained components"));
        return stack;
    }
    private static void malformedCommit(int kind, int originalCount) {
        var pipe = new Pipe(); pipe.put(named(originalCount));
        int[] calls = {0}, externallyAccepted = {0};
        var target = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                require(!simulate, "Delivery is an actual insert");
                calls[0]++; externallyAccepted[0] += stack.getCount();
                require(pipe.getItemHandlerCapability(null).extractItem(0, 64, false).isEmpty(), "Reentrant extraction cannot steal the buffer");
                return switch (kind) {
                    case 0 -> null;
                    case 1 -> new ItemStack(Items.EMERALD, 1);
                    case 2 -> new ItemStack(Items.DIAMOND, 1);
                    case 3 -> stack.copyWithCount(stack.getCount() + 1);
                    default -> throw new IllegalStateException("External insert already committed");
                };
            }
        };
        try { pipe.send(target, 64); }
        catch (IllegalStateException expected) { require(kind == 4, "Only injected execution failure may escape"); }
        require(pipe.hasUncertainTransfer(), "Unconfirmed external commit remains held, kind=" + kind);
        require(pipe.getBufferItem().getCount() == originalCount - 64, "Untouched buffer is neither replaced nor refunded");
        for (int i = 0; i < 10; i++) require(pipe.send(target, 64) == 0, "Uncertain call is never replayed");
        require(calls[0] == 1 && externallyAccepted[0] == 64, "Only one outgoing batch reaches the external inventory");
        var saved = pipe.saved();
        require(saved.getString("scex_pipe_phase").equals("insert"), "Persist transfer direction");
        require(ItemStack.matches(ItemStack.parse(REGISTRIES, saved.getCompound("scex_pipe_uncertain")).orElseThrow(), named(64)), "Persist exact unresolved batch and components");
        var resumed = new Pipe(); resumed.loadAdditional(saved, REGISTRIES);
        resumed.markForUpdate();
        require(resumed.hasUncertainTransfer() && resumed.send(target, 64) == 0 && calls[0] == 1, "Reload and connection invalidation do not replay unknown commit");
        require(resumed.getBufferItem().getCount() == originalCount - 64, "Reload retains untouched historical oversized buffer");
        require(resumed.getItemHandlerCapability(null).insertItem(0, named(1), false).getCount() == 1, "External capabilities cannot merge into unresolved state");
    }
    private static void knownRemainders() {
        var pipe = new Pipe(); pipe.put(named(96));
        var rejecting = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
        };
        require(pipe.send(rejecting, 64) == 0 && ItemStack.matches(pipe.getBufferItem(), named(96)) && !pipe.hasUncertainTransfer(), "Known full rejection restores the entire oversized buffer");
        var partial = new ItemStackHandler(1); partial.setStackInSlot(0, named(60));
        require(pipe.send(partial, 64) == 4 && ItemStack.matches(pipe.getBufferItem(), named(92)), "Known partial rejection retains exact components and every other item");
        require(partial.getStackInSlot(0).getCount() == 64 && !pipe.hasUncertainTransfer(), "Confirmed partial insertion closes the journal");
        var saved = pipe.saved(); var resumed = new Pipe(); resumed.loadAdditional(saved, REGISTRIES);
        require(ItemStack.matches(resumed.getBufferItem(), named(92)), "Normal oversized custody survives reload");
        var healthy = new ItemStackHandler(1);
        require(resumed.send(healthy, 64) == 64 && resumed.getBufferItem().getCount() == 28, "Healthy target drains the next bounded batch after reload");
    }
    private static void endpointFailures() {
        int[] fluctuatingQuotes = {0};
        var throwsQuote = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { throw new IllegalStateException("Broken simulation"); }
        };
        var throwsSlots = new ItemStackHandler(1) {
            @Override public int getSlots() { throw new IllegalStateException("Broken inventory enumeration"); }
        };
        var nullQuote = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return null; }
        };
        var fluctuating = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                require(simulate, "Route search never commits external inventory");
                return ++fluctuatingQuotes[0] % 2 == 1 ? ItemStack.EMPTY : stack;
            }
        };
        var healthy = new ItemStackHandler(1);
        var access = new ItemPipeRoute.Access() {
            @Override public BlockPos pipe(BlockPos from, Direction side) { return null; }
            @Override public IItemHandler inventory(BlockPos from, Direction side) {
                return switch (side) { case DOWN -> throwsQuote; case UP -> throwsSlots; case NORTH -> nullQuote; case SOUTH -> fluctuating; case WEST -> healthy; default -> null; };
            }
            @Override public long revision() { return 0; }
        };
        var route = new ItemPipeRoute(BlockPos.ZERO, null, named(16));
        ItemPipeRoute.Target target = null;
        for (int tick = 0; tick < 40 && target == null; tick++) {
            long before = route.work(); target = route.advance(access, 2);
            require(route.work() - before <= 2, "Failed endpoint handling stays within the work budget");
        }
        require(target != null && target.side() == Direction.WEST && target.accepted() == 16, "Broken or fluctuating endpoints cannot starve a healthy outlet");
        require(healthy.getStackInSlot(0).isEmpty(), "Routing only simulates");
    }
    private static void confirmedRejectionMovesOn() {
        var rejecting = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return simulate ? ItemStack.EMPTY : stack; }
        };
        var healthy = new ItemStackHandler(1);
        var access = new ItemPipeRoute.Access() {
            @Override public BlockPos pipe(BlockPos from, Direction side) { return null; }
            @Override public IItemHandler inventory(BlockPos from, Direction side) {
                return side == Direction.DOWN ? rejecting : side == Direction.UP ? healthy : null;
            }
            @Override public long revision() { return 0; }
        };
        var pipe = new Pipe(); pipe.put(named(16));
        var route = new ItemPipeRoute(BlockPos.ZERO, null, named(16));
        var first = route.advance(access, 64);
        require(first != null && first.side() == Direction.DOWN, "First outlet promises capacity");
        require(pipe.send(rejecting, first.accepted()) == 0 && !pipe.hasUncertainTransfer(), "Execution rejection has known custody");
        route.rejectTarget();
        var next = route.advance(access, 64);
        require(next != null && next.side() == Direction.UP && pipe.send(healthy, next.accepted()) == 16, "Continue to another outlet after a confirmed rejection");
        require(pipe.isEmpty() && ItemStack.matches(healthy.getStackInSlot(0), named(16)), "Only one healthy outlet receives the original batch");
    }
    private static void storedCustody() {
        for (int count : new int[]{1, 64, 99, 100, 192, 256, Integer.MAX_VALUE}) {
            var pipe = new Pipe(); pipe.put(named(count));
            var tag = pipe.saved();
            require(tag.getCompound("buffer").getInt("count") <= 99, "Serialized stack stays within vanilla codec bounds");
            var restored = new Pipe(); restored.loadAdditional(tag, REGISTRIES);
            require(ItemStack.matches(restored.getBufferItem(), named(count)) && !restored.hasUncertainTransfer(), "Exact large count and components survive NBT round trip");
        }
        var legacy = new CompoundTag();
        var oldBuffer = (CompoundTag)named(1).save(REGISTRIES); oldBuffer.putInt("count", 256); legacy.put("buffer", oldBuffer);
        var oldPipe = new Pipe(); oldPipe.loadAdditional(legacy, REGISTRIES);
        require(ItemStack.matches(oldPipe.getBufferItem(), named(256)), "Read a legacy over-limit count without truncation");
        for (String key : new String[]{"buffer", "scex_pipe_uncertain"}) {
            var missing = new CompoundTag();
            var unknown = (CompoundTag)named(1).save(REGISTRIES); unknown.putString("id", "missing_mod:retained_item"); unknown.putInt("count", 256);
            missing.put(key, unknown);
            if (key.equals("scex_pipe_uncertain")) missing.putString("scex_pipe_phase", "insert");
            var pipe = new Pipe(); pipe.loadAdditional(missing, REGISTRIES);
            require(pipe.hasUncertainTransfer() && pipe.saved().getCompound(key).equals(unknown), "Missing item data and count remain intact and blocked");
            require(pipe.getItemHandlerCapability(null).insertItem(0, named(1), false).getCount() == 1, "Unknown data cannot be overwritten through a capability");
        }
        var orphan = new CompoundTag(); orphan.put("scex_pipe_uncertain", named(32).save(REGISTRIES));
        var orphanPipe = new Pipe(); orphanPipe.loadAdditional(orphan, REGISTRIES);
        require(orphanPipe.hasUncertainTransfer() && orphanPipe.saved().contains("scex_pipe_uncertain"), "Missing direction cannot erase an existing transfer intent");
        var badCount = new CompoundTag(); var bad = (CompoundTag)named(1).save(REGISTRIES); bad.putInt("scex_pipe_count", -1); badCount.put("buffer", bad);
        var held = new Pipe(); held.loadAdditional(badCount, REGISTRIES);
        require(held.hasUncertainTransfer() && held.saved().getCompound("buffer").equals(bad), "Malformed extended count is preserved for recovery rather than clamped away");
    }
    private static void explicitRecovery() {
        for (String phase : new String[]{"insert", "extract"}) for (int confirmed : new int[]{0, 1, 32, 64}) {
            var tag = new CompoundTag(); tag.putString("scex_pipe_phase", phase); tag.putString("scex_pipe_transfer_id", "saved-intent");
            tag.put("scex_pipe_uncertain", named(64).save(REGISTRIES));
            var pipe = new Pipe(); pipe.loadAdditional(tag, REGISTRIES); pipe.put(named(192));
            var before = pipe.saved();
            require(!pipe.resolveUncertainTransfer("wrong-id", confirmed) && pipe.saved().equals(before), "Wrong intent cannot authorize recovery");
            require(!pipe.resolveUncertainTransfer("saved-intent", -1) && !pipe.resolveUncertainTransfer("saved-intent", 65) && pipe.saved().equals(before), "Out-of-range recovery cannot alter custody");
            pipe.setProcessing(true);
            require(!pipe.resolveUncertainTransfer("saved-intent", confirmed), "Reentrant resolution is refused");
            pipe.setProcessing(false);
            var resumed = new Pipe(); resumed.loadAdditional(before, REGISTRIES);
            require(resumed.getUncertainTransferId().equals("saved-intent"), "Intent ID survives restart");
            require(resumed.resolveUncertainTransfer("saved-intent", confirmed), "Operator supplied external amount resolves known item intent");
            int expected = 192 + (phase.equals("insert") ? 64 - confirmed : confirmed);
            require(ItemStack.matches(resumed.getBufferItem(), named(expected)) && !resumed.hasUncertainTransfer(), "Resolve uses exact externally confirmed amount and preserves components");
            require(resumed.paidExtractions == (phase.equals("extract") ? confirmed : 0), "Recovered extraction pays its extraction cost exactly once");
            require(!resumed.resolveUncertainTransfer("saved-intent", confirmed) && resumed.getBufferItem().getCount() == expected, "A repeated command cannot refund the same journal twice");
            require(!resumed.saved().contains("scex_pipe_transfer_id") && !resumed.saved().contains("scex_pipe_uncertain"), "Completed recovery removes persisted uncertainty");
            var next = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return null; }
            };
            resumed.send(next, 64);
            require(resumed.hasUncertainTransfer() && !resumed.getUncertainTransferId().equals("saved-intent")
                && !resumed.resolveUncertainTransfer("saved-intent", 0), "A stale recovery command cannot touch a later transfer");
        }
        var tag = new CompoundTag(); tag.putString("scex_pipe_phase", "insert"); tag.put("scex_pipe_uncertain", named(64).save(REGISTRIES));
        var overflow = new Pipe(); overflow.loadAdditional(tag, REGISTRIES); overflow.put(named(Integer.MAX_VALUE));
        require(!overflow.resolveUncertainTransfer(overflow.getUncertainTransferId(), 0) && overflow.getBufferItem().getCount() == Integer.MAX_VALUE, "Recovery refuses count overflow without deleting custody");
        var missing = (CompoundTag)named(1).save(REGISTRIES); missing.putString("id", "missing_mod:unknown"); tag.put("scex_pipe_uncertain", missing);
        var unknown = new Pipe(); unknown.loadAdditional(tag, REGISTRIES); var raw = unknown.saved();
        require(!unknown.resolveUncertainTransfer(unknown.getUncertainTransferId(), 0) && unknown.saved().equals(raw), "Operator count cannot discard unresolved item metadata");
    }
    public static void run(String candidatePath) throws Exception {
        for (var type : new Class<?>[]{mio_icif_pipe_item.class, ItemPipeRoute.class}) {
            require(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(candidatePath).toRealPath()), "Pipe regression must load just-compiled candidate classes");
            System.out.println("SCEX_PIPE_ORIGIN " + type.getName() + " " + type.getProtectionDomain().getCodeSource().getLocation());
        }
        var failures = new ArrayList<String>();
        for (int kind = 0; kind < 5; kind++) for (int size : new int[]{64, 256}) {
            try { malformedCommit(kind, size); } catch (AssertionError | RuntimeException failure) { failures.add("commit[" + kind + "," + size + "]: " + failure); }
        }
        try { knownRemainders(); } catch (AssertionError | RuntimeException failure) { failures.add("known remainder: " + failure); }
        try { endpointFailures(); } catch (AssertionError | RuntimeException failure) { failures.add("endpoint route: " + failure); }
        try { confirmedRejectionMovesOn(); } catch (AssertionError | RuntimeException failure) { failures.add("confirmed rejection: " + failure); }
        try { storedCustody(); } catch (AssertionError | RuntimeException failure) { failures.add("stored custody: " + failure); }
        try { explicitRecovery(); } catch (AssertionError | RuntimeException failure) { failures.add("explicit recovery: " + failure); }
        for (String failure : failures) System.out.println("SCEX_PIPE_FAILURE " + failure);
        if (!failures.isEmpty()) throw new AssertionError("Pipe regressions failed: " + failures.size());
        System.out.println("SCEX_PIPE_TRANSFER assertions=" + assertions + " PASS scope=actual_pipe_and_route_no_world");
    }
}
