# Player customization in ORAS — feasibility scope

Measured 2026-09-04 against the owner's own dump. Nothing here is inferred from
what XY does; every claim is a string, symbol or script command found in this
game's files. Written outside the repo because a sweep was running (mutate2.py
ends with `git reset --hard`, which erases commits made during it).

## The owner's correction, which reframes the whole scope

An earlier version of this ranked the boutique SCREEN as "not worth it" and
proposed a plain script menu instead. That was wrong, and the reason it was
wrong is worth keeping:

  "the player doesn't want to choose from a blank list, they want to see what
   each item looks like as they mix and match their character in front of them.
   the point of player customization is to see what you are customizing,
   otherwise why do it?"

Correct. The live preview is not the polish on the feature, it IS the feature.
A wardrobe you cannot see is an inventory screen. So the scope below is built
around "see yourself change" as the requirement, not as a stretch goal.

## What ORAS still has — measured

### The dress-up engine (code.bin RTTI, so these classes are linked)
    xy_system::dress_up::DressUpManager, DressUpResourceManager,
                         DressUpDataCacheManager
                         DressUpIHero / IHeroine / IModel   (+Core, +Resource)
                         DressUpFieldHeroCore / FieldHeroineCore  (+Resource)
                         DressUpBattleHero / BattleHeroine        (+Core, +Resource)
    app::kisekae::Manager        the dress-up screen manager
    savedata::Fashion            the save file carries a fashion block

Field and Battle variants exist separately, which matters: the model is built
for both contexts, so a change is not cosmetic to one screen only.

### It is LIVE, not dead code
`DllField.cro`'s script command table contains:
    BattleHouse_WaitSetupDressUpModel   rebuilds the dress-up model (async - it waits)
    GetCharTypeDressup                  reads the player's dress-up character type
    IsSelectedFriendDressup
    AddStylishPoint, GetStylishPoint, GetStylishLevel   <- XY's Style stat, scriptable
The Battle Maison runs this pipeline every time the player walks in.

### The mirror system — this is the preview primitive, and it is substantial
    MirrorEnable, SetMirror, ResetMirror
    SetMirrorNPC, SetMirrorParam, SetMirrorParamSide
    MirrorMatrix0 / MirrorMatrix1 / MirrorMatrix2
    GetMirrorModelDrawFlag, SetMirrorModelDrawFlag
    field::mmodel::EvTypeTrMirror, field::mmodel::EvTypeTrDoubleMirror
    DllUSMirror.cro  ->  field::FieldUniqueSequenceMirror
A script-drivable reflection of the player in the field, with per-model draw
flags and a DOUBLE mirror event type. This is the "see yourself" facility, and
it already exists.

### The shop engine
    field::EventShopBuyCall  with SelectState, NumState, YesNoState,
                                  NotEnoughMoneyState, AlreadyHaveState,
                                  HaveMaxNumState, ThankYouState
    field::EventShopSellCall
Complete state machine. Every Poke Mart runs it.

## What is missing — measured

1. NO CUSTOMIZATION UI MODULE. 145 CROs; the only "shop" match is DllEshop.cro,
   which is the Nintendo eShop. GameFreak kept the engine and cut the screen.

2. NO SETTER. This is the precise gap and the sharpest finding here. The script
   layer can READ the dress-up type, REBUILD the model, read and add style
   points, and fully drive mirrors - but nothing in the command table WRITES a
   clothing part. Searched: Kisekae, Fashion, Cloth, Wear, Hair, Costume, Equip,
   Chara, Avatar, Skin, Color, Look. The only appearance-adjacent writes found
   are AddStylishPoint and the mirror setters.

## The deciding unknown, NOT yet established

Whether the wardrobe part models are still in the archives. 298 GARCs were
enumerated but the player-model-parts archive was not identified. This is the
difference between:
    parts present -> reverse engineering work
    parts absent  -> somebody must author 3D clothing models, a different project
Establish this BEFORE committing to anything below. It is a cheap, focused
investigation and it gates everything.

## The build, if the parts are there

