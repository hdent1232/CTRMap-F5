# Pristine dump vault — design

Requested 2026-09-04. Decisions taken by the owner:
  SCOPE  — ship all three sizes, ask once at setup, default to compressed-full.
  TIMING — build AFTER the current mutation work lands (sweep, baseline, push).

Written outside the repo on purpose: the sweep running at the time ends with
`git reset --hard <sha it started from>`, so a commit made while it runs is
erased. Move this into CTRMap/docs/ when the sweep is done.

## The ask, in the owner's words

"when a user adds their fresh vanilla files dumped from their 3ds, can this app
automatically make a back up of it? so it will always have a pristine version of
oras or xy or sm/usum in case something goes wrong, like it did for me a few days
ago? Just removes the hassle of asking them to redump and re-upload later and
would be useful for bug checking and verification"

Note the three separate motives: recovery, not having to re-dump, and having a
known-good reference for bug checking. The third is the one that argues for a
verified manifest rather than a plain folder copy.

## What already exists (do NOT rebuild it)

  Workspace.snapshotOriginals()      one-time, automatic on first workspace load;
                                     copies ModDeployer.MODDABLE to
                                     _original_garcs/<archive path>, NEVER
                                     overwrites, so it captures first-load state
  Workspace.originalSnapshotStamp()  taken-from.txt, records the source folder
  Workspace.snapshotIsForeign()      catches a workspace re-pointed at another dump
  Workspace.snapshotMissingArchives() detects a PARTIAL snapshot
  SetupWizard.backupBelongsHere      the prompt, now guarded by WorkspaceRepointTest
  WorkspaceSettings.keepOrRetakeBackup  same, on the settings path
  ctrmap.setup.DumpCheck             verifies a dump — REUSE THIS as the gate
  ModDeployer park/unpark            "Turn mod OFF (play vanilla)" — parks the
                                     DEPLOYED mod. NOT a restore. Different thing.

So auto-backup is real today. Four gaps make it not what was asked for.

## The four gaps

1. COVERAGE. 161 MB of a 1.8 GB dump (measured, ORAS). Only the 16 moddable
   archives. code.bin, models, sound, every other GARC have no copy. It is a
   diff baseline, not a pristine game.
2. NO RESTORE. discardSnapshot() deletes; nothing puts anything back. Returning
   a vanilla game is a manual procedure today.
3. WRONG PLACE. The snapshot lives inside the workspace, so it dies with the
   workspace — precisely when it is needed.
4. NOT VERIFIED. Nothing checks the backup still matches what was dumped. Silent
   rot is discovered at the moment of need.

Gaps 3 and 4 are why the existing snapshot did not save zone 536 when a stale
pack corrupted it.

## Design

Generally applicable, per the standing rule — keyed on TITLE ID, not on ORAS.
XY / ORAS / SM / USUM all take the same path.

  1. VAULT, outside every workspace:  %LOCALAPPDATA%\CTRMap\vault\<titleid>\
     Deduplicated: five workspaces from one dump share one vault entry.
  2. MANIFEST per entry: relative path, size, sha256, plus the title id, the
     detected game name and region, and when it was sealed.
  3. GATE ON DumpCheck. A vault of an already-modified dump is WORSE than none,
     because it looks pristine. Only seal a dump that passes; if it fails, say
     so and offer to vault anyway, clearly labelled "not verified vanilla".
  4. NAME THE GAME from the title id ("Omega Ruby (EUR)"), and refuse to
     overwrite a vault entry belonging to a different game or region.
  5. VERIFY ON LOAD, cheaply: size+mtime first, sha256 only where those disagree.
     Report drift loudly. Never repair silently.
  6. RESTORE at two granularities:
       - whole game back to pristine
       - a SINGLE archive or file  <- this is the one that fixes a zone-536 case
                                      in one click instead of a repair session
  7. SEALED after writing. Same rule RomFS_original_garcs follows today: the
     vault is read-only to everything except the sealing step.

## Size choice — ship all three, ask once at setup, default compressed-full

  compressed full     ~0.8-1.2 GB est   complete; slower to seal and restore
  uncompressed full    1.8 GB measured  fastest, browsable in Explorer
  moddable + code.bin  ~200 MB est      covers everything CTRMap or an IPS patch
                                        can alter; not damage from outside CTRMap

Measured on this machine: RomFS/000400000011C400 = 1.8 GB,
RomFS_original_garcs = 161 MB. The compressed figure is an ESTIMATE and must be
measured before the setup wizard quotes a number to anyone.

## UI placement (standing rule: features live in their main UI area)

The vault belongs to setup and to the workspace, not in a menu dump:
  - the size question goes in the SETUP WIZARD, asked once, with real numbers
  - restore belongs beside the existing backup controls in WorkspaceSettings
  - a failed verify should surface where the workspace is loaded, not in a dialog
    the user has to go looking for
Undo/redo does not apply; restore is itself the undo.

## Guards this needs (fixes ship with guards)

  - seal then damage a file then verify -> reports exactly that file
  - restore one archive -> byte-identical to the sealed copy, others untouched
  - vault a second workspace from the same dump -> reuses the entry, seals nothing
  - point at a different game -> refuses, names both titles
  - DumpCheck fails -> not sealed as vanilla; label survives a reload
  - the vault is not writable through any normal path once sealed
  - a partial or interrupted seal is detectable and never counts as complete
    (this is the snapshotMissingArchives lesson: a partial backup that looks
    whole is the dangerous state)
