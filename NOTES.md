**CTRMap-F5 1.0.0 — the first packaged release.**

A world editor for Pokémon Omega Ruby, Alpha Sapphire, X and Y. Build towns that were never in
the game, then play them.

### Download

| | |
|---|---|
| **`CTRMap-F5-1.0.0-windows-x64.zip`** | Unzip, double-click `CTRMap-F5.exe`. **No Java needed.** Start here if you're not sure. |
| **`CTRMap-F5-1.0.0-portable.zip`** | 5 MB instead of 27, but needs Java 8+. `run.bat` on Windows, `run.sh` on macOS/Linux. |

### You need your own copy of the game

CTRMap ships no game files and cannot download them. It edits a copy of a game **you own**, which
you unpack yourself from your own cartridge or eShop copy. A setup wizard walks you through
pointing it at one, and tells you what to look for.

### What's in it

- **Setup wizard** on first run — finds your unpacked game, validates it, and explains exactly
  what's wrong when you pick the wrong folder.
- **Map Builder** — paint terrain onto real maps and keep everything you didn't touch.
- **3,583 catalogued buildings** to search, preview and stamp.
- **Talking NPCs without scripting**, in all 536 zones.
- **Trainers, shops, wild encounters and battle facilities.**
- **Live 3D view**, area fog and lighting, zone cloning and appending.
- **Deploy to emulator** — ships only what you actually changed, and switches off again when you
  want the retail game back.
- **In-app updates** that replace the copy you have instead of leaving a second one beside it.

Verified against a real dump by 84 headless test suites: every format writer round-trips
byte-identically across all 536 zones, and the guards themselves are measured - a mutation
sweep breaks each fix on purpose and records every change no suite notices.

GPLv3. A continuation of [HelloOO7's CTRMap](https://github.com/HelloOO7).
