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

**WHAT DOES NOT.** Two things were in this list and should not have been, and
both would have blocked every measurement forever:

- A LIMITATION is not an item. "The script archive cannot be forked" can never
  be ticked, because it is not work anybody is going to do - it is a fact about
  what the editor can reach. Those live under _Known limitations_, which the
  guard does not read, and they are closed by a refusal in the product rather
  than by an entry here.
- THE MEASUREMENT IS NOT AN ITEM IN ITS OWN QUEUE. "Re-run the sweep" sat in
  this list and therefore blocked the sweep, forever, which is the sort of
  circular rule that gets a guard disabled instead of obeyed. The queue GATES
  the sweep; it does not contain it.

## Open

_(nothing)_

## Done

- [x] 2026-09-13 F. One camera thread instead of four, a daemon, that honours the
      interrupt. Each of the four swallowed the InterruptedException that exists to
      stop it, so a key press whose release was lost - focus change, modal dialog,
      window closing - left a thread walking the camera for the life of the process
      and kept the JVM alive after the last window closed. Folding them also showed
      the four copies had drifted: two moved vertically with the camera angle and two
      did not, which is now one readable rule.
- [x] 2026-09-13 G. A release ships the program, not its proofs. package.ps1 jarred
      all of build/classes, so every portable zip and jpackage image carried the whole
      battery - about 40% of the jar - and the suites were runnable out of the shipped
      artifact against the user's own game folder. It stages a copy without
      ctrmap/tests and refuses to jar if a *Test.class survives the cut.
- [x] 2026-09-13 H. The silent catches have a number and it may only fall.
      SilentCatchTest counts empty and log-only catch blocks in production code -
      measured at 125 (55 empty, 70 log-only) - and refuses the 126th. Not a ban: some
      are genuinely nothing-to-do and deciding which, one at a time, is weeks of
      reading, while a ban would be reverted in a day and the doctrine would go back to
      being decoration. It also names the 30 empty ones that carry no comment and no
      reason at all, which is where the falling starts.
- [x] 2026-09-13 D. The app stopped naming places that do not exist, and stopped
      hiding things. Three messages sent users to "Map > Fork area", an item renamed
      long ago, and a suite pinned the wording - all four corrected, and
      MainframeShapeTest now reads every "Menu > Item" named in a production string
      and refuses one the menu bar cannot resolve (26 checked). The Extras bar offers
      all three tools that open in Extras rather than one. Save is Ctrl+S and Deploy
      is Ctrl+D, the first accelerators in this menu bar. Help opens the quick start
      guide that has shipped beside the program with no door to it. And OBJ to
      collisions says what it did - it used to do nothing at all, silently, when no
      collision was open.
- [x] 2026-09-13 C. One closure engine, two transplants. The message and sign
      injectors were 293 of 326 substantive lines the same, byte-identical in the
      closure walk, the stub-insert preconditions and the branch-boundary check -
      code that rewrites Pawn bytecode in save-bound zone scripts, so a fix to one
      copy left the other wrong. PawnClosure holds the walk, the CRC cell order, the
      preconditions and the boundary check; each injector keeps what actually differs
      (which wrapper to find, the closure geometry, the vanilla fingerprint, the
      data-segment step) and translates what the engine refuses into its own
      InjectionException, because the editor and both corpus suites catch that by
      name. 190 lines lighter. Proven by the corpus, not by a build: 536 zones each,
      289 composition zones both orders, 228 refusal zones byte-untouched.
- [x] 2026-09-13 A. A reader that cannot read refuses instead of inventing data.
      LittleEndianDataInputStream grew readFully/skipFully - the primitive whose
      absence caused every hand-rolled short read in the tree - and the container and
      both GARC entry readers use them. Measured before: a/0/4/0 cut in half handed
      back 278 of 431 entries as pure-zero buffers reported as data. After: none, and
      the 281 past the cut are refused. verify() can say no (wrong header, negative
      count, offsets past the end or going backwards) and carries whyNot(); open()
      refuses a file that fails it rather than printing to a console no user has;
      storeFile THROWS on a failed write instead of returning a false that twelve
      callers dropped, two of them one statement before saying "saved"; and a
      half-parsed GARC refuses to exist rather than leaving length and getEntryCount
      disagreeing.