The pieces line up better than expected, because the preview does not need the
cut CRO. It needs a mirror, which exists.

    mirror                exists, scriptable        OK
    model rebuild         exists, scriptable        OK
    style gating          exists, scriptable        OK
    save block            exists                    OK
    menu / choice / money scripts already do this; CTRMap generates them  OK
    SETTER                MISSING - one new script native, code.bin patch  <-- the work
    part assets           UNKNOWN                                          <-- the risk

The fitting-room shape: stand at a mirror, pick from a script menu, the setter
writes the part, the model rebuilds, the mirror shows it. Mix and match in
real time, in 3D, in front of the player. That is the XY boutique experience
delivered through this game's own facilities rather than by resurrecting a
module GameFreak deleted.

## Why the setter is tractable here specifically

The owner already has everything this kind of patch needs:
  - code.bin dumped and BLZ-decompressed
  - VA = file offset + 0x100000, established
  - the binary is NOT stripped: RTTI and source paths present, so
    DressUpManager's vtable and members are locatable rather than guessed
  - IPS patching with stock-byte verification already working
  - PKHeX documents the XY savedata::Fashion layout, same engine family, so the
    save structure is a reference rather than a derivation
  - the Maison work already proved a script-native can be added, hashed the way
    the engine expects, and called from a generated script

## Honest risks
  - async rebuild: WaitSetupDressUpModel implies latency per change. Visible,
    probably acceptable, a polish problem not a blocker.
  - a part id the engine will not accept may fault rather than refuse; needs the
    same "guard first, prove it refuses" treatment the rest of this project uses.
  - hairstyles are assumed to be another part slot in the same system. NOT
    verified.

## The runtime question, settled — and it settles the field question too

Measured 2026-09-06 against `code.bin` (BLZ-decompressed, VA = file offset + 0x100000)
and the owner's dump. `customization-assets.md` left this open: *"Runtime loading is
not proven… proving the shipped executable actually opens `a/0/8/8` needs either the
`DressUpResourceManager` call sites traced in `code.bin` or an emulator file-access
log."* The call sites were traced.

### Verdict: **CONFIRMED.** The shipped executable opens `a/0/8/8` — for battle. The field path opens `a/0/8/7`, which GameFreak shipped as a zero-byte file.

The chain, each link a byte in the binary:

1. **`gfl::fs::ArcFile`'s constructor is `0x0011C94C`.** It stores the `ArcFile`
   vtable pointer `0x005DD508` into `[r0]` at `0x0011C954`–`0x0011C95C`. Its third
   argument (`r2`) is an **archive id**: at `0x0011C9C0` it loads the `gfl` system
   singleton `0x006173F0`, reads the per-archive descriptor array at `+0x54`, and
   indexes it `array[arcid]` (`ldr r0, [r0, r6, lsl #2]`, `r6 = r2`).

2. **Two of its 90 call sites are inside `xy_system::dress_up`** — `0x0049506C`
   (in the function at `0x00495020`) and `0x004950E4` (in `0x0049507C`). Both take
   the id from a two-entry table:

   ```
   0049505C  ldr r2, [pc, #0x14]   ; = 0x00580B70
   00495068  ldr r2, [r2, r6, lsl #2]
   0049506C  bl  #0x11c94c         ; ArcFile::ArcFile(this, heap, arcid, 0)
   ```

   `0x00580B70` = `{ 0x57, 0x58 }` and `0x00580B78` = `{ 0x57, 0x58 }` — **`{ 87, 88 }`**.
   (Read straight out of `.data`; the same 4-word block continues `{4,5} {2,3} {0,1}
   {53,54} {51,52} {55,56}` — more per-context pairs.)

3. **The table index is the field/battle context byte.** All nine callers of
   `0x00495020` load it identically: `ldrsb r1, [obj, #4]`. That `+4` byte is the
   context: `DressUpManager::GetCore` (`0x00494D90`) branches on it, and in the
   `type == 0` branch writes `[core+4] = 0`, `[core+5] = 1` and the
   **`DressUpFieldHeroCore`** vptr `0x005E199C`; in the `type == 1` branch it writes
   `[core+4] = 1` and the **`DressUpBattleHeroCore`** vptr `0x005E19E4`. The same
   convention appears in the Resource factory at `0x0049F898`, which for
   `type == 0, sex == 0` allocates `0x21CC` bytes and stores the
   `DressUpFieldHeroineResource` vptr `0x005E1B04`.

   So: **battle → arcid 88, field → arcid 87.**

