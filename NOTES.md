**CTRMap-F5 1.0.1 — the editor stops losing your work.**

A world editor for Pokémon Omega Ruby, Alpha Sapphire, X and Y. Build towns that were never in
the game, then play them.

### Download

| | |
|---|---|
| **`CTRMap-F5-1.0.1-windows-x64.zip`** | Unzip, double-click `CTRMap-F5.exe`. **No Java needed.** Start here if you're not sure. |
| **`CTRMap-F5-1.0.1-portable.zip`** | 5 MB instead of 27, but needs Java 8+. `run.bat` on Windows, `run.sh` on macOS/Linux. |

Already have 1.0.0? Help > Check for updates will offer this one and replace the copy you have.

### What's fixed in 1.0.1

Every one of these is something 1.0.0 got wrong where you could not see it. Most were found by
reading the editor's own failure paths rather than by hitting them.

**Edits that went nowhere.**

- **File > Save silently discarded every tile edit to a loose GR map.** It saved the matrix path
  and skipped the single-region one. Nothing said so.
- **Saving a zone never committed the warp editor**, and threw away the NPC editor's refusal
  three lines above one it honoured — so a zone could report "saved" with a warp still unwritten.
- **The prop editor could go permanently inert.** One out-of-range model number left it looking
  switched on and responding to nothing until you restarted.
- **Answering "save" for props with no map open** dropped the edits *and* marked them clean, with
  nothing said anywhere. It now refuses in words and keeps them.
- **A matrix that failed to load left the matrix editor writing nothing** — every chunk id, LOD
  reference, multizone cell and camera boundary you typed afterwards was dropped in silence.

**Data written into the wrong place.**

- **Switching matrix tools wrote a sub-chunk coordinate into the region grid.** Going multizone,
  clicking a cell and switching back put the next save into the *wrong region* — and quietly,
  because the number was in range.
- **File > Open Zone planted a loose `.zo` at whatever table row the dropdown last showed**, and
  the master zone-header table followed it there, replacing an unrelated map's entry.
- **Add row / Add column corrupted the save** when "Allow LOD and Multizone" was unticked at the
  time and ticked back on before saving.
- **Removing the first of two props** left the editor showing the prop you had just deleted, and
  the survivor could not be selected from the list or either tool.

**Crashes, including ones you only saw as things not working.**

- The **Matrix Editor's three tool buttons threw on the first click** from a cold start.
- The **3D view threw sixty times a second** if you turned it on before opening a zone.
- **Opening a smaller map after picking a tile** threw out of the tile inspector on the next
  category click.
- Saving a matrix you had grown, a region with no model, and a map with no collision mesh each
  had a path that threw instead of coping.

**Things that looked wrong on screen.**

- **Changing the tileset in Workspace Settings repainted every region in the old palette.**
- **The matrix grid could not be scrolled** when the matrix was bigger than the panel, so cells
  past the edge could not be seen, selected or edited at all.
- **Opening a loose GR map** kept the previous zone's textures, its undo history, its picked
  tile and its camera angle. It now asks before dropping unsaved entity edits, and clears all
  four.
- **The picked-cell rectangle survived zone loads**, marking a cell you had not chosen.
- **"Trainer ID (1..949)" refused 949.** Trainer 949 is real.
- Typing a prop's coordinate moved it in the 3D view and left the 2D map drawing it where it was.

### You need your own copy of the game

CTRMap ships no game files and cannot download them. It edits a copy of a game **you own**, which
you unpack yourself from your own cartridge or eShop copy. A setup wizard walks you through
pointing it at one, and tells you what to look for.

### What's in it

- **Setup wizard** on first run — finds your unpacked game, validates it, and explains exactly
  what's wrong when you pick the wrong folder.
- **Map Builder** — paint terrain onto real maps and keep everything you didn't touch.
- **3,583 catalogued buildings** to search, preview and stamp — 48 curated and door-wired,
  3,535 auto-harvested from every map in the game.
- **Talking NPCs without scripting**, in all 536 zones.
- **Trainers, shops, wild encounters and battle facilities.**
- **Live 3D view**, area fog and lighting, zone cloning and appending.
- **Deploy to emulator** — ships only what you actually changed, and switches off again when you
  want the retail game back.
- **In-app updates** that replace the copy you have instead of leaving a second one beside it.

Verified against a real dump by 129 headless test suites: every format writer round-trips
byte-identically across all 536 zones, and the guards themselves are measured - a mutation
sweep breaks each fix on purpose and records every change no suite notices. For 1.0.1 that
sweep broke 209 lines and every one of them was caught.

GPLv3. A continuation of [HelloOO7's CTRMap](https://github.com/HelloOO7).
