# Really Fast Paintings — Minecraft 26.3 Porting & API Delta Inventory

## Overview

- **Port Target:** Minecraft `26.3-rc-2`
- **Toolchain & Dependencies:**
  - Minecraft: `26.3-rc-2`
  - Fabric Loader: `0.19.3`
  - Fabric Loom: `1.17.20` (includes Fabric Loom PR #1624 native crash fix)
  - Fabric API: `0.160.4+26.3`
  - Java: `Zulu 25.0.4`
  - Gradle: `9.5.1`
  - Mod Version: `1.2.0-rc.2`
  - Optional Distant Decorations: `0.1.0` (compile-only, unchanged)
- **Source Generation:** Decompiled and generated with Vineflower via `./gradlew genSources`:
  - Common sources: `minecraft-common-6974b2190e-26.3-rc-2-sources.jar`
  - Client-only sources: `minecraft-clientOnly-6974b2190e-26.3-rc-2-sources.jar`

---

## API Delta Inventory

| Symbol | Current Use in 26.2 | Target Signature / Behavior in 26.3-rc-2 | Evidence | Necessary Adaptation (Phase 3/4) | Validating Test | Status |
| --- | --- | --- | --- | --- | --- | --- |
| `Block.simpleCodec` / `Block.codec()` | `PaintingBlock.CODEC`, `PaintingPartBlock.CODEC` | Method removed from `Block` and `BlockBehaviour`. Only `StateHolder` has `MapCodec`. | `net.minecraft.world.level.block.Block` in `minecraft-common` sources has no `codec()` method. | Remove static `CODEC` and `@Override codec()` from `PaintingBlock` and `PaintingPartBlock`. | `FastPaintingsGameTests.test1x1PlacementAndBreak` | **Confirmed Break** |
| `PushReaction.DESTROY` | Block properties in `PaintingBlock` and `PaintingPartBlock` | Enum constant renamed to `PushReaction.POPPED`. | `net.minecraft.world.level.material.PushReaction` defines `{ PUSH_PULL, PUSH, POPPED, IMMOVEABLE, IGNORE_ENTITY }`. | Replace `PushReaction.DESTROY` with `PushReaction.POPPED`. | `FastPaintingsGameTests.test1x1PlacementAndBreak` | **Confirmed Break** |
| `Projectile.mayBreak` | Checked on projectile collision in `PaintingBlock` and `PaintingPartBlock` | `boolean mayBreak(ServerLevel level, BlockPos pos)` takes `BlockPos` parameter for adventure mode checks. | `net.minecraft.world.entity.projectile.Projectile.java` line 67: `public boolean mayBreak(final ServerLevel level, final BlockPos pos)`. | Pass hit block position `hitResult.getBlockPos()` to `projectile.mayBreak(serverLevel, hitResult.getBlockPos())`. | `FastPaintingsGameTests` / projectile collision | **Confirmed Break** |
| `HangingEntityItemMixin` | Shadowed `type`, shadowed `mayPlace`, injection into `useOn(UseOnContext)` | Identical signature and behavior. | `HangingEntityItem.java` signatures unchanged in 26.3-rc-2. | None required. | `FastPaintingsGameTests.test1x1PlacementAndBreak` | **Unchanged** |
| `PaintingBlockEntity` persistence | `saveAdditional`, `loadAdditional`, `ValueInput`, `ValueOutput` | Identical signatures and contracts. | `BlockEntity.java` in 26.3-rc-2 retains identical `ValueInput`/`ValueOutput` APIs. | None required. | `FastPaintingsGameTests.test2x2MigrationAndRestore` | **Unchanged** |
| `PaintingBlockEntity` network sync | `getUpdatePacket`, `getUpdateTag`, `ClientboundBlockEntityDataPacket` | Identical signatures and contracts. | `BlockEntity.java` and `ClientboundBlockEntityDataPacket.java` unchanged. | None required. | Dedicated server GameTests | **Unchanged** |
| `PaintingConversionService` | Entity spawning, suppression tagging, staged recovery | Identical signatures and contracts. | `Painting.java` constructor and tags API unchanged. | None required. | `testRestorationCleanupFailurePreventsDuplicateRepresentations` | **Unchanged** |
| `PaintingPlacementService` | Coordinate arithmetic, footprint checking, placement ordering | Identical signatures and contracts. | `Level.setBlock` flags and `Direction` arithmetic unchanged. | None required. | `testPartAnchorResolutionAndBreakOutsideOrigin` | **Unchanged** |
| `PaintingMigrationCommand` | Section-level block state scan (`section.maybeHas`) | Identical signatures and contracts. | `LevelChunkSection.java` retains `maybeHas(Predicate<BlockState>)`. | None required. | `testStatsCountsAnchorBlockWithoutBlockEntity` | **Unchanged** |
| `BlockEntityRenderer` | `createRenderState()`, `extractRenderState(...)`, `submit(...)` | Identical signatures and contracts. | `BlockEntityRenderer.java` retains all three methods with identical parameter types. | None required. | Client render pipeline tests | **Unchanged** |
| `PaintingRenderer` (Vanilla) | `AtlasIds.PAINTINGS`, `submitNodeCollector.submitCustomGeometry` | Identical pipeline and vertex emission. | `net.minecraft.client.renderer.entity.PaintingRenderer.java` inspected. | None required. | Client visual inspection | **Unchanged** |

---

## Compilation Differences (Phase 2 Baseline Output)

Running `./gradlew compileJava` against `26.3-rc-2` isolated 10 compilation errors across 2 files, all directly corresponding to the 3 confirmed breaks above:

1. `PaintingBlock.java:46`: `cannot find symbol: method simpleCodec(PaintingBlock::new)`
2. `PaintingBlock.java:71`: `cannot find symbol: variable DESTROY in class PushReaction`
3. `PaintingBlock.java:75`: `method does not override or implement a method from a supertype: @Override public MapCodec<PaintingBlock> codec()`
4. `PaintingBlock.java:110`: `method mayBreak in class Projectile cannot be applied to given types (found: ServerLevel, required: ServerLevel, BlockPos)`
5. `PaintingBlock.java:148`: `method mayBreak in class Projectile cannot be applied to given types (found: ServerLevel, required: ServerLevel, BlockPos)`
6. `PaintingPartBlock.java:48`: `cannot find symbol: method simpleCodec(PaintingPartBlock::new)`
7. `PaintingPartBlock.java:68`: `cannot find symbol: variable DESTROY in class PushReaction`
8. `PaintingPartBlock.java:72`: `method does not override or implement a method from a supertype: @Override public MapCodec<PaintingPartBlock> codec()`
9. `PaintingPartBlock.java:93`: `method mayBreak in class Projectile cannot be applied to given types (found: ServerLevel, required: ServerLevel, BlockPos)`
10. `PaintingPartBlock.java:215`: `method mayBreak in class Projectile cannot be applied to given types (found: ServerLevel, required: ServerLevel, BlockPos)`

These 10 errors constitute the exact, complete inventory of common-side adaptations required for Phase 3.
