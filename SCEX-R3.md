# SCEX R3 development candidate

Target: Minecraft 1.21.1, NeoForge 21.1.218, Java 21. Reference: the supplied
IC2 Experimental 2.8.222-ex112 distribution, observed through ordinary placement,
Minecraft commands, and saved-state snapshots. No IC2 source or API was used.

## Changes

- Base cables use losses measured in EU per packet per block, rather than the
  previous small decimal values described as proportions: tin/copper 0.2,
  gold 0.4, iron 0.8, glass fibre 0.025. Bare and insulated versions have equal
  measured loss. Added SI voltage tiers retain their existing settings.
- Actual energy changes mark an already-loaded server chunk dirty. Simulation
  and unchanged values do not. The marker does not serialize NBT, load chunks,
  or send comparator notifications on every energy operation.
- Basic machines also persist a progress reset or a transition into an outage
  when no energy changes. This addresses rollback to an earlier autosave on
  normal shutdown/restart; an in-memory NBT roundtrip cannot expose that failure.
- Non-positive receive/extract requests return zero without changing storage.
  The old negative-extraction path could increase stored energy.
- The optional scenario probe samples real server ticks. Its runtime record
  identifies the distribution JAR containing SI, and it is excluded from the
  distribution. `exportSmokeLaunch` exports launch dependencies without starting
  Minecraft; both the normal and late-provided classpaths are included.

## Evidence and limits

R3 development artifacts and remote receipts are retained in the clean-room
workspace under `evidence/r3`. The actual R2 distribution reproduced 20 mismatches
in 35 initial finite-energy circuits. The expanded candidate suite has 39 cases:
all 35 normal-transfer cases match, including 50 copper and 80 glass-fibre blocks.
Three of four overvoltage cases still differ. A completed server run means that
its observations and exit were collected, not that every behavior matched.

The corrected save fixture uses 12 machines (four types, 0/1/3 overclockers), an
actual R1 world, an upgrade, and a second process restart. It preserves ordinary
per-tick NBT, phase JAR identities, region-file hashes, and real stop/save/exit
records. Before the old R1 shutdown, it places an unrelated stone block in each
sampled chunk to freeze its intended starting state: R1 itself has the missing
dirty-marker problem. No such assistance is given to the two new-version phases.
Assertions allow initial asynchronous chunk activation and then check each tick,
including power exhaustion. The first fixture attempt had inventory-parser and
duration assumptions corrected before the controlled red/green comparison.

The previous candidate fails 8 of 432 API assertions. Save/restart fixtures also
required correcting the compressor's distinct upgrade-slot indices. Candidate
validation receipts are authoritative for the latest results. The final dev2
save fixture passes all 31,473 assertions across three real JVM runs and 420
observed server ticks (`si-save-r1-r3dev2-02-20260912`). This document does not
claim full IC2 equivalence.

Full grid topology, fuse/explosion ordering, nuclear reactors, UU, crops, all
recipes, client visuals, multiplayer, and old production worlds remain outside
the proven coverage. Provenance questions around the upstream grid/legacy-grid
and jetpack implementations remain recorded; these implementations were not
used as a behavioral specification or rewritten from IC2 code.

Build with the locked dependencies:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
.\gradlew.bat --offline --no-daemon --max-workers=2 build contractTest
```

Do not run two Gradle invocations against the same build directory concurrently.
Keep the R1 performance-only branch when the author's intentional 1.3 design is
desired. This candidate follows the requested original-behavior alignment and is
for review and selective adoption; it has not been deployed or sent to the author.
