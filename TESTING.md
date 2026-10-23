# TESTING.md — how this project is verified

Two halves, and they answer different questions.

- **The offline battery** — 221 suites, run on the unattended desktop of your machine against a
  real dump, answering *"does the code still do what it says?"*. That is the
  first half of this file.
- **The in-emulator checklist** — everything only the real game engine can
  answer, starting at [TESTZONE.md](TESTZONE.md). That is the second half,
  from "Map Builder" onwards, and it is the larger part of the file.

---

# Part one — the offline battery

## What you need

| | |
|---|---|
| A JDK | 17 or newer. `build.ps1` compiles with `--release 8`. Found under `C:\Program Files\Eclipse Adoptium`, or set `CTRMAP_JDK` to any JDK. |
| A pristine GARC dump | the untouched `a/` tree from *your own* copy of the game. Most suites take its path as `args[0]`. |
| A full RomFS dump | the whole title folder (`.../RomFS/000400000011C400`), not just the GARCs — the setup and item suites validate real folder layouts. |
| A decompressed `code.bin` | optional. Only the executable-patch suites (`ItemIconPatch`, `ShopData`) need it; without it they say so and skip. |

Nothing in this repository is a game file, and the battery downloads nothing.
No dump, no battery — that is by design.

## Build, then run

```
powershell -ExecutionPolicy Bypass -File build.ps1

powershell -ExecutionPolicy Bypass -File test.ps1 -Quick `
    -Pristine "<...>\RomFS_original_garcs" `
    -GameDir  "<...>\RomFS\000400000011C400"
```

- **Always `build.ps1`, never a bare `javac`.** A bare `javac` copies no
  resources, so the catalogue tables in `build\classes` go stale while every
  class looks fresh. `build.ps1` signs what it built (see `stamp.ps1`), and
  `test.ps1` **refuses to run** — exit code 2, "REFUSING TO RUN" — against a
  `build\classes` that is not exactly that script's output from exactly these
  sources. Rebuild and run it again.
- **`-Pristine` / `-GameDir` are how you point it at a dump anywhere on disk.**
  Without them it looks beside the repo, which is the author's layout and
  nobody else's; six suites used to fail in a fresh clone for a reason that had
  nothing to do with the code.
- **`-Quick` is the LIGHTER run, not the thorough one.** It is a sampling
  *stride*: 60 instead of 20, so the corpus sweeps visit a third as many
  regions. Roughly 15 minutes with it. Drop the flag for the full sweep and
  expect appreciably longer.
