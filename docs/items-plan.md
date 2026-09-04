# Plan: item and behaviour editing

Status: **written, not started.** Grounded in three read-only investigations of the retail
game (2026-09-03); every offset and count below was measured, not assumed. Raw findings:
`wt/_state/items_feasibility.json`.

## The one thing that decides the design

**Reassigning an existing behaviour is a data edit. Authoring a new behaviour is not possible
generally.**

Hold-effect ids are a dense, fully-occupied palette: 183 values, 0..182, no gaps, and retail
*shares* them across items — Mystic Water, Sea Incense and Wave Incense are all effect 77;
Amulet Coin and Luck Incense are both 57. That sharing is the proof that the number selects the
behaviour rather than identifying the item. Point any item at any of the 183 and it behaves that
way, for the cost of one byte.

There is no room to add a 184th. A search of `code.bin` and all 145 `.cro` modules for the ARM
jump-table idiom (`cmp Rn,#N` / `ldr<cc> pc,[pc,Rm,lsl#2]`) found the largest switch anywhere is
161 cases; `DllBattle.cro` tops out at 63. **No 183-case dispatch structure exists.** The
hold-effect id is compared at scattered sites inside an event-handler architecture, so there is
no single hook a general tool could extend.

Worse for the hardest cases: some behaviour is keyed on the **item id itself**, not the record.
Exp. Share (216) appears as a literal at 6+ `code.bin` sites and inside nine separate CROs —
`DllBag.cro` 0x2208 is `cmp r7,#0xD8`. Its record shares field-routine 5 with the Repels, which
proves the record is not what makes it an Exp. Share. Ability Capsule (645) has an all-zero
record and works anyway.

So the tool owns the data layer completely and the code layer not at all. Any design that blurs
that will promise things it cannot deliver.

## What the game actually holds

| thing | where | shape |
|---|---|---|
| item records | GARC `a/1/9/7` | 776 × 36 bytes, uncompressed, **id == entry index**, contiguous from 0x3CE0 |
| item names | gametext `a/0/7/3` file 114 | 776 lines, **line index == item id** |
| item descriptions | gametext `a/0/7/3` file 117 | 776 lines, same 1:1 |
| item → icon | `code.bin` file offset 0x47C644 | `u32[776]`, **zero slack** (next table at 0x47D264) |
| icon textures | GARC `a/0/9/2` | 631 entries, LZ11, 32×32 |
| personal (species) data | GARC `a/1/9/5` | 826 × 0x50; abilities at 0x18/0x19/0x1A |

The 36-byte record is fully mapped — price, hold effect + argument, fling, natural gift, field
and battle use routines, pockets, sort index, status-cure mask, stat-boost nibbles, six signed EV
deltas, heal amount, PP gain, three friendship deltas. Verified against retail values (Ultra Ball
1200, Life Orb argument 30, Rare Candy level-up bit, HP Up +10 EV HP). Two bytes (0x22, 0x23) are
zero in all 776 records.

Two gotchas worth carrying into the code: byte 0x10 is **context-dependent** (a status-cure mask
normally, but the ball index for Balls), and byte 0x03 is a general magnitude that is *mirrored*
into the typed field for healing items — 112 items use it, 98 of them for something of their own.

## Do not rebuild pk3DS

`pk3DS` (GPLv3, compatible, in this tree) already edits item data *and* personal data for ORAS.
Its `Item6.cs` carries the same 36-byte layout, its `GARCReference_AO` knows archive 197 =
`a/1/9/7`, and its `TextReference` knows 114/117. **Building a plain item editor in CTRMap is
duplication** unless it buys something pk3DS cannot: workspace integration, deploy through the
existing pipeline, and cross-referencing against the map data CTRMap already owns.

That is the honest justification, and it should be stated in the UI's own terms — this is the
item editor for people already building a world in CTRMap, not a better pk3DS.

## The design: a schema registry, not another hardcoded class

Every structure these investigations touched is a **fixed-stride record array**: items 776×0x24
in a GARC, personal 826×0x50 in a GARC, gift encounters 37×0x24 at a fixed offset inside
`DllField.cro`, BP prices N×8 in the executable, shop stock 215×2 behind a string anchor.

