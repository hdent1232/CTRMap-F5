# TESTZONE.md — the in-game test world (walk it once, test everything)

> **The test world is a build product (F138).** `testworld.ps1` builds it from the retail archives,
> through the editor's own operations, into a folder of its own; that folder's `BUILT.txt` names the
> commit and source tree that built it, every archive it changed, and every step's own account. It used to
> be built by hand once and packed into one dump, so walking it tested that day's editor; now a world is
> built by the code you are running, every battery builds one and reads it back (`TestWorldBuildTest`), and
> two builds from the same code and sources are byte for byte the same.

## Build it

1. `powershell -ExecutionPolicy Bypass -File build.ps1` — the world names the commit this stamps.
2. `powershell -ExecutionPolicy Bypass -File testworld.ps1 -Out <a new folder> -Image "<the decrypted
   Omega Ruby .cci>"`

The sources are `RomFS_original_garcs`, then the cartridge image, then the live game folder. Every archive
the world reads is taken from the first source holding the **retail** copy, by its SHA-256; the cartridge
image supplies the story text the pristine copy lacks. `BUILT.txt` then says **CANONICAL**. Without the
image the live game's story text is not retail and the build refuses, naming it — `-NotVanilla` builds
anyway and the stamp says **NOT CANONICAL** and which archive was not retail. Report bugs against a
canonical world.

