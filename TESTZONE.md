# TESTZONE.md — the built-in test world (walk it once, test everything)

A complete test world is now packed into your game data. **Entering May's
house in Littleroot Town (the very first house you'd enter in the game)
warps you to the TEST HUB instead.** Both doors that led to her house — the
front door outside AND the doorway inside your own house's map — are
redirected, so you reach it within a minute of starting a playthrough.

Everything the editor can build is in this world, and it is deliberately
split across four zones so that **if something crashes or misbehaves, WHERE
it happens tells us WHICH subsystem is broken** (table at the bottom).

The world uses four repurposed zone slots, all verified safe for a fresh
playthrough (two unused placeholder zones + two empty, unreferenced Sea
Mauville floors that nothing can reach before Surf/Dive):

| Zone | Slot | What it tests |
|---|---|---|
| TEST HUB | 473 | painted terrain: every brush, heights, ramps, ledges, stairs, signs, a talking NPC |
| BUILDINGS | 474 | building palette: Pokémon Center round trip, Mart/house, decor, texture carry |
| WATER | 153 | painted water + the spliced sea-scroll animation, waterfall |
| PC INTERIOR | 157 | the cloned private Pokémon Center room (entered from BUILDINGS) |

To deploy: open CTRMap → File → Deploy (the game files are already packed;
this pushes them to the emulator), then **fully close and restart the
emulator**. To play: start/continue a save in Littleroot and walk into
May's house.

## The walkthrough (recommended order)

### 0. Boot check
The game must boot to the title screen and load your save. If it doesn't,
stop here — that's a packing-level failure (see the table).

### 1. Enter May's house → TEST HUB
You should appear on a dirt plaza. The mat you arrived on returns you to
Littleroot at any time (step on it to confirm the return trip works).
Near the spawn: **two signs and one NPC** — read/talk to all three:
- Sign 1 (plaza): confirms sign-script injection works.
- Sign 2 (near the plateau): the plateau instructions.
- The NPC: confirms talking-NPC injection works.

### 2. Hub stations (each one is a single brush/feature)
- **West — tall grass patch**: walk in it. Expect rustle + wild encounters
  (Route 102's table, since the hub is cloned from Route 102).
- **West — bike rail strip** (the two-tile-wide vertical line): should only
  be passable on the bike, like a Cycling Road rail.
- **West — deep sand** (below the grass): slow trudge + footprints.
- **North — plateau**: walk UP via the yellow ramp tiles at its south-center.
  You should climb smoothly, and the cliff edges elsewhere must block you.
- **Ledge row** (south face of the plateau): jump DOWN over it toward the
  plaza; you must not be able to climb up through it.
- **Stairs strip** (east of the ramp): the stairs walking behavior.
- **East column, top to bottom**: cave floor, indoor floor, boardwalk,
  **ice** (should slide), sand. One step on each.
- **South edge doors**: the WEST door leads to BUILDINGS, the EAST door to
  WATER.

### 3. West door → BUILDINGS zone
Three buildings on the north side, decor scattered around, one sign.
- **Pokémon Center (left)**: THE headline test. Walk through the door
  (it should swing open) into your own private Center copy — heal, use the
  PC if you like — then step on the exit mat: you must come back OUT onto
  this zone at the Center's door. That's the full round trip.
- **Mart (middle) and house (right)**: enter-only by design. Their rooms are
  the retail interiors, so their exits lead to retail towns — walking out
  somewhere else is EXPECTED, not a bug. Just confirm entering works and
  the rooms render. (Return via the hub: the mat at the plaza's south goes
  back to the hub.)
- **Decor**: sign post, two tree types, iron fence — all should render with
  correct textures and block movement like their retail versions.

### 4. East door → WATER zone
A big pool with three waterfall tiles on its south rim.
- **The pool must RIPPLE and SCROLL** like a real sea route — this is the
  spliced animation, the highest-priority test of all. Still water = the
  splice plays dead; report that specifically.
- **Surf** on it (if you have Surf on this save).
- Try the **waterfall** tiles with the Waterfall HM if available.
- IMPORTANT side-check: the splice edits AREA 9, which the retail
  **Route 102** also uses. After testing, walk Route 102 (west of Oldale)
  and confirm it still looks and animates normally.

## If something goes wrong: symptom → culprit

Report the ROW that matches, plus whether it froze (music keeps playing?)
or hard-crashed. Each zone isolates a subsystem, so one broken thing does
not invalidate the rest.

| Symptom | Culprit subsystem |
|---|---|
| Game won't boot / dies before the title screen | GARC packing itself (global) — restore the backup, everything else is moot |
| May's door just enters her normal house | Deploy didn't take — re-deploy and fully restart the emulator |
| Crash/freeze the moment you enter May's door | Warp redirect or hub zone header (zone cloning) |
| Hub loads but terrain is invisible / black / full of holes | Painted map geometry (the model writer) |
| Hub looks right but you walk through walls / get stuck | Collision floor generation |
| A specific station misbehaves (ice doesn't slide, ledge won't jump, rail lets you walk…) | That one tile's behavior bytes — name the station |
| Tall grass rustles but no encounters | Encounter-table binding on the cloned zone |
| A sign does nothing when you press A | Sign script injection |
| A sign shows garbled text | Story-text encoding |
| The NPC freezes the game when talked to | Talker script cloning |
| The NPC is invisible or silent | NPC entity placement |
| Crash entering the BUILDINGS zone (hub was fine) | Building stamping / prop placement / texture carry |
| Buildings load but textures are wrong or checkerboard | Cross-area texture carry |
| PC door won't warp or crashes | Interior clone wiring |
| PC interior exit leads somewhere wrong | Interior exit-warp retargeting |
| Mart/house exit leads to a retail town | EXPECTED — not a bug (see walkthrough) |
| Crash entering the WATER zone (hub was fine) | The animation splice — also check retail Route 102 immediately |
| Water zone loads but the pool is still | Splice plays-but-inert — the append is valid but the engine ignores it |
| Retail Route 102 broken | Area-9 splice or texture-carry side effect |

## Undo everything

The builder snapshotted your game data BEFORE touching it. To restore:
copy the five files from `testzone_backup\1788046535845\` back into
`RomFS\000400000011C400\`:

| Backup file | Restores to |
|---|---|
| `3_ZONE_DATA` | `a\0\1\3` |
| `9_FIELD_DATA` | `a\0\3\9` |
| `0_MAP_MATRIX` | `a\0\4\0` |
| `4_AREA_DATA` | `a\0\1\4` |
| `1_STORYTEXT` | `a\0\8\1` |

…then re-deploy. (Or just ask me to do it.) The test world touches ONLY
those five archives — no executable patch, no save-file changes.
