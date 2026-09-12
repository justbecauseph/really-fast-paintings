# Really Fast Paintings — Execution Progress

## Phase 0 — Baseline Verification & Audit

- **Date:** September 13, 2026
- **Starting Commit:** `64b381f` (`main`)
- **Dependency Tuple:**
  - Minecraft: `26.2`
  - Fabric Loader: `0.19.3`
  - Fabric Loom: `1.17.20`
  - Fabric API: `0.158.0+26.2`
  - Java: `Zulu 25.0.4`
  - Gradle: `9.5.1`
  - Distant Decorations: `0.1.0` (compileOnly)
- **Baseline Commands Executed:**
  - `./gradlew --version`
  - `./gradlew test` (Passed: 3 tests in `PaintingRenderPipelineTest`)
  - `./gradlew runGameTest` (Passed: 5 tests originally, but lacked part-block anchor resolution and multi-tick restoration coverage)
- **Defects Identified:**
  1. `PaintingPartBlock.findAnchorPos()` allocated `candidate` but never set its coordinates, always testing `(0, 0, 0)`. Any painting outside origin failed anchor lookup on part blocks.
  2. Part blocks receiving neighbor updates during player placement were treated as orphans and destroyed prior to anchor block creation due to placement ordering.
  3. Restored vanilla paintings spawned via `tryRestore()` were immediately reconverted back to block entities on the following tick via scheduled `onEntityLoad` callbacks.
  4. Restoration entity-add failures lacked atomic rollback, destroying saved paintings if `addFreshEntity()` returned false.
  5. `testBackingWallDestruction` bypassed game physics by directly calling `be.removeFootprint()`.
  6. `PaintingRenderPipelineTest` swallowed registry initialization exceptions with empty catch blocks.

---

## Phase 1 — Baseline Correctness Repairs & Regression Tests

- **Modified Files:**
  - `src/main/java/me/justbecause/fastpaintings/block/PaintingPartBlock.java`
  - `src/main/java/me/justbecause/fastpaintings/painting/PaintingPlacementService.java`
  - `src/main/java/me/justbecause/fastpaintings/painting/PaintingConversionService.java`
  - `src/main/java/me/justbecause/fastpaintings/event/PaintingEntityHandler.java`
  - `src/gametest/java/me/justbecause/fastpaintings/test/FastPaintingsGameTests.java`
  - `src/test/java/me/justbecause/fastpaintings/block/PaintingPartBlockUnitTest.java`
  - `src/test/java/me/justbecause/fastpaintings/client/render/PaintingRenderPipelineTest.java`
  - `README.md`

- **Key Implementations:**
  1. **Anchor Search & Bounded Lookup (1A):**
     - Updated `PaintingPartBlock.findAnchorLookup()` to properly compute `candidate.set(partPos.getX() + left.getStepX() * dx, partPos.getY() + dy, partPos.getZ() + left.getStepZ() * dx)`.
     - Added `AnchorLookupResult` (`Found`, `Unloaded`, `Orphan`) distinguishing uninspected unloaded candidates from proven orphans.
     - Prevented orphan destruction and forced chunk loads when candidate chunks are unloaded.
     - Fixed placement order in `PaintingPlacementService`: anchor is placed first and BE variant set before placing part blocks, preventing false orphan self-healing during multi-block placement.
  2. **Multi-Tick Restoration & Atomic Rollback (1B):**
     - Implemented `PaintingConversionService.RESTORED_TAG` (`"fastpaintings:restored"`) and transient UUID suppression set (`RESTORED_UUIDS`).
     - Suppressed automatic conversion in `PaintingEntityHandler.onEntityLoad` and `PaintingConversionService.tryConvert` for restored entities across subsequent ticks.
     - Implemented atomic transactional rollback in `PaintingConversionService.tryRestore`: if `entitySpawner` / `addFreshEntity` fails, the anchor is restored first with its variant, followed by parts, fluids, and no item drops.
     - Documented safe uninstallation maintenance procedure in `README.md`.
  3. **Honest Coverage & Harness Hardening (1C):**
     - Replaced manual `removeFootprint()` in `testBackingWallDestruction` with `helper.succeedWhen()` testing true neighbor/update propagation.
     - Retained direct removal test as `testDirectFootprintRemoval`.
     - Eliminated swallowed initialization exceptions in `PaintingRenderPipelineTest`.
     - Added `PaintingPartBlockUnitTest` covering 16x16 maximum footprints, unloaded candidate chunk safety, proven orphan self-healing, and neighboring painting isolation.

- **Verification Results:**
  - `./gradlew test`: 7 unit tests passed across `PaintingRenderPipelineTest` and `PaintingPartBlockUnitTest`.
  - `./gradlew runGameTest`: 10 server GameTests passed (0 failures).
  - `./gradlew build`: Build successful, jar and sourcesJar generated.