Nothing is written to the game folder, the workspace or the cartridge image. The folder holds `romfs\`
(only the 9 archives the world changes, at their RomFS paths) and `BUILT.txt`.

## Deploy it — and take it away again

The world is built over the **retail** game, so its archives carry none of your own edits: while it is
installed, your own mod is not.

1. Close the emulator. Move your own mod aside: `%APPDATA%\Azahar\load\mods\000400000011C400` to
   `%APPDATA%\Azahar\load\mods_disabled\000400000011C400`.
2. Copy the world folder's `romfs` into `%APPDATA%\Azahar\load\mods\000400000011C400\` (so the files land
   in `...\000400000011C400\romfs\a\...`). No `exefs` patch is needed: the world adds no zones.
3. Start the emulator. To take the world away: delete that `000400000011C400` folder and move your own back.

Enter the world **after you have your starter** — the battle challenge needs a party, and the game's
opening is fragile until then.

## What it is

One zone and one room, the retail game's two placeholder slots (zones 473 and 474 — the only ones the
editor will take without asking; see `MapPainter.interiorSlots`):

| Zone | What it tests |
|---|---|
| 473 — the world | a clone of Route 102 with its own map and its own area; one map cell repainted in place (the composite pipeline): every brush, heights, a ramp, ledges, stairs, water with the sea-scroll animation, a waterfall, a Pokemon Center, signs, decor, a talking NPC and the battle challenge |
| 474 — the Center's room | the private interior its door was given (a clone of the retail Pokemon Center room), with a small path patch painted in place on its floor |

**The way in:** both doors that lead into the rival's house in Littleroot — the town's front door, and the
one in the player's own house map — now lead to the world. You arrive on a mat at its south side; stepping
back onto the mat returns you to Littleroot.

**The map** (tiles of the repainted cell, x east and y south): a path plaza ringed two tiles deep with rock,
so nothing walks off onto the Route 102 scenery around it. Everything outside the ring is genuine Route 102,
kept by the composite pipeline — that is the feature, not a leftover.

- **West:** tall grass (wild Pokémon — Route 102's table), a bike rail two tiles wide, deep sand.
- **North:** a plateau one level up — a ramp in the middle of its south edge, a ledge row either side of
  the ramp, stairs on its east edge.
- **East:** one column of the brushes a route has no material for — cave floor, indoor floor, boardwalk,
  ice (five tiles, to slide on), sand. These materials are imported from other maps.
- **South:** a pool with a three-tile waterfall on its south rim, the arrival mat below it, the Pokémon
  Center to the west of the pool, an iron fence and a fir tree.
- **The plaza:** two signs, the talking NPC and, beside it, the battle challenge.

## The walkthrough

### 0. Boot
The game boots to the title screen and loads your save. If it does not, stop: that is packing (see the
table).

### 1. Enter the rival's house in Littleroot
You arrive on the mat at the world's south side. Step off and back on: you return to Littleroot.

### 2. The plaza
- **Sign 1** says the world was built by the test world builder. **Sign 2** points at the plateau.
- **The talking NPC** speaks two lines.
- **The battle challenge** (beside it) battles three trainers authored for the world, through the game's
  ordinary battle natives: Newbie Nick (Zigzagoon Lv3), Rookie Rita (Poochyena Lv5), Boss Bruno (Wurmple Lv4
  and Taillow Lv6). Expect 3 BP a win; the streak advances each win (talk again for the next); 20 more BP
  after the third; a loss starts the streak over; the streak survives saving and reloading.

### 3. The stations
- **Tall grass:** rustle and wild encounters.
- **Bike rail:** passable only on the bike, like a Cycling Road rail.
- **Deep sand:** slow, with footprints.
- **Plateau:** walk up the ramp; the cliff edges block you; jump down the ledges (you cannot climb back up
  through them); climb the stairs on its east edge.
- **East column:** one step on each; ice slides. Check each LOOKS like cave, indoor floor, boardwalk, ice,
  sand — not like grass or a wall.
- **Pool:** it must ripple and scroll like a sea route. Surf on it if you can; try the waterfall.
- **Fence and tree:** correct textures; they block movement.

### 4. The Pokémon Center
Walk through its door (it swings open) into your own private Center — heal, use the PC — then step on the
exit mat: you come back out at the Center's door. Inside, a 3×2 path patch is painted on the floor near the
middle of the room: it is visible and walkable, and the rest of the room — counter, machine, walls, nurse —
is intact.

## If something goes wrong: symptom → culprit

Report the row that matches, plus whether it froze (music keeps playing?) or hard-crashed, and the commit
line of the world's `BUILT.txt`.

| Symptom | Culprit subsystem |
|---|---|
| Game won't boot / dies before the title screen | GARC packing (global) — take the world away; everything else is moot |
| The rival's house is just the rival's house | The world is not deployed — check the mod folder, fully restart the emulator |
| Crash or freeze entering the rival's door | The warp redirect, or the world's zone header (zone cloning) |
| The world loads but terrain is invisible, black or full of holes | Composite map geometry (clipping / the model writer) |
| You walk through walls, or get stuck | Composite collision merge |
| A station floats above or sinks below the scenery beside it | Composite height frame |
| Route 102 scenery pokes through a painted floor | Composite clipping missed geometry |
| A gap at the plaza's edge where a Route 102 tree or ledge used to be | Wall regeneration at cut edges |
| One station misbehaves (ice does not slide, a ledge will not jump, the rail lets you walk) | That tile's behaviour bytes — name the station |
| Tall grass rustles but no encounters | The encounter editor's save - the build copies Route 102's table to the world, as "Copy from zone" does |
| A sign does nothing when you press A | Sign script injection |
| A sign shows garbled text | Story-text encoding |
| The talking NPC freezes the game | Talker script cloning |
| An NPC is invisible or silent | NPC placement |
| Crash or freeze near the Pokémon Center, the fence or the tree | Building stamping / prop placement / texture carry |
| Textures wrong or checkerboard | Cross-area texture carry |
| The Center's door will not warp, or crashes | Interior clone wiring |
| The Center's exit leads somewhere else | Interior exit-warp retargeting |
| The patch inside the Center is invisible | Composite visual path |
| The room is damaged around the patch | Composite clipping regression — the thing it must never do |
| The water is still | The sea-scroll animation in the world's own area |
| Talking to the challenge does nothing, or freezes | Challenge script emission, or the battle natives unavailable here |
| The battle starts but the opponent is empty or glitched | Trainer authoring (the three entries `BUILT.txt` names) |
| Wrong sprite or a blank class name in the intro | The class the trainers took from Route 102's first trainer — open Game Data → Edit trainer and try another |
| A win but no BP | The BP-award natives — report the counter before and after |
| The streak does not advance, or resets on its own | The streak work variable `BUILT.txt` names may be the engine's — report it |
