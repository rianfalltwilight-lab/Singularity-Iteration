# Independent public-platform topology adapter — R10 experimental

This Java 21 library connects the independent conductor registry to public Minecraft 1.21.1 / NeoForge events. It has **no mod entrypoint, no SI/IC2 class reference and no energy transfer**. It is one integration prerequisite; it is not a replacement for the full SI energy network or a complete behavior-alignment claim.

Attach `PlatformTopology` on the server thread **before levels and chunks load**, supplying block IDs, per-conductor integer milli-EU losses and resource limits. Only block-entity conductors on an undirected six-neighbour lattice are supported. No world state is persisted by this library: it reconstructs registration from the actual loaded chunks' saved block-entity positions and block states.

The adapter coalesces bounded position/chunk/level updates. Neighbour notifications register ordinary block edits; callers must invoke `changed` for their own mutation paths that suppress these events. Chunk callbacks only queue coordinates. World reads happen later on the server thread and use `getChunkNow`, never a lookup that loads or renews a chunk ticket. Level unload and server stop release registries and invalidate snapshot leases. Capacity/error handling fails closed instead of authorizing a partial topology.

Real R10 testing found that `ChunkEvent.Unload` alone is too late: removing a forced ticket can make a chunk inaccessible before physical unload. Public `ChunkTicketLevelUpdatedEvent` now also queues removal on a transition out of `FULL`, and defers reconstruction on a transition back. This supports regaining access before physical unload without scanning the world each tick. A change queued for a level makes `ready` and `isCurrent` false until processed. The future energy adapter must check `isCurrent` again immediately before committing balances, and independently revalidate its actual endpoints.

`workPerTick` bounds queued changes, not the number of block entities in a chunk scan. Snapshot rebuilding still scales with registered topology size. Source-route caches are bounded by the core's supplied limit. The current queue is conservative per level: pending changes elsewhere in that level temporarily prevent use of its snapshot. No comparative integrated tick/GC/TPS claim is made.

Build through the frozen development project's public mapped platform classpath:

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 :cleanroom-minecraft:build :cleanroom-minecraft:writePlatformClasspath
```

The isolated probe is in `src/smoke/java/dev/scex/si/TopologyScenarioProbe.java` and uses only public platform APIs plus this new library. Its SI runtime input is the previously frozen R5 binary, which is explicitly **not provenance cleared**. Root main outputs are replaced with that historical JAR in the test capsule; the root full-mod archive/publication gate is unchanged. Neither that SI binary nor reference IC2 is part of the independent library.

Remaining integration requirements include switching actual SI source/sink lifecycles and commits, special cable direction/colour/splitter policies, powered gameplay, suppressed-notification mutations, dimension transfer and more platform versions. See `../SCEX-ROADMAP.md` for the full outstanding stages.

New library license: Apache-2.0. Public platform APIs retain their respective upstream licenses.
