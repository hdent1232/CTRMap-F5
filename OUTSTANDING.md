# Outstanding work

The queue, as data rather than as something somebody remembers.

**WHY THIS FILE EXISTS.** Outstanding work used to live in prose - in a message,
in a paragraph promising what was next. On 2026-09-11 that cost two things in one
afternoon: a 3D preview the owner had asked for drifted for hours behind other
work without anyone saying it had, and a five-hour mutation sweep was queued in
front of changes that were already known to be coming, which would have thrown
the whole measurement away. Both are written rules. Neither was enforced by
anything.

So the queue is a file, and `tools/guard/work_order.py` refuses the long
measurements while it has open items. A rule that only exists as an intention is
the same silent failure this project removes from the product; there is no reason
the process should be held to a lower standard than the code.

**HOW IT WORKS.** An open item is a line starting `- [ ]`. A done item is
`- [x]` and stays, so the file is also the record of what was cleared and when.
`tools/mutate2.py` asks this file before it starts and refuses while anything is
open. The escape is `--anyway "<reason>"`, which is recorded in the sweep's own
output - visible, not silent.

**WHAT COUNTS AS AN ITEM.** Anything the owner asked for that is not shipped, and
anything known to be broken. Not ideas, not maybes. If it is here it is owed.

## Open

- [ ] **The SCRIPT row may never be forkable.** `ArchiveType` has no script
      archive: CTRMap neither extracts nor packs it. Until that changes a
      created zone runs its donor's events, and the only close available is the
      refusal that is now in place. Revisit if the script archive is ever added.
- [ ] **Repair the resources already shared** in workspaces made by earlier
      versions, the way `PaddingZoneRepair` repairs the map.
- [ ] **Relink / connect zones.** Asked for on 2026-09-11.
- [ ] **`GfEnvPicker` card clipping** - the atmosphere card is laid out for
      340px and clipped to 92px, so most of it is never drawn.
- [ ] **An encounter workflow guard.** `EncounterEditDialog.show` builds a modal
      dialog with no seam below it; the battery runs without
      `-Djava.awt.headless=true`, so a suite driving it would HANG rather than
      fail. Needs the decision extracted the way `openChosenGr` was.
- [ ] **Re-run the mutation sweep** (`python tools/mutate2.py 999`) once the
      above are done and committed. The baseline is stale by design until then -
      `MutationBaselineTest` is red on purpose and says so.

## Done

- [x] 2026-09-11 Deploy refuses to write over a parked mod, and the refusal
      reaches the user instead of being swallowed by the pack worker.
- [x] 2026-09-11 Fog & lighting edits the fog. The block is `float[61][12]`,
      four times of day; the old controls wrote into channel 0, which is not
      fog. Retargeted `AreaEnvTest` pins the structure over all 228 areas.
- [x] 2026-09-11 View-only "Fog off" toggle beside "3D view".
- [x] 2026-09-11 Padding spares an earlier version left sharing the donor's MAP
      are repaired on open, in one pass, and blanked when untouched.
- [x] 2026-09-11 The zone list waits for the pack that repair starts.
- [x] 2026-09-12 A created zone gets its own STORY TEXT. Copied rather than
      emptied: the script cannot be forked, so every created zone runs the
      donor's events and those ask for line numbers - an empty text file under
      a script that wants line 12 misbehaves in game. The append refuses up
      front on a game folder with no STORYTEXT archive rather than half-running.
      SCRIPT is now the only row left in the refusal.
- [x] 2026-09-12 Browse zones: a searchable list with a live 3D preview, on the
      Zone actions bar and in the Zone menu. Nothing is loaded by looking. Fixed
      two dormant MapPreview3D bugs on the way - a failed decode kept the
      PREVIOUS zone on screen under the new zone's name, and every swap leaked a
      map of GPU buffers - and one that was not dormant at all: a region too
      short to hold a BCH header killed the editor with an OutOfMemoryError
      before the magic check could refuse it.
- [x] 2026-09-12 A created zone gets its own AREA at birth - atmosphere, water
      animations, prop registry and NPC models - in one pack cycle, spares
      included. The 8-bit area budget is checked for the whole batch before
      anything is written, so an append cannot leave two zones private and two
      sharing. AREA left the refusal on its own the moment it joined
      MADE_PRIVATE.
- [x] 2026-09-11 A zone header's four shareable resources are a table
      (`ZoneResource`), the appender records which of them it makes private,
      and it REFUSES to create a zone that would share the rest until the user
      has been shown which. Guarded by looping the table, so a fifth row is
      covered the day it is added.
- [x] 2026-09-11 The work order is a file and the sweep refuses to measure a
      tree that is still moving (`tools/guard/work_order.py`).
- [x] 2026-09-11 A fix must be closed by a refusal or carry a `No-refusal:`
      line saying why the point of action cannot refuse (`commit_guard.py`).
