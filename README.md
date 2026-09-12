# Really Fast Paintings

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-brightgreen.svg)](https://minecraft.net/)
[![Fabric API](https://img.shields.io/badge/Fabric%20API-0.158.0%2B26.2-blue.svg)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MPL--2.0-blue.svg)](LICENSE)

**Really Fast Paintings** is a Fabric performance mod for Minecraft 26.2 that replaces vanilla `Painting` entities with non-ticking, block-backed decorations while preserving vanilla painting variants, placement behavior, collision/raycast interaction, support checks, and datapack-defined painting dimensions.

Each converted painting uses a single logical `PaintingBlockEntity` at its anchor and lightweight non-ticking part blocks for the rest of its footprint. This removes the need to keep a full entity alive for every painting while still allowing large paintings to behave like normal wall decorations.

---

## 🌟 Key Features

- **Block-Backed Paintings**: Converts exact vanilla `Painting` entities into block-backed decorations with one logical block entity per painting.
- **No Painting Entity Tick Cost**: Converted paintings no longer remain as ticking vanilla entities.
- **Compact Multipart Footprints**:
  - One anchor `PaintingBlockEntity` stores the painting state.
  - Remaining occupied tiles use lightweight `PaintingPartBlock` states.
  - Part blocks encode `OFFSET_X`, `OFFSET_Y`, and `FACING`, allowing the anchor position to be reconstructed with direct arithmetic rather than lookup maps.
- **Vanilla-Compatible Geometry**: `PaintingFootprint` mirrors vanilla painting bounding-box and hanging-support calculations, including multi-block collision/raycast coverage and backing-block validation.
- **Datapack-Compatible Variants**: Painting dimensions and assets are resolved dynamically from `Registries.PAINTING_VARIANT`, supporting variants from datapacks without hard-coded size tables.
- **Variant-Preserving Drops**: Broken paintings can retain their exact `minecraft:painting_variant` data component and restore the same variant when placed again.
- **Safe Migration Tools**: Existing vanilla paintings can be converted automatically or manually, and block-backed paintings can be restored to vanilla entities before uninstalling the mod.

---

## 🎨 Client Rendering & LOD

Really Fast Paintings uses a custom `PaintingBlockRenderer` with projected-screen-size LOD selection and hysteresis to reduce geometry and lighting work as paintings become smaller on screen.

| LOD | Representation |
| :--- | :--- |
| **FULL** | Full segmented front/back/edge geometry with per-block lighting. |
| **SIMPLIFIED** | Simplified box-style geometry with a single light sample. |
| **FAR** | Single front-facing quad with one light sample. |
| **SKIP** | Painting is not submitted when its projected size is below the active threshold. |

The default projected-size thresholds are approximately:

- `FULL`: 64 px and above
- `SIMPLIFIED`: 12–64 px
- `FAR`: 1–12 px
- `SKIP`: below 1 px

Promotion/demotion hysteresis uses separate thresholds to reduce visible LOD flicker while moving toward or away from paintings.

---

## 🌄 Distant Decorations Integration

Really Fast Paintings has optional integration with **[Distant Decorations](https://github.com/justbecauseph/distant-decorations)**.

When Distant Decorations is installed:

- Really Fast Paintings registers its own lightweight server-side decoration provider.
- Only painting metadata is synchronized through DD: asset ID, facing, width, and height.
- Physical bounds are calculated from the same `PaintingFootprint` geometry used by the live painting implementation.
- The distant client renderer uses the vanilla painting atlas and submits a single front-facing quad.
- The DD renderer opts into a `0.01 px` projected-size culling threshold and can apply up to `16×` visual-only far-LOD scaling to keep extremely distant paintings rasterizable without changing their authoritative physical AABB.

Distant Decorations is optional; Really Fast Paintings runs normally without it.

---

## ⚙️ Configuration

Configuration is stored in:

```text
config/fastpaintings.json
```

| Option | Default | Description |
| :--- | :---: | :--- |
| `preserveVariantOnDrop` | `true` | Preserve the exact painting variant when a converted painting is broken and dropped. |
| `convertExistingPaintings` | `true` | Convert vanilla paintings loaded from disk when they enter loaded chunks. |
| `convertOnPlacement` | `true` | Place block-backed paintings directly when a painting item is used. |
| `convertCommandCreatedPaintings` | `true` | Convert newly created vanilla painting entities, including command-created paintings. |
| `skipSpecialEntityData` | `true` | Leave paintings with special entity data such as custom names, invulnerability, or glowing state as vanilla entities. |
| `debugInstrumentation` | `false` | Enables additional renderer instrumentation counters for profiling/debugging. |

---

## 🛠️ Commands

All Really Fast Paintings commands require gamemaster-level command permission.

- `/fastpaintings stats`
  - Displays the total number of currently loaded vanilla painting entities across all worlds.
- `/fastpaintings convert`
  - Attempts to convert currently loaded vanilla painting entities across all dimensions.
- `/fastpaintings restore`
  - Restores loaded block-backed paintings to vanilla `Painting` entities for migration or safe uninstallation.

### ⚠️ Safe Uninstallation Procedure

To uninstall Really Fast Paintings cleanly from a world without losing placed paintings:

1. In `config/fastpaintings.json`, set `"convertExistingPaintings": false` and `"convertCommandCreatedPaintings": false` and save the configuration.
2. Load the world and run `/fastpaintings restore` across all areas where block-backed paintings exist. This restores them to vanilla `Painting` entities with persistent suppression markers.
3. Verify via `/fastpaintings stats` that no unconverted block-backed paintings remain.
4. Shut down the server/client and safely remove the Really Fast Paintings mod JAR.

---

## 🧱 Architecture

A converted painting is represented as:

```text
Painting footprint
├── 1 × PaintingBlockEntity anchor
└── N × PaintingPartBlock tiles
```

The anchor stores the logical painting state and variant. Part blocks store only enough block-state information to reconstruct their anchor position in constant time.

`PaintingFootprint` is the shared geometry authority for:

- painting world-space bounds
- occupied cells
- backing/support cells
- anchor/part coordinate conversion
- vanilla-compatible support validation

This keeps rendering, interaction, conversion, and Distant Decorations metadata aligned to the same physical footprint.

---

## 📦 Building from Source

```bash
git clone https://github.com/justbecauseph/fast-paintings.git
cd fast-paintings
./gradlew build
```

The compiled mod JAR will be located in:

```text
build/libs/
```

The project targets:

- Minecraft `26.2`
- Fabric Loader `0.19.3`
- Fabric API `0.158.0+26.2`
- Java `25`

---

## 📄 License

Really Fast Paintings is licensed under the [Mozilla Public License 2.0 (MPL-2.0)](LICENSE).
