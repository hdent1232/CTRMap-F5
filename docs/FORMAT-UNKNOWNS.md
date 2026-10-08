# Open reverse-engineering: the fields nobody has named

Every field a format class reads under a name that admits nobody knows what the bytes are: `unknown...`,
or a bare offset such as `u10`. MainframeShapeTest holds this table to the tree in both directions. A
field added under such a name without a row here fails it by name, and so does a row whose field has
gone or been named (F221).

Every one of them is written back exactly as it was read, so a round trip loses none of them (measured
2026-10-08: each name appears in a write call of its own class). Where one is shown, the form says the
bytes are raw and folds them behind their label (`Forms.unknownField`): a click on the label shows them.

| Field | Where the bytes are | Shown |
|---|---|---|
| `CameraData.unknownBytes` | camera record, 0x00-0x0F | no |
| `CameraData.unknown00` | camera record, 0x15 | no |
| `CameraData.unknown01or03` | camera record, 0x17: 3 for a camera whose movement is 7, else 1 | set from the Camera form's motion |
| `CameraData.unknownInt1` | camera record, 0x3C-0x3F | no |
| `CameraData.unknownFFFF` | camera record, 0x46-0x47: FFFF except in the Elite Four rooms and Parfum Palace | no |
| `MapMatrix.unknown` | map matrix header, 0x02-0x03, after the LOD flag | no |
| `NPCRegistry.u1` | NPC registry entry, 0x02: 1 except in UID-0 dummies | no |
| `NPCRegistry.u4` | NPC registry entry, 0x04: 2 or 4 when set; to do with NPCs in cutscenes | no |
| `NPCRegistry.u5` | NPC registry entry, 0x05: always 0 | no |
| `NPCRegistry.u8` | NPC registry entry, 0x08: 1 except in UID-0 dummies | no |
| `NPCRegistry.u9` | NPC registry entry, 0x09: used only by X/Y NPCs | no |
| `NPCRegistry.uC` | NPC registry entry, 0x0C: used only by the Cut animation | no |
| `NPCRegistry.uD` | NPC registry entry, 0x0D: always 0 | no |
| `NPCRegistry.uE` | NPC registry entry, 0x0E: always 0 | no |
| `NPCRegistry.u10` | NPC registry entry, 0x10: always 0 | no |
| `NPCRegistry.u11` | NPC registry entry, 0x11: 1 or 2 | no |
| `NPCRegistry.u12` | NPC registry entry, 0x12: always 0 | no |
| `NPCRegistry.u13` | NPC registry entry, 0x13: always 0 | no |
| `NPCRegistry.u16` | NPC registry entry, 0x16: always 0 | no |
| `NPCRegistry.u17` | NPC registry entry, 0x17: always 0 | no |
| `ADPropRegistry.u0` | area prop registry entry, 0x06 | no |
| `ADPropRegistry.u1` | area prop registry entry, 0x08-0x0B | no |
| `ADPropRegistry.u2` | area prop registry entry, 0x2C-0x4F: mostly constant; X/Y's Aquacorde fountain differs | no |
| `GRProp.unknown` | map prop record, the int after the position | no |
| `ZoneEntities.unknown2` | zone prop record, 0x02-0x03 | no |
| `ZoneEntities.unknown4` | zone prop record, 0x04-0x05 | no |
| `ZoneEntities.unknown6` | zone prop record, 0x06-0x07 | no |
| `ZoneEntities.unknown10` | zone prop record, 0x10-0x13 | no |
| `ZoneEntities.u10` | NPC record, 0x10-0x11 | NPC form, folded |
| `ZoneEntities.u12` | NPC record, 0x12-0x13 | NPC form, folded |
| `ZoneEntities.u14` | warp record, 0x14-0x15; and trigger record, 0x14-0x15 | trigger form, folded; warps: no |
| `ZoneEntities.u16` | warp record, 0x16-0x17 | no |
| `ZoneEntities.u2` | trigger record, 0x02-0x03 | trigger form, folded |
| `ZoneEntities.u6` | trigger record, 0x06-0x07 | trigger form, folded |
| `ZoneEntities.u8` | trigger record, 0x08-0x09 | trigger form, folded |
| `ZoneEntities.uA` | trigger record, 0x0A-0x0B | no (carried through an edit) |
| `ZoneEntities.u16val` | trigger record, 0x16-0x17 | trigger form, folded |
| `ZoneHeader.unknownFlag` | zone header, bit 15 of the word at 0x20 | Zone tab, folded |
| `ZoneHeader.unknownFlags` | zone header, the flag word at 0x28 whole; its named bits are the Zone tab's boxes | Zone tab, folded |

## Other open work, written down here so it is visible

- **World animations.** AreaData subfile 2 makes water scroll, grass sway and waves foam. The parser
  reads all of it, but the only thing written is `WorldAnim.spliceSeaScroll`, which grafts the retail
  sea-scroll pair copied from area 10. A painted area can scroll its sea and nothing else: no grass
  sway, no foam.
- **Zone music.** The zone header holds four music slots, one per season. Nothing records which one
  ORAS or X/Y plays. The Zone tab shows Spring's, and a change moves every slot that held the same
  track (`ZoneHeader.setMusic`).
- **Comments that admit a guess.** The 2026-10-06 audit (F221) counted 36 comment lines in 24 format
  files that say a meaning is unknown or guessed; DressUpIndex and PartyParam lead. They are not
  fields, so the table above does not hold them.
