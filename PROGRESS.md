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
  - `84c3faf`: `fix: address remaining review items`
  - `9219c07`: `fix: enforce safe restoration rollback, complete footprint cleanup, and chunk-granular diagnostics`
  - `7baecfe`: `fix: separate cleanup and rollback recovery stages and enumerate anchor block states`

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
  2. **Failure-Safe Restoration Recovery Boundary & Staged Rollback (1B):**
     - Expanded recovery boundary in `PaintingConversionService.tryRestore()` to begin before destructive footprint removal (`footprintRemover.accept`).
     - Added pre-spawn footprint verification: validates that all footprint cells were cleared of `PAINTING_BLOCK` and `PAINTING_PART_BLOCK` before attempting entity spawning; aborts on incomplete removal without invoking the spawner.
     - Separated Stage 1 (entity reconciliation and cleanup) from Stage 2 (footprint rollback) in `finally`.
     - Added `EntityCleaner` functional interface and `defaultEntityCleanup(Painting, ServerLevel)`.
     - Verified attempted entity status before rollback: if entity cleanup fails and the attempted vanilla entity remains active in the level, footprint rollback is skipped to prevent duplicate representations (entity + block), throwing a location-bearing `RestorationException` preserving both the primary and cleanup errors.
     - Implemented `RollbackHandler` functional interface allowing deterministic testing of verification and I/O error handling during rollback. Made `rollbackRestoration` `public static` and added overloaded `tryRestore` methods.
     - On rollback write failure, throws `RestorationException` containing anchor position, cause, and attaches the original failure as a suppressed exception.
     - Restores operational removal-guard state (`restoredBe.setRemoving(false)`) on surviving anchors.
     - Preserved `RESTORED_TAG` and UUID suppression during failed operator reconversion attempts (e.g. special data obstacle) until conversion commits successfully.
  3. **Migration Commands & Section-Granular State Auditing:**
     - Updated `PaintingMigrationCommand.scanChunk(LevelChunk chunk)` to scan chunk sections via `section.maybeHas(...)` and iterate intra-section blocks `(0..15)` for both `PAINTING_BLOCK` anchor states and `PAINTING_PART_BLOCK` helper states, never reading across chunk boundaries into unloaded chunks.
     - Separated anchor block state counts (`scanChunk(chunk).anchorBlocks()`) from instantiated block entities (`scanChunk(chunk).anchorBlockEntities()`), ensuring missing-BE anchor states never report false clean (0 anchors).
     - Retained block entity count as a visible diagnostic in `/fastpaintings stats`: `Block painting anchors: %d (BlockEntities: %d)`.
     - Clarified command scopes in console/player messages and `README.md`: `/fastpaintings convert` checks all loaded entities across dimensions; `/fastpaintings stats` and `/fastpaintings restore` inspect loaded chunks around active players/spawn without scanning offline region files or unloaded chunks.
  4. **Test Suite Hardening & Honesty (1C):**
     - `PaintingPartBlockUnitTest`: Renamed `testAnchorLookupCandidateMakesNoReadsIntoUnloadedChunks`, verifying candidate lookup makes zero reads into unloaded chunks using chunk-granular stub assertions.
     - `testPartAnchorResolutionAndBreakOutsideOrigin`: Asserts both `drops.size() == 1` and `totalDropCount == 1`.
     - `testStatsCountsOrphanHelperPartsWithoutAnchors`: Proves orphan helper parts without anchors are accurately counted in chunk stats.
     - `testRestorationRemovalThrowsRollback`: Removal throws after cell mutation with removal guard active; asserts footprint recovered, zero entity leaks, drops are empty, and surviving anchor operational (`!isRemoving`).
     - `testRestorationIncompleteRemovalAborts`: Rejected removal aborts without invoking spawner.
     - `testRestorationSpawnerThrowingWithInsertedEntity`: Spawner inserts entity (`added == true`, `level.getEntity == painting`, `painting.isAlive()`) then throws; asserts attempted entity is discarded (0 leaked entities) and block painting restored.
     - `testRestorationRollbackFailureThrows`: Rollback write throws; asserts `RestorationException` with location, cause, and suppressed original error.
     - `testRestorationCleanupFailurePreventsDuplicateRepresentations`: Spawner throws with inserted entity and cleanup throws leaving entity active; asserts `RestorationException` with location and preserved errors, verifies footprint rollback is skipped so no duplicate block representation is created, and confirms active entity survives until teardown.
     - `testStatsCountsAnchorBlockWithoutBlockEntity`: Fixture with `PAINTING_BLOCK` state and no `BlockEntity` in chunk; asserts anchor count == 1 and anchor BE count == 0.
     - `testOperatorReconversionPreservesSuppressionOnFailure`: Failed operator reconversion preserves suppression markers; removing obstacle permits conversion to succeed.

- **Verification Evidence:**
  - `./gradlew test`: 67 unit tests passed (0 failures):
    - `me.justbecause.fastpaintings.block.PaintingPartBlockUnitTest` (5 tests)
    - `me.justbecause.fastpaintings.painting.PaintingFootprintTest` (31 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingLodTest` (12 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingLodManagerTest` (8 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingRenderMetricsTest` (4 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingRenderPipelineTest` (3 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingInstrumentationTest` (2 tests)
    - `me.justbecause.fastpaintings.client.render.PaintingLightingCacheTest` (2 tests)
  - `./gradlew runGameTest`: 23 server GameTests passed (22 mod tests in `FastPaintingsGameTests` + 1 Fabric API test, 0 failures):
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
    15. `testStatsCountsOrphanHelperPartsWithoutAnchors`
    16. `testRestorationRemovalThrowsRollback`
    17. `testRestorationIncompleteRemovalAborts`
    18. `testRestorationSpawnerThrowingWithInsertedEntity`
    19. `testRestorationRollbackFailureThrows`
    20. `testOperatorReconversionPreservesSuppressionOnFailure`
    21. `testRestorationCleanupFailurePreventsDuplicateRepresentations`
    22. `testStatsCountsAnchorBlockWithoutBlockEntity`
  - `./gradlew build`: Build successful, generated distributable `build/libs/fastpaintings-1.0.0.jar` and `build/libs/fastpaintings-1.0.0-sources.jar`.