CTRMap today encodes each such discovery as a hand-written Java class — `ZoneLimitPatch`,
`ShopData` — which is exactly why every new one needs a developer. **Make the discovery a data
row instead.** A registry entry names:

- the container (GARC path, CRO name, or `code.bin`)
- the locator (entry index, absolute offset, or a byte/string anchor **with stock bytes to verify**)
- the stride and record count
- a field list: offset, width, signedness, and a semantic kind (raw number, enum, item id,
  species id, ability id, bitfield)

One generic record editor renders any of them, resolving ids to names through the gametext
indices the game profile already knows. Writing back to a GARC uses the existing archive layer;
writing back to `code.bin` reuses `ShopData`'s diff-IPS-and-merge path **including its stock-byte
verification**, so a mismatched build is refused rather than corrupted.

Tomorrow's discovery becomes a TSV edit.

## Effect labels: derive them, don't author them

The game ships no name table for the 183 hold effects, 27 field routines, 24 natural-gift effects
or 31 fling effects. A dropdown reading `hold effect: 77` is useless.

The fix is free and self-maintaining: **for each effect id, list the retail items that carry it**,
computed from the same GARC. `77 — Mystic Water, Sea Incense, Wave Incense` tells the user exactly
what it does. It cannot go stale, because it is derived from the data being edited. Add a
hand-written one-line gloss only where the item list is ambiguous.

## Three tiers, kept visibly distinct

The UI must not blur these, because their blast radii differ:

1. **Pure data** — the 36-byte record and the two text lines. Workspace files, existing deploy
   pipeline, no patch, reversible. Because records are fixed-size and contiguous, a writer can
   poke bytes in place and never repack the GARC — which also sidesteps the stale-pack corruption
   this project has already been bitten by.
2. **Code-side per-item table** — the icon, one `u32` at `0x47C644 + 4*id`, shipped as an IPS
   merged into the same `code.ips` as the zone and shop patches. Labelled "needs the code patch".
3. **Nothing else.** The editor must not pretend a third tier exists.

## Order of work

1. `ITEM_DATA` archive type + `a/1/9/7` in the game profile; `ITEM_DESCRIPTIONS` = 117 beside the
   existing `ITEM_NAMES` = 114. This is the entire integration surface.
2. `ItemData` record class, field-per-offset (the layout is a Gen 6/7 constant, so it belongs in
   `formats/pokedata/`, not in a profile). Guard: round-trip all 776 retail records byte-for-byte.
3. The derived effect-label table. Guard: every id used by a retail item resolves to a non-empty
   item list.
4. The schema registry and the generic record editor, with items as its first consumer.
5. Icon reassignment as tier 2, reusing `ShopData`'s IPS path.
6. **New items**: ids 113, 114, 115 and 126 are genuinely empty (zero record, `???` name, blank
   icon) and sit below every engine bound, so they already work. Four is a real limit, not a
   design choice — say so in the UI.

Deliberately **not** in scope: raising the 776 ceiling (14 located `cmp #776` sites, 12 in
`code.bin` and 2 in a CRO — `ZoneLimitPatch`-shaped but the CRO half is gated on `static.crr`'s
145 hashes), and anything that authors a new behaviour.

## The behaviour half lives in scripts, not items

The capability the owner actually described — an in-game way to change a Pokémon's nature or
ability — is **not an item feature**. `PokePartyGetParam` / `PokePartySetParam` are real script
natives, already listed in CTRMap's own `src/ctrmap/resources/oras_natives.tsv`, and used by 22
and 4 retail zone scripts respectively. With `CallPokeSelect` to pick the Pokémon and
`CallBag`/`ItemGetNum` to charge an item, that is an NPC — no code patch, no new UI.

One bounded blocker: nobody has established which integer means Nature, Ability, IV_HP, EV_Atk.
Recover it by disassembling those 22 retail scripts with CTRMap's existing `PawnDisassembler` and
recording the constants pushed before each SYSREQ. Roughly a focused day.

And EV/IV display already ships in the game: the IV Judge is storytext entry 246 (four tiers plus
a best-stat line) and Super Training is the EV readout. Re-siting the Judge into another zone is
NPC placement work, not UI work.
