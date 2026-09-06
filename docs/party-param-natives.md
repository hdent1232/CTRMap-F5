# PokePartyGetParam / PokePartySetParam — what the arguments are

Measured 2026-09-04 by disassembling all 536 retail ORAS zone scripts with
CTRMap's own `PawnDisassembler`. Probe: `wt/_state/queue1/probe/Param.java`.

## Why this was worth doing

`docs/items-plan.md` names this the one bounded blocker on the behaviour half of
the item work. The capability actually wanted — an in-game way to change a
Pokémon's nature or ability, or to read out IVs and EVs — is **not an item
feature**. It is an NPC: `PokePartyGetParam` / `PokePartySetParam` are real
script natives, `CallPokeSelect` picks the Pokémon, and `CallBag` / `ItemGetNum`
can charge for it. No code patch, no new UI. The only thing missing was knowing
which integer selects which parameter.

## Measured

    PokePartyGetParam    160 calls across 29 zones
    PokePartySetParam     13 calls across  5 zones
    CallPokeSelect         5 calls across  4 zones

The plan estimated 22 and 4 zones. The real figures are **29 and 5**.

### Call shape

Both party-param natives take three arguments (argbytes 12). Pawn pushes
arguments in reverse, so the last `PUSH_*` before the `SYSREQ_N` is argument 1,
and the arguments reach the native as `params[1..3]`:

    PUSH_P_C <value / index>    arg3   (pushed first, furthest from the SYSREQ)
    PUSH_P_C <parameter id>     arg2   <-- the selector
    PUSH_P_S <party slot>       arg1   (pushed last, nearest the SYSREQ)
    SYSREQ_N <native>, 12

`<var>` below means the argument came from a stack slot rather than a literal,
so its value is not visible statically.

**Correction, 2026-09-06.** The first probe printed its three columns in the
opposite order: the column it labelled `arg1` was the FURTHEST push and the one
labelled `arg3` was the nearest. The middle column - the selector, which is all
the histogram below was ever used for - is unaffected, so the finding stands.
The outer two were the wrong way round, which is why the original note reads
"arg1 is `<var>` in 11 of the calls; arg3 is `<var>` in 140 of 160": it is the
PARTY SLOT that is a variable in 140 of 160 calls, which is what you would
expect when the player picks the Pokemon. `PartyParamTest` now pins this down
without relying on anyone's labelling: every literal in the nearest-push
position, across both natives, is in 0..5, and a party slot is the only
argument that can be.

### PokePartyGetParam — arg2, the parameter id

    8  x42     0  x30     2  x22     13 x11     49 x6      53 x6
    44 x5      10 x4      48 x3      1  x2      3  x2      4  x2
    5  x2      45 x2      11 x1      12 x1      14 x1      15 x1
    29 x1      36 x1      37 x1      38 x1      39 x1      40 x1
    46 x1      47 x1      51 x1
    (152 of the 160 calls pass a literal selector; the party slot is a
     variable in 140 of 160)

**Correction, 2026-09-06.** The row `0 x30` was missing from the first version
of this table - selector 0 is the second most used of all, and reading it as
"retail never asks for selector 0" would have been wrong. Re-measured by
`ctrmap.tests.PartyParamTest`, which now locks the whole histogram.

So Get selectors occupy a small dense range, 0..53.

### PokePartySetParam — arg2

    1007 x12     1006 x1

**Set uses a completely different id range from Get.** Twelve of thirteen calls
write parameter 1007. This is the sharpest single finding here: whatever 1000+
means, retail almost never writes party parameters from script, and when it does
it writes essentially one thing.

### CallPokeSelect

Identical in all five calls: `(1, 0, 32780, 1)`. 32780 = 0x800C, which looks
like a flag word rather than a count.

## ANSWERED, 2026-09-06: what the selectors are

The question is settled from the retail ARM code. The machine-readable answer
is `src/ctrmap/formats/pokedata/PartyParam.java`; this section is the working
and the evidence. `ctrmap.tests.PartyParamTest` guards it.

### How the implementations were found

