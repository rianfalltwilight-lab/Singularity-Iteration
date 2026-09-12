# SCEX R4 development candidate

Reference: the supplied IC2 Experimental 2.8.222-ex112 binary, observed through
normal placement, Minecraft commands, public platform chunk lifecycle operations,
and saved-state snapshots. No IC2 source or IC2 API was used.

## Storage output threshold

BatBox, CESU, MFE and MFSU wait for at least 32, 128, 512 and 2048 EU respectively
before offering energy to the EU grid. For example, a CESU with 129 EU sends
128 EU and retains 1 EU. If it only has 127 EU, it offers nothing. Once output
starts, a receiver may still accept less than a full packet when nearly full.

The opt-in hook is confined to those four storage variants. Other SI storage
extensions and FE bridge transfers keep their existing output policy. The check
adds no allocation, neighbor lookup or graph traversal.

The paired 48-case fixture covers zero, one, packet-minus-one, exact packets,
remainders and nearly-full MFSU receivers. R3 dev2 differs in 20 of these cases;
R4 dev1 matches all 48. The 96-case overload matrix also reproduces the unwanted
damage caused by underfilled CESU/MFE output. Correcting this threshold resolves
10 of the 54 R3 differences. The other 44 are unresolved cases, including repeats;
they are not 44 distinct defects.

The final dev3 distribution also matches all 48 packet-boundary cases and has
44 remaining differences in the 96-case overload matrix. Its 432 existing JAR
contract assertions pass. All 3,300 packaged JSON resources parse; only six
implementation classes and version metadata differ from the R3 dev2 distribution.

## Inactive chunks

After removing the loading ticket, R4 dev1 reproduced continued energy transfer
during 439 observed ticks without a FULL chunk available. The three copper/tin/
glass circuits each transferred 14,080 EU across the last-available and first-
available snapshots. The original reference paused. An extra unload callback
in dev2 did not fix the issue and is absent from the final patch: explicit event
recording showed that final chunk-unload events had not fired in that interval.

R4 dev3 checks the platform's existing block-ticking range at the base energy
source/sink boundaries and storage source overrides. A removed or inactive node
offers and requests no energy and rejects transfer calls. No chunk lookup that
loads or renews a ticket is used. This prevents continued transfers while final
unload is pending; it does not establish that all retained grid references or
compatibility proxy nodes are released.

The final 15-case scenario passes 17,671 accounting, disconnect, reconnect and
pause/resume checks. Twelve cases cover cable removal, receiver/source replacement
and cable-type replacement. The three inactive-chunk cases observe 440 ticks
outside the platform block-ticking range and 439 ticks without a FULL chunk.
They resume without the old hidden transfer. No final chunk-unload event occurs
in this NeoForge fixture, so complete unload/graph-reference cleanup remains open.

The scenario probe reads already-loaded chunks directly and records the platform
block-ticking flag plus actual chunk load/unload events. World-level block
reads can keep chunks resident, so successful forceload removal alone is not an
unload test. The reference probe also releases its own ticket and requests the
normal platform unload queue. A null getChunkNow result alone is not evidence of
a final unload event. Initial fixture failures are preserved separately.

## Reproduction and limits

Use Java 21, Gradle 9.2.1 and locked NeoForge 21.1.218 dependencies:

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 build contractTest
.\gradlew.bat --offline --no-daemon --max-workers=2 -PwithSmoke -PwithCurios -PsmokeMode=scenario exportSmokeLaunch
```

The checked-in smoke runtime lock includes Curios; use the withCurios flag when
exporting that fixture. This does not make Curios a mandatory mod dependency.
Do not run two Gradle invocations in the same directory concurrently.

The author review bundle includes QA-R4.zip with both platform fixtures, probe
sources, scripts, exact JAR identities, ordinary tick traces and exit receipts.
BUILD-MANIFEST.json records final candidate checks and unresolved differences.
The probe is excluded from the distribution JAR. These are isolated headless
server tests, not client, multiplayer, production or complete IC2 equivalence.

Unresolved coverage includes fuse/explosion ordering and blast effects, partial
network chunk unload, branches/loops, independent registrants and FE/AE proxies,
nuclear, UU, crops, all recipes, client behavior and multiplayer. The R1 upgrade
microbenchmark is not a server TPS measurement. R4 does not claim a new TPS gain.
Existing upstream grid/legacy-grid and jetpack provenance questions remain;
those implementations were not opened or used to derive this patch.

This candidate has not been deployed or sent to the author.
