# Independent energy accounting — R7 experimental

This is a new, standalone Java 21 library, **not a Minecraft mod or a complete energy network replacement**. It has no SI, Minecraft, NeoForge, IC2 or other external dependency. No IC2 source, API or decompiled implementation was used to write it. Its implementation and contracts were independently authored against ordinary game observations and accounting requirements. Existing upstream implementations with unresolved provenance were not used as templates.

## Observed scope

`PacketLedger.settle` models the final balance of a finite source, one receiver and a straight line of identical conductors. All policy values are explicit arguments. It returns source debit, receiver credit, dissipated energy, transfer count and whether the uniform line fuses. It does not apply effects to a world or choose a tick order.

The frozen TSV contains 188 observed outcomes from the supplied IC2 Experimental 2.8.222-ex112 binary: 35 normal/loss cases, 48 reserve-threshold cases and 105 new partial-transfer/fuse cases. Four old receiver-overvoltage cases are explicitly excluded. Each fixture records input amounts and observed energy/cable outcomes; the companion manifest identifies original evidence hashes. No reference JAR, API class or implementation is included.

Examples from the new server experiments:

- A 128-unit source packet through one tin wire can supply 33 units to a nearly full receiver without melting the wire; supplying 34 units melts it.
- With five tin wires, delivering 32 units debits 33 and survives; delivering 33 debits 34 and melts all five wires.
- A 32-unit packet over five copper wires loses one unit per transfer. Filling a 32-unit gap therefore debits 34 units across two transfers.
- A full receiver causes no transfer, loss or fuse. A source below one complete offered packet does not start a transfer.

The caller's tin/copper/gold safe-debit inputs 33/129/513 reflect measured boundaries for these integer-energy scenarios. Iron/glass fixture bounds of 2048 are only tested safe bounds, **not measured ultimate limits**. `?` cable positions were not sampled and are not asserted; `-` denotes an empty path.

The R6 `PacketLedger` scope is unchanged. R7 adds the two components below for safe-conductor branching. Multiple sources, loops, mixed-wire behavior, fractional stored energy, receiver damage/explosions, world synchronization, persistence, chunk lifecycle, upgrades and flight remain uncovered. Loss at or above packet size is rejected because it is outside this observation set. A calculated plan needs fresh world-state validation before future integration.

## R7 tree paths and shared packets

`TreeTopology` owns an immutable copy of a connected acyclic conductor graph. Caller-assigned vertex IDs and per-conductor losses form the entire input. Compact adjacency and iterative traversal use O(V) time and storage; a source-contact index is built once and reused for allocation-free O(1) path-loss and length queries. The caller must rebuild snapshots/indexes when the network changes. There is no world reference, hidden global cache or automatic Minecraft lifecycle integration. Path enumeration is iterative O(path length).

`PacketDistributor.allocate` shares **one** offered packet across receiver contacts in a caller-supplied permutation. The full-packet reserve requirement is checked once; receivers with smaller remaining capacity can leave budget for later contacts. Each positive receiver delivery pays its whole-path loss, including a shared trunk. Closed receivers cost nothing. When the remaining budget cannot cover a path loss plus positive delivery, that contact is skipped. Work and temporary storage are O(receiver count), using the reusable topology index. Conductors and receivers are assumed safe; this component does not implement overload effects.

The new TSV freezes 70 valid black-box scenes and 88 nonzero transfer events with two or three receivers. For each observed preceding state, contracts enumerate all 2 or 6 receiver orders, then check the observed successor against the resulting set. **28 observed transitions have a unique result; 60 depend on order.** All 88 are compatible with this accounting model, but **receiver selection, fairness, random distribution and tick timing are not reproduced or verified**. This is not a claim of full behavior equivalence. The initial 40-scene fixture was excluded because some horizontal wires were not at the intended positions; a corrected fixture passed block identity checks before its data was used.

Examples: a 32-unit offer can fill two 1-unit gaps in one transfer, debiting 2 with zero whole-path loss or 4 if each route loses 1. With 1 unit left in the packet and a remaining route loss of 1, no additional debit occurs. Empty large receivers do not necessarily receive equal shares; changing priority is deliberately the caller's responsibility.

## Build and verification

From this directory with JDK 21 and PowerShell, use a new output directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
.\build-independent.ps1 -OutputDirectory C:\Temp\scex-energy-r7-build
```

The script compiles the library and three contract runners, runs the 188 R6 cases, 70 R7 scenes, 20,000 randomized single-line and 20,000 randomized branching accounts, a 100,000-conductor chain with a million indexed queries, and invalid-input/overflow contracts, then creates a reproducible standalone JAR. It needs no Gradle, Minecraft files, network or legacy repository. The JAR's timestamp is fixed. Its SHA differs from Gradle's container because packaging metadata differs.

Inside the development repository, the alternative is:

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 :cleanroom-energy:build
```

The real verification tasks are `:cleanroom-energy:contractTest`, `treeContractTest` and `distributorContractTest`; ordinary `test NO-SOURCE` is not the evidence. `verifyDependencyBoundary` rejects any external compile/runtime dependency.

Bulk accounting uses constant time and space rather than allocating an object or iterating for every energy packet. The maximum-integer contract covers a 9,223,372,036,854,775,807-transfer direct connection. This establishes the component's calculation bound; **no integrated Minecraft TPS improvement has been measured**.

## Provenance and release boundary

The existing full SI source still contains unresolved grid, legacy-grid, flight/input and energy API review boundaries. The R6 hold on root JAR/publication tasks remains unchanged. Deleting a comment, changing a package name or changing a policy boolean does not clear this hold. This standalone component does not certify the remaining source or previously built R5 artifacts.

License: Apache-2.0 for this new component; see LICENSE and file identifiers.