4. **Archive ids are the flat index of the RomFS `a/` tree**, `id = 100a + 10b + c`.
   The tree is perfectly regular: 30 directories of 10 files each (`a/2/9` short by
   one) = 299 archives, and `code.bin` contains no archive path string in ASCII or
   UTF-16, no `printf` path template and no `GARC`/`CRAG` magic anywhere — the id is
   all there is.

**The proof that the mapping is the identity and not offset by some constant:
`a/0/8/7` is the only zero-byte archive in the entire ORAS RomFS.** All 299 files
were stat'ed; exactly one is empty, and it is the one the field branch of the
dress-up loader asks for. A wrong mapping would have to land id 87 on the unique
empty file *and* id 88 on the wardrobe.

Two independent corroborations, which hold even if you distrust the id arithmetic:

- **Names that exist nowhere else.** The `dress_up` module (which occupies
  `0x004921D4`–`0x004A6FFF`, bounded below by `xy_system::DrawUtil` at `0x004920C8`
  and above by `gamesystem::GameProc` at `0x004A7130`) hard-codes the literals
  `loc_acchat`, `nohat`, `paintl`, `paintr`, `acchat` — inline at `0x00492A90`,
  `0x00492B34`, `0x00496434`, `0x004964D8`, and in a name table at `0x005F4180`
  (`Head, Neck, Waist, nohat, loc_acchat, acchat, paintl, paintr`, then a six-entry
  repeat) that five `dress_up` functions read. Scanned across the game:
  `paintl`/`paintr`/`nohat` occur in **`a/0/8/8` only** — not in `a/1/3/3`, whose
  assembled `bt0001_00` has no such bone or mesh. Code that looks up a texture named
  `paintl` can only be satisfied by `a/0/8/8` subfiles 446/447/721/722.
- **A container format that fits one shipped file.** `0x00497E50`, reachable from
  the `DressUpFieldHeroResource` / `DressUpBattleHeroResource` vtables, parses
  `[u16 off[n]]` + per-record `{u8 A, u8 B, u8 C, u8 pad}` + `A` u16s + `B` u16s.
  `a/0/8/8` subfile 2 satisfies it byte-exactly for **216 of 217 records** (the odd
  one is a 2-byte empty span), with `off[0] = 2n` and `off[n-1] = filelength`.
  Subfiles 0, 3, 4, 5 and 6 of the same archive do **not** — the test discriminates.

**What this does not establish.** The *call* is proven; a successful *read* is not —
there is no emulator trace here. And the field branch's archive is empty, so
whatever the field path does at runtime, it is not loading parts today.

### Why that matters more than the yes/no

`customization-assets.md` framed the field problem as "there is no field part
library". That is true, but the sharper fact is this: **there is a field part
library slot, the engine asks for it by id, and it ships empty.** `a/0/8/7` is a
socket, not an absence. And the loader on the other side of it is not a separate
code path to be resurrected — it is the *same* code:

| class | vtable slots |
|---|---|
| `DressUpFieldHeroCore` `0x005E1994` | dtor, dtor, `0x498728`, `0x497CF4`, `0x498600`, `0x4987D0` |
| `DressUpBattleHeroCore` `0x005E19DC` | dtor, dtor, `0x498728`, `0x497CF4`, `0x498600`, `0x4987D0` |
| `DressUpFieldHeroResource` `0x005E1AAC` | dtor, dtor, `0x499C7C`, `0x49E314`, `0x49E9D4`, `0x4EE968`, `0x499A8C`, `0x4EE970` |
| `DressUpBattleHeroResource` `0x005E1AD4` | dtor, dtor, `0x499C7C`, `0x49E314`, `0x49E9D4`, `0x4EE968`, `0x499A8C`, `0x4EE970` |