- One suite on its own, with the arguments `test.ps1` gives it:
  ```
  java -Xmx4g -Djava.awt.headless=true -cp "build\classes;lib\jogl-all.jar;lib\gluegen-rt.jar" `
       ctrmap.tests.DispatchGuardsTest "<...>\RomFS_original_garcs\a\0\1\3"
  ```

## Reading the result

Every suite prints `ALL PASS` (or `PASS`) as its last line and **exits 0**; the
exit code is the verdict, not the text. `test.ps1` ends in one of:

```
ALL SUITES PASS  (912s)
FAILED: <suite name>, <suite name>          # and exit code 1
```

A suite that prints `skip:` and passes is telling you it could not run — nearly
always a missing dump path. **A skipping suite is not a passing suite.** That
hole has been paid for twice (`BchMapModelTest` skipped from every worktree and
looked green; `MaisonClassListTest` round-tripped nought of nought entries and
said ALL PASS), which is why `BatteryHygieneTest` now fails the battery if a
suite that names the dump is registered in `test.ps1` without a path to one.

## What the guards mean

Five families, and they assert different kinds of thing:

1. **Corpus round-trips** (`ZoneEntitiesRoundTrip`, `GFMessageFileRoundTrip`,
   `TrainerData`, `AreaEnv`, `GfColl`…) — parse then re-serialize every record
   in the retail game and demand the bytes come back identical. These pin the
   *formats*.
2. **Operation sweeps** (`ZoneAppend`, `MapPrefab`, `CompositeBuild`,
   `PaintedRegion`…) — run a real editing operation across every eligible zone
   or region and check the result, often against the pristine archive. These
   pin the *edits*.
3. **Guard suites** (the 15 named `*Guard*Test`) — take a refusal the code is
   supposed to make and feed it an input built to trip it, then demand it
   refuses **by name**: the declared exception type, with the archive on disk
   untouched. "It threw something" is not "it refused" — a dropped guard turns
   into an index blowup a few lines later, after the damage. `DispatchGuardsTest`
   and `PaintedRegionTest` are the models to copy.
4. **Meta-suites**, which assert about the repository rather than the program:
   - `SourceSeamTest` — no RomFS path, GameText index or other game-detected
     constant outside `src/ctrmap/gamedef/`. See ARCHITECTURE.md. Its last
     rule is not a grep: `noApplicationClassAsksWhichGameIsLoaded` reads
     `build\classes` through `ClassFileScanner` and fails on any class outside
     `ctrmap.gamedef` that names `isOA`/`isXY`/`isOADemo` (all six deleted),
     that holds both `Workspace.game()` and a `GameType` constant, or that names
     a `GameType` constant at all - a class handed the game decides on it as
     surely as one that fetched it (F207). A static import defeats a source
     pattern completely and does not defeat this one, which is why it reads
     bytecode. One asker is allowed, argued in the suite's `ALLOWED_ASKERS`
     table, and six namers in `ALLOWED_NAMERS` (two per-game tables with no
     else, four tools that open an ORAS dump); code needing the one game a
     feature is open to asks `GameProfile.onlyGameWith(feature)`.
   - `BatteryHygieneTest` — how a suite may touch the machine, that every
     suite is registered with its corpus, that `build\classes` came from
     `build.ps1`, and that the counts the docs publish match the data.
   - `CommitGuardTest` — the `commit-msg` hook refuses what it says it
     refuses. It builds a scratch repository, stages exactly what each case
     means to stage, and runs the real guard there, because driving it against
     this working tree would make the answers depend on whatever you happened
     to have staged. An unwired hook is the first thing it checks: every
     refusal can be perfect and none of them will ever run.
   - `MutationBaselineTest` — below.
   - `SnapshotIntegrityTest`, `UiOutputTest`, `VaultGuardsTest` — the pristine
     backup, and that a message the user should see is actually reachable.
5. **Ownership guards**, which assert that a class is HANDED what it uses
   rather than fetching it from a global. The test is always the same: can two
   of them exist at once, holding different things? A class that reads a static
   cannot, however the expression is spelled.
   - `GameFilesSeamTest` / `HandedGameTest` — the format layer is handed its
     game and reaches `Workspace` nowhere: 0 classes, 0 edges, by equality.
   - `LoadedZoneTest` — the loaded zone has one owner; two panels over two
     owners answer about their own zone, and the bytecode says exactly one
     class makes an owner and NO class still names the Zone tab's static. The
     four that did wanted an operation, not zone state, and were handed the
     operation: `ZoneSaver`, `ZoneList`, and for the wizard, a number.
   - `EditToolGuardsTest` — which tool is held is an object, two selections
     hold two tools, and a switch shuts the outgoing one down BEFORE building
     the incoming one, which is the order a tool's setup depends on.
   - `MainframeEdgesTest` — what still reaches into the main window, field by
     field, with the classes that read each and what for, by equality. That
     table is now EMPTY and the count is zero: nothing outside the window reads
     a window static. It stays a table rather than becoming a number because an
     empty table still says WHICH class and WHICH field when a name appears in
     it again. Zero does not mean the window holds nothing — it still owns
     public statics for its own use (GlobalStateTest counts them); it means nothing else asks it for
     them. Its fourth rule reads the window's own source and refuses to let
     anything the window builds be handed a static that method has not assigned
     yet — the defect that shipped once, when a list was built where it read
     best and the two classes handed it were built sixty lines earlier. Three
     handings are deliberately two-phase (the matrix grid, the map view's
     editors, the tool row's Set-tool callback) because those are genuine
     construction cycles, and each says so where it is written.
   - `OpenEditorsTest` — "save what the editors hold" and "show this zone" are
     each one list in one order: every editor is asked, a refusal stops the
     ones after it and the caller's reload, an empty set is refused rather
     than answering "everything saved" forever.
   - `SourceSeamTest`'s later rules — the format layer may not name the UI, the
     window, the dialog seam or the global; the workspace facade may not name
     the window; the editing tools may not name the window. All zero, no
     exceptions, all read from bytecode.
   - `PackageCycleTest` — no strongly connected component of production
     packages holds both a format package and one above it; the largest cycle
     is the recorded size; no format class names an OpenGL type (nine did
     until 2026-10-10, when the model reader's matrices left JOGL and the
     drawing moved to `ctrmap.render`). Read from bytecode, the packages
     derived from the tree.
   - `DialogSeamTest` rule six — a dialog opened without naming a window is
     parented to the one the application gave the seam.

## The mutation sweep, and what to do when `MutationBaselineTest` fails

The suites are themselves measured. `tools/mutate2.py` takes each line the
merged fix branches added, breaks it in a way that still **compiles** (deletes a
`throw`, inverts an `if`, flips a `return true`), rebuilds, and runs the battery
to see whether anything notices. A mutant nothing notices is a line no suite
asserts — a hole — and it is recorded in `mutation_baseline.json` with the exact
text of the line. The last recorded sweep (2026-09-07, on 84db86d, after the
structure merges): **227 of 227 measurable mutants
killed, 0 survivors, 2 lines excluded by hand with a written reason each.**
160 fix lines whose text later merges rewrote are outside that measurement
(16 of them were scored in the previous sweep); their replacements are guarded
by the structure branches' own suites but not yet mutation-scored, because the
sweep measures the `sf/` merges only.

The sweep costs hours. `MutationBaselineTest` costs seconds and keeps the record
honest in between: it re-hashes every file the sweep measured and refuses a
baseline that no longer describes the tree.

**So when you edit one of the files the sweep measured, this suite fails. That is correct, and
it is a digest check, not a regression.** The fix is:

- **Do not hand-edit `mutation_baseline.json`.** Updating a digest without
  measuring asserts a measurement nobody took — the one thing the file exists
  to prevent.
- If it names files you did not edit, compare line endings before anything else:
  the digest is byte-for-byte, and a checkout that has LF where the sweep's
  worktree had CRLF for the same commit fails it (seen 2026-09-07, seven files
  a `sed -i` had left LF). Make the bytes match; do not re-sweep for that.
- Commit your work **first**: the sweep ends in `git reset --hard <the sha it
  started from>`, so anything uncommitted while it runs is erased.
- Re-run it — `python tools/mutate2.py` — which writes `mutation_baseline.json` in
  the repository itself, and commit that. There is NO copy step: the sweep derives
  that one path from its own location and `MutationBaselineTest` refuses a second
  copy, because two baselines kept equal by hand is the defect it was written for.
- If you are not the person who will re-run it, **say so in your handover**
  rather than silencing the suite. A failing MutationBaselineTest is the record
  telling the truth about itself.

`python tools/mutate2.py --selftest` re-runs the harness's own guards against
synthetic input in seconds, with no JDK, worktree or dump. The battery runs it
as its last entry, and skips it (loudly) where there is no Python.

---

# Part two — things only the emulator can answer

> **START HERE: [TESTZONE.md](TESTZONE.md).** The in-game test world is a
> build product: `testworld.ps1` builds it from the retail archives through the
> editor's own operations, so it checks the editor you build it with, and its
> BUILT.txt names that commit. Build it, deploy it as TESTZONE.md says (your own
> mod is set aside while it is installed), and enter the rival's house at the
> start of the game: the world exercises nearly every item on this list, with a
> symptom→culprit table for anything that breaks. Come back to this list for the
> features it doesn't cover (shops, Maison, trainer editing, encounter slots,
> zone removal).

## Map Builder: edit-in-place + live 3D (NEW — the big behavior change)
Two fundamentals changed after your New Mauville feedback:

**1. The Map Builder now EDITS the existing map instead of replacing it.**
Only tiles you actually touch are rebuilt — walls, fountains, and every
baked detail on untouched tiles stay exactly as they are (offline-proven:
an untouched apply is byte-identical; a corpus test sweeps retail regions).
The overlay shows it: touched tiles draw solid, untouched ones faint.
"Fill all with brush" is the explicit way to rebuild a whole cell.
- [ ] Load a real zone (e.g. your New Mauville copy), paint a few tiles,
  Apply, Deploy. Expect: your tiles changed IN the existing room — the
  fountain and walls are still there.
- [ ] Walkability follows: painted rock blocks, painted path walks, and the
  untouched area keeps its retail collision.
- [ ] Re-apply a second edit to the same zone. Expect: it stacks on the
  applied map (and no "geometry fork" happens again — the zone already owns
  its map).
- [ ] Paint on ELEVATED retail terrain (a raised route section or plateau).
  Expect: the painted tiles sit level with their retail surroundings — the
  elevation grid seeds from the map's real collision heights, so you don't
  fall into a pit where you painted.

**2. The 3D preview is no longer a separate window.** The painted cell now
updates LIVE inside the main map scene (~0.2s after each stroke):
- [ ] The 2D view's built-in 3D underlay (the half-transparent render)
  changes as you paint.
- [ ] Click the **3D view** toggle in the paint panel (or press F3): the
  full 3D view shows your edits live among the zone's real walls, props and
  neighbor cells, with the fly camera (WASD + drag). F2 returns to 2D.
- [ ] Buildings placed with the palette appear in 3D right after placing.
- [ ] Switching tools puts the real (unapplied) map back — nothing painted
  leaks into the scene unless you Apply.

**3. Moved geometry carries its bounding box (F075, 2026-10-09).** Every map
mesh names a box the engine may cull it by; every retail box holds its mesh,
and an edit that moves vertices out of it now moves the box too. Whether the
engine culls by it is an in-game question:
- [ ] Move or grow a region's geometry well past where it was (Geo box move,
  or resize a map), pack, and walk past it in game at grazing angles and
  from far away. Expect: nothing of the moved part blinks out at the edge of
  the screen.

## Shared-map zones (NEW — the Petalburg Gym bug)
Some zones share one big map with their neighbours (Route 102 and Petalburg
City are two zones on the same matrix; 94 retail zones are like this). The
Map Builder used to paint the map's FIRST cell, which on those zones belongs
to the NEIGHBOUR — so edits landed on their ground and warps put the player
in their territory, where the game announces their name and runs their story.
It now paints the zone's own cell.
- [ ] Load a shared-map zone (Route 102, Petalburg City, Littleroot, Oldale,
  Rustboro, Sootopolis...) and paint a few tiles. Expect: the edit appears
  in THAT zone in-game, and the location banner still names the zone you
  edited.
- [ ] The test world is the live check for this: its zone is a clone of Route
  102, a shared-map zone, and the cell you arrive in is the painted one (see
  TESTZONE.md).

## Game-wide asset palette (NEW — the comprehensive catalog)
"Buildings & decor" now holds **3,520 auto-harvested structures from every
map in the game** (plus the 48 curated, door-wired entries pinned first —
3,568 in all):
houses, domes, fountains, lamps, stair blocks, bridges, trees, rocks —
each one offered machine-verified to cut and stamp cleanly. The table was
regenerated from the dump on 2026-10-09 by the fixed harvester (F129, F251):
no box ends before it starts, every name says what the cut is made of, and no
cut is offered twice - 16 that repeated a curated or earlier box are no longer
written. Narrow by type and by place ("Sootopolis City", "Rustboro City"...),
or search.
- [ ] The palette opens with no "skipped rows" warning.
- [ ] Open the palette in the Map Builder → filter to a town → previews
  render for harvested entries.
- [ ] Place a harvested structure (e.g. a Sootopolis dome house) on a map,
  Apply, Deploy. Expect: it stands there with correct textures.
- [ ] Auto entries have no door wiring (curated ones keep it) — an auto
  building is scenery; use a curated enterable one for round trips.
- [ ] Names are auto-generated ("<place> structure N") — report entries
  whose cut looks wrong (bleed from neighbours, missing pieces), with the
  name; each carries a signature we can trace.

## View toggle + fixes (quick in-editor pass)
- [ ] The **3D view** toggle now lives on the main toolbar — works with
  every tool, stays in sync with F2/F3.
- [ ] Load a zone while the Map Builder is active: the paint panel now
  re-seeds to the newly loaded zone (no more stale "Painting zone N").
- [ ] Blank canvas: the ground-material list is sorted largest-first with
  the main ground labeled; atlas-looking previews are explained in the
  dialog.
- [ ] Zone 536 (custom Mauville): loads its map again (its matrix was
  corrupt and has been rebuilt; it shows Mauville from its own private
  copy).

## Map Painter integration (previous round — quick in-editor pass)
The painter is the **Build map tool** on the World Editor toolbar (also Map
menu → Map Builder). Its brushes, buildings, lighting and Apply live in the
right-side panel; painting happens directly ON the main map view — your
painted cell draws over the zone at its real position (blue frame), with
the map's own zoom.
- [ ] Select the Build map tool → paint terrain on the main map, drag to
  stroke, right-click ramp tool clears ramps, place a building
  (right-click removes).
- [ ] Undo/Redo in the paint panel reverts strokes/fills/placements (and
  the faint/solid touched state follows).
- [ ] Apply to zone from the panel works end to end (same flow as before).
- [ ] Fog & lighting now opens the GameFreak atmosphere picker FIRST (live
  preview of your zone); "Custom settings" leads to the manual sliders.
- [ ] No more "..." suffixes reading as cut-off labels.
- [ ] The Build map and Geometry tools now have proper toolbar icons
  (a terrain grid + brush; a blue wireframe mountain) matching the rest of
  the tool row — hover for their names, selection highlights like the others.

## UI reorganization (quick in-editor pass, no emulator needed)
- [ ] World Editor: two toolbar rows — tools + Undo/Redo on top, the Map
  actions row (Paint map / Blank canvas / Resize / Fog & lighting /
  Encounters / Fork geometry) below. Paint some tiles, then Undo/Redo
  (buttons or Ctrl+Z / Ctrl+Y) — drags and fills undo as one step.
- [ ] Picking a tile type from the side list with nothing selected switches
  to the Set tool and the pick STICKS; click/drag paints it. Entries show
  color swatches.
- [ ] Zone Loader: zone actions bar on top (Rename / Empty / Find reusable /
  Remove added / Battle facility).
- [ ] New Game Data tab: Trainer / Maison / Shops / Encounters entry points.
- [ ] Builder is gone as a tab; it opens from the Extras tab's
  "Raw archive browser" button. Menus: Tools replaced by Map / Zone /
  Game Data.
- [ ] Tile painter dialog: Undo/Redo at the top of the side panel undoes
  strokes, fills, and building placements/removals.

A running list of everything built offline that needs an in-game check.
Ordered roughly by how much other work depends on the answer. Check items
off (`[x]`) as you confirm them; note anything that looks wrong and I'll fix it.

Every item below already passes the offline regression battery (parsers,
byte-round-trips, renders) — these checks are about how the REAL game engine
reacts, which can't be verified without the emulator.

## Tile painter — core loop
- [ ] **Paint & deploy**: open a zone → Map → Map Builder (this zone) → paint some
  grass/path/rock → Apply to zone → Deploy. Walk around. Expect: you walk on
  painted ground, rock blocks you, the map looks like the 3D preview.
- [ ] **Tall grass**: walk through painted tall grass. Expect: rustle animation
  + wild encounters trigger (uses the zone's encounter table).
- [ ] **Water + surf**: paint water, surf onto it. Expect: surfable, water looks
  like water (2-layer sea material).

## Tile painter — elevation & ramps
- [ ] **Raised plateau**: raise a block of tiles 1–2 levels. Expect: cliff walls
  appear at the drops, you canNOT walk up/off the cliff edge.
- [ ] **Ramp/slope**: mark ramp tiles connecting level 0 → 1 → 2 (the yellow
  triangle tool). Expect: you walk smoothly up and down the slope, no hopping,
  no clipping through.
- [ ] **Ledge tiles**: paint a south-facing ledge on a cliff edge. Expect: you
  can jump DOWN over it (Hoenn ledge hop), not up.

## Tile painter — full brush catalog (one visit each)
- [ ] **Cave floor** brush: encounters use cave slot? footstep sound differs?
- [ ] **Ice**: sliding behavior.
- [ ] **Deep sand**: slow trudge + footprints.
- [ ] **Bike rail** (`60 00 00 00` deck): only passable on bike (Cycling-Road rail).
- [ ] **Waterfall** tile placed on a cliff face next to water: usable with the
  Waterfall HM?
- [ ] **Door/warp tile** (`01 00 0E D4`): steps trigger a warp — NOTE it warps
  wherever the zone's warp table entry points; without editing warps it may
  warp somewhere odd or do nothing. Just confirm the step-on reaction.

## Edge blending (NEW — grass↔dirt/sand transition strips)
- [ ] Paint a dirt path through grass with **"Blend grass edges" ON** → Apply →
  Deploy. Expect: GameFreak's soft grass fringe along the grass/path seam
  (like Route 101's path edges), not a hard texture line.
  - The offline render only shows a faint band — the fringe texture is
    camera-projected by the game engine, so the real look needs this check.
- [ ] Same map with the checkbox OFF → hard seam (control test).
- [ ] Edges on a SAND beach seam (grass↔sand) — same fringe expected.

## Lighting (baked) + area fog
- [ ] **Lighting presets**: apply the same map twice — once Day, once Night
  preset. Expect: visibly darker/tinted ground at Night.
- [ ] **Area fog editor** (Map → Edit area fog & lighting): push fog near/far
  in close + strong color. Expect: in-game distance fog changes to match.
  REMEMBER: fog is per-AREA — other zones sharing the area change too.
- [ ] **GameFreak atmosphere picker**: copy e.g. the Sootopolis or a cave
  zone's atmosphere onto your zone. Expect: in-game mood matches the picker's
  live preview of YOUR zone.

## Water scrolling (NEW — "Make water ripple here" is BUILT, this is its in-game check)
The tile painter's water banner now has a one-click **"Make water ripple here"**
button on amber zones: it splices GameFreak's exact two-layer sea-scroll
animation (copied byte-for-byte from Route 105's) into the zone's area data,
bound to this zone's map cells. Offline it's about as verified as possible
(all 228 retail files re-validate, 560 test splices pass, the tree builder
reproduces GameFreak's own serializer byte-identically) — but only the game
engine can prove it PLAYS. **This is the highest-priority test on the list.**
- [ ] **The big one**: on a landlocked zone, click "Make water ripple here"
  (banner turns green) → paint water → Apply → Deploy. Expect: the water
  scrolls in two layers exactly like a real sea route. If instead it's still,
  or the map hard-locks on load, report which.
- [ ] The area's OTHER animations still play after the splice: visit a
  neighbouring map in the same area (and the same zone pre-splice-state
  things like grass wind sway) — nothing else should have changed.
- [ ] A zone whose banner was already GREEN: painted water ripples with no
  button needed.
- [ ] Landlocked zone WITHOUT clicking the button: water still (amber banner
  told the truth).
- [ ] Click the button on a zone in a SHARED area, then visit another zone of
  that area: it must be unaffected (the animation binds by map-cell name).

## Building palette (NEW — "Buildings & decor..." in the tile painter)
Pick a Pokémon Center / Mart / Gym / 12 house styles / signs / trees / fence /
bush from a searchable list with a live 3D preview, click the grid to place.
Every catalog entry is offline-proven (extracts from your dump, stamps, and
renders complete) — these checks are about the in-game result.
- [ ] **The full round trip** (the headline test): place a **Pokémon Center**
  on a painted map → Apply → choose **"Clone private interiors"** → Deploy.
  Expect: walk THROUGH the door (its swinging-door prop is placed
  automatically, registry + textures handled) into your own private copy of
  the Center room, then step on the exit mat and come back OUT onto your
  map at the same door. The apply dialog names which zone slot the interior
  clone used (rename it via Zone → Rename zone).
- [ ] The "Link retail interiors" option instead: entering works, the exit
  leads to the retail town (as the dialog warns).
- [ ] The swinging door prop animates (opens on contact) — its registry
  entry is cloned from retail with the animation bindings.
- [ ] Place a **sign** and give it text when asked at Apply time. If the zone
  lacks the sign routine (467 of 536 do), the editor offers to transplant
  the vanilla one — say yes. Expect: pressing A on the sign shows your text
  in the frame style you picked. **This transplant is new** (same proven
  mechanism as the talking-NPC one, offline-verified on all 467 zones) —
  one in-game read of an injected-zone sign confirms it.
- [ ] Place a house + a sign + trees on one map. Expect: all render with
  correct textures (auto-carried across areas), trees/signs block movement
  per their retail footprint tiles.
- [ ] Place a building on RAISED terrain (height 1-2). Expect: it sits on the
  plateau, not floating or buried (base-height compensation).

## Props & furniture
- [ ] Place a TV / PC / door prop via the Prop Tool on a painted map → Deploy.
  Expect: visible, and interaction (PC menu) works if scripted/expected.

## Shop inventories (NEW — Game Data → "Edit shop inventories (Marts)...")
Shop lists live in the executable, so this ships as a code.ips like the zone
patch (offline: all 24 retail inventories byte-verified in your code.bin).
- [ ] Change an easy-to-check shop (e.g. Lavaridge Herbs, or the 0-badge
  Mart list) → Save code.ips → deploy to Azahar
  (load/mods/&lt;titleid&gt;/exefs/code.ips) → full emulator restart → talk to the
  clerk. Expect: your items, at their normal prices.
- [ ] If you also use the zone-limit patch: save into the same code.ips (it is
  merged, never asked about — the other patches in it are kept), then
  confirm BOTH still work (game boots with added zones AND the shop sells
  the edited items) — one code.ips carries both.

## Items (NEW — Game Data → "Edit items (price, effects, name)...")
Two tiers, and they install differently. **Tier 1 (the record, the name, the
description) is plain data**: it saves straight into your game folder, Deploy
ships it, no patch. **Tier 2 is the icon only** — that word lives in the
executable and needs a code.ips, exactly like the shop and zone patches.
Offline-proven: all 776 retail records round-trip byte-for-byte, an edit
changes only the bytes of the field you changed (43 KB archive compared byte
by byte, container header included), and the icon patch refuses a code.bin
whose stock bytes do not read as recorded.

What this **cannot** do, so don't test for it: invent a new effect. The 183
held-effect ids are all taken, and some behaviour (Exp. Share, Ability
Capsule) is keyed on the item id in code and is not in the record at all.
Reassigning an existing effect to a different item is the whole trick.

- [ ] Pick an item you will meet early (e.g. a Potion), change its **price**,
  Save item, Deploy, restart the emulator, and buy one. Expect: the new
  price at the counter, and the sell price at half of it.
- [ ] Give a held item a different **held effect** — e.g. point a Potion at
  the effect the dropdown labels with Life Orb — and battle with it held.
  Expect: that behaviour, on that item.
- [ ] Change an item's **name and description**, then Pack Workspace and
  Deploy. Expect: both show in the bag. (Name/description go through the
  workspace like every other text edit; the record does not.)
- [ ] **New item**: press "New item", take one of the four slots, give it a
  name, description and a record copied from something real. Get it in-game
  (a script `ItemGetNum`, or put it in a shop with the shop editor) and check
  the bag shows your name and the item works.
- [ ] **Icon** (needs the patch): "Load code.bin...", set an icon (or "Use
  another item's icon..."), "Save code.ips..." into the same file, deploy,
  restart. Expect: the new icon in the bag, and the zone and shop patches
  still working — one code.ips carries all three.
- [ ] **Revert**: change an item, save, then "Revert to pre-edit copy" and
  save again. Expect: the item is exactly as it shipped.

## Interiors (Pokémon Centers, houses — just so it's on the list)
- [ ] Load a Pokémon Center interior zone in the zone loader (interiors are
  ordinary zones), move an NPC or prop, Apply/Deploy. Expect: the change
  shows inside every Center that uses that interior zone.

## Scripted NPCs on an X / Y workspace (F085 — in the editor, no emulator)
Every template that writes a zone script was measured on ORAS only, and now
refuses on any other game rather than writing ORAS's shapes into it.
- [ ] Open an X / Y workspace and paint a sign with the Map Builder; then try
  NPC tool → Add → "Trainer". Expect: each says the template is not available
  for X / Y, and nothing is added to the zone or its script.

## Zones a script sends the player to (F225 — in the editor, no emulator)
"Find reusable zones" and "Remove added zones" now read where scripts send the
player - each zone's own script and the 85 shared scripts no zone owns - as
they read warps. Measured on the retail dump: the scan offered 165 zones and
now offers 140; the 25 it stopped offering are reached by a script and by no
warp (17 by zone scripts, 432-439 by shared scripts alone).
- [ ] Zone → Find reusable base zones... on a retail workspace. Expect 140 candidates,
  473 and 474 first, and an uncounted list naming shared scripts 35, 46, 71
  and 74 - map changes whose destination is computed at run time or is a
  secret base - with "...and N more" if it is longer than five lines.
- [ ] Zone → Remove added zones on a workspace with added zones. Expect the
  warning to say "warps and script map changes" and to list those four shared
  scripts as not checked.

## JOGL 2.6.0 (F077 — in the editor, no emulator)
lib/ moved from JOGL 2.3.2 with Windows natives only to 2.6.0 with natives for
Windows, Linux (x64 and arm64) and macOS. Every jar matches JogAmp's published
SHA-1, every retail bone matrix the model reader computes is bit-identical to
2.3.2's (ModelTransformTest), and OpenGL starts here on Windows. What only the
owner's machine can show:
- [ ] Open the editor: the 3D map view, the collision view, the prop form's
  model preview, the building palette preview and the atmosphere preview all
  draw as before, and models and NPCs sit where they did.
- [ ] If a Linux or macOS machine is to hand: unzip the portable build, run
  `run.sh`, and the editor opens - drawing in 3D, or standing 2D-only with the
  placeholder saying why, never crashing the JVM (2.3.2's Linux natives did,
  on a modern Mesa).

## Drawing moved out of the formats (F214 — in the editor, no emulator)
The model and collision drawing moved from the format classes to
`ctrmap.render`, and OffscreenRenderTest now draws seven frames offscreen and
holds each to its recorded pixels. Moving it found one thing that draws
differently on purpose: a map mesh with no texture used to be drawn with the
previous mesh's texture still on (7 of the 19 meshes of area 9's first region),
or with whatever texture OpenGL had left. It is drawn untextured now, in its
vertex colours (white where its material gives it none).
- [ ] Open a map in the 3D view. Expect: textured ground, buildings and props
  look as before; a few small pieces (edges, trims, decals the game draws in
  plain colour) may look plain or tinted where they used to show a stray
  texture. Report any surface that was textured before and is plain now.
- [ ] Collision view: select a triangle. Expect: it highlights as before.

## Zone removal (NEW — Zone → "Remove added zones (restore stock 536)...")
Offline-proven: append-then-remove restores the pristine archive content
byte-for-byte. The in-game check:
- [ ] After removing added zones, pick the deployed code.ips when asked (the
  zone-limit patch is taken out of it, shop and icon patches kept): the game
  boots and plays like stock (title screen, load save, walk around), and an
  edited shop still sells its edited items.

## Independent battle challenge (NEW — the highest-priority battle test)
An NPC that battles YOUR trainer entries through the game's ordinary
trainer-battle natives (the same ones rival battles use) — fully decoupled
from the Battle Maison engine and its shared pools. NPC tool → Add →
"Battle challenge (own trainers)". Offline: the emitted script verifies on
all 536 zones (dispatch, branches, switch table, natives); the trainer
codec is byte-exact on all 949 retail trainers.

**The test world has a challenge NPC** (see TESTZONE.md) with three
authored competitors — walk it first; the checklist below is for when you
build your own:
- [ ] **The core test**: author a team in an unused trainer entry
  (one with a blank name that no NPC battles — 51, 52 and 61 in retail, the
  ones the test world takes),
  add a challenge NPC with it, deploy, talk. Expect: a real trainer battle
  against your team starts.
- [ ] Win: the streak advances (talk again → next trainer in the lineup)
  and BP appears (check the BP counter).
- [ ] Lose: the streak resets (talk again → first trainer), and with
  "white out on defeat" checked you warp to the last Pokémon Center.
- [ ] The streak survives leaving the zone and saving/reloading (it lives
  in a save-data variable).
- [ ] Milestone: set "bonus at streak 2" with a 2-trainer lineup, win
  twice → the bonus BP lands on the second win.
- [ ] The retail Battle Maison still battles its normal teams (nothing
  shared was touched).

## Battle facility opponents (UPDATED — vanilla-safe by default)
The editor (Game Data → Edit battle facility opponents) now marks RETAIL rows grey and
guards them: editing one offers "Copy to a free slot" first, so the shipped
game's teams stay vanilla unless you explicitly choose otherwise. The pools
are engine-wide (retail facility + every clone share them) and the dialog
says so; "Go to first free slot" jumps to your authoring space.
- [ ] Author a team in a FREE slot, point a class at it via Class
  assignments (the one-time engine-wide warning appears), battle in the
  retail Maison or your cloned facility. Expect: your team appears.
- [ ] Without touching any grey row, the retail facility still battles its
  normal teams ("Restore retail row" in Class assignments undoes a row).

## Earlier systems (from previous sessions, still unverified in-game)
- [ ] **Facility opponent editor**: edit a set (a/1/8), battle it in the
  Maison. Expect: edited species/moves appear.
- [ ] **GiveBP script**: the PlayerSetBP native script edit awards BP correctly
  (check BP counter before/after).
- [ ] **Trainer editor**: edited trainer team appears in battle.
- [ ] **Encounter editor**: edited wild slots appear at expected rates.
- [ ] **Zone-limit patch** (code.bin): game boots with the patched executable
  and added zones load.

## How to report back
For anything that fails: say which item, what you saw vs expected, and (if it
crashed) whether it hard-locked or errored. Screenshots help. I keep this file
updated every time a new offline-built feature lands.
