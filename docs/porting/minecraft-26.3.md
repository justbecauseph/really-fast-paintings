# Really Fast Paintings — Minecraft 26.3 Porting & API Delta Inventory

## Overview

- **Port Target:** Minecraft `26.3-rc-2`
- **Toolchain & Dependencies:**
  - Minecraft: `26.3-rc-2`
  - Fabric Loader: `0.19.5`
  - Fabric Loom: `1.17.20` (includes Fabric Loom PR #1624 native crash fix)
  - Fabric API: `0.160.4+26.3`
  - Java: `Zulu 25.0.4`
  - Gradle: `9.5.1`
  - Mod Version: `1.2.0-rc.2`
  - Optional Distant Decorations: `0.1.0` (compile-only, unchanged)
- **Source Generation:** Decompiled and generated with Vineflower via `./gradlew genSources`:
  - Common sources: `minecraft-common-6974b2190e-26.3-rc-2-sources.jar` (5,037 classes)
  - Client-only sources: `minecraft-clientOnly-6974b2190e-26.3-rc-2-sources.jar` (2,264 classes)
- **Metadata Verification:** Verified via `./gradlew verifyMetadata` (depends on `processResources`, runs without requiring common compilation):
  - Validates absence of unresolved `${...}` placeholders in processed `build/resources/main/fabric.mod.json`.
  - Validates mod ID (`fastpaintings`) and version (`1.2.0-rc.2`).
  - Validates `minecraft` predicate strictly accepts `26.3-rc-2` and rejects `26.2`, `26.1`, `26.3-rc-1`, `26.3-rc-3`, `26.3-pre-3`, `26.3` (final release), and `26.4`.
  - Validates `fabricloader` predicate enforces `>=0.19.5` (accepts `0.19.5`, `0.19.6`; rejects `0.19.4`, `0.19.3`, `0.18.4`).
  - Validates `fabric-api` predicate enforces `>=0.160.4`.

---

## API Delta Inventory

| Symbol | Current Use in 26.2 | Target Signature / Behavior in 26.3-rc-2 | Evidence | Necessary Adaptation (Phase 3/4) | Validating Test Plan | Status |
| --- | --- | --- | --- | --- | --- | --- |
| `Block.simpleCodec` / `Block.codec()` | `PaintingBlock.CODEC`, `PaintingPartBlock.CODEC` | Method removed from `Block` and `BlockBehaviour`. Only `StateHolder` has `MapCodec`. | `net.minecraft.world.level.block.Block` in `minecraft-common` sources has no `codec()` method. | Remove static `CODEC` and `@Override codec()` from `PaintingBlock` and `PaintingPartBlock`. | Phase 3 compilation & block registration check | **Resolved in Phase 3** |
| `PushReaction.DESTROY` | Block properties in `PaintingBlock` and `PaintingPartBlock` | Enum constant renamed to `PushReaction.POPPED`. | `net.minecraft.world.level.material.PushReaction` defines `{ PUSH_PULL, PUSH, POPPED, IMMOVEABLE, IGNORE_ENTITY }`. | Replace `PushReaction.DESTROY` with `PushReaction.POPPED`. | Unit tests in `PaintingPartBlockUnitTest` & GameTests `testPistonPopsAnchorBlock` / `testPistonPopsHelperPartAndCleansFootprint` | **Resolved in Phase 3** |
| `Projectile.mayBreak` | Checked on projectile collision in `PaintingBlock` and `PaintingPartBlock` | `boolean mayBreak(ServerLevel level, BlockPos pos)` takes `BlockPos` parameter for adventure mode checks. | `net.minecraft.world.entity.projectile.Projectile.java` line 67: `public boolean mayBreak(final ServerLevel level, final BlockPos pos)`. | Pass hit block position in hit callback (`pos`) and queried block position in collision check (`pos`) to `projectile.mayBreak(serverLevel, pos)`. | GameTest `testProjectileAllowedAndDeniedImpactOnAnchorAndPart` validating allowed/denied impact on anchor and helper part with local position verification | **Resolved in Phase 3** |
| `PoseStack.mulPose(Quaternionf)` | Rotations in `PaintingBlockRenderer` & `FastPaintingDistantDecorationRenderer` | Replaced by `poseStack.rotateDegrees(Axis, float)` and `poseStack.rotate(Quaternionfc)`. | `com.mojang.blaze3d.vertex.PoseStack` in `minecraft-clientOnly` sources. | Call `poseStack.rotateDegrees(Axis.YP, ...)` directly. | Client compilation and GameTest execution | **Resolved in Phase 3** |
| `HangingEntityItemMixin` | Shadowed `type`, shadowed `mayPlace`, injection into `useOn(UseOnContext)` | Declaration signatures match. | `HangingEntityItem.java` signatures in 26.3-rc-2 sources match. | None required; verified at runtime. | Item-use placement game test on 26.3 runtime (`testPlayerSurvivalPlacementConsumesItem`, etc.) | **Verified at runtime** |
| `PaintingBlockEntity` persistence | `saveAdditional`, `loadAdditional`, `ValueInput`, `ValueOutput` | Declaration signatures match. | `BlockEntity.java` in 26.3-rc-2 retains identical `ValueInput`/`ValueOutput` APIs. | None required; verified at runtime. | Dedicated NBT/disk save & reload game test (`testEntityPersistenceRoundtrip`) | **Verified at runtime** |
| `PaintingBlockEntity` network sync | `getUpdatePacket`, `getUpdateTag`, `ClientboundBlockEntityDataPacket` | Declaration signatures match. | `BlockEntity.java` and `ClientboundBlockEntityDataPacket.java` match. | None required; verified at runtime. | Client/server packet synchronization test suite | **Verified at runtime** |
| `PaintingConversionService` | Entity spawning, suppression tagging, staged recovery | Declaration signatures match. | `Painting.java` constructor and tags API match. | None required; verified at runtime. | Full conversion & staged restoration test suite (`test2x2MigrationAndRestore`, etc.) | **Verified at runtime** |
| `PaintingPlacementService` | Coordinate arithmetic, footprint checking, placement ordering | Declaration signatures match. | `Level.setBlock` flags and `Direction` arithmetic match. | None required; verified at runtime. | Multipart placement regression suite across all 4 facings | **Verified at runtime** |
| `PaintingMigrationCommand` | Section-level block state scan (`section.maybeHas`) | Declaration signatures match. | `LevelChunkSection.java` retains `maybeHas(Predicate<BlockState>)`. | None required; verified at runtime. | Diagnostic chunk scan regression suite (`testStatsCountsAnchorBlockWithoutBlockEntity`, etc.) | **Verified at runtime** |
| `BlockEntityRenderer` | `createRenderState()`, `extractRenderState(...)`, `submit(...)` | Declaration signatures match. | `BlockEntityRenderer.java` retains all three methods with identical parameter types. | None required; client compilation verified. | Client compile & render pipeline tests (Phase 4) | **Inspected; verified in Phase 3 compilation** |
| `PaintingRenderer` (Vanilla) | `AtlasIds.PAINTINGS`, `submitNodeCollector.submitCustomGeometry` | Pipeline structure matches. | `net.minecraft.client.renderer.entity.PaintingRenderer.java` inspected. | None required; client compilation verified. | Client visual inspection & LOD transition tests (Phase 4) | **Inspected; verified in Phase 3 compilation** |

---

## Phase 3 Execution & Test Results

### 1. Code Adaptations
- **Block Codec Removal:** Removed obsolete static `CODEC` and `@Override protected MapCodec codec()` from `PaintingBlock.java` and `PaintingPartBlock.java`.
- **PushReaction Invariant:** Updated block definition `.pushReaction(PushReaction.DESTROY)` to `.pushReaction(PushReaction.POPPED)` in both `PaintingBlock.java` and `PaintingPartBlock.java`.
- **Projectile mayBreak Signature:** Updated all 4 call sites in `PaintingBlock.java` (`getCollisionShape`, `onProjectileHit`) and `PaintingPartBlock.java` (`getCollisionShape`, `onProjectileHit`) to pass the local block position: `projectile.mayBreak(serverLevel, pos)`. Made `getCollisionShape` and `onProjectileHit` `public` to allow direct, exhaustive testing.
- **Client Renderer Rotations:** Adapted `FastPaintingDistantDecorationRenderer.java` and `PaintingBlockRenderer.java` from `poseStack.mulPose(Axis.YP.rotationDegrees(...))` to `poseStack.rotateDegrees(Axis.YP, ...)`.
- **Dependency Normalization:** Set `minecraft_dependency=26.3-rc.2` in `gradle.properties` to align with Fabric Loader's normalized SemVer scheme (`26.3-rc.2`), resolving loader runtime dependency resolution.

### 2. Test Verification
- **`./gradlew verifyMetadata`:** All 4 tests PASS. Validates zero unexpanded `${...}` placeholders, mod ID `fastpaintings`, version `1.2.0-rc.2`, strict acceptance of `26.3-rc.2`, rejection of older/other RCs/final releases, `fabricloader >=0.19.5`, and `fabric-api >=0.160.4`.
- **`./gradlew test`:** All 10 unit test suites PASS, including `PaintingPartBlockUnitTest.testPushReactionPopped` asserting `PushReaction.POPPED` on both block states.
- **`./gradlew runGameTest`:** All 26 GameTests PASS in 1.2-1.4s, including:
  - `testPistonPopsAnchorBlock`: Piston extension pops 1x1 anchor block (`PushReaction.POPPED`), removing the block and dropping the painting item.
  - `testPistonPopsHelperPartAndCleansFootprint`: Piston extension pops helper part block, triggering `affectNeighborsAfterRemoval` which cleans up the entire 2x2 footprint (anchor + helper parts) and drops the painting item.
  - `testProjectileAllowedAndDeniedImpactOnAnchorAndPart`: Validates local position checks and allowed/denied behavior. When `GameRules.PROJECTILES_CAN_BREAK_BLOCKS` is `false`, `mayBreak` is false, collision shapes are empty (pass-through), and hits on anchor and helper parts leave the painting intact. When enabled, `mayBreak` is true, collision shapes are solid, hits on helper parts remove the full footprint with item drop, and hits on anchors destroy the anchor with item drop.
