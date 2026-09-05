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
`C:/Users/flami/Desktop/Claude/sessions/3DS Editor/RomFS/000400000011C400/a/`
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
| 0–6 | seven raw index tables (see below) |
| 7–70 | **64 BCH part models, heroine (`b1_`)**; 70 = assembled default `bt0001_00` |
| 71–76 | `Bchara_hight00/01`, `Bchara_hlight00..03` toon/highlight lookup textures |
| 77–449 | heroine part textures (design/colour variants) |
| 450–455 | small binary blobs |
| 456–494 | **39 BCH part models, hero (`b2_`)**; 494 = assembled default `bt0002_00` |
| 495–498 | highlight textures |
| 499–720 | hero part textures |
| 721–723 | `paintl`, `paintr`, `star` textures |
| 724 | `DB` container that re-packs the same seven tables as subfiles 0–6 |
| 725 | `DA` container |
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

`_mae` = the front-hair half of a hairstyle (mae = 前, "front"), so the seven
hairstyles ship as back+front pairs.

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

### The slot list, read off the assembled model

`bt0001_00`'s eleven meshes ARE the eleven dress-up slots:

```
nb1_tops   nb1_bottoms   nb1_shoes   nb1_socks_none   nb1_hat01
nb1_hairlong_on   nb1_face_on_0   nb1_face_on_1   nb1_bag01
nb1_bangle   nb1_accehat02
```

`bt0002_00` has the hero's ten equivalents (`nb2_topsjer`, `nb2_btmsskin`,
`nb2_shoessboots`, `nb2_hathun`, `nb2_hair01_on`, `nb2_face_on_0/1`,
`nb2_bagbag`, `nb2_bangle01`, `nb2_pointglasses`).

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

Subfiles 0–6 are raw, uncompressed tables, not models. Table 0 (1,380 bytes) is
6-byte records `u16 id, u16 ?, u16 group`, ids 0x00–0x1D across four groups
(11 + 6 + 6 + 7). Table 1 (1,064 bytes) opens with a 0x70-byte array of `u16`
offsets and then per-group id lists. Subfile 724 is a `DB` container whose eight
section offsets carve out lengths 1380 / 1064 / 1432 / 100 / 112 / 376 / 500 —
byte-for-byte the lengths of subfiles 0–6, with section 0's bytes matching
subfile 0. So the archive is **self-describing**: whatever indexes parts by id
has its table shipped alongside the parts. Decoding those tables fully is
follow-up work, not a blocker.

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
- **The index tables in subfiles 0–6 / 724 are not decoded** past their record
  shape and grouping.
- **Whether this is XY's complete catalogue** cannot be established from an ORAS
  dump alone — there is no XY dump here to diff against. What is established is
  that ORAS ships 101 swappable part models and 544 part textures, across the 11
  mesh slots of `bt0001_00` and the 10 of `bt0002_00`.
- **Whether the field dress-up path in `code.bin` is reachable** — the
  `DressUpField*` classes are linked, but no call site was traced.

## Reproducing this

Probes are in
`C:/Users/flami/Desktop/Claude/sessions/3DS Editor/wt/_state/queue1/assets/probe/`
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

Game data was read only; nothing under `RomFS/` was written.
