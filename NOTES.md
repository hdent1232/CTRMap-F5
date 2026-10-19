**CTRMap-F5 1.0.4 — the audit and the census closed, undo across the editor, and JOGL 2.6.0.**

A world editor for Pokémon Omega Ruby, Alpha Sapphire, X and Y. Build towns that were never in
the game, then play them.

### Download

| | |
|---|---|
| **`CTRMap-F5-1.0.4-windows-x64.zip`** | Unzip, double-click `CTRMap-F5.exe`. **No Java needed.** Start here if you're not sure. |
| **`CTRMap-F5-1.0.4-portable.zip`** | Smaller, but needs Java 8+. `run.bat` on Windows, `run.sh` on macOS/Linux. |

Already have 1.0.3 or earlier? Help > Check for updates will offer this one and replace the copy
you have.

### What is in it

- **The 2026-10-06 audit:** 251 of its 255 findings fixed, 3 partly, and 1 shown not to be a defect.
- **The whole-app census of 2026-09-13:** all 280 confirmed defects are now closed — 95 of them in
  this release, the rest in the releases before it.
- **Undo and redo reach more of the editor:** zone edits, game data, text, workspace operations and
  the tileset each keep a history you can step back through.
- **A shared map shows which part of it a zone is**, with that zone's actions in one row, and the
  zone preview no longer moves its camera on its own.
- **Painted terrain closes up:** one side wall of every painted ramp was invisible from the ramp
  itself, a ramp's side cliff stood a full step above its own foot, two lanes of a ramp whose feet
  differed left a gap between them, and walls painted onto an existing route could show sky where
  they changed depth or turned an inside corner. All of these are fixed and measured.
- **JOGL 2.6.0**, the 3D library, with its native parts for Windows, Linux (x64 and ARM64) and
  macOS — each checked against the checksums JogAmp publishes.

### Nightlies

From this release on, work can also go out as a **nightly** (`1.x.y-nightly.N`), published as a
GitHub pre-release before it has been through the project's full mutation sweep. Help > Check for
updates never offers a nightly — you get one only by downloading it from the Releases page — and a
copy running a nightly is offered the next core release like everyone else.

Report anything odd on the issue tracker (Help > Support/Issue tracker).
