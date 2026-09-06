# Are the dress-up part models still in ORAS? — measured

Settles the "deciding unknown" left open by `customization-scope.md`.

**Verdict: YES. The part models are present, complete, rigged and parseable.**
Adding player customization to ORAS is reverse-engineering work, not a 3D asset
production project. The wardrobe is in **`a/0/8/8`** — 741 subfiles, 6,610,088
bytes — and it holds 103 BCH model subfiles (101 swappable parts plus the two
assembled default player models), 544 part textures, its own part index tables,
and the hair/skirt physics animations.

One important qualification, measured and stated up front: the surviving part
set is the **battle** model's. The **field** player model is a single baked
model with no part library and a *different, smaller skeleton*. See
"What is NOT there" below — that is the real cost line, and it is not the one
the scope doc feared.

Everything below was measured against the owner's dump at
`<romfs>/a/`
using CTRMap's own `ctrmap.formats.garc.GARC`, `ctrmap.formats.h3d.BCHFile` and
`ctrmap.formats.text.GFMessageFile` (no hand-written container parsing — the
earlier FATB drift is exactly why). All 298 non-empty GARCs were enumerated and
keyword-scanned, including the 1 GB `a/0/0/8`.

## The answer in one line

`a/0/8/8` subfile 25 is a BCH whose model is named `b1_hair_bob` — 2 meshes,
2,988 vertices, 11 bones. Subfiles 27, 29, 31, 33, 36 are `b1_hair_long`,
`b1_hair_midi`, `b1_hair_pony`, `b1_hair_short`, `b1_hair_twin`. Subfile 70 is
the assembled player battle model `bt0001_00` (11 meshes, 12,198 vertices, 76
bones), the same model that ships as subfile 0 of the battle-trainer archive
`a/1/3/3`, and every one of those hair parts is rigged to `bt0001_00`'s own
named bones.

## How `a/0/8/8` is laid out

741 subfiles. Verified per-entry with `GARC.getDecompressedEntry` +
`BCHFile`.

| range | contents |
|---|---|
| 0–6 | the heroine's index set, seven raw tables (decoded below) |
| 7–70 | **64 BCH part models, heroine (`b1_`)**; 70 = assembled default `bt0001_00` |
| 71–76 | `Bchara_hight00/01`, `Bchara_hlight00..03` toon/highlight lookup textures |
| 77–449 | heroine part textures (design/colour variants) |
| 450–455 | the hero's index set — SIX tables; his master table is only in 725 |
| 456–494 | **39 BCH part models, hero (`b2_`)**; 494 = assembled default `bt0002_00` |
| 495–498 | highlight textures |
| 499–720 | hero part textures |
| 721–723 | `paintl`, `paintr`, `star` textures |
| 724 | `DB` container: the heroine's whole index set, 8 sections |
| 725 | `DA` container: the hero's whole index set, master table included |
| 726–739 | hairstyle and skirt physics animations |
| 731, 734, 737, 740 | empty (0 bytes) |

Every part model parses with `BCHFile` at `errorlevel == 0`.

### The heroine part models (subfiles 7–70)

Slot naming is `b1_<slot>_<style>`; every one anchors to `tr0001_00_ba`
(trainer 0001, battle).