Every non-destructor slot is identical. Field and battle differ in **exactly one
thing: which archive id they open.** Whatever `a/0/8/7` was meant to contain, it was
meant to be in `a/0/8/8`'s format.

And the field side of the engine is wired for it. `field::mmodel` (the module between
`field::mmodel::MoveModel`'s vtable at `0x003F4558` and `field::script::InitObject`
at `0x003FB220`) calls into `dress_up` at four sites — `0x003F6ABC → 0x004988F8`
(a `DressUpParam` field-by-field comparison, used as the key of a model cache walked
at `0x003F6A78`), `0x003F7748 → 0x00494C18` and `0x003FA7C4`/`0x003FA810 →
0x0049E794` (two `DressUpParam` clone routines that copy a **`0x40`-byte block — 32
u16 part slots** — and branch on the same field/battle byte at `+1`). Separately,
`app::kisekae::Manager` — the dress-up *screen* — is compiled code at
`0x00365E00`–`0x00366740`; it opens arcid 66 (`a/0/6/6`, a single 10,080-byte entry:
a UI layout) and drives `field::mmodel` functions (`0x3F58C8`, `0x3F5E30`,
`0x3F6268`, `0x3F82D0`, `0x3F8F48`).

Honest gap: the context byte is *data*-driven. That some code path at runtime sets it
to `0` was not proven statically — only that the field move-model module is what
copies and compares these parameters, which is where a `0` would come from.

---

## The three field routes, with numbers

### (a) Drive the five `sw_parts` slots — measured, and smaller than it sounds

Per-mesh measurement of `a/0/2/1` #171 `rstr0001_00_fi` (heroine) and #172
`rstr0002_00_fi` (hero); triangles are index-buffer length / 3:

| mesh | tris (b1 / b2) | bones it is weighted to | material | texture |
|---|---|---|---|---|
| `sw_parts01` | 118 / 118 | `LHandEX` only | `rstr000X_00_body` | `..._fi_body` 128×128 |
| `sw_parts02` | 128 / 196 | `LFoot LLeg LToe RFoot RLeg RToe` | `rstr000X_00_body` | `..._fi_body` 128×128 |
| `sw_parts03` | 268 / 268 | `Head` only | `rstr000X_00_sw_parts_02` | `..._fi_sw_parts` 128×64 |
| `sw_parts04` | 340 / 340 | `Head` only | `rstr000X_00_sw_parts_02` | `..._fi_sw_parts` 128×64 |
| `sw_parts05` | 136 / 136 | `LFoot LLeg LToe RFoot RLeg RToe` | `rstr000X_00_sw_parts` | `..._fi_sw_parts` 128×64 |

990 triangles total (1,058 for the hero) out of the model's 2,801 (2,786). All five
ship with `isVisible = true`. They are addressed **by name** at runtime:
`DllField.cro`, `DllSequence.cro` and `DllSkyTrip.cro` each carry the literal table
`sw_parts01\0sw_parts02\0sw_parts03\0sw_parts04\0sw_parts05\0Head\0loc_head\0LHand\0RHand\0LToe\0RToe\0loc_eye…`,
and `code.bin`'s own bone-name table carries `sw_parts01`. So the slots are real and
individually drivable.

But they are **one wrist item, two head props and two footwear overlays** — a fixed
prop set (this is where the Mega Bracelet and the roller-skate/shoe swap live), not a
wardrobe. Correcting the assets doc: they do *not* "share two materials on a single
128×64 texture" — there are **three** materials over **two** textures, and
`sw_parts01`/`02` ride on the 128×128 *body* sheet, not the sw_parts sheet.

**Ceiling: five fixed attachment points, fixed bone bindings, 128×64 of dedicated
texture.** Cheapest of the three and genuinely useful for accessories. Not
customization.

### (b) Re-rig the battle parts onto the 50-bone field skeleton — the bone map

