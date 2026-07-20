# CTRMap-F5
A world editor for the Nintendo 3DS Generation 6 Pokémon games (X/Y, Omega Ruby/Alpha Sapphire).

**CTRMap-F5** is a revival and major extension of [HelloOO7's CTRMap](https://github.com/HelloOO7)
(2019, preserved via the Rynbo mirror), continued in 2026 with the goal of making Gen 6 fan games
as buildable as Gen 3 ones. The "F5" edition was developed with Anthropic's Claude (Fable 5).

![CTRMapPreview](https://user-images.githubusercontent.com/20842714/63652270-91e3f600-c75e-11e9-9131-74c4f1a65c2e.png)

**Start here: [QUICKSTART.md](QUICKSTART.md)** — how to point the editor at a RomFS dump, open
zones, and use every feature below.

## Features

Inherited from the original CTRMap:
- Tilemap move-permission editing
- Collision mesh editing + OBJ import
- World prop, 3D camera, and NPC placement
- Map matrix editing, zone headers, warps
- Experimental Pawn script assembler/disassembler

New in the F5 edition (2026):
- **Script trigger editing** — the "step on this tile → run script" events, fully decoded, with a
  dedicated tool and map overlay
- **Talking NPCs without scripting** — *Add talking NPC...* creates an NPC with your dialogue and a
  generated talker script; works in **all 536 ORAS zones** (zones lacking the message-display
  routine get it injected automatically, transplanted from the game's own code)
- **Dialogue editing** — view and edit what any vanilla talker NPC says (STORYTEXT support with a
  full, byte-faithful text codec that fixes several silent data-loss bugs found in prior tools)
- **Named prop palette** — search props by their real model names ("tree", "pc01"...), preview in
  3D, place; **cross-area texture import** automatically carries a prop's textures into areas that
  lack them (previously a guaranteed hardlock)
- **Zone cloning** — copy any zone over another slot, master-table sync included
- **UI Text editor** tab for GameText
- Byte-faithful writers throughout: zone entities, zone headers, text, prop registries, and BCH
  texture packs all round-trip **byte-identically** against a real ORAS dump (536/536 zones,
  175/175 GameText files, 637/637 STORYTEXT files, 228/228 texture packs)
- Command-line build (`build.ps1`), bundled JOGL, a 10-suite headless regression battery under
  `src/ctrmap/tests/`, and many crash/data-loss fixes

## Building

No IDE required:

```
powershell -ExecutionPolicy Bypass -File build.ps1
```

Requires any JDK 17+ (targets Java 8 bytecode). The JOGL jars are bundled in `lib/`. Run with:

```
java -Xmx1024m -cp "build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar;lib/jogl-all-natives-windows-amd64.jar;lib/gluegen-rt-natives-windows-amd64.jar" ctrmap.CtrmapMainframe
```

(NetBeans still works for GUI-form editing; note some F5 forms are hand-coded past the generated
blocks.)

## Verification philosophy

Every binary format writer in the F5 edition is validated headlessly against a real, legally
dumped ORAS RomFS: parse → re-serialize must be byte-identical, and every surgical operation
(talker cloning, routine injection, texture import) is dry-run across *every* eligible zone/pack
in the game with full structural re-verification before it is allowed into the UI. Run the suite
with the test classes in `src/ctrmap/tests/` (each is a `main()` class printing PASS/FAIL).

No ROM data, Nintendo assets, or game files are included in this repository.

## 3D model editing and conversion

You can add new 3D models to the Pokémon world with [SPICA](https://github.com/HelloOO7/SPICA)
(HelloOO7's fork). Native model injection is on the F5 roadmap.

## Credits & license

- **HelloOO7** — the original CTRMap (2019), the foundation of everything here
- **gdkchan** — SPICA / Ohana3DS research the model/texture code descends from
- **Kaphotics / pk3DS contributors** — decoded structures (triggers, text crypt) referenced under GPLv3
- F5-edition development driven by Claude (Fable 5, Anthropic), 2026

GPLv3, same as the original — see [LICENSE](LICENSE).
