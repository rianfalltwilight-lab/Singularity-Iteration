# Independent energy accounting — R17 experimental

This is a new, standalone Java 21 library, **not a Minecraft mod or a complete energy network replacement**. It has no SI, Minecraft, NeoForge, IC2 or other external dependency. No IC2 source, API or decompiled implementation was used to write it. Its implementation and contracts were independently authored against ordinary game observations and accounting requirements. Existing upstream implementations with unresolved provenance were not used as templates.

## R17 shared source packet budget

Current version: `0.10.0-r17-experimental`. Twelve contract runners pass 1,452,005 assertions. `DomainDistributor` now fixes one packet budget per source before visiting any conductor domain. Every later domain spends only the unused part, including path loss. Full-packet storage eligibility is checked once; partial spending does not invalidate the remainder. Incoming credit cannot increase that round's budget.

The change is checked against 18,720 public tick deltas from 102 reset layouts. The prior actual SI baseline violated the shared budget in 640 vectors; both current SI controls pass the accounting checks. Newly joined U-shaped networks still differ in their fixed receiver priority, explicitly excluded from this accounting acceptance and scheduled for R18. No integrated performance result or complete equivalence is claimed.

## R16 source domains and receiver offsets

R16 checkpoint version: `0.9.0-r16-experimental`. Eleven contract runners pass 1,412,841 assertions. The older sections below describe the scopes at those checkpoints; this section supersedes R8's aggregate-room stop rule. R13–R15 timing and receiver-order details are in the repository's corresponding progress reports.

R16 freezes 14,520 public source-debit vectors and 1,800 receiver-capacity-mask vectors. Full but connected receivers retain their position in `ReceiverOrder`'s offset population; accounting skips zero demand. Within a connected conductor domain, `SourceOrder` chooses a uniform starting offering source, then visits registration IDs forwards. Sources without an offer are excluded. This differs from the receiver's reverse cycle and every-fourth-world-tick fixed phase.

`MultiSourceDistributor` retains each receiver's original quote within one domain. A source satisfying that entire quote closes the receiver for later sources in the domain. Partial credits from different sources do not accumulate towards that stop condition. For example, four 1 EU sources sharing copper can credit 4 EU into an initial 2 EU gap. The reported remaining room still accounts for all actual credits; checked arithmetic rejects unrepresentable totals before callers can commit.

`DomainDistributor` separates physical conductor components and direct machine contacts. It shuffles domains independently, refreshes room between domains, and shares source reserves so total debit cannot exceed a source snapshot reserve. Direct contacts and separate wire arms therefore fill live room. An exact two-source BatBox control with 63 EU of room receives 63 EU through separate arms, leaving 1 EU at a source; joining those arms produces 64 EU of credit. Conductor component labels are computed once per immutable graph and exposed through the same invalidatable registry lease. Source and receiver orders are caller registration IDs, never inferred from coordinate sorting.

These are independently chosen models consistent with finite observations, not an inspected implementation. The actual contract enters `DomainDistributor` and `PacketDistributor`, checks every frozen vector, and applies declared 6-sigma finite-distribution guards. It does not certify the original PRNG, serial independence, equal-cost physical path selection, complex shared-source domains or all lifecycle orderings. The integrated Minecraft implementation still needs stage-5 profiling; no speedup is asserted from these component checks.

## Observed scope

`PacketLedger.settle` models the final balance of a finite source, one receiver and a straight line of identical conductors. All policy values are explicit arguments. It returns source debit, receiver credit, dissipated energy, transfer count and whether the uniform line fuses. It does not apply effects to a world or choose a tick order.

The frozen TSV contains 188 observed outcomes from the supplied IC2 Experimental 2.8.222-ex112 binary: 35 normal/loss cases, 48 reserve-threshold cases and 105 new partial-transfer/fuse cases. Four old receiver-overvoltage cases are explicitly excluded. Each fixture records input amounts and observed energy/cable outcomes; the companion manifest identifies original evidence hashes. No reference JAR, API class or implementation is included.

Examples from the new server experiments:

- A 128-unit source packet through one tin wire can supply 33 units to a nearly full receiver without melting the wire; supplying 34 units melts it.
- With five tin wires, delivering 32 units debits 33 and survives; delivering 33 debits 34 and melts all five wires.
- A 32-unit packet over five copper wires loses one unit per transfer. Filling a 32-unit gap therefore debits 34 units across two transfers.
- A full receiver causes no transfer, loss or fuse. A source below one complete offered packet does not start a transfer.

The caller's tin/copper/gold safe-debit inputs 33/129/513 reflect measured boundaries for these integer-energy scenarios. Iron/glass fixture bounds of 2048 are only tested safe bounds, **not measured ultimate limits**. `?` cable positions were not sampled and are not asserted; `-` denotes an empty path.

The R6 `PacketLedger` scope is unchanged. R7 adds the two components below for safe-conductor branching; R8 extends accounting to selected multi-source, loop and mixed copper/glass scenes. Fractional stored energy, receiver damage/explosions, world synchronization, persistence, chunk lifecycle, upgrades and flight remain uncovered. Loss at or above packet size is rejected because it is outside this observation set. A calculated plan needs fresh world-state validation before future integration.

## R7 tree paths and shared packets

`TreeTopology` owns an immutable copy of a connected acyclic conductor graph. Caller-assigned vertex IDs and per-conductor losses form the entire input. Compact adjacency and iterative traversal use O(V) time and storage; a source-contact index is built once and reused for allocation-free O(1) path-loss and length queries. The caller must rebuild snapshots/indexes when the network changes. There is no world reference, hidden global cache or automatic Minecraft lifecycle integration. Path enumeration is iterative O(path length).

`PacketDistributor.allocate` shares **one** offered packet across receiver contacts in a caller-supplied permutation. The full-packet reserve requirement is checked once; receivers with smaller remaining capacity can leave budget for later contacts. Each positive receiver delivery pays its whole-path loss, including a shared trunk. Closed receivers cost nothing. When the remaining budget cannot cover a path loss plus positive delivery, that contact is skipped. Work and temporary storage are O(receiver count), using the reusable topology index. Conductors and receivers are assumed safe; this component does not implement overload effects.

The new TSV freezes 70 valid black-box scenes and 88 nonzero transfer events with two or three receivers. For each observed preceding state, contracts enumerate all 2 or 6 receiver orders, then check the observed successor against the resulting set. **28 observed transitions have a unique result; 60 depend on order.** All 88 are compatible with this accounting model, but **receiver selection, fairness, random distribution and tick timing are not reproduced or verified**. This is not a claim of full behavior equivalence. The initial 40-scene fixture was excluded because some horizontal wires were not at the intended positions; a corrected fixture passed block identity checks before its data was used.

Examples: a 32-unit offer can fill two 1-unit gaps in one transfer, debiting 2 with zero whole-path loss or 4 if each route loses 1. With 1 unit left in the packet and a remaining route loss of 1, no additional debit occurs. Empty large receivers do not necessarily receive equal shares; changing priority is deliberately the caller's responsibility.

## R8 general graphs and multi-source rounds

`ConductorGraph` accepts immutable undirected conductor snapshots with cycles and disconnected parts. An indexed binary heap constructs a minimum-loss source index in O((V+E) log V) time with at most V active heap entries, followed by allocation-free O(1) reachability/loss queries. Unreachable contacts receive no energy. Path enumeration is iterative. This is an independently chosen algorithm consistent with the observed loop balances; it does not establish the target's algorithm or physical path choice at equal cost. Snapshots must be replaced when topology changes.

`MultiSourceDistributor` settles one offered packet per source, in caller-supplied source and per-source receiver orders. Still-active receivers retain the round's original demand quote; receivers already filled are skipped. **Remaining room can become negative:** this intentionally models observed multi-source capacity overshoot. It does not silently clamp receipt energy or assume every source sees an updated capacity. Input demand is nonnegative; callers start the next round with `max(0, actual remaining room)`.

The R8 fixtures freeze 54 valid scenes and 318 nonzero transitions: 12 loop/disconnection scenes, 26 source-contention/selection scenes and 16 dedicated overshoot/control scenes. Contracts enumerate all permitted order combinations before comparing observable per-source debits and aggregate receiver credits. Of the transitions, 23 have a unique outcome and 295 depend on order; 12 transitions overshoot capacity, across 9 scenes, by at most 31 EU in this sample. Repeated two-source overshoot and single-active-source controls confirm that strict shared live-capacity accounting would reject actual observations. This does not prove the internal mechanism or the general maximum overshoot.