```
 7 b1_bag01              25 b1_hair_bob           42 b1_point_badge
 8 b1_base               26 b1_hair_bob_mae       43 b1_point_flower
 9 b1_bngl01  (bangle)   27 b1_hair_long          44 b1_point_glasses
10 b1_body_coat01        28 b1_hair_long_mae      45 b1_point_metal
11 b1_body_costume02     29 b1_hair_midi          46 b1_point_ribbon
12 b1_body_onepiece01    30 b1_hair_midi_mae      47 b1_point_wing
13 b1_body_onepiece04    31 b1_hair_pony          48 b1_shoes00 / socks_tights
14 b1_btms_askirt        32 b1_hair_pony_mae      49 b1_shoes_lboots
15 b1_btms_lpants_lboots 33 b1_hair_short         50 b1_shoes_lboots02
16 b1_btms_lpants_shoes  34 b1_hair_short_mae     51 b1_shoes_pajama
17 b1_btms_pajama        35 b1_hair_shortmae      52 b1_shoes_sboots
18 b1_btms_pskirt        36 b1_hair_twin          53 b1_shoes_shoes
19 b1_btms_tskirt        37 b1_hair_twin_mae      54 b1_shoes_shoes_socks
20 b1_btms_tspants       38 b1_hat_can            55 b1_socks_lboots
21 b1_face00             39 b1_hat_cap            56 b1_socks_shoes
22 b1_face01             40 b1_hat_cas            57 b1_standbag01
23 b1_face02             41 b1_hat_hat            58 b1_standhead01
24 b1_face03                                      59 b1_tops_cami
                                                  60 b1_tops_cami02
61 b1_tops_noslee   64 b1_tops_puffslee   67 b1_tops_stole
62 b1_tops_pajama   65 b1_tops_ribbon     68 b1_tops_tievest
63 b1_tops_parka    66 b1_tops_shirt      69 b1_tops_tshirt
70 bt0001_00  (assembled default: 11 meshes, 12,198 verts, 76 bones)
```

`_mae` = the front-hair half of a hairstyle (mae = 前, "front"), so the
hairstyles ship as back+front pairs. **Six** pairs, not seven: the index offers
`bob, long, midi, pony, short, twin`, and `b1_hair_shortmae` (36) is a stray
duplicate of `b1_hair_short_mae` (35) that nothing points at.

### The hero part models (subfiles 456–494)

Anchored to `tr0002_00_ba`.

```
456 b2_bag01             465 b2_face00        473 b2_hat_cap      483 b2_shoes_pajama
457 b2_base              466 b2_face01        474 b2_hat_hat      484 b2_shoes_sboots
458 b2_bngl01            467 b2_face02        475 b2_hat_hun      485 b2_shoes_shoes
459 b2_btms_lpants_lboots 468 b2_face03       476 b2_hat_knit     486 b2_standbag01
460 b2_btms_lpants_shoes 469 b2_hair_midi     477 b2_hat_poke     487 b2_standhead01
461 b2_btms_pajama       470 b2_hair_perm     478 b2_leg_sboots   488 b2_tops_jersey
462 b2_btms_scargo       471 b2_hair_short    479 b2_leg_shoes    489 b2_tops_jumper
463 b2_btms_skinny_lboots 472 b2_hair_vshort  480 b2_point_badge  490 b2_tops_pajama
464 b2_btms_skinny_shoes                      481 b2_point_glasses 491 b2_tops_shirt
                                              482 b2_point_wing   492 b2_tops_sweat
                                                                  493 b2_tops_tshirt
494 bt0002_00  (assembled default: 10 meshes, 13,161 verts, 70 bones)
```

### The composite render targets, read off the assembled model

`bt0001_00` has eleven `nb1_*` names:

```
nb1_tops   nb1_bottoms   nb1_shoes   nb1_socks_none   nb1_hat01
nb1_hairlong_on   nb1_face_on_0   nb1_face_on_1   nb1_bag01
nb1_bangle   nb1_accehat02
```

`bt0002_00` has the hero's ten equivalents (`nb2_topsjer`, `nb2_btmsskin`,
`nb2_shoessboots`, `nb2_hathun`, `nb2_hair01_on`, `nb2_face_on_0/1`,
`nb2_bagbag`, `nb2_bangle01`, `nb2_pointglasses`).

**Two corrections to what an earlier pass wrote here**, both measured:

