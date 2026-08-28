# ARCHITECTURE.md — the multi-game layering

CTRMap-F5's long-term goal is one shared editor for the 3DS Pokémon games:
**X/Y** and **ORAS** (Gen 6), **Sun/Moon** and **USUM** (Gen 7). Today ORAS is
the reference game (everything is built and corpus-verified against it); this
document defines the layering that keeps the shared engine game-agnostic so
the other games can be added without untangling anything.

## The three layers

```
┌────────────────────────────────────────────────────────────┐
│ per-game profiles      src/ctrmap/gamedef/                 │
│   OrasProfile · XyProfile · SmProfile · UsumProfile        │
│   archive paths · text indices · feature gates · detection │
├────────────────────────────────────────────────────────────┤
│ format layers                                              │
│   Gen 6 (BCH/"H3D", shared by XY+ORAS):                    │
│     formats/h3d (models) · formats/tilemap (painter)       │
│     formats/area (fog, world animations) · gfcollision     │
│   Gen 7 (GFModel/GFMotion — DOES NOT EXIST YET):           │
│     to be built when SM/USUM work starts                   │
├────────────────────────────────────────────────────────────┤
│ universal engine (every 3DS Pokémon game)                  │
│   formats/garc (GARC+LZ11) · formats/containers (AD/GR/ZO) │
│   formats/text (message cipher) · scripts (VM + GfHash)    │
│   patricia dicts · Workspace/pack machinery · all UI       │
└────────────────────────────────────────────────────────────┘
```

## The seam rule (enforced)

**No RomFS path, GameText entry index, or other game-detected constant may
live anywhere outside `ctrmap.gamedef`.** `SourceSeamTest` (in the battery)
scans every non-test source's string literals and fails the build on a leak.
Callers ask the active profile:

```java
Workspace.profile().archivePath(ArchiveType.PERSONAL)      // or null
Workspace.profile().textIndex(GameProfile.TextIndex.SPECIES_NAMES)  // or -1
Workspace.profile().supports(GameProfile.Feature.TILE_PAINTER)
```

A profile answering null/-1/false means "absent or not yet verified for this
game" — callers must degrade gracefully, never guess. Every number IN a
profile must be measured against that game's dump (or cited from an
established reference like pk3DS's GARCReference tables, and commented so).

### What deliberately stays OUTSIDE gamedef

- **Struct layouts and measured invariants inside format classes**
  (EncounterTable's 260-byte record, AreaEnv's fog offsets, WorldAnim's
  relocation rules, TilePalette's tuple table, ZoneLimitPatch's code.bin
  addresses). A format class IS the implementation of one game family's
  format — splitting its offsets into a profile would just scatter it.
  When a second game needs a different layout, give it its own class (or a
  parameterized reader) behind the same interface, selected by profile.
- **`Workspace.isOA()`-style gates in editors.** They are the current form of
  feature gating; migrate them to `profile().supports(Feature.X)` lazily, as
  each feature is actually verified on a second game.
- Corpus sizes quoted in comments/UI text (536 zones, 857 regions…) — they
  document ORAS measurements and move to profiles only when a second game's
  numbers exist to compare against.

## Known duplication / cleanup candidates (pre-existing)

- `ParserLoader` and `GRColorPalette` duplicate ~55 TILE_* collision
  constants verbatim.
- `ZoneCloner.ZONE_HEADER_SIZE` / `GeometryForker.MASTER_ROW` /
  `ZoneLimitPatch.MASTER_ROW` are three names for the ORAS zone-header size
  (0x38).
- Trainer/Maison dialogs each carry a private GAMETEXT reader helper.
- `ExtrasPanel` injects the XY Lumiose camera-collision dummy into every
  AreaData regardless of game (upstream behavior, unreviewed).

## Porting recipe — X/Y (Gen 6 sibling; a port, not a rewrite)

1. Drop the X (or Y) dump at `dumps/XY/` (see "What to upload" below).
2. Run the corpus suites against the XY GARCs (they take dump paths as
   args) — each suite's pass/fail maps directly to a format-layer claim.
3. Fill `XyProfile`: trainer/Maison-equivalent/PERSONAL/MOVE_DATA paths,
   the remaining `TextIndex` entries — measured, or pk3DS-cited.
4. Re-measure the per-game invariants the Gen 6 layer asserts (tilemap
   tuples, AreaEnv offsets, WorldAnim header constants, zone-header bit
   layouts — `ZoneHeader` already branches XY/ORAS in places).
5. Flip `XyProfile.supports(...)` flags one feature at a time, each only
   after its suite passes on the XY corpus AND an in-emulator check.

## Porting outline — Sun/Moon and USUM (Gen 7; a new format layer)

Gen 7 kept GARC (a newer revision), LZ11 and the text cipher, but replaced
BCH with GFModel/GFMotion and restructured the overworld. The work order:

1. Dump survey: identify the zone/area/map archives (pk3DS's SM/USUM
   references are the starting map), fill `SmProfile`/`UsumProfile` paths +
   detection probes.
2. Verify the universal layer holds (GARC version, containers, text) —
   corpus round-trip suites, same methodology as ORAS.
3. Build the GFModel/GFMotion layer (a sibling of `formats/h3d`) with
   lossless round-trip as the acceptance gate, then port the editors that
   sit on top feature by feature.

## What to upload (for the user)

One version per pair is enough (X *or* Y, Sun *or* Moon, US *or* UM); both
is fine. From each legal cart/eShop copy, dump with GodMode9 (same procedure
that produced the ORAS dump):

```
3DS Editor/
  RomFS_original_garcs/          (ORAS — already present)
  dumps/
    XY/romfs/a/...               (the whole a/ tree)
    XY/exefs/code.bin            (+ exheader.bin if offered)
    SM/romfs/a/...    SM/exefs/...
    USUM/romfs/a/...  USUM/exefs/...
```

The `a/` GARC tree is the essential part (romfs `sound/` etc. can come
later); `code.bin` + `exheader.bin` enable the executable-side RE (zone
limits, caps) per game.
