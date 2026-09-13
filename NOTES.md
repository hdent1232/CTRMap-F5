**CTRMap-F5 1.0.2 — browse zones before you open one, and wire two of them together.**

A world editor for Pokémon Omega Ruby, Alpha Sapphire, X and Y. Build towns that were never in
the game, then play them.

### Download

| | |
|---|---|
| **`CTRMap-F5-1.0.2-windows-x64.zip`** | Unzip, double-click `CTRMap-F5.exe`. **No Java needed.** Start here if you're not sure. |
| **`CTRMap-F5-1.0.2-portable.zip`** | 5 MB instead of 27, but needs Java 8+. `run.bat` on Windows, `run.sh` on macOS/Linux. |

Already have 1.0.0 or 1.0.1? Help > Check for updates will offer this one and replace the copy
you have.

### New

- **A zone browser in the Zone Loader tab.** A list you arrow through, the zone under the cursor
  drawn live beside it, and a **Load this zone** button. Arrowing past a row only draws it, so you
  can look at five candidates without loading any of them — the dropdown still loads on selection,
  because choosing there *is* the load.
- **Connect two zones through a warp, both ways, in one action.** Wiring two maps together used
  to mean loading one, retyping a target in the warp form, loading the other and retyping it back,
  with nothing checking that the halves agreed. Half a link is a door the player walks through and
  cannot walk back out of; this writes both ends or refuses.
- **A created zone owns its area and its dialogue**, not just its map. The area carries the
  atmosphere, the water animations, the prop registry and the NPC models — sharing one meant a fog
  edit in a new zone changed the zone it was cloned from, and a line of dialogue written for it
  appeared in the donor's town.
- **Workspaces made by older versions repair themselves when you open them**, for all three
  resources. Measured on a real workspace: zones 536–539 still shared story text 491 with zone 534,
  and 537–539 still shared area 24 — the repair gives each of them their own.
- **Extras offers what opens in Extras.** The tileset editor and Workspace & paths were reachable
  only from menus. **Ctrl+S** saves and **Ctrl+D** deploys. **Help > Quick start guide** opens the
  guide that has always shipped beside the program and had no door.

### Fixed

**Adding zones.**

- **"An appended zone is already pending. Pack the workspace before adding more."** Packing could
  not lift it, in any version — the check asked whether a path was in the persisted-file list, and
  packing does not touch that list. One orphaned entry (from a pack that threw part way, a revert,
  a hand-deleted file) blocked zone appending for the rest of that workspace's life. Six operations
  asked the question that way; all six now ask whether the file is *there*, and the list drops
  entries whose file is gone as it reads them, so an affected workspace heals when you open it.
- **The padding zones kept the donor's city.** Adding zones rounds the count up to a multiple of
  four, and the spares were never forked: adding one zone to Sootopolis left 537, 538 and 539
  pointing at Sootopolis's own map, so editing a spare rewrote the city and its siblings. Every
  appended zone is forked now, and a spare is forked into a *blank* map — 3.6 MB smaller on a 2×2
  city, and an unused slot opens empty instead of on somebody else's town.
- **Reverting "add zones" left the encounter pack grown**, so the next append refused with
  "EN pack count 540 != zone count 536". A revert that leaves you unable to append again has not
  reverted.
- **The shared-map dialog stopped asserting history it cannot know.** It told users a zone "was
  added before the editor forked new zones automatically" — including zones this editor had created
  seconds earlier.

**Reads that returned the wrong bytes and said nothing.**

- **An archive shorter than its own table handed back buffers of zeros and reported success.**
  `skip()` and `read()` answer how much they actually managed and both answers were being dropped.
  Measured on a half-truncated archive: 278 of 431 entries came back pure zero, none null, no
  exception. A container that cannot read itself now refuses and names the file.
- **A camera table that declared more cameras than it held produced the missing ones out of
  nothing** — and the editor offered them with a Save button, which would have written them back
  over the area's real table.
- **Writes that failed now say which region or area.** A locked or read-only workspace file could
  stop an Apply with a message naming a temp path instead of the map you were editing.

**Atmospheres and menus.**

- **The atmosphere card showed a white blank page.** It is laid out for a full-height panel and was
  handed 92 pixels whenever the 3D view existed, so every colour it exists to show was cropped off.
- **Fog editing edited the fog** — and three messages sent users hunting a menu for "Map > Fork
  area", which has not existed under that name for a long time.

**Housekeeping you can see.**

- **The camera stopped walking by itself.** A movement key whose release was lost — a focus change,
  a modal dialog, the window closing — left a thread driving the camera for the life of the process
  and kept the program alive after its last window closed.
- **The release ships the program, not its proofs.** Every earlier zip carried the whole test
  battery, about 40% of the jar, runnable out of the shipped artifact against your own game folder.
  The jar is 1.5 MB now.

### How this was checked

Verified against a real dump by 130 headless test suites: every format writer round-trips
byte-identically across all 536 zones, and the guards themselves are measured — a mutation sweep
breaks each fix on purpose and records every change no suite notices. For 1.0.2 that sweep broke
202 lines and every one of them was caught.

What the suites cannot check is the game: load a map you have edited in an emulator before you
build on top of it.