1. These are the assembled model's **material** names, not its mesh names.
   `bt0001_00`'s eleven *meshes* are `bag, body, bottom, face01, face01, hair,
   kutu, kutusita, pc_g_hat, ring, sunglass`. The same eleven `nb1_*` names are
   also real textures, at subfiles **435–445** (`nb2_*` at **711–720**), and
   the index points every part model at them — they are the surfaces the
   dress-up system composites into.
2. They are **not the slot list**. The slot list is in the index tables, and
   there are **14** slots for the heroine and **13** for the hero, not 11 and
   10 — see "The seven index tables, decoded" below. Eleven is the number of
   composite targets, which is smaller because the two hair slots share one
   target, the two shop display-stand slots have no target at all, and the
   one-piece slot writes into the tops and bottoms targets.

### 544 part textures — the colour/pattern half of the wardrobe

544 distinct texture names in the texture blocks, in the naming
`b1_<slot><style>_d<design><colour><nn>`. Sample spreads:

- `b1_btmslpan_d01b01 … d01b07, d02b01 …` — 22 leggings textures
- `b1_topstshirt_d01s01 … d05spe01` (+ `_m` mask variants) — 36 t-shirt textures
- `b1_bagbag01_d01b01 … d04c03` — 23 bag textures
- `b1_socks_kd01p01…p07`, `od01p01…p13`, `od02..od06` — 44 sock/tights textures
- 58 face textures, 16 hair-colour textures, 13 makeup, 7 face paint

Make-up and face paint survive too, as their own texture set:

```
b1_make_face01_cheek_1   b1_make_face01_contact_0   b1_make_face01_eyeshadow_0
b1_make_face01_freckles_1 b1_make_face01_lip_1      b1_make_face01_mas_01_0
b1_make_face01_mas_02_0   b1_make_face03_contact_0
b2_make_face01_baron_1   b2_make_face01_contact_0   b2_make_face01_freckles_1
b2_make_face01_mustache_1 b2_make_face03_contact_0
b2_paint_ball  b2_paint_eyeblack  b2_paint_hoppe  b2_paint_naughty
b2_paint_tape  b2_paint_tearful   b2_paint_whisker
```

### The archive carries its own index tables

Subfiles 0–6 are raw, uncompressed tables, not models. Subfile 724 is a `DB`
container whose eight section offsets carve out lengths 1380 / 1064 / 1432 /
100 / 112 / 376 / 500 — byte-for-byte the lengths of subfiles 0–6, with section
0's bytes matching subfile 0. So the archive is **self-describing**: whatever
indexes parts by id has its table shipped alongside the parts.

They are now decoded. See the next section.

## The seven index tables, decoded

**All seven are decoded.** They are not seven tables — they are **one index set
of seven tables, and there are two of them**, one per player character. Both
ship twice over: the heroine's as loose subfiles 0–6, the hero's as loose
subfiles 450–455, and each set again *whole* inside a container (`DB` at 724
for the heroine, `DA` at 725 for the hero).

The counting that made this look like seven tables rather than 2 × 7 is worth
stating, because it is the trap: the hero's **master table exists only inside
the `DA` container** and has no loose subfile of its own. Subfiles 450–455 are
six tables, not seven. Reading the containers is therefore the only way to get
a complete set, and `DressUpIndex` reads the containers.

Read with `ctrmap.formats.dressup.DressUpArchive` / `DressUpIndex`; guarded by
`ctrmap.tests.DressUpIndexTest`.

### The shape they share

Sections 2–7 are **self-describing offset arrays**: the first `u16` is the byte
offset of the first record and therefore also the size of the offset array
itself, so `count = first / 2 - 1` and no separate count field exists. Sections
4 and 5 put a `u16 n, u16 item[n]` prefix in front of that array, naming which
items they carry textures for. That one rule parses six of the seven; the
master table is a flat array of 6-byte rows.

### Table by table

| # | subfile (heroine / hero) | bytes | records | what it is | status |
|---|---|---|---|---|---|
| 1 | 0 / *DA section 1 only* | 1380 / 744 | 230 / 124 | **master** — the wearable list in menu order | DECODED |
| 2 | 1 / 450 | 1064 / 608 | 55 / 36 | **items** — a style, its designs, the models that draw it | DECODED |
| 3 | 2 / 451 | 1432 / 764 | 217 / 114 | **designs** — one colour/pattern variant, its textures | DECODED |
| 4 | 3 / 452 | 100 / 100 | 8 / 8 | **face texture sets** | DECODED |
| 5 | 4 / 453 | 112 / 76 | 12 / 8 | **hair texture sets** | DECODED |
| 6 | 5 / 454 | 376 / 212 | 64 / 39 | **parts** — one record per part model | DECODED |
| 7 | 6 / 455 | 500 / 356 | 64 / 39 + trailer | **part textures**, then the **make-up** table | DECODED; the make-up record's leading fields PARTIAL |

**Master** — `u16 category, u16 item, u16 design`, 6 bytes flat. The category is
the dress-up slot. 230 rows for the heroine, 124 for the hero.

**Items** — `u8 nDesigns, u8 nParts, u16 kind, u16 design[nDesigns],
{u16 partModel, u16 flag}[nParts]`. The record's own two count bytes must
reproduce its length exactly, which is what makes a mis-parse loud instead of
plausible. A design id `>= 0xFF00` is a marker meaning "this item's textures
live in the face or hair table" — `0xFFFE` for hair, `0xFFFD` for face,
`0xFFFC` for one shoe entry.

**Designs** — `u16 flag, u16 textureId[]`. Usually one or two ids: the base
texture and its `_m` mask.

**Face / hair texture sets** — `u16 flag, u16 textureId[]`, with the prefix
naming the items they belong to. The heroine's face table's prefix is
`{4, 12, 13, 14, 15}` — four items, and 12..15 are exactly the master table's
four face items. Its eight records are the four faces × `on`/`off`:
`b1_face01_on_0, _on_0m, _on_1, _on_1m` and the `off` equivalents. The hair
table's twelve are six hairstyles × `on`/`off`, each `b1_hairbob_on` +
`b1_hairbob_on_h`.

**Parts** — one record per part model **in archive order**, so record *i* is the
*i*th part model. `u16 item[]` then a terminator word `0xFF00 | flag`. An empty
record means the archive ships that model but the index never offers it.

**Part textures** — one `u16 textureId[]` per part model, again in archive
order: the toon/highlight lookup that part's own material binds. Then a
trailer holding the make-up table.

### The two bases, and why they are not guessed

A texture id is an index into the set's own texture block, and

```
textureId 0  ==  the first subfile after that set's part models
```

so heroine texture id 0 is subfile **71** and hero texture id 0 is subfile
**495** — in both cases `modelBase + partCount` (7 + 64, 456 + 39). `DressUpArchive`
measures both by finding the runs of consecutive model-bearing subfiles and
matching a run's length against the set's own part count, and reports `-1` when
it cannot. Off-by-one here is not academic: base 70 also "resolves" every id to
a real texture, and only the names show it is wrong — which is why the guard
checks names, not resolvability.

### The slots

The master table's category *is* the slot. There is **no name for it anywhere
in the data**; the labels below are read off the part models the slot contains,
and the guard suite asserts that reading is consistent — every part whose name
carries a slot word lands in that word's slot and nowhere else.

| heroine | rows | slot | hero | rows |
|---|---|---|---|---|
| 0 | 31 | hats | 0 | 23 |
| 1 | 2 | hair pairing (worn with a hat / without) | 1 | 1 |
| 2 | 6 | hairstyle | 2 | 4 |
| 3 | 4 | face | 3 | 4 |
| 4 | 35 | tops | 4 | 35 |
| 5 | 45 | bottoms | 5 | 17 |
| 6 | 10 | **one-piece / body** (heroine only) | — | — |
| 7 | 27 | socks / legs | 6 | 5 |
| 8 | 27 | shoes | 7 | 13 |
| 9 | 16 | bag | 8 | 7 |
| 10 | 1 | bangle | 9 | 1 |
| 11 | 24 | hat accessory (`b1_point_*`) | 10 | 12 |
| 12 | 1 | shop display stand — head | 11 | 1 |
| 13 | 1 | shop display stand — bag | 12 | 1 |

14 slots for the heroine, 13 for the hero: the hero has no one-piece slot, and
after it every hero slot number is one lower. **Slot numbers are per-character
ordinals, not a shared enum** — an editor must not carry a number from one set
to the other.

Slots 12 and 13 (11 and 12) have no part model at all; their single design is
`b1_standhead_d01` / `b1_standbag_d01`, the boutique's display stand.

Two parts sit in more than one slot, both legitimately: a hairstyle is listed
under both its style slot and the hat-pairing slot, and `b1_shoes_shoes_socks`
— shoes with the socks baked into the same mesh — is in both shoes and socks.

### The part flag: footwear compatibility

The low byte of a part record's terminator, same values in both sets:

| flag | meaning | seen on |
|---|---|---|
| 0x01 | cut for boots | `*_btms_lpants_lboots`, `*_btms_skinny_lboots` |
| 0x02 | cut for shoes | `*_btms_lpants_shoes`, `*_btms_skinny_shoes` |
| 0x04 | socks worn under boots | `b1_socks_lboots`, `b2_leg_sboots` |
| 0x05 | socks worn under shoes | `b1_socks_shoes`, `b2_leg_shoes` |
| 0x06 | the footwear is boots | `b1_shoes_lboots`, `b2_shoes_sboots` |
| 0x07 | the footwear is shoes | `b1_shoes_shoes`, `b2_shoes_shoes` |
| 0xFF | none | everything else (45 heroine, 23 hero) |

**This is the thing name-guessing gets wrong.** A model name is
`<prefix>_<slot>_<style>[_<compat>]`, and the trailing word is this flag, not a
slot: `b1_btms_lpants_shoes` is leggings *cut for shoes*, and it is in the
bottoms slot, not the shoes slot. The index says so; a name prefix read
carelessly does not.

### The relation is stored twice, and it agrees

The parts table says which items a model renders; the items table says which
models an item uses. Every pair appears in both directions — **68 pairs for the
heroine, 35 for the hero, zero one-way** — which is the strongest evidence the
decoding is right, because a wrong field offset breaks the agreement rather
than merely looking odd. `DressUpIndexTest` asserts it.

### Make-up and face paint

The trailer after section 7's records is the make-up table: 28-byte records
each carrying one texture id in one of three `u16` slots. It resolves to the
**7 heroine make-up textures** (`b1_make_face01_cheek_1`, `contact_0`,
`eyeshadow_0`, `freckles_1`, `lip_1`, `mas_01_0`, `mas_02_0`) and the **4 hero
ones** (`baron_1`, `contact_0`, `freckles_1`, `mustache_1`). The hero's trailer
then has a tail block — `u32 length`, then `{u8, u8, u16 textureId}` — holding
the **7 face paints** (`b2_paint_ball, eyeblack, hoppe, naughty, tape, tearful,
whisker`). The heroine has no such block; her `paintl`/`paintr` are reached
through her `b1_face00` part-texture record instead.

What is **not** decoded in that trailer: its own header, and the two leading
`u16` fields of each 28-byte record (a constant 13 / 12, then an index 0..n
that is not in ascending order). Only the texture id is read, and the reader
locates the record run by pattern rather than by a guessed header length.

### What the index says is unused

Nine heroine part models and eight hero ones have an empty parts record — the
archive ships them, the index never offers them:

```
b1_base  b1_btms_pajama  b1_face00  b1_hair_shortmae  b1_shoes00
b1_standbag01  b1_standhead01  b1_tops_pajama  bt0001_00
b2_base  b2_btms_pajama  b2_face00  b2_shoes_pajama
b2_standbag01  b2_standhead01  b2_tops_pajama  bt0002_00
```

So of the 101 swappable part models, **86 are actually wearable** (55 heroine,
31 hero). The pyjama set and the display-stand props are modelled but not in
the wardrobe, `b1_hair_shortmae` is a duplicate of `b1_hair_short_mae` that
nothing points at, and the two `bt*_00` entries are the assembled defaults.

### A GARC reading defect found on the way

`GARC.sniffLZ11` refuses to treat an entry as compressed when its declared size
exceeds 64× its stored size. **697 entries across the dump fail that test while
being genuinely LZ11-compressed** — measured, by decompressing them and getting
exactly the declared length back. In this archive that is 46 subfiles, the `_m`
mask textures, which come back to a caller as raw bytes. `DressUpIndexTest`
decompresses them itself and says how many it had to. Fixing the sniff touches
the packing path as well as the reading path, so it is filed separately rather
than done here.

### Not wired into `GameProfile.archivePath` yet

`DressUpArchive` takes a `GARC`, and nothing in `ctrmap.gamedef` names this
archive. Adding an `ArchiveType` constant means editing `Workspace.java`, which
`mutation_baseline.json` has measured; touching it requires re-running
`tools/mutate2.py`, a mainline sweep. The path belongs in `OrasProfile` when
that sweep next runs.

## The decisive measurement: the parts share the model's skeleton

Not "they look like parts" — every part's bone names were compared against
`bt0001_00` / `bt0002_00`'s own 76 / 70 bone names.

```
part                    bones  shared with the assembled model   bones not in it
b1_tops_cami              45    45                               (none)
b1_tops_parka             45    45                               (none)
b1_tops_shirt             45    45                               (none)
b1_tops_tshirt            45    45                               (none)
b1_shoes_lboots           12    12                               (none)
b1_shoes_shoes            12    12                               (none)
b2_tops_jersey            47    47                               (none)
b2_tops_shirt             47    47                               (none)
b2_btms_lpants_lboots      9     9                               (none)
b2_btms_scargo            14    14                               (none)
b2_shoes_sboots           12    12                               (none)
b2_hat_cap                 9     9                               (none)
b1_hat_can                 9     8                               loc_acchat
b1_hat_hat                 9     8                               loc_acchat
b1_point_glasses           1     0                               acchat
b1_hair_bob               11     8                               model, b1_hair_bob, nohat
b1_hair_long              20    17                               model, b1_hair_long, nohat
b1_hair_pony              16     8                               BHair01A..D, BHair02A, model, nohat
b1_hair_twin              21     8                               Lhair1..5, Rhair1..5, model, nohat
b1_btms_askirt            22     9                               LSkirt01A..RSkirt03B, loc_acchat
b1_btms_tskirt            22     9                               LSkirt01A..RSkirt03B, loc_acchat
```

Tops, bottoms, shoes and hats are **pure subsets** of the assembled model's
skeleton — zero unknown bones. The only extras are exactly what a part system
needs and nothing else:

- **per-part physics chains** — `LSkirt01A..RSkirt03B` for skirts, `Lhair1..5` /
  `Rhair1..5` / `BHair01A..D` for the hairstyles that swing
- **attachment locators** — `loc_acchat` / `acchat`, the hat-accessory mount
- **visibility helpers** — `nohat` (the hair mesh drawn when no hat is worn),
  `model`

And subfiles 726–739 are the animations for exactly those extra chains: bone
sets `Head + BHair1-3 + LHair1-4 + RHair1-4` (long), `Lhair1-5 + Rhair1-5`
(twin-tails), `BHair01A-D + BHair02A` (ponytail), `BHair01A/B + LHair01A/B +
RHair01A/B` (midi), and `LSkirt01A..RSkirt03B + Waist + Hips + Spine1` (skirts).
The wardrobe ships with its own motion.

## The parts are wired into a live path

- `a/1/3/3` subfile 0 is `bt0001_00`, **identical mesh/vertex/bone counts to
  `a/0/8/8` subfile 70** (11 / 12,198 / 76), but carrying 14 embedded textures
  (`nb1_accehat02`, `nb1_bag01`, `nb1_bangle`, `Bchara_hlight00..02`, …).
  Subfile 5 is `bt0002_00`, likewise (10 / 13,161 / 70). That is precisely the
  resource split a dress-up system needs: the default model with baked textures
  in the battle-trainer archive, the same model *without* textures plus the
  swappable parts and their textures in the parts archive.
- `a/1/3/3` subfiles 3 and 8 are skeletal animations literally named
  **`bt0001_00_ba42_dressup01`** and **`bt0002_00_ba42_dressup01`**. ORAS ships
  a dress-up pose animation for the player battle model.
- `code.bin` (decompressed, RTTI intact) links the whole family, field variants
  included: `DressUpFieldHeroCore`, `DressUpFieldHeroineCore`,
  `DressUpFieldHeroResource`, `DressUpFieldHeroineResource`,
  `DressUpBattleHero(ine)(Core|Resource)`, `DressUpResourceManager`,
  `DressUpDataCacheManager`, `app::kisekae::Manager`, `savedata::Fashion`.
- `DllField.cro` has `BattleHouse_WaitSetupDressUpModel` (already established in
  `customization-scope.md`).

### The shop side left fingerprints in the text too

English game text (`a/0/7/3`, the English `gametext` bank):

- item 699 name `Discount Coupon`, description: *"This special coupon allows you
  to buy items at a discount when you are shopping at a boutique."*
- PSS memory line 79:94 — *"…when she went to a boutique and tried on clothes,
  but she left the boutique without buying anything."*
- place-type list 0:6 `a boutique`, 0:24 `a stylish café`

Same strings present in the French, German and Spanish banks (`a/0/7/4`,
`a/0/7/6`, `a/0/7/7`). XY's boutique economy is still described in ORAS's
shipped text.

## What is NOT there — measured, and this is the real cost line

**There is no FIELD part library.** The field player is one baked model.

`a/0/2/1` (544 subfiles, field character models, `MM` container v3) holds the
player field models at subfiles **171 = `rstr0001_00_fi`** (heroine) and
**172 = `rstr0002_00_fi`** (hero), duplicated at 314 / 315. Parsed:

```
rstr0001_00_fi   8 meshes, 8,403 verts, 5 materials, 50 bones
  meshes    bag, etc, face, sw_parts01..05
  materials rstr0001_00_body / _face / _head / _sw_parts / _sw_parts_02
  textures  projection_dummy, rstr0001_00_fi_body(128x128),
            _fi_face(256x128), _fi_head(128x128), _fi_sw_parts(128x64)
