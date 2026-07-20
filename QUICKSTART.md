# CTRMap Quickstart (Gen 6: X/Y, Omega Ruby/Alpha Sapphire)

The original tool shipped with no usage docs. This is the flow that actually works.

## 1. Point it at a game

There is **no "Open ROM" menu** — and `File → Open Zone` is *not* how you browse zones
(it opens loose zone files, and your RomFS contains packed archives, not loose files).

Instead: **Options → Workspace settings**
- **RomFS path** — your extracted RomFS folder (the one containing `a/0/...`, `a/2/...`)
- **Workspace path** — any separate empty folder; CTRMap unpacks working copies there
- Leave Tileset on "Default". The ESPICA path is only for 3D model injection; ignore it.
- **Save**

The game is auto-detected: `a/2/9/8` present → ORAS, `a/2/7/0` → X/Y. Gen 7 (Sun/Moon,
USUM) is **not supported** — it will say "Could not detect game version".

> **Work on a COPY of your dump.** `File → Pack Workspace` repacks the archives and writes
> them back **over the originals in your RomFS folder**, in place. There is no undo.

## 2. Open a zone

Go to the **Zone Loader** tab and pick a zone from the dropdown at the top. Entries are
named `Location - index`, e.g. `Littleroot Town - 5`, `Route 102 - 24`.

That dropdown is the only way to load a zone. Selecting one loads its header (BGM,
weather, transitions), its entities (NPCs, warps, props, triggers) and its script, then
switches you to the World Editor tab automatically.

(`File → Open Zone` is a header inspector for a single loose zone file, not a way to
browse the game. It now opens in the right folder and warns you if you pick something
that isn't a zone, but the Zone Loader dropdown is what you want.)

## 3. Edit the world

Switch to the **World Editor** tab. The toolbar picks what you are placing; the panel on
the right changes to match the selected tool:

| Tool | What it edits |
|---|---|
| Edit / Set / Fill | tilemap move permissions (where the player can walk) |
| Cam | 3D camera objects |
| Prop | world props / furniture |
| NPC | NPC placement and behaviour |
| Warp | doors, stairs, map transitions |
| **Trigger** | **script triggers — step-on and interaction events** |

Every toolbar button has a tooltip saying what it does — hover if you are unsure.

With the Trigger tool selected, existing triggers are drawn as rectangles over the map
(white = interaction, yellow = step-on). Click one to select it; the right-hand panel
shows its fields. `New entry` adds one at the centre of the view, `Remove entry` deletes
the selected one, and `Save` commits your field edits.

Trigger X/Y are **tile** coordinates (unlike warps, which are stored in world units of
18 per tile) — confirmed against real ORAS data, where trigger coordinates run ~18x
smaller than warp coordinates in the same zone.

### Trigger fields

- **Script** — the script index this trigger runs. This is the link to the game's Pawn
  script for that zone (see the Script Editor tab).
- **X / Y / W / H** — tile position and size of the trigger rectangle.
- **Constant**, **Unknown 2/6/8/14/16** — not yet decoded. Leave them alone unless you are
  reverse-engineering; they are preserved byte-for-byte when untouched.

### NPC dialogue

With the NPC tool selected, the bottom of the right-hand panel has a **Dialogue** section:
for NPCs whose script is a simple "talker", it previews the STORYTEXT line they speak and
**Edit dialogue...** lets you change it in place (shared lines are automatically split so
other NPCs keep their text). **Add talking NPC...** creates a brand-new NPC at the centre
of the view with its own dialogue and a freshly generated talker script — it works in any
zone whose script has a dispatch (and whose STORYTEXT file is readable). If the zone's
script lacks the message-display routine the talker needs, CTRMap offers to inject one,
copied from the game's own code (~2.4 KB added to the zone script), before creating the
NPC. The button's tooltip explains when it is disabled.

### Prop palette

With the Prop tool selected, the panel ends with a **Prop palette**: type a name fragment
(e.g. `tree`) into the search box, click a result to preview it, then **Place selected
prop** to drop it at the centre of the view. If the prop's textures are not in the current
area (placing it without them would hardlock the game when the area loads), CTRMap offers
to import them automatically from a donor area into this area's texture pack — accept and
the prop is placed fully textured; cancel and nothing is modified.

### Cloning zones

The Zone Loader tab has **Clone zone...**: it copies the currently loaded zone (header,
NPCs, warps, triggers, scripts — not wild encounters) over another existing zone slot.
Pending edits are saved first; the overwrite is only reversible by restoring a backup.

### Text Editor tab

The **Text Editor** tab edits **UI GameText only** (menu strings, item names, etc. — the
numbered files of `a/0/7/3`). NPC dialogue does *not* live there: edit it from the NPC
tool's Dialogue section, which works on the separate STORYTEXT archive.

## 4. Save

`File → Save` writes your edits into the workspace. `File → Pack Workspace` then repacks
the GARC archives back into the RomFS folder (see the warning above).

To play the result, use the RomFS folder as a **LayeredFS mod** (Luma3DS on console, or
the mods folder in Citra/Lime3DS/Azahar). You do not need to rebuild a CIA.

## Good zones to experiment with

190 of 538 ORAS zones contain triggers. Ones with plenty to look at:

| Zone | Triggers |
|---|---|
| `Route 102 - 24` | 6 step-on, 1 interaction |
| `Route 113 - 40` | 4 step-on, 1 interaction |
| `Fortree City - 17` | 14 interaction |
| `Rustboro City - 16` | 7 interaction |
| `Littleroot Town - 1` | 4 interaction |

Step-on triggers are the "walk here and something happens" events, so `Route 102 - 24` is
the most instructive place to start.

## Verification status

Every ORAS zone (536/536) and every game text file (175/175) parses and re-saves to
**byte-identical** output, so loading and saving without editing changes nothing.
