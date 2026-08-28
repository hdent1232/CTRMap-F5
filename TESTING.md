# TESTING.md — things to verify in the emulator

A running list of everything built offline that needs an in-game check.
Ordered roughly by how much other work depends on the answer. Check items
off (`[x]`) as you confirm them; note anything that looks wrong and I'll fix it.

Every item below already passes the offline regression battery (parsers,
byte-round-trips, renders) — these checks are about how the REAL game engine
reacts, which can't be verified without the emulator.

## Tile painter — core loop
- [ ] **Paint & deploy**: open a zone → Tools → Tile painter → paint some
  grass/path/rock → Apply to zone → Deploy. Walk around. Expect: you walk on
  painted ground, rock blocks you, the map looks like the 3D preview.
- [ ] **Tall grass**: walk through painted tall grass. Expect: rustle animation
  + wild encounters trigger (uses the zone's encounter table).
- [ ] **Water + surf**: paint water, surf onto it. Expect: surfable, water looks
  like water (2-layer sea material).

## Tile painter — elevation & ramps
- [ ] **Raised plateau**: raise a block of tiles 1–2 levels. Expect: cliff walls
  appear at the drops, you canNOT walk up/off the cliff edge.
- [ ] **Ramp/slope**: mark ramp tiles connecting level 0 → 1 → 2 (the yellow
  triangle tool). Expect: you walk smoothly up and down the slope, no hopping,
  no clipping through.
- [ ] **Ledge tiles**: paint a south-facing ledge on a cliff edge. Expect: you
  can jump DOWN over it (Hoenn ledge hop), not up.

## Tile painter — full brush catalog (one visit each)
- [ ] **Cave floor** brush: encounters use cave slot? footstep sound differs?
- [ ] **Ice**: sliding behavior.
- [ ] **Deep sand**: slow trudge + footprints.
- [ ] **Bike rail** (`60 00 00 00` deck): only passable on bike (Cycling-Road rail).
- [ ] **Waterfall** tile placed on a cliff face next to water: usable with the
  Waterfall HM?
- [ ] **Door/warp tile** (`01 00 0E D4`): steps trigger a warp — NOTE it warps
  wherever the zone's warp table entry points; without editing warps it may
  warp somewhere odd or do nothing. Just confirm the step-on reaction.

## Edge blending (NEW — grass↔dirt/sand transition strips)
- [ ] Paint a dirt path through grass with **"Blend grass edges" ON** → Apply →
  Deploy. Expect: GameFreak's soft grass fringe along the grass/path seam
  (like Route 101's path edges), not a hard texture line.
  - The offline render only shows a faint band — the fringe texture is
    camera-projected by the game engine, so the real look needs this check.
- [ ] Same map with the checkbox OFF → hard seam (control test).
- [ ] Edges on a SAND beach seam (grass↔sand) — same fringe expected.

## Lighting (baked) + area fog
- [ ] **Lighting presets**: apply the same map twice — once Day, once Night
  preset. Expect: visibly darker/tinted ground at Night.
- [ ] **Area fog editor** (Tools → Edit area fog & lighting): push fog near/far
  in close + strong color. Expect: in-game distance fog changes to match.
  REMEMBER: fog is per-AREA — other zones sharing the area change too.
- [ ] **GameFreak atmosphere picker**: copy e.g. the Sootopolis or a cave
  zone's atmosphere onto your zone. Expect: in-game mood matches the picker's
  live preview of YOUR zone.

## Water scrolling (NEW — "Make water ripple here" is BUILT, this is its in-game check)
The tile painter's water banner now has a one-click **"Make water ripple here"**
button on amber zones: it splices GameFreak's exact two-layer sea-scroll
animation (copied byte-for-byte from Route 105's) into the zone's area data,
bound to this zone's map cells. Offline it's about as verified as possible
(all 228 retail files re-validate, 560 test splices pass, the tree builder
reproduces GameFreak's own serializer byte-identically) — but only the game
engine can prove it PLAYS. **This is the highest-priority test on the list.**
- [ ] **The big one**: on a landlocked zone, click "Make water ripple here"
  (banner turns green) → paint water → Apply → Deploy. Expect: the water
  scrolls in two layers exactly like a real sea route. If instead it's still,
  or the map hard-locks on load, report which.
- [ ] The area's OTHER animations still play after the splice: visit a
  neighbouring map in the same area (and the same zone pre-splice-state
  things like grass wind sway) — nothing else should have changed.
- [ ] A zone whose banner was already GREEN: painted water ripples with no
  button needed.
- [ ] Landlocked zone WITHOUT clicking the button: water still (amber banner
  told the truth).
- [ ] Click the button on a zone in a SHARED area, then visit another zone of
  that area: it must be unaffected (the animation binds by map-cell name).

## Building palette (NEW — "Buildings & decor..." in the tile painter)
Pick a Pokémon Center / Mart / Gym / 12 house styles / signs / trees / fence /
bush from a searchable list with a live 3D preview, click the grid to place.
Every catalog entry is offline-proven (extracts from your dump, stamps, and
renders complete) — these checks are about the in-game result.
- [ ] Place a **Pokémon Center** on a painted map → Apply (say YES to door
  warps) → Deploy. Expect: the building looks complete in-game, you collide
  with its walls, and **walking into the door warps you into the standard
  Pokémon Center room**. (The interior's exit will take you to Oldale — that's
  expected until you clone an interior; retarget with the Warp tool.)
- [ ] Add the swinging-door prop (Prop Tool, the palette names it —
  com_bm_pcdoor01 for the Center) at the door tile. Expect: the animated
  door renders and opens.
- [ ] Place a house + a sign + trees on one map. Expect: all render with
  correct textures (auto-carried across areas), trees/signs block movement
  per their retail footprint tiles.
- [ ] Place a building on RAISED terrain (height 1-2). Expect: it sits on the
  plateau, not floating or buried (base-height compensation).

## Props & furniture
- [ ] Place a TV / PC / door prop via the Prop Tool on a painted map → Deploy.
  Expect: visible, and interaction (PC menu) works if scripted/expected.

## Shop inventories (NEW — Tools → "Edit shop inventories (Marts)...")
Shop lists live in the executable, so this ships as a code.ips like the zone
patch (offline: all 24 retail inventories byte-verified in your code.bin).
- [ ] Change an easy-to-check shop (e.g. Lavaridge Herbs, or the 0-badge
  Mart list) → Save code.ips → deploy to Azahar
  (load/mods/&lt;titleid&gt;/exefs/code.ips) → full emulator restart → talk to the
  clerk. Expect: your items, at their normal prices.
- [ ] If you also use the zone-limit patch: save with MERGE when asked, then
  confirm BOTH still work (game boots with added zones AND the shop sells
  the edited items) — one code.ips carries both.

## Interiors (Pokémon Centers, houses — just so it's on the list)
- [ ] Load a Pokémon Center interior zone in the zone loader (interiors are
  ordinary zones), move an NPC or prop, Apply/Deploy. Expect: the change
  shows inside every Center that uses that interior zone.

## Earlier systems (from previous sessions, still unverified in-game)
- [ ] **Maison opponent editor**: edit a Maison set (a/1/8), battle it in the
  Maison. Expect: edited species/moves appear.
- [ ] **GiveBP script**: the PlayerSetBP native script edit awards BP correctly
  (check BP counter before/after).
- [ ] **Trainer editor**: edited trainer team appears in battle.
- [ ] **Encounter editor**: edited wild slots appear at expected rates.
- [ ] **Zone-limit patch** (code.bin): game boots with the patched executable
  and added zones load.

## How to report back
For anything that fails: say which item, what you saw vs expected, and (if it
crashed) whether it hard-locked or errored. Screenshots help. I keep this file
updated every time a new offline-built feature lands.