The brief expected a `(hash, function pointer)` table in `code.bin`. **There is
no such table, in `code.bin` or in any `.cro`.** The 32-bit name hashes
`E5AB2CFA` / `6EFC380E` do not appear anywhere in the executable as a word, a
`MOVW`/`MOVT` pair or a literal-pool constant. `GfHash`'s own javadoc already
said so, from the earlier work that cracked the hash: the engine registers each
native **by name** at CRO load and hashes the string at run time.

The names are what to look for, and they are present as plain ASCII:

    DllField.cro   "PokePartyGetParam" @ file 0x10CF4C   "PokePartySetParam" @ 0x10CF5E

Those strings sit in the module's read-only segment (segment 1, file 0xF1000),
and the registration table is an array of `{const char *name; int (*fn)(...);}`
pairs in the same segment. Both fields are relocated, so the file holds zeros
where the pointers go and the values live in the CRO **relocation patch table**
(header field at 0x121710, 0x2A9E entries of 12 bytes: patch site as a
segment-encoded offset, type, last-flag, then the symbol's segment-relative
offset). Walking that table reconstructs the array.

**Known-answer check.** `oras_natives.tsv` has 801 rows. Reconstructing every
`{name, function}` pair in `DllField.cro` and hashing each recovered name with
`GfHash` yields **771 pairs, 769 distinct names, and 769/769 match a TSV row
with the same name and the same hash - zero mismatches.** The 32 TSV rows with
no pair are demonstrably not field natives at all: they are `+`, `-`, skeleton
bone names (`Head`, `LFoot`, `Waist`), and scene dummies
(`room01_pokecen_dummy1`), harvested from script name tables rather than the
native table. So the table-finding was verified against effectively the whole
known-good corpus before being trusted on the two natives in question.

One off-by-one worth recording: the pairs are `{name, fn}`, not `{fn, name}`.
Pairing a name with the pointer at `-4` instead of `+4` gives a perfectly
plausible function for every native - it is just the previous native's. It was
caught by pairing `FlagGet`, the first entry, whose implementation is obvious.

### The shape of the answer

    PokePartyGetParam   DllField.cro seg0+0x4E498
        r6 = params[1] & 0xFFFF    party slot, bounds-checked against the party count
        r7 = params[2] & 0xFFFF    THE SELECTOR
        r4 = params[3] & 0xFFFF    sub-index
        cmp r7, #0x36 ; ldrlo pc, [pc, r7, lsl #2]     <-- 54-entry ARM jump table

    PokePartySetParam   DllField.cro seg0+0x4E988
        r4 = slot, r5 = selector, r6 = value
        sub r1, r5, #0x3E8 ; cmp r1, #0x14 ; ldrlo pc, [pc, r1, lsl #2]
                                                       <-- 20-entry table, base 1000

So both really are dense jump tables after all - 54 and 20 cases, well under
the 161-case maximum the owner's note recorded. Every case tail-calls a
`pml::pokepara` accessor in the main executable through a `ldr pc, [pc, #-4]`
import veneer whose literal is filled in from the CRO's anonymous-import table,
and those accessors bottom out in one load or store at a fixed offset of the
decrypted Pokemon record.

### Why the offsets are readable as PK6 fields

The accessors reach their field through one of four block getters. Each one
computes `(PID >> 13) & 0x1F`, indexes a 24-row shuffle table, and returns
`recordBase + 8 + blockIndex * 56`. That is exactly PKHeX's documented Gen 6
PK6 block shuffle: an 8-byte header, then four 56-byte blocks A/B/C/D at
0x08 / 0x40 / 0x78 / 0xB0, ordered by `((PID >> 0xD) & 0x1F) % 24`. So a load
at `blockA + 0x16` is PK6 0x1E and nothing else, and the whole PKHeX PK6 field
map can be used as the dictionary. Sanity spot-checks that came out right
without being aimed at: `ldrh [blockA+0]` = 0x08 species, `ldrb [blockA+0x15]
>> 3` = 0x1D AltForm, `[blockB+0x34]` = 0x74 as a u32 of six consecutive 5-bit
fields plus two flag bits.

