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
arguments in reverse, so the last `PUSH_*` before the `SYSREQ_N` is argument 1.

    PUSH_P_S <party slot>       arg1
    PUSH_P_C <parameter id>     arg2   <-- the selector
    PUSH_P_C <value / index>    arg3
    SYSREQ_N <native>, 12

`<var>` below means the argument came from a stack slot rather than a literal,
so its value is not visible statically.

### PokePartyGetParam — arg2, the parameter id

    8  x42     2  x22     13 x11     49 x6      53 x6      44 x5
    10 x4      48 x3      1  x2      3  x2      4  x2      5  x2
    45 x2      11 x1      12 x1      14 x1      15 x1      29 x1
    36 x1      37 x1      38 x1      39 x1      40 x1      46 x1
    47 x1      51 x1
    (arg1 is <var> in 11 of the calls; arg3 is <var> in 140 of 160)

So Get selectors occupy a small dense-ish range, roughly 1..53.

### PokePartySetParam — arg2

    1007 x12     1006 x1

**Set uses a completely different id range from Get.** Twelve of thirteen calls
write parameter 1007. This is the sharpest single finding here: whatever 1000+
means, retail almost never writes party parameters from script, and when it does
it writes essentially one thing.

### CallPokeSelect

Identical in all five calls: `(1, 0, 32780, 1)`. 32780 = 0x800C, which looks
like a flag word rather than a count.

## What is NOT established — do not guess past this

**Which selector means Nature, Ability, IV_HP or EV_Atk is still unknown.** This
probe recovered the argument POSITION and the set of ids retail uses, not their
meanings. Attaching names to 8, 2, 13, 49, 53 requires one more step, and there
are two honest routes:

1. Read what the surrounding script does with the result — a zone that branches
   on the returned value into nature-specific dialogue names that selector by
   its use. Zone 8 has the densest cluster and is the place to start.
2. Find the enum in `code.bin`. The binary is not stripped, so the native's
   implementation is locatable via its 32-bit name hash `E5AB2CFA`.

Until one of those lands, a UI must not offer "change nature" — it would be
guessing at which byte it is writing, in the player's save.

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
