# Independent energy accounting — R6 experimental

This is a new, standalone Java 21 library, **not a Minecraft mod or a complete energy network replacement**. It has no SI, Minecraft, NeoForge, IC2 or other external dependency. No IC2 source, API or decompiled implementation was used to write it. Its two Java files were independently authored against ordinary game observations and accounting requirements. Existing upstream implementations with unresolved provenance were not used as templates.

## Observed scope

`PacketLedger.settle` models the final balance of a finite source, one receiver and a straight line of identical conductors. All policy values are explicit arguments. It returns source debit, receiver credit, dissipated energy, transfer count and whether the uniform line fuses. It does not apply effects to a world or choose a tick order.

The frozen TSV contains 188 observed outcomes from the supplied IC2 Experimental 2.8.222-ex112 binary: 35 normal/loss cases, 48 reserve-threshold cases and 105 new partial-transfer/fuse cases. Four old receiver-overvoltage cases are explicitly excluded. Each fixture records input amounts and observed energy/cable outcomes; the companion manifest identifies original evidence hashes. No reference JAR, API class or implementation is included.

Examples from the new server experiments:

- A 128-unit source packet through one tin wire can supply 33 units to a nearly full receiver without melting the wire; supplying 34 units melts it.
- With five tin wires, delivering 32 units debits 33 and survives; delivering 33 debits 34 and melts all five wires.
- A 32-unit packet over five copper wires loses one unit per transfer. Filling a 32-unit gap therefore debits 34 units across two transfers.
- A full receiver causes no transfer, loss or fuse. A source below one complete offered packet does not start a transfer.

The caller's tin/copper/gold safe-debit inputs 33/129/513 reflect measured boundaries for these integer-energy scenarios. Iron/glass fixture bounds of 2048 are only tested safe bounds, **not measured ultimate limits**. `?` cable positions were not sampled and are not asserted; `-` denotes an empty path.

Not covered: multiple sources/receivers, branching/loops, mixed wires, fractional stored energy, receiver damage/explosions, world synchronization, persistence, chunk lifecycle, upgrades or flight. Loss at or above packet size is rejected because it is outside this observation set. A calculated plan needs fresh world-state validation before future integration.

## Build and verification

From this directory with JDK 21 and PowerShell, use a new output directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
.\build-independent.ps1 -OutputDirectory C:\Temp\scex-energy-r6-build
```

The script compiles the library and contract runner, runs all 188 frozen cases plus 20,000 deterministic randomized accounting checks and integer-overflow boundaries, then creates a reproducible standalone JAR. It needs no Gradle, Minecraft files, network or legacy repository. The JAR's timestamp is fixed. Its SHA differs from Gradle's container because packaging metadata differs.

Inside the development repository, the alternative is:

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 :cleanroom-energy:build
```

The real verification task is `:cleanroom-energy:contractTest`; ordinary `test NO-SOURCE` is not the evidence. `verifyDependencyBoundary` rejects any external compile/runtime dependency.

Bulk accounting uses constant time and space rather than allocating an object or iterating for every energy packet. The maximum-integer contract covers a 9,223,372,036,854,775,807-transfer direct connection. This establishes the component's calculation bound; **no integrated Minecraft TPS improvement has been measured**.

## Provenance and release boundary

The existing full SI source still contains unresolved grid, legacy-grid, flight/input and energy API review boundaries. R6 blocks root JAR/publication tasks until a separately reviewed release procedure exists. Deleting a comment, changing a package name or changing a policy boolean does not clear this hold. This standalone component does not certify the remaining source or previously built R5 artifacts.

License: Apache-2.0 for this new component; see LICENSE and file identifiers.