The handlers that return a party member's level and battle stats reach them
through a second pointer, `[coreParam+4]`, which is null for a boxed Pokemon
and makes those handlers fall back to computing the value. That structure lines
up the same way against PK6's party block at 0xE8: `+0` is the u32 at 0xE8,
`+4` the level byte at 0xEC, `+8` and `+0xA` the two HP halfwords at 0xF0 and
0xF2, then `+0xC`, `+0xE`, `+0x10`, `+0x12`, `+0x14` for the remaining stats at
0xF4..0xFC. Five consecutive fields of the right widths in the right order is
not a coincidence, and it is what pins selectors 2, 3, 14 and 30-35.

### CONFIRMED - handler traced to a load or store at a named PK6 offset

| Get | meaning | evidence |
|----:|---------|----------|
| 0  | Species | `ldrh [blockA+0]` = PK6 0x08 (returns a constant instead when the mon is an egg) |
| 1  | Form | `ldrb [blockA+0x15] >> 3` = PK6 0x1D bits 3-7 |
| 2  | Current HP | `ldrh [partyExt+8]` = PK6 0xF0 |
| 3  | Max HP | `ldrh [partyExt+0xA]` = PK6 0xF2; the same handler as selector 30, i.e. `GetPower(HP)` |
| 4  | Move PP, slot in arg3 | `ldrb [blockB+0x22+arg3]` = PK6 0x62+arg3, arg3 forced < 4 |
| 8  | Is egg | `(IV32 bit 30) OR runtime egg flag`; PK6 0x74 bit 30 is PKHeX `IsEgg` |
| 10 | Friendship | `ldrb [blockC+0x1A]` = PK6 0x92, or the handler's byte when `CurrentHandler != 0` |
| 11 | EV total | the six EV getters called with 0..5 and summed |
| 12 | Held item | `ldrh [blockA+2]` = PK6 0x0A |
| 13 | Ribbon, id in arg3 | tests bit arg3 of `[blockA+0x28]` = PK6 0x30 (0x34 for bits 32..63) |
| 14 | Level | `ldrb [partyExt+4]` = PK6 0xEC |
| 15 | Met level | `ldrb [blockC+0x2D] & 0x7F` = PK6 0xA5 bits 0-6 |
| 16 | **IV HP** | `IV32 & 0x1F` |
| 17 | **IV Attack** | `IV32 lsl 22, lsr 27` (bits 5-9) |
| 18 | **IV Defense** | `IV32 lsl 17, lsr 27` (bits 10-14) |
| 19 | **IV Sp. Attack** | `IV32 lsl 7, lsr 27` (bits 20-24) |
| 20 | **IV Sp. Defense** | `IV32 lsl 2, lsr 27` (bits 25-29) |
| 21 | **IV Speed** | `IV32 lsl 12, lsr 27` (bits 15-19) |
| 22 | **Ability** | `ldrb [blockA+0xC]` = PK6 0x14 |
| 23 | **EV HP** | `ldrb [blockA+0x16]` = PK6 0x1E |
| 24 | **EV Attack** | PK6 0x1F |
| 25 | **EV Defense** | PK6 0x20 |
| 26 | **EV Sp. Attack** | PK6 0x22 |
| 27 | **EV Sp. Defense** | PK6 0x23 |
| 28 | **EV Speed** | PK6 0x21 |
| 29 | Gender | egg-guarded, then `ldrb [blockA+0x15] lsl 29, lsr 30` = PK6 0x1D bits 1-2 |
| 30-35 | Battle stats HP/Atk/Def/SpA/SpD/Spe | `GetPower(0..5)` -> `partyExt` +0xA, +0xC, +0xE, +0x12, +0x14, +0x10 = PK6 0xF2, 0xF4, 0xF6, 0xFA, 0xFC, 0xF8; every case but HP calls the nature getter |
| 36-41 | Contest Cool/Beauty/Cute/Smart/Tough/Sheen | `ldrb [blockA+0x1C..0x21]` = PK6 0x24..0x29 |
| 49 | Shininess | `TID ^ SID ^ PIDlow ^ PIDhigh`, from PK6 0x0C and 0x18 |
| 50 | Is nicknamed | `IV32 >> 31` = PK6 0x74 bit 31 |

