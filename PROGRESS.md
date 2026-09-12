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

- **Commits:**
  - `6ccddc9`: `fix: resolve multipart painting anchors outside world origin`
  - `afa0d9e`: `test: cover callback removal and multi-tick restoration`
  - `105191d`: `fix: address reproduced restoration safety failures`
  - Pending Commit: `fix: harden restoration safety, uninstallation scope, and honest test harness`

- **Modified Files:**
  - `src/main/java/me/justbecause/fastpaintings/block/PaintingPartBlock.java`
  - `src/main/java/me/justbecause/fastpaintings/block/entity/PaintingBlockEntity.java`
  - `src/main/java/me/justbecause/fastpaintings/painting/PaintingPlacementService.java`
  - `src/main/java/me/justbecause/fastpaintings/painting/PaintingConversionService.java`
  - `src/main/java/me/justbecause/fastpaintings/painting/RestorationException.java`
  - `src/main/java/me/justbecause/fastpaintings/command/PaintingMigrationCommand.java`
  - `src/main/java/me/justbecause/fastpaintings/event/PaintingEntityHandler.java`
  - `src/gametest/java/me/justbecause/fastpaintings/test/FastPaintingsGameTests.java`
  - `src/test/java/me/justbecause/fastpaintings/block/PaintingPartBlockUnitTest.java`
  - `src/test/java/me/justbecause/fastpaintings/client/render/PaintingRenderPipelineTest.java`
  - `README.md`

- **Key Implementations & Hardening:**
  1. **Anchor Search & Bounded Lookup (1A):**
     - Updated `PaintingPartBlock.findAnchorLookup()` to compute candidate coordinates: `partPos.getX() + left.getStepX() * dx, partPos.getY() + dy, partPos.getZ() + left.getStepZ() * dx`.
     - Added `AnchorLookupResult` (`Found`, `Unloaded`, `Orphan`) distinguishing uninspected unloaded candidates from proven orphans.
     - Prevented orphan destruction and forced chunk loads when candidate chunks are unloaded.
     - Fixed placement order in `PaintingPlacementService`: anchor is placed first and BE variant set before placing part blocks.
  2. **Failure-Safe Multi-Tick Restoration & Atomic Rollback (1B):**
     - Implemented `PaintingConversionService.RESTORED_TAG` (`"fastpaintings:restored"`) and transient UUID suppression set (`RESTORED_UUIDS`).
     - Added `PaintingConversionService.tryConvert(painting, level, allowSuppressedOverride)` enabling operator `/fastpaintings convert` to clear suppression markers while automatic entity load continues to respect them.
     - Wrapped entity spawning in `tryRestore()` with try-catch and discarded partially added entities (`painting.discard()`) on failure.
     - Implemented `rollbackRestoration()` with verified postconditions (exact block states, waterlogged fluid states, facing, BE variant, and zero item drops). Throws `RestorationException` if rollback verification fails; rethrows spawner exceptions if rollback succeeds.
     - Added `PaintingBlockEntity.setRemoving(boolean)` guarding `preRemoveSideEffects()` to ensure transactional rollback in `PaintingPlacementService` produces zero item drops.
  3. **Migration Commands & Safe Uninstallation Disclosure:**
     - Updated `PaintingMigrationCommand.runStats()` to count and report loaded-only objects: vanilla painting entities, block painting anchors, and helper parts across loaded chunks.
     - Added explicit command scope disclaimers in commands and `README.md`: commands inspect only loaded chunks around active players (or spawn chunks), not offline world saves.
     - Updated `README.md` uninstallation procedure: requires world backup, disabling `convertExistingPaintings`, `convertCommandCreatedPaintings`, and `convertOnPlacement`, visiting all populated areas, and verifying 0 anchors and helper parts via `/fastpaintings stats` before mod removal.
  4. **Test Suite Hardening Without Bypasses (1C):**
     - `PaintingPartBlockUnitTest`: Implemented a chunk-granular test stub where reading an unloaded chunk throws an immediate `AssertionError`, proving reads are never attempted in unloaded territory.
     - `testBackingWallDestruction`: Asserts placement and anchor BE exist prior to removing backing wall, using bounded wait for neighbor propagation.
     - `testPartAnchorResolutionAndBreakOutsideOrigin`: Triggers real block destruction (`helper.destroyBlock()`) at part position; asserts adjacent painting survives intact and drops equal exactly 1.
     - `testCreativePartBreakZeroDrops`: Destroys part with creative mock server player; asserts all cells cleared with 0 drops.
     - `testAnchorResolutionAllFacings`: Tests all 4 horizontal facings (NORTH, SOUTH, EAST, WEST) across both even and odd dimension parity (`POOL` 2x1 and `WANDERER` 1x2).
     - `testPlacementFailureRollbackZeroDrops`: Injects write failure after anchor and part placement; asserts full rollback to AIR with 0 drops.
     - `testOrphanNeighborUpdateSelfHealing`: Places lone part block, triggers neighbor change, asserts self-healing to AIR with 0 drops.
     - `testRestorationThrowingSpawnRollback`: Injects throwing spawner on waterlogged multipart painting; asserts exact block, fluid, and variant restoration with 0 drops and no leaked entities.
     - `testOperatorReconversionOverridesSuppression`: Proves operator reconversion clears restoration suppression and converts entity.

- **Verification Evidence:**
  - `./gradlew test`: 54 unit tests passed (0 failures):
    - `me.justbecause.fastpaintings.block.PaintingPartBlockUnitTest` (4 tests)
    - `me.justbecause.fastpaintings.painting.PaintingFootprintTest` (31 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingLodTest` (12 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingRenderMetricsTest` (4 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingRenderPipelineTest` (3 tests)
  - `./gradlew runGameTest`: 15 server GameTests passed (14 mod tests in `FastPaintingsGameTests` + 1 Fabric API test, 0 failures):
    1. `test1x1PlacementAndBreak`
    2. `test2x2MigrationAndRestore`
    3. `testWaterloggedPreservation`
    4. `testBackingWallDestruction`
    5. `testDirectFootprintRemoval`
    6. `testPartAnchorResolutionAndBreakOutsideOrigin`
    7. `testCreativePartBreakZeroDrops`
    8. `testAnchorResolutionAllFacings`
    9. `testMultiTickRestorationAcrossTicks`
    10. `testRestorationEntityAddFailureRollback`
    11. `testPlacementFailureRollbackZeroDrops`
    12. `testOrphanNeighborUpdateSelfHealing`
    13. `testRestorationThrowingSpawnRollback`
    14. `testOperatorReconversionOverridesSuppression`
  - `./gradlew build`: Build successful, generated distributable `build/libs/fastpaintings-1.0.0.jar` and `build/libs/fastpaintings-1.0.0-sources.jar`.
