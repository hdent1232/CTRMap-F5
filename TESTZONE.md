# TESTZONE.md — the built-in test world (walk it once, test everything)

A complete test world is now packed into your game data. **Entering May's
house in Littleroot Town (the very first house you'd enter in the game)
warps you to the TEST HUB instead.** Both doors that led to her house — the
front door outside AND the doorway inside your own house's map — are
redirected, so you reach it within a minute of starting a playthrough.

Everything the editor can build is in this world, and it is deliberately
split across four zones so that **if something crashes or misbehaves, WHERE
it happens tells us WHICH subsystem is broken** (table at the bottom).

> **FULLY REBUILT 2026-08-30 (latest) — everything below was regenerated
> through the editor's CURRENT pipeline**, including the vertex-colour fix
> (painted tiles could render black), the ground-material fix, and material
> import (brushes a map lacks — ice, stairs, cave — now borrow real retail
> materials instead of falling back). If you deployed earlier today, deploy
> again: the older build shows the pre-fix look.
>
> **SEALED 2026-08-30 — read this before testing.** The test world is now
> escape-proof: its zones are ringed with impassable rock, and the Mart/house
> doors that used to lead into RETAIL interiors (whose exits drop you in the
> real town they belong to) are removed. Previously, walking out of one of
> those put you in Petalburg City mid-intro, which starts the Wally sequence
> and dead-ends when you have no Pokémon.
>
> **Also: enter the test world AFTER you have your starter.** Several tests
> (above all the battle challenge) need a party, and the game's opening
> sequence is fragile until then. The other door in Littleroot leads to your
> own custom Mauville zone 536 — that one showed glitched geometry because
> its map matrix was corrupt; it has been repaired, so it now loads Mauville.

**REBUILT 2026-08-30 on the current pipeline.** The three outdoor zones
were regenerated through the composite edit-in-place path — the exact code
the Map Builder runs today: the stations were painted ONTO a pristine copy
of Route 102's real map, so **everything outside the stations is genuine
Route 102 scenery (trees, grass, ledges) that the editor KEPT — that's the
feature, not a leftover.** Walk off the first map cell and you'll see pure
Route 102; that's expected too (the painter edits one cell). Heights,
buildings, door props and warps all live in the retail-height frame.

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
Near the spawn: **two signs and two NPCs** — read/talk to all of them:
- Sign 1 (plaza): confirms sign-script injection works.
- Sign 2 (near the plateau): the plateau instructions.
- The talker NPC: confirms talking-NPC injection works.
- **The BATTLE CHALLENGE NPC** (right of the talker) — the single most
  important new test. It runs battles against three custom-authored
  trainers through the game's ordinary battle machinery (nothing shared
  with the Battle Maison): Newbie Nick (Zigzagoon Lv3) → Rookie Rita
  (Poochyena Lv5) → Boss Bruno (Wurmple Lv4 + Taillow Lv6). Expect: 3 BP
  per win, the streak advances each win (talk again → next fighter),
  +20 bonus BP after the third, a loss resets to Nick, and the streak
  survives saving/reloading. Known possible quirk: the intro sprite or
  class name could look odd (the custom trainers use spare class slots) —
  report what you see either way.

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
  **ice** (should slide), sand. One step on each. These are the stations
  whose materials do NOT exist on a route — they were imported from other
  maps, so also check they LOOK like cave/indoor/boardwalk/ice/sand rather
  than like grass or a wall.
- **South edge doors**: the WEST door leads to BUILDINGS, the EAST door to
  WATER.

### 3. West door → BUILDINGS zone
Three buildings on the north side, decor scattered around, one sign.
- **Pokémon Center (left)**: THE headline test. Walk through the door
  (it should swing open) into your own private Center copy — heal, use the
  PC if you like — then step on the exit mat: you must come back OUT onto
  this zone at the Center's door. That's the full round trip.
  - **NEW — edit-in-place check, inside the Center**: a small 3×2 dirt
    patch was composite-painted onto the room's floor (center-ish of the
    walkable area). Expect: the patch is visible and walkable, and the
    REST of the room — counter, healing machine, walls, nurse — is
    completely intact. This is the new "only touched tiles change"
    pipeline running on real retail geometry.
- **Mart (middle) and house (right)**: scenery only now. Their doors used to
  lead into the retail interiors, whose exits drop you into the real town
  they belong to (Petalburg, mid-intro) — that escape is removed. The
  Pokémon Center is the door that works, and it round-trips.
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
| Hub loads but terrain is invisible / black / full of holes | Composite map geometry (clipping / the model writer) |
| Hub looks right but you walk through walls / get stuck | Composite collision merge |
| A station floats above or sinks below the Route 102 ground around it | Composite height frame (collision-seeded heights) |
| Route 102 scenery pokes THROUGH a station's painted floor | Composite clipping missed geometry — the exact defect the clip fix targets |
| A hole/see-through gap at a station's edge where a Route 102 tree or ledge used to be | Wall regeneration at cut edges |
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
| Challenge NPC: talking does nothing / freezes | Challenge script emission or the battle natives are unavailable in this zone |
| Challenge battle starts but the opponent is empty/glitched | Custom trainer authoring (trdata/trpoke entries 51/52/61) |
| Challenge battle: wrong sprite or blank class name in the intro | The spare class slots (2/9) — open Game Data → Trainers and try an ORAS-era class id instead |
| Win but no BP appears | BP-award natives — report the BP counter before/after |
| Streak doesn't advance, or resets on its own | The save variable 0x4020 may be engine-owned — report; it's user-changeable |
| PC interior: the dirt patch is invisible | Composite visual path (clip + generated floor) |
| PC interior: room damaged, holes, or missing furniture around the patch | Composite clipping regression — the exact thing it must never do |
| PC interior: can't walk on the patch (or walk through the counter) | Composite collision merge |

## Undo everything

Every build step snapshotted your game data BEFORE touching it. The most
recent snapshot (before the composite rebuild) is
`testzone_backup\1788069475407\` — restore its five files to undo just the
rebuild.

**To undo the whole 2026-08-30 session** (challenge NPC + competitors +
Center patch + rebuild): copy ALL files from
`testzone_backup\1788068497503\` back into `RomFS\000400000011C400\` per
the table below, then re-deploy.

**To remove the whole test world**: copy the five files from
`testzone_backup\1788046535845\` (the original pre-build snapshot) AND
the three trainer/text files from `testzone_backup\1788068497503\`:

| Backup file | Restores to |
|---|---|
| `3_ZONE_DATA` | `a\0\1\3` |
| `9_FIELD_DATA` | `a\0\3\9` |
| `0_MAP_MATRIX` | `a\0\4\0` |
| `4_AREA_DATA` | `a\0\1\4` |
| `1_STORYTEXT` | `a\0\8\1` |
| `3_GAMETEXT` | `a\0\7\3` |
| `6_TRAINER_DATA` | `a\0\3\6` |
| `8_TRAINER_POKE` | `a\0\3\8` |

…then re-deploy. (Or just ask me to do it.) No executable patch and no
save-file changes are involved. The three repurposed trainer entries
(51/52/61) were provably-unused blank filler in the retail game.