| Set | meaning | evidence |
|----:|---------|----------|
| 1000-1005 | **IV HP/Atk/Def/SpA/SpD/Spe** | value clamped to 31, then one 5-bit field of PK6 0x74 rewritten: masks 0x1F, 0x3E0, 0x7C00, 0x1F00000, 0x3E000000, 0xF8000 |
| 1006 | Friendship | value clamped to 255, then `strb [blockC+0x1A]` = PK6 0x92 (or the handler's byte) |
| 1007 | Ribbon, id in arg3 | sets bit arg3 of `[blockA+0x28]` = PK6 0x30, and 0x34 for bits 32..63 |
| 1008-1013 | **EV HP/Atk/Def/SpA/SpD/Spe** | clamped to 252, checked against a 510 total, then `strb [blockA+0x16..0x1B]` |
| 1014-1019 | Contest Cool..Sheen | clamped to 255, then `strb [blockA+0x1C..0x21]` |

### The stat order is NOT the storage order

The six-way stat families are indexed `HP, Attack, Defense, Sp. Attack,
Sp. Defense, Speed` - the battle order - while the record stores EVs and IVs in
the older `HP, Attack, Defense, Speed, Sp. Attack, Sp. Defense` order. Index 3
is Sp. Attack and index 5 is Speed, in all four families. Two independent
encodings agree on the same permutation:

    IV  index 3 -> IV32 bits 20-24 (Sp. Atk)   index 5 -> bits 15-19 (Speed)
    EV  index 3 -> PK6 0x22 (Sp. Atk)          index 5 -> PK6 0x21 (Speed)
    IV setter index 3 -> mask 0x1F00000        index 5 -> mask 0xF8000
    stat cache  index 3 -> PK6 0xFA (Sp. Atk)  index 5 -> PK6 0xF8 (Speed)

Getting this backwards would silently swap a Pokemon's Speed and Sp. Attack.
It is the single easiest thing to get wrong here.

### PROBABLE - handler traced and understood, name is the best reading

| Get | meaning | why only probable |
|----:|---------|-------------------|
| 5  | Max PP of move arg3 | combines `GetWaza(arg3)` with `GetPPUp(arg3)`; the arithmetic was not followed to the PP table |
| 6, 7 | Type 1, Type 2 | species+form lookup with an explicit species 493 / ability 121 (Arceus, Multitype) special case, which is what type resolution does and little else would |
| 9  | Is egg, strict | `(IV32 bit 30) AND NOT runtime flag`, the third mode of the same three-way |
| 44 | Fateful encounter | offset confirmed (PK6 0x1D bit 0); the PKHeX name for that bit is the only inference |
| 45 | Has a status condition | `(u32 at partyExt+0) & 0xFF` tested nonzero; the offset is PK6 0xE8, and `Status_Condition` is PKHeX's name for it |
| 46, 47 | Super Training unlocked / complete | offsets confirmed (PK6 0x72 bits 0 and 1); names from PKHeX |
| 48 | Affection | `ldrb [blockC+0x1B]` = PK6 0x93, gated on whether the player is the OT |
| 51 | Has default name | reads the 13-character nickname and compares it with the species' default name |
| 53 | Game of origin | `ldrb [blockC+0x2F]` = PK6 0xA7 |

### Traced but NOT named - deliberately absent from the table

* **42, 43, 52** discard the Pokemon entirely. Each loads a global manager
  pointer and calls into it with a constant (37, 38, 21). Whatever they return,
  it is not a party parameter.

`PartyParam.get()` returns null for all three. Null means "not established" and
callers must treat it as absence, not as an invitation to guess.

## Nature and ability cannot be changed from a script

This is the load-bearing negative and it is the answer to the feature the owner
asked for by name.

* **Nature is not reachable at all** through these two natives. The nature
  accessors exist in the executable - getter at `code.bin +0x52F08`
  (`ldrb [blockA+0x14]` = PK6 0x1C), setter at `+0x68B10`
  (`strb [blockA+0x14]`) - and the stat handlers call the getter, which is how
  selectors 30-35 were identified. But **no case of either native's switch
  reaches either one**, and only one import veneer in the whole of
  `DllField.cro` resolves to the nature getter (at `seg0+0x4178`, called from
  two places nowhere near these natives). **No veneer resolves to the nature
  setter at all.**
* **Ability can be read but not written.** Selector 22 reads PK6 0x14. The
  ability setter at `code.bin +0x4F25C` has **no veneer in `DllField.cro`**, so
  no field-script native calls it.

All 4079 of `DllField.cro`'s external patches are type 2 - absolute 32-bit data
patches - and 2914 of them sit immediately after a `ldr pc, [pc, #-4]`, so the
veneer sweep covers every call site. (The other 1165 are data pointers -
vtables, literal pools - and were not individually chased; a function whose
address is only ever taken, never called through a veneer, would escape the
sweep. Both switches were enumerated case by case regardless, which is the
stronger evidence.)

**So: an NPC can hand out IVs, EVs, ribbons, friendship and contest conditions,
and can read out nature-adjusted stats, IVs, EVs, ability, gender, shininess
and met level. It cannot change a nature and it cannot change an ability.**
Doing either needs a `code.bin` patch - a new native, or a new case in the
existing switch - which is a different piece of work with a different risk
profile, not a script change.

**No UI may offer "change nature" or "change ability" on a selector.** There is
no selector to offer. `PartyParam.isSafeToWrite(int)` is the gate: it is true
only for a Set selector whose meaning was confirmed against the executable, and
`PartyParamTest` fails if anything ever claims to write nature or ability.

### Reproducing this

The analysis ran against `DllField.cro` from the read-only dump and the
decompressed `code.bin`, with Capstone for ARM. Neither file is in the
repository, so the test cannot re-derive the names; what it does instead is in
its own javadoc. The scripts are in the queue-2 scratch directory
(`find_table.py`, `table.py`, `armdis.py`, `cases.py`, `imports.py`,
`globalscan.py`, `allnatives.py`, `checks.py`).


## A trap this probe fell into, recorded so nobody repeats it

The native's name hash lives in **`data[1]`** of a `PawnPrefixEntry`, not
`data[0]`. `publics` use `data[0]`, which is what the existing wizard code reads,
and copying that pattern makes every native resolve to nothing — the first run
of this probe reported "0 calls" across all 536 scripts and looked like a clean
negative result rather than a bug.

Second trap: the arguments are pushed with `PUSH_P_C` (the packed form), not
`PUSH_C`. Matching only `PUSH_C` also yields a confident zero.

Both failures produce *plausible* empty results, which is the dangerous kind.
The check that caught it was asking a question with a known answer: how many of
the 14,894 natives in the corpus resolve to any known name at all. Zero was
obviously wrong, where "no party-param calls" was not obviously wrong.

## A method that did NOT work — recorded so it is not retried

Reading what the surrounding script does with the result - the obvious
alternative to the binary - was tried, twice, and does not identify the fields. Written down because it is a
plausible idea that costs an afternoon.

The reasoning was sound: a script that reads a parameter compares it against
something, and the RANGE of those comparisons should name the field — 25 values
means Nature, 3 means an ability slot, 32 an IV, 253 an EV, 100 a level.

Attempt 1 counted every constant in the 25 instructions after each call. Far too
wide: it swept up the next call's arguments and message ids, so selectors 36-40
each came back with a "range" of 260-264, which are sequential text ids and not
comparisons at all.

Attempt 2 narrowed to comparison opcodes only (`EQ*`, `JEQ*`, `SLESS*`, `SGRTR*`,
`SLEQ*`, `SGEQ*`). Almost every selector then reported no constants whatsoever —
the busiest two, selector 8 (42 calls) and selector 2 (22 calls), both came back
empty. The VM does not carry the compared constant in the operand this reads;
the value goes through PRI/ALT and a stack slot first, so a static scan of one
instruction window cannot see it.

Making this work would mean tracking PRI/ALT and stack slots through the
disassembly — a small dataflow pass, not a scan. That is real work and it is not
obviously cheaper than the other route.

The binary route in the last line of that note is the one that worked. What it
took is written up above; the short version is that no hash-to-function table
exists to find, because the natives are registered by NAME, and the names are
right there in `DllField.cro`.
