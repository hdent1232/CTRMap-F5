**CTRMap-F5 1.0.5 — an emergency fix for 1.0.4: the zone list can no longer be lost, the launch warning ends, and the freeze is gone.**

A world editor for Pokémon Omega Ruby, Alpha Sapphire, X and Y. Build towns that were never in
the game, then play them.

### Download

| | |
|---|---|
| **`CTRMap-F5-1.0.5-windows-x64.zip`** | Unzip, double-click `CTRMap-F5.exe`. **No Java needed.** Start here if you're not sure. |
| **`CTRMap-F5-1.0.5-portable.zip`** | Smaller, but needs Java 8+. `run.bat` on Windows, `run.sh` on macOS/Linux. |

On 1.0.4? Help > Check for updates offers this one and replaces the copy you have. The Help menu
works even when 1.0.4 has left you on a zone tool with no way back to the zone list.

### What is fixed

- **You could be locked out of loading any zone.** In 1.0.4 a zone tool (Connect zones, Clone
  zone and others) covered the zone list, the only way to open a zone, and the way back was a flat
  button that looked like a caption, with nothing in the menus. Every view the editor swaps now has
  a plain **Back to ...** button above it, **Esc** does the same, and **Zone list** is on the zone
  actions bar and in the Zone menu, so the list is always one click away.
- **"Zones added by an earlier version still share another zone's resources ... An area fork is
  already staged and not yet packed" on every launch.** The repair that runs when a workspace
  opens picked its new ids from the end of what was already packed, found an unpacked change
  there, and gave up - on every launch, with nothing saying how to end it. Every append now counts
  past what is already staged, so the repair finishes. **The first launch of 1.0.5 packs your
  workspace** when it repairs it, as any repair does, and that includes any change you made and
  never packed.
- **A long freeze every time the zone list was filled**: on opening, and after every clone, add,
  undo or repair. The zone browser was rebuilt once per zone, 540 times; it is now built once.
  Measured through the editor's own window on the same workspace and game: the worst freeze was
  15.7 seconds in 1.0.4 (24 ms in 1.0.3), and the zone list is full 8 seconds after launch in
  1.0.5, against 22 in 1.0.4 and 16 in 1.0.3.
- **Messages no longer send you to the zone dropdown** that 1.0.4 removed. Twelve of them now
  name the zone list.
- **The downloads unpack correctly on macOS and Linux.** Every path inside the 1.0.4 zips was
  written with a Windows backslash (`lib\jogl-all.jar`), which unpacks off Windows as one oddly
  named file instead of a folder. A macOS or Linux copy that updated itself to 1.0.4 may have been
  left with the old 3D libraries: if it will not start, download the portable zip again and unpack
  it over it.

### What is new

- **Re-link zone** (zone actions bar, and Zone > Re-link zone): a zone that was made independent
  can go back to sharing its map, area, story text or script with the vanilla zones it shared them
  with as shipped - one at a time, or **All back to as shipped** - or share another zone's. It is
  one change Undo can take back, and the private copy a zone leaves behind stays in the game,
  unused, so you can re-link back to it.
- **Connect zones through a warp is gone.** It was not the feature that was asked for.

Report anything odd on the issue tracker (Help > Support/Issue tracker).