rstr0002_00_fi   8 meshes, 8,358 verts, 5 materials, 50 bones
```

The `sw_parts01..05` meshes/bones are a five-slot swap rig on the field model,
but they share two materials on a single 128×64 texture — that is a small fixed
prop set (bag/bracelet-scale), not a wardrobe. No `b1_`/`b2_` part, no
`loc_acchat`, no `_btms_`/`_tops_` model exists anywhere outside `a/0/8/8` and
`a/1/3/3`.

And the field skeleton is **not** the battle skeleton:

| | battle `bt0001_00` | field `rstr0001_00_fi` |
|---|---|---|
| bones | 76 | 50 |
| spine | Spine1, Spine2, Spine3 | Spine1, Spine2 |
| arms | LArmA, LArmB | LArmA, LArmB |
| fingers | LFingerA1..D3 (12/hand) | LFingerA1/A2/B1/B2 (4/hand) |
| face | FJlipL/U, FJteethL/U, L/RFJLipS | (none) |
| character-specific | BagA..D | Bag, LHair/RHair, LRibbon/RRibbon |

A `b1_tops_*` part is rigged to 45 bones including `Spine3` and
`LFingerC/D` — bones the field skeleton does not have. **The battle parts will
not bind to the field model unmodified.**

### What that means for the build

- **Battle / Maison / anywhere the dress-up model is used** — parts are there,
  rigged, animated, indexed. Pure RE work.
- **Field** — three options, none of them "author a wardrobe from scratch":
  1. drive the field model's five `sw_parts` slots (cheapest, narrowest);
  2. re-rig the existing battle parts onto the 50-bone field skeleton (an asset
     *conversion*, mechanical, and the geometry already exists);
  3. make the field use the dress-up model — `DressUpFieldHeroCore` and
     `DressUpFieldHeroResource` are linked in `code.bin`, so the engine has a
     field path; whether it can be reached, and what it loads, is not settled.

The scope doc's stated risk ("somebody must author 3D clothing models") is
**retired**. What replaces it is a much smaller, bounded question about the
field skeleton.

## What was ruled out, and how

- **All 298 non-empty GARCs** were opened with CTRMap's `GARC` and every subfile
  under 16 MB decompressed and substring-scanned for
  `acchat, _btms_, _tops_, hairpony, hairbob, socks_od, point_ribbon,
  pointglasses, kisekae, dressup, sw_parts, b1_, b2_, f1_, f2_, d1_, p1_, p2_,
  tr0001, tr0002, cloth, hair, wear, shoes, boots, hat_, cap_, bag_, accessor,
  skirt, pants, shirt, hero, heroine, costume, fashion, make`.
- `acchat` (the hat mount) appears in **exactly two** archives: `a/0/8/8` (49
  subfiles) and `a/1/3/3` (7). `_btms_` / `_tops_` likewise. There is no second
  part set.
- **`a/0/0/8`** — the 1 GB, 8,067-subfile archive — was scanned separately and in
  full: **zero hits** on any dress-up token.
- Other large model archives were identified and excluded by content:
  `a/0/3/1` (2,040 CGFX) = particle effects; `a/0/3/2` (1,030 BCH) = primitive /
  test shapes; `a/1/5/2` (1,263) = the `ad00_rs_*` demo/opening scene;
  `a/0/2/1` = field character models; `a/1/3/3` = battle trainer models;
  `a/0/3/9` (858, 108 MB) = map graphics.
- No customization UI module: consistent with `customization-scope.md`'s CRO
  survey; nothing found here contradicts it.

## What is still open

- **Runtime loading is not proven.** Everything above proves the assets are
  present, complete, indexed and rigged. Proving the shipped executable actually
  opens `a/0/8/8` needs either the `DressUpResourceManager` call sites traced in
  `code.bin` or an emulator file-access log. Neither was done here.
- ~~The index tables are not decoded.~~ **Settled** — see "The seven index
  tables, decoded". What remains open inside them is small and named there:
  the make-up trailer's own header, the two leading fields of each 28-byte
  make-up record, and the meaning of the `kind` word on an item record.
- **Whether this is XY's complete catalogue** cannot be established from an ORAS
  dump alone — there is no XY dump here to diff against. What is established is
  that ORAS ships 101 swappable part models (86 of them actually offered by the
  index) and 544 part textures, across 14 dress-up slots for the heroine and 13
  for the hero.
- **Whether the field dress-up path in `code.bin` is reachable** — the
  `DressUpField*` classes are linked, but no call site was traced.

## Reproducing this

Probes are in
`wt/_state/queue1/assets/probe/`
(`Enum1`, `Names1`, `Scan1`, `Ctx1`, `One`, `Hex1`, `Bch1`, `Skel1`, `Mm1`,
`Txt1`, `Line1`), compiled against
`CTRMap/build/classes` plus `CTRMap/lib/*.jar`:

```powershell
$jdk = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory |
        Where-Object Name -like "jdk-*" | Sort-Object Name -Descending |
        Select-Object -First 1).FullName
& "$jdk\bin\java.exe" -cp "<classes>;<libs>;<probe>" `
    Skel1 "<romfs>\a\0\8\8" 70 25 27 31 36 59 63 66 69
```

The index decoding has its own reader and suite in the tree, so it needs no
probe:

```powershell
& "$jdk\bin\java.exe" -Djava.awt.headless=true `
    -cp "build\classes;lib\jogl-all.jar;lib\gluegen-rt.jar" `
    ctrmap.tests.DressUpIndexTest "<romfs-root>"
```

It prints every slot and the part models in it, for both characters, and fails
if any of that stops agreeing with the models' own names.

Game data was read only; nothing under `RomFS/` was written.
