# ARCHITECTURE.md — the multi-game layering

CTRMap-F5's long-term goal is one shared editor for the 3DS Pokémon games:
**X/Y** and **ORAS** (Gen 6), **Sun/Moon** and **USUM** (Gen 7). Today ORAS is
the reference game (everything is built and corpus-verified against it); this
document defines the layering that keeps the shared engine game-agnostic so
the other games can be added without untangling anything.

## Where things are, and how to run them

```
src/ctrmap/          the program            build.ps1   compile (never a bare javac)
  gamedef/           per-game profiles      test.ps1    the 123-suite battery
  formats/           readers and writers    stamp.ps1   "these classes came from these sources"
  humaninterface/    the Swing UI           package.ps1 cut a release
    tools/           the ten editing tools
  util/              pure helpers: no game, no window, no display
  tests/             the battery
tools/mutate2.py     the mutation sweep + its own --selftest
mutation_baseline.json   what the last sweep measured; MutationBaselineTest guards it
```

[TESTING.md](TESTING.md) is the operating manual for all of that: what you need,
how to run one suite or all of them, what the four families of guard assert, and
what to do when `MutationBaselineTest` fails after an edit.

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
- ~~**`Workspace.isOA()`-style gates in editors.**~~ **Gone, and now refused.**
  `Workspace.isOA/isXY/isOADemo` and `WorkspaceSession.isOA/isXY/isOADemo` have
  been deleted: they answered a four-game question with a boolean, so "false"
  quietly meant "then it is the other one" and handed Sun/Moon ORAS's warp
  labels, move codes, archive-tail arithmetic and emulator title id. Ask
  `profile().supports(Feature.X)`, a measured number
  (`zoneDataTrailingEntries()` and friends, via `ZoneTables` for the archive
  tails), or `variant()` for the edition.
  `SourceSeamTest.noApplicationClassAsksWhichGameIsLoaded` reads the COMPILED
  CLASSES and fails on a class outside `ctrmap.gamedef` that names one of those
  gates, or that holds both `Workspace.game()` and a `GameType` constant. It
  keeps one argued exception, listed in that suite with its reason.
- Corpus sizes quoted in comments/UI text (536 zones, 857 regions…) — they
  document ORAS measurements and move to profiles only when a second game's
  numbers exist to compare against.

## Known duplication / cleanup candidates (pre-existing)

*Re-counted 2026-09-06; the numbers below are what is in the tree today, not
what the list said when it was written.*

- `ParserLoader` (60) and `GRColorPalette` (61) duplicate the TILE_* collision
  constants verbatim.
- The ORAS zone-header size (0x38) has **four** independent literal definitions
  outside the test sources — `ZoneCloner.ZONE_HEADER_SIZE`,
  `GeometryForker.MASTER_ROW`, `ZoneLimitPatch.MASTER_ROW` and
  `WorkspaceIntegrity.MASTER_ROW` — plus two that correctly alias one of them
  (`AreaForker`, `ZoneRemover`) and three more literals in suites. Anyone
  consolidating these should start from the four, not the three this list used
  to name.
- Four dialogs each carry a private GAMETEXT reader helper: Trainer, Maison,
  MaisonClassList and Encounter.
- `ExtrasPanel` injects the XY Lumiose camera-collision dummy into every
  AreaData regardless of game (upstream behavior, unreviewed).

## Who owns what

The editor's state has owners, and a guard names each. "Handed" means a class
takes the thing in its constructor or at the call, and could be handed a
different one by a test; a class that fetches a global is not handed anything,
however the expression is spelled.

| What | Who owns it | Handed to | The guard |
|---|---|---|---|
| the open game | `WorkspaceSession` | 30 classes, 25 of them in `formats` through `GameFiles` | `GameFilesSeamTest`, `HandedGameTest` |
| the loaded zone | `ctrmap.LoadedZone` | 10 classes keep one, 5 take one at the call | `LoadedZoneTest` |
| the held tool | `ToolSelection` | the mouse router and 8 readers | `EditToolGuardsTest` |
| the editor around a tool | `ToolHost` + `ToolBox` | all 10 tools | `SourceSeamTest` (tools rule) |
| "draw it again" | `ctrmap.humaninterface.Redraw` | 5 editor forms | `DataSafetyGuardsTest` |
| the window a dialog belongs to | `ctrmap.Ui` | every unparented call | `DialogSeamTest` (rule six) |
| which game is loaded | `ctrmap.gamedef.GameProfile` | asked, never guessed | `SourceSeamTest` (game-identity rule) |