- [x] 2026-09-13 B. 1,663 lines of unreachable code deleted (ParserLoader,
      GRColorPalette, ImageMapCreator, PLY2CMVD, BCSArStringLoader, ComboHighlight and
      three orphan tool icons), and the hole that hid them is closed: DuplicateWorkTest
      now walks references from the application and the updater to a fixed point and
      refuses a class nothing reaches unless it is named with what it is instead. Four
      of the dead ones looked alive only because they had their own main(). It found
      one the census missed on its first run.
- [x] 2026-09-12 The sweep tries the suite that killed a line LAST time first. It
      knew which suite killed each mutant and threw the answer away, so every run
      re-guessed the order from a hand table the file itself records as wrong about
      48 of 86 files - and a kill found by the fortieth suite cost thirty-nine JVM
      starts again. The killer is recorded in the baseline, the order is evidence in
      four ranks, and the ordering is a PERMUTATION of the judges: dropping a suite
      would not slow the sweep, it would manufacture survivors. The run reports how
      often the first suite tried was the killer, so the next change to this can be
      judged instead of argued about.
- [x] 2026-09-12 Every feature window is part of the UI it belongs to, and the
      ceiling fell 22 -> 9. The nine left are the application window, the progress
      window, the first-run wizard, the update window, the About box, three modal
      pickers that return a value to their caller, and the upstream model viewer -
      each argued in DialogSeamTest. What moved: the raw archive browser, tileset
      editor and workspace settings into Extras; five Game Data editors into the
      Game Data tab; the prop and NPC registry editors into the World Editor tool
      column; fog & lighting into the same column; Connect zones into the Zone
      Loader column. FormPanel is what made the four form-designer frames movable
      without touching a line of generated initComponents.
- [x] 2026-09-12 The zone preview draws through the EDITOR'S loader. It was a second,
      smaller copy of TileMapPanel - and every defect reported about it was a hole in
      the copy: one region instead of the map, a region belonging to the zone next
      door, four cells of a thirteen-cell map. The copy is deleted, TileMapPanel
      grew loadRegions (one body, two callers) and the camera arithmetic became
      PanelScene3D instead of being copied. The Zone Loader now browses like the
      atmosphere picker: a list you arrow through, a live preview, and a button that
      opens - because selecting in the dropdown IS the load.
- [x] 2026-09-12 Three rules stopped being intentions. build.ps1 refuses a tree that
      breaks a structural rule and deletes the classes, so it cannot be run; it also
      refuses to build under a running battery; and test.ps1 asks the work order
      before spending forty minutes measuring a tree with work still queued.
- [x] 2026-09-12 Six windows became part of the UI they belong to: the raw archive
      browser is the lower half of the Extras tab, and the trainer, facility
      opponent, shop, item and wild-encounter editors open in the Game Data tab -
      which until now existed and held nothing but the five buttons that opened those
      windows. Every door into them (tab entry, menu item, map-row button) goes to
      the same place and brings that tab forward. Ceiling 22 -> 15.
- [x] 2026-09-12 The preview draws the regions the zone OWNS, all of them. A zone
      names a matrix and a matrix is not one zone map: 25 retail matrices are named
      by more than one zone, and taking the first filled cell handed 39 of the 61
      zones with an ownership grid a region belonging to somebody else - Mossdeep
      City was shown Route 125 - while every zone with several regions got one
      corner tile of itself. MapMatrix.regionsOwnedBy reads the game own zone grid;
      MapPreview3D lays the regions out on the matrix. The same wrong answer fed the
      OBJ export default and the blank-canvas material probe, where it was not a
      wrong picture but another zone map edited, and both now ask for the zone own
      region. Guarded over all 540 zones against the grid, against a golden table of
      hand-measured constants, and proven by breaking.
