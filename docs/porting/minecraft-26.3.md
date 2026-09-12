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
| `Block.simpleCodec` / `Block.codec()` | `PaintingBlock.CODEC`, `PaintingPartBlock.CODEC` | Method removed from `Block` and `BlockBehaviour`. Only `StateHolder` has `MapCodec`. | `net.minecraft.world.level.block.Block` in `minecraft-common` sources has no `codec()` method. | Remove static `CODEC` and `@Override codec()` from `PaintingBlock` and `PaintingPartBlock`. | Phase 3 compilation & block registration check | **Observed compiler break** |
| `PushReaction.DESTROY` | Block properties in `PaintingBlock` and `PaintingPartBlock` | Enum constant renamed to `PushReaction.POPPED`. | `net.minecraft.world.level.material.PushReaction` defines `{ PUSH_PULL, PUSH, POPPED, IMMOVEABLE, IGNORE_ENTITY }`. | Replace `PushReaction.DESTROY` with `PushReaction.POPPED`. | Dedicated piston push tests for anchor and helper part (Phase 3) | **Observed compiler break** |
| `Projectile.mayBreak` | Checked on projectile collision in `PaintingBlock` and `PaintingPartBlock` | `boolean mayBreak(ServerLevel level, BlockPos pos)` takes `BlockPos` parameter for adventure mode checks. | `net.minecraft.world.entity.projectile.Projectile.java` line 67: `public boolean mayBreak(final ServerLevel level, final BlockPos pos)`. | Pass hit block position in hit callback (`pos`) and queried block position in collision check (`pos`) to `projectile.mayBreak(serverLevel, pos)`. | Dedicated projectile allowed/denied impact tests for anchor and part (Phase 3) | **Observed compiler break** |
| `HangingEntityItemMixin` | Shadowed `type`, shadowed `mayPlace`, injection into `useOn(UseOnContext)` | Declaration signatures match. | `HangingEntityItem.java` signatures in 26.3-rc-2 sources match. | None currently observed; verify at runtime. | Item-use placement game test on 26.3 runtime (Phase 3) | **Signature inspected; no change observed** |
| `PaintingBlockEntity` persistence | `saveAdditional`, `loadAdditional`, `ValueInput`, `ValueOutput` | Declaration signatures match. | `BlockEntity.java` in 26.3-rc-2 retains identical `ValueInput`/`ValueOutput` APIs. | None currently observed; verify at runtime. | Dedicated NBT/disk save & reload game test (Phase 3) | **Signature inspected; no change observed** |
| `PaintingBlockEntity` network sync | `getUpdatePacket`, `getUpdateTag`, `ClientboundBlockEntityDataPacket` | Declaration signatures match. | `BlockEntity.java` and `ClientboundBlockEntityDataPacket.java` match. | None currently observed; verify at runtime. | Client/server packet synchronization test (Phase 3) | **Signature inspected; no change observed** |
| `PaintingConversionService` | Entity spawning, suppression tagging, staged recovery | Declaration signatures match. | `Painting.java` constructor and tags API match. | None currently observed; verify at runtime. | Full conversion & staged restoration test suite (Phase 3) | **Signature inspected; no change observed** |
| `PaintingPlacementService` | Coordinate arithmetic, footprint checking, placement ordering | Declaration signatures match. | `Level.setBlock` flags and `Direction` arithmetic match. | None currently observed; verify at runtime. | Multipart placement regression suite (Phase 3) | **Signature inspected; no change observed** |
| `PaintingMigrationCommand` | Section-level block state scan (`section.maybeHas`) | Declaration signatures match. | `LevelChunkSection.java` retains `maybeHas(Predicate<BlockState>)`. | None currently observed; verify at runtime. | Diagnostic chunk scan regression suite (Phase 3) | **Signature inspected; no change observed** |
| `BlockEntityRenderer` | `createRenderState()`, `extractRenderState(...)`, `submit(...)` | Declaration signatures match. | `BlockEntityRenderer.java` retains all three methods with identical parameter types. | None currently observed; client compilation pending. | Client compile & render pipeline tests (Phase 4) | **Signature inspected; no change observed** |
| `PaintingRenderer` (Vanilla) | `AtlasIds.PAINTINGS`, `submitNodeCollector.submitCustomGeometry` | Pipeline structure matches. | `net.minecraft.client.renderer.entity.PaintingRenderer.java` inspected. | None currently observed; client compilation pending. | Client visual inspection & LOD transition tests (Phase 4) | **Signature inspected; no change observed** |

---

## Compilation Differences (Phase 2 Baseline Output)

Running `./gradlew compileJava` against `26.3-rc-2` isolated 10 compilation errors across 2 files, all directly corresponding to the 3 confirmed compiler breaks above:

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

These are the common-side compiler errors observed in this initial Phase 2 build invocation. Substantive code adaptations, secondary compiler passes, and runtime behavior verification will be carried out in Phase 3.