Three properties follow, each with a rule that keeps it true:

* **The format layer reaches the global nowhere** - 0 classes, 0 edges, by
  equality rather than as a ceiling, and no class under `ctrmap.formats` may
  name the UI package, the window, the dialog seam or the global at all.
* **The workspace facade does not know the window** - 0 references. It answers
  with the session it opened and the caller tells the window, which is why a
  clean now runs headless where the suite recorded it could not.
* **The tools do not know the window** - 0 references, which is what lets the
  checks about what a tool does as it starts run with no display at all.

What still reaches into `CtrmapMainframe` is 64 field references over 17
fields, and `MainframeEdgesTest` names every one with the classes that read it
and what for. That is a real tangle rather than an oversight: the map view
reads the NPC form and the NPC form reads the map view, so no order of
constructors hands them to each other. Breaking it needs a decision about
which of them owns what.

## Global mutable state (measured, and where the line is)

CTRMap keeps a lot in `public static` fields. Measured from the compiled
classes (`ctrmap.tests.GlobalStateTest`, which counts fields rather than
grepping lines), there are **36 public static mutable fields outside
`ctrmap.tests`**, and they are not scattered - they sit in four classes:

| where | count | what it is |
|---|---|---|
| `CtrmapMainframe` | 20 | The world toolbar, two scroll panes and seventeen panels and editor forms other classes reach (tilemap, tile, camera, prop, NPC, warp, trigger, geometry, collision, matrix, zone list, script, text, builder, 3D debug). Each panel is assigned once while the window is built. The frame is PRIVATE (nothing below the window needs it: the dialog seam holds the window a dialog belongs to, and "draw it again" is a capability the forms and tools are handed) and the held tool is an owner of its own (`ToolSelection`), which is why this is 20 and not 22. The 38 menu items, the tool-row buttons and the split-pane and tab plumbing that used to sit here are locals of their builders or private, and `MainframeShapeTest` ratchets this class on its own. |
| `Workspace` | 5 | The settings: the four paths and the tileset flag kept in `java.util.prefs`, written by the settings dialog and the setup wizard. The open game itself - paths, `GameType`, archive `File`s, `GARC` handles, the edited-file list - is a `WorkspaceSession` instance (below), not a static. |
| `Selector`, `MatrixSelector` | 11 | The 2D cursor: selected/highlighted tile and region coordinates, rewritten on every mouse move. Genuinely per-interaction mutable state, confined to the two panels that own it. |

The three per-operation smells the 2026-09-06 sweep listed here are gone:
`AreaForkPrompt.lastForked` (a return value smuggled through a static) is
returned as a `ForkResult`; `PawnInstruction.nativeResolver` (per-script
assembler context in a class field) rides on the `PawnAssembly`; and
`LocationNames.textfile` (a lazy table dereferenced with no null check) is
private behind an accessor that loads it and refuses in words when there is
no workspace to load from.

**The open game is a `WorkspaceSession`, and `Workspace` is being strangled
around it.** `Workspace` used to hold the open game in 36 public statics that
seventy files read; now `WorkspaceSession` is that state as an instance -
opened whole by `WorkspaceSession.open(workspaceDir, gameDir)`, which probes
the folder, checks every archive the game needs, opens them all and either
returns a session or throws with every problem found, touching nothing
global either way. It is headless (no window, no dialog, no static of its
own, never reads `Workspace`), and `WorkspaceSessionTest` holds it to that.
`Workspace` keeps the settings, the CURRENT session (installed only by a
successful `validate()`), the dialogs around opening and packing, and every
old static as a one-line delegator to the current session.

The migration is file by file, and a file only counts as migrated when it
is HANDED its session (a parameter, or a field set by whoever owns it) and
could be handed a different one in a test - `Workspace.session()` at a call
site is the same global with a longer name. `WorkspaceSessionTest` counts the
production files that still reach a `Workspace` static (62 on the day the
state moved; every one of them) and fails when the recorded number is not the
measured one, so the boundary is always written down. `GlobalStateTest`
still fails if a `Workspace` static is added and left out of `reset()`, if a
public static field is added that nothing assigns, or if the count of 38
rises.

Fixed on the way: `validate()`'s failure path used to keep the previous
game's `GameType` and all of its `GARC` handles live, with `valid` false and
the archive `File`s rebuilt from the NEW folder using the OLD game's layout
- half of each game at once, and the error list padded with archives "not
found" in a folder that was never identified as that game. A failed switch
now leaves NO current session: `game()` null, every `getArchive` null, the
user told only what is wrong with the folder they chose.

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