- [x] 2026-09-12 The zone preview is IN the Zone Loader tab, beside the dropdown,
      where the owner looked for it twice. It was built twice against the standing
      rule that a feature lives in its own part of the UI - as a dialog behind a
      button, then as a window floating beside the popup - and both times every suite
      was green, because they all asked whether the DECODE worked and none asked
      whether the thing was on screen. Both windows are deleted. MainframeShapeTest
      now builds the tab and looks for the preview in it, DialogSeamTest refuses a
      new top-level window anywhere in the application, and three plants prove all
      of it by breaking.
- [x] 2026-09-11 Deploy refuses to write over a parked mod, and the refusal
      reaches the user instead of being swallowed by the pack worker.
- [x] 2026-09-11 Fog & lighting edits the fog. The block is `float[61][12]`,
      four times of day; the old controls wrote into channel 0, which is not
      fog. Retargeted `AreaEnvTest` pins the structure over all 228 areas.
- [x] 2026-09-11 View-only "Fog off" toggle beside "3D view".
- [x] 2026-09-11 Padding spares an earlier version left sharing the donor's MAP
      are repaired on open, in one pass, and blanked when untouched.
- [x] 2026-09-11 The zone list waits for the pack that repair starts.
- [x] 2026-09-12 Connect zones: wire a warp in one zone to a warp in another,
      BOTH ways, in one action - on the Zone actions bar and in the Zone menu.
      It says what it will abandon before it writes, refuses a warp that does
      not exist or a door leading to itself, and handles two doors of the SAME
      zone, which is where the obvious implementation loses half the link.
- [x] 2026-09-12 The encounter workflow is guarded. EncounterEditDialog grew a
      seam (readEncounters/saveEncounters) so edit -> pack -> edit again can be
      driven headlessly; the section that used to be a comment explaining why it
      could not exist is now a section. It also checks the zone NEXT DOOR is
      untouched, because one archive entry holds every zone's table.
- [x] 2026-09-12 The atmosphere card fits its box. It was laid out for 340px
      and handed 92 whenever the 3D view existed, so most of it was cropped -
      which is what "the atmospheres show a white blank page" was. There is now
      a compact variant for beside the 3D view, and AreaEnvTest PAINTS both into
      an image three times their height and measures the lowest ink. That also
      caught the full card overflowing its own 340 by 9px once the four
      time-of-day rows were added.
- [x] 2026-09-12 Workspaces made by earlier versions are repaired on open for
      EVERY resource the append makes private, not just the map - driven off
      ZoneAppender.madePrivate() so a row that joins it is repaired the same
      day. A zone whose resource is already private is left alone: forking one
      twice appends a copy nothing uses and orphans the one in use.
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

## Known limitations

These are not work. They are things the editor cannot reach, closed by a
refusal at the point of action rather than by anything here, and the guard does
not count them.

- **A created zone shares its donor's SCRIPT.** `ArchiveType` has no script
  archive: CTRMap neither extracts nor packs it, so a new zone cannot be given
  one of its own and runs the donor's events. `ZoneAppender` refuses to create a
  zone without saying so first. Revisit only if the script archive is ever
  managed - at which point SCRIPT joins `MADE_PRIVATE` and both the refusal and
  the repair follow on their own.

- **Two formats are measured and proven, and nothing in the editor reaches them.**
  DressUpIndex/DressUpArchive read the whole XY wardrobe (101 rigged parts, proven by
  DressUpIndexTest) and PartyParam holds 71 script-selector names (proven by
  PartyParamTest). Both are the expensive half of a feature, already done; neither has
  a UI, so today the only way to use PartyParam's names is to read the Java source.
  This is NOT debt to delete and it is not work anyone has committed to: it is
  recorded here, and in DuplicateWorkTest's UNREACHED table as MEASURED, NOT
  SHIPPED, so neither can sit in the tree looking alive. Surfacing either is a
  feature decision for the owner, not a cleanup.