Every `b1_`/`b2_` part's bone list was compared name-by-name against
`rstr0001_00_fi` / `rstr0002_00_fi` (50 bones each), counting only bones that
actually carry weight, and for each unmatched bone walking its own parent chain to
the nearest ancestor the field skeleton *does* have.

**Result: there is no bone without a home.** 70 distinct weighted bone names on the
heroine side and 32 on the hero side have no field counterpart, and every one of them
falls into six families with an obvious target:

| family | bones | nearest ancestor present in the field skeleton | better retarget | consequence of collapsing |
|---|---|---|---|---|
| `Spine3` | 1 | `Spine2` | `Spine2` | upper-chest deformation flattens slightly |
| finger tips and extra fingers — `L/RFingerA3`, `L/RFingerB3`, `L/RFingerC1‑3`, `L/RFingerD1‑3` | 16 | `L/RFingerA2`, `L/RFingerB2`, `L/RHand` | same | 4-finger hands become 2-finger; the field model already has only `A1/A2/B1/B2` |
| face joints — `FJlipL`, `FJlipU`, `FJteethL`, `FJteethU`, `LFJLipS`, `RFJLipS`, `RFJRipS` | 7 | `Head` | `Head` | no mouth articulation — the field model has none anyway |
| hair physics — `BHair1‑3`, `L/RHair1‑3`, `BHair01A‑D`, `BHair02A`, `L/RHair01A/B`, `Lhair1‑5`, `Rhair1‑5` | 29 | `Head` | `Head`, or the field skeleton's own `LHair`/`RHair`/`LRibbon`/`RRibbon` | long hair stops swinging |
| skirt physics — `L/RSkirt01A…03B` | 12 | `Hips` | `Hips` | skirts stop swinging |
| attachments — `BagA‑D` (`BagA‑F` on the hero), `LRing`, `acchat` | 6 (8) | `RShoulder`, `LForeArm`, none | field `Bag` (`Bag1`/`Bagbelt1‑3` on the hero), `LHandEX`, `loc_head` | none — these are mounts, and the field skeleton has equivalents |

`RFJRipS` is GameFreak's own typo in `b1_face00`; every other face part spells it
`RFJLipS`. Worth knowing before a name-matching converter reports a mystery bone.

How much actually rides on the collapsed bones, as a fraction of total vertex weight:

| part | tris | verts touching a missing bone | weight mass on missing bones | dominant contributor |
|---|---|---|---|---|
| `b1_shoes_lboots`, `b1_shoes_shoes`, `b1_hat_can`, `b1_hair_bob` | 520 / 376 / 212 / 996 | 0 | **0.000%** | — binds unchanged |
| `b1_tops_shirt` | 1,208 | 1,460 of 3,624 | 32.2% | `Spine3` 13.5% |
| `b1_tops_tshirt` | 1,064 | 1,388 of 3,192 | 33.8% | `Spine3` 12.6% |
| `b1_tops_parka` | 1,518 | 2,190 of 4,554 | 34.4% | `Spine3` 19.6% |
| `b1_tops_puffslee` | 1,530 | 2,341 of 4,590 | 37.4% | `Spine3` 22.7% |
| `b1_body_onepiece01` | 1,618 | 2,780 of 4,854 | 41.0% | `Spine3` 7.2% + skirt chain |
| `b1_face01` | 738 | 818 of 2,214 | 30.7% | `FJlipL` 12.8% |
| `b1_hair_pony` | 1,296 | 1,997 of 3,888 | 45.0% | `BHair01B` 16.3% |
| `b1_hair_twin` | 1,369 | 2,148 of 4,107 | 50.7% | `Lhair4`/`Rhair4` 7.5% each |
| `b1_hair_long` | 1,101 | 2,232 of 3,303 | 63.1% | `BHair3` 17.7% |
| `b1_btms_askirt` | 266 | 676 of 798 | 71.4% | skirt chain |
| `b1_btms_pskirt` | 285 | 765 of 855 | 78.0% | skirt chain |
| `b1_bag01` | 432 | all | 100% | `BagA‑D` — pure mount |
| `b1_bngl01` | 176 | all | 100% | `LRing` — pure mount |
| `b1_point_glasses` | 124 | all | 100% | `acchat` — pure mount |

