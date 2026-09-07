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
│     formats/dressup (player part index: slots, designs)    │
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

## Global mutable state (measured, and where the line is)

CTRMap keeps a lot in `public static` fields. Measured from the compiled
classes (`ctrmap.tests.GlobalStateTest`, which counts fields rather than
grepping lines), there are **141 public static mutable fields outside
`ctrmap.tests`**, and they are not scattered - they sit in seven classes:

| where | count | what it is |
|---|---|---|
| `CtrmapMainframe` | 91 | Swing widgets and the panels/forms of the main window. Each is assigned exactly once, while `main()` builds the window, and never again: effectively final after startup. A smell, low risk. |
| `Workspace` | 36 | The open game: 4 config strings, 11 derived archive `File`s, 17 `GARC` handles, the `GameType` and `valid`. These do change during operation - but together, as one "a workspace was opened / packed" transaction. |
| `Selector`, `MatrixSelector` | 11 | The 2D cursor: selected/highlighted tile and region coordinates, rewritten on every mouse move. Genuinely per-interaction mutable state, confined to the two panels that own it. |
| `AreaForkPrompt.lastForked` | 1 | A return value smuggled through a static: `ensurePrivate` sets it, `packIfForked` reads it later. Its sibling `GeometryForker.ensurePrivate` returns a `ForkResult` instead, which is the shape this wants. |
| `PawnInstruction.nativeResolver` | 1 | The script whose natives table a disassembly resolves names against. Per-script context living in a class field; five suites set it and null it again in a `finally`, which is what knowing it is a hazard looks like. |
| `LocationNames.textfile` | 1 | A lazily-loaded name table. `getLocName` dereferences it without a null check; `ZoneRepurposeScanner` loads it first by hand rather than risk that, which is the workaround the missing check forces. |

**Workspace is deliberately NOT de-globalised.** That is a rewrite touching
every file, on a program that writes people's game data. What exists instead
is `Workspace.reset()`, which puts every static this class owns back to its
pre-startup value so a test can exercise more than one workspace per JVM,
and `GlobalStateTest`, which fails if a field is added and left out of the
reset, if a public static field is added that nothing ever assigns, or if
the count of 141 rises. Nothing in the application calls `reset()`:
re-pointing a live workspace goes through `validate()`, and rerouting that
through the reset would be a behaviour change with no test behind it.

Known and not fixed: on `validate()`'s failure path (a game folder that is
missing, or whose version cannot be detected) `game` keeps its previous
value, the `File` fields are rebuilt from the NEW folder using the OLD
game's archive layout, and the `GARC` handles still point at the OLD game's
archives - all while `valid` is set false. Every menu action checked so far
tests `Workspace.valid` first, so this is a latent hazard rather than a
reachable defect, but it is the concrete cost of the god object.

### The battery does not depend on its own order (measured)

`test.ps1` launches a separate `java.exe` per suite, so no static field can
carry state from one suite to the next; the only channels left are the
filesystem and `java.util.prefs`. That claim is now testable rather than
asserted: `test.ps1 -Order desc` and `-Order shuffle [-Seed n]` re-run the
same suites in a different sequence. Run forward, reversed and shuffled
(seed 4242) against the pristine dump, all three produce the identical
verdict for all 95 entries. Nothing in the battery is holding a suite up.


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
