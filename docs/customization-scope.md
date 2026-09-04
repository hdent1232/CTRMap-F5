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