Four longer selection scenes yield 256 rounds with receiver-credit vectors `[32,32]` 161 times, `[0,64]` 45 times and `[64,0]` 50 times. These are descriptive counts only. **Scheduler order, probability distributions, fairness and tick timing remain unverified.** R8 does not establish full IC2 behavior equivalence. The initial loop fixture failed placement before charging and is excluded; an initial multi-source capsule was superseded before execution.

## R9 registration lifecycle and uniform-path effects

`ConductorRegistry` is a new, thread-confined six-neighbour lattice registry. Caller-supplied positions and losses are its only inputs. A chunk membership index removes only the registered positions in an unloaded chunk; no world/chunk lookup occurs. Mutations invalidate old snapshot leases and release their cached route indexes. Identical updates do not invalidate work. Repeated edits coalesce into one lazy topology rebuild, and source-route indexes use a caller-bounded LRU cache. Node capacity, negative coordinates, coordinate overflow, cross-thread access and close are explicit contracts. This is a platform-neutral design; actual Minecraft unload, event ordering and world-commit checks remain future integration work.

`UniformPacketEffects` models one integer-energy packet on a uniform straight line. It returns source debit, path loss, whole-line fuse and receiver destruction. A destroyed receiver's retained energy is absent (`OptionalLong.empty()`), never represented as measured zero. The matching independent model keeps the receiver alive when the uniform line fuses. Otherwise it detects excessive receiver delivery and the measured conductor-boundary case. With five tin wires and a BatBox, a 33-unit source debit for a 32-unit gap destroys the receiver; a 34-unit debit for a 33-unit gap fuses the line and the receiver survives. Higher-tier receiver controls distinguish these effects. This is a model consistent with observations, not an inspected internal mechanism.

The R9 fixture freezes 156 scenes in three normally stopped reference-server runs, with 126319 scene checks. The contracts compare source debit, receiver survival, and retained credit where observable. Cable survival is checked when the receiver survives; cable destruction near a destroyed receiver is excluded from the fuse oracle because it can be blast damage. Mixed paths, multi-source overload, shock damage, explosion strength/shape and timing remain unverified. The R6/R7/R8 accounting scopes are unchanged; R9 does not expand them automatically to arbitrary destructive networks.

## Build and verification

From this directory with JDK 21 and PowerShell, use a new output directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
.\build-independent.ps1 -OutputDirectory C:\Temp\scex-energy-r9-build
```

The script compiles the library and seven contract runners: 188 R6 cases, 70 R7 scenes, 54 R8 scenes, 156 R9 scenes, 20,000 randomized accounts each for single-line, branching and multi-source accounting, 120 random graphs against an independent all-pairs reference, a 100,000-conductor chain and ring with one million indexed queries each, 2000 random registry edits checked against independent connectivity, a 100,000-node registry, and invalid-input/overflow contracts. It then creates a reproducible standalone JAR. It needs no Gradle, Minecraft files, network or legacy repository. The JAR's timestamp is fixed. Gradle includes extra debug metadata; signatures and instruction listings are compared separately from standalone archive reproducibility.

Inside the development repository, the alternative is:

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 :cleanroom-energy:build
```

The real verification tasks are `:cleanroom-energy:contractTest`, `treeContractTest`, `distributorContractTest`, `graphContractTest`, `multiSourceContractTest`, `registryContractTest` and `effectsContractTest`; ordinary `test NO-SOURCE` is not the evidence. `verifyDependencyBoundary` rejects any external compile/runtime dependency.

Bulk accounting uses constant time and space rather than allocating an object or iterating for every energy packet. The maximum-integer contract covers a 9,223,372,036,854,775,807-transfer direct connection. This establishes the component's calculation bound; **no integrated Minecraft TPS improvement has been measured**.

## Provenance and release boundary

The existing full SI source still contains unresolved grid, legacy-grid, flight/input and energy API review boundaries. The R6 hold on root JAR/publication tasks remains unchanged. Deleting a comment, changing a package name or changing a policy boolean does not clear this hold. This standalone component does not certify the remaining source or previously built R5 artifacts.

License: Apache-2.0 for this new component; see LICENSE and file identifiers.