So the conversion splits three ways: **shoes, hats and short hair are free**; **tops,
bottoms and faces need a `Spine3`→`Spine2` weight merge that costs a little
silhouette**; **skirts and long hair lose their secondary motion** unless you also add
the physics chains to the field skeleton (which is possible — they are just bones, and
`a/0/8/8` subfiles 726–739 already ship the animations that drive them).

Triangle budget, for scale: a full assembled battle outfit is `bt0001_00` = 12,198
verts = **4,066 tris**; the baked field player is 8,403 verts = **2,801 tris**. A
dressed field player would cost about **45% more geometry** than the one shipping
today. That is a real but unalarming number for a 3DS field scene where the player is
one model among many.

**But route (b) on its own has a hard ceiling that has nothing to do with bones.**
The field player is a *single baked BCH*. Re-rigging parts and welding them into that
model produces one model per outfit. Eleven slots times dozens of parts is not a
combinatorial space you can pre-bake. Route (b) alone buys you a fixed set of preset
outfits, no mix-and-match — which is precisely the thing the owner said was the point.

### (c) Make the field use the dress-up model — no longer speculative

The trace above turns this from "unsettled" into "there is a socket and here is its
name". Cost drivers, measured:

- The loader **already exists and is byte-identical to the battle one** (vtable table
  above). Nothing to resurrect.
- The archive it wants is **`a/0/8/7`, present in the ROM as a zero-byte file** —
  which means it is also a normal, moddable GARC slot; there is no need to steal
  another archive's id or repoint anything in `code.bin`.
- The format is `a/0/8/8`'s: an index-table group (subfiles 0–6, re-packed as the
  `DB` container at 724), part BCHs, part textures, physics animations. `a/0/8/8`
  subfile 8 is `b1_base` — **75 bones, 36 vertices, 12 triangles**: a skeleton-donor
  model that carries the full dress-up rig and almost no geometry. A field wardrobe
  needs the same thing, carrying the field rig.
- The module resolves bones and meshes **by name** (it carries `Head`, `Waist`,
  `nohat`, `loc_acchat`, `paintl`… as literals and looks them up), which is what makes
  a rename-and-reparent conversion viable at all rather than an index-level rewrite.
  Proven for the special bones; assumed for the general skin merge.

Unknowns to state plainly: nobody here knows what `a/0/8/7` contained in XY (no XY
dump to diff against), nor which skeleton a field dress-up base model would use, nor
whether the runtime ever sets the context byte to `0`.

## Ranking, and what to build

1. **(c), fed by (b).** These are not alternatives — (b) is (c)'s content pipeline.
   (c) supplies the runtime assembly the feature needs, and (b) supplies the parts,
   which already exist as geometry and need a mechanical bone rename plus four
   weight-merge decisions.
2. **(a)** as an independent quick win: five drivable prop slots, 990 triangles,
   no new pipeline. Worth doing for accessories, useless for a wardrobe.
3. **(b) alone** — only if the goal shrinks to a handful of preset outfits.

**Cheapest decisive next measurement:** copy `a/0/8/8` verbatim into `a/0/8/7` and
watch what the field dress-up path does. The battle-rigged parts will bind wrongly or
be rejected, but the experiment answers the one question static analysis cannot: does
the loader actually run when the archive is non-empty? That is a one-file change to a
slot that is empty in stock data, and it is reversible by truncating the file back to
zero bytes.

## Reproducing this

Probes in `wt/_state/queue2/runtime/`: `rtti.py` (RTTI names → typeinfo → vtables),
`callgraph.py` (BL index over the whole binary), `p15_classes.py` (vtable slot →
class), `dz.py` / `dumpregion.py` (annotated ARM disassembly with literal-pool
resolution), `p13_verify.py` / `p17_e2.py` (index-table layout check), and `Q3.java`
(`skel` / `mesh` / `map` / `mass` / `scan` / `nm` modes over GARC + BCH, MM-container
aware) compiled against CTRMap's own `build/classes`. Game data was read only.
