# PROPERTIES.md — what is supposed to be true

The battery answers *"would anything notice if this broke?"* for 126 suites. It
has never answered *"what is supposed to be true?"*, and those are different
questions. A suite can be green because the property it guards holds, or because
the property was never written down and the suite guards something adjacent.

So this file is the list. Each statement is something that must be true of what
CTRMap produces or of what it refuses to do, and each names the suite that
checks it — or is marked **OPEN**, which means nothing checks it and we know.

Two rules for keeping it honest:

- **A statement names a guard or says OPEN.** "Probably covered by" is not a
  guard. If you cannot name the suite, it is OPEN.
- **OPEN is not a defect report.** Some of these are open because the property
  is expensive to assert, some because it needs the game running, and some
  because nobody has got to them. Where the reason is known it is written down.

Where a suite covers a statement only in part, that is said in the line, because
"partly" that reads as "yes" is exactly the failure this file exists to prevent.

---

## 1. A packed archive

The pack step is the last thing between an edit and the user's game, and it is
where a silent failure costs the most: everything looked fine and the game does
not boot.

| Must be true | Guard |
|---|---|
| A pack writes exactly the archives that were edited, and nothing else. | `PackScopeTest` |
| A pack that refuses part-way leaves the archive's table as it was, not half rewritten. | `PackRollbackTest` |
| Every warning the pack produces reaches the user rather than a log nobody reads. | `PackReportTest` |
| Deploying a mod ships only edited archives. | `ModDeployerTest` |
| A stored entry's type is read from what it *is*, not from where it sits. | `GarcSniffTest` |
| Cross-archive references still resolve after a pack. | `IntegrityTest` |
| **OPEN** — a packed GARC is byte-identical to the retail one when nothing was edited. Partly covered: the round-trip suites prove per-record fidelity, and `SnapshotIntegrityTest` proves the pristine copy is intact, but nothing asserts the whole-archive identity of an untouched pack end to end. | OPEN |

## 2. The workspace and the pristine copy

| Must be true | Guard |
|---|---|
| A pristine copy exists and can be put back. | `VaultGuardsTest`, `SnapshotIntegrityTest` |
| Pointing the workspace at a different game folder does not orphan or overwrite the backup. | `WorkspaceRepointTest` |
| The open game is an instance handed to what needs it, not a set of statics — two can exist at once. | `WorkspaceSessionTest`, `GameFilesSeamTest`, `HandedGameTest` |
| A game whose profile has not been measured is refused, not guessed at. | `GameProfileTest` |
| A dump missing archives is reported before anything runs against it. | `DumpCheckTest`, and `test.ps1` refuses the battery outright |
| An update applied in place loses nothing the user had. | `UpdaterTest` |

## 3. A zone

| Must be true | Guard |
|---|---|
| A cloned zone is a complete, independently editable copy — no field still points at the source. | `ZoneClonerTest` |
| Appending several zones at once produces the same result as appending them one at a time. | `ZoneAppendMultiTest` |
| Clearing, renaming or repointing a zone changes exactly the bytes it claims to. | `ZoneManagerTest` |
| Which zones are safe to reuse is answered from the data, not from a list someone typed. | `ZoneRepurposeScannerTest` |
| A zone that fails to load leaves no editor showing the previous one. | `ZoneLoadingStateTest`, `OpenEditorsTest` |
| The loaded zone has exactly one owner; two panels over two owners answer about their own. | `LoadedZoneTest` |
| A shared map offered as a fork remembers a decline. | `ZoneLoadingStateTest` |
| Damage an old fork left behind is found rather than inherited. | `MisplacedRegistryTest`, `ForkGuardsTest` |
| Saving a zone commits every open editor first, including the warp form, and stops when one refuses. | `DataSafetyGuardsTest`, `OpenEditorsTest` |
| A zone opened from a loose file goes to its own index, not to the one the dropdown last showed. | `MainframeActionGuardsTest` |

## 4. A placed building, and the map under it

| Must be true | Guard |
|---|---|
| A placed building's footing matches what the palette showed. | `PlacementGuardsTest`, `HarvesterGuardsTest` |
| Nothing of a replaced composite is left standing. | `CompositeLeftoverTest` |
| Editing a composite in place does not disturb its neighbours. | `CompositeBuildTest` |
| A cut is named for what it is, so two different cuts are never the same entry. | `HarvesterRankingTest` |
| Cliffs are never resolved as the ground the player walks on. | `GroundResolveTest` |
| Which region and which mesh is the ground is decided the same way every time. | `MapDefaultsTest` |
| A door names the model it will actually draw. | `DoorPropGuardsTest` |
| The prop registry's order survives an edit. | `ADPropRegistryOrderTest` |
| Switching matrix tools carries the cursor whole: a coordinate from one grid is never written into another. | `MatrixEditFormGuardsTest` |
| The matrix tool buttons work before a matrix is loaded, or refuse in words. | `MatrixEditFormGuardsTest` |
| Growing a matrix grows every layer `assembleData` will later read. | `MatrixEditFormGuardsTest` |
| **OPEN** — a placed building is reachable and collidable in the running game. Only the emulator can answer this; see TESTZONE.md. | OPEN, by nature |

## 5. An applied paint

| Must be true | Guard |
|---|---|
| Apply writes nothing it cannot finish — a refusal leaves the map as it was. | `PaintApplyGuardsTest` |
| Apply saves every open editor first, and gives up if one refuses. | `OpenEditorsTest`, `PaintApplyGuardsTest` |
| The painter's document reports what it holds, including after a failed load. | `PaintFormGuardsTest` |
| Slopes, water and seeded ground come out as the tool showed them. | `PaintedFloorTest`, `PaintedRegionTest` |
| Ramps settle the same way whether drawn, dragged or filled. | `PaintFormGuardsTest` |
| Saving a loose GR map writes the tile edits the user made, and opening another one asks before dropping what the entity editors hold. | `MainframeActionGuardsTest`, `DataSafetyGuardsTest` |

## 6. An imported model or texture

| Must be true | Guard |
|---|---|
| A carry that reports it wrote a texture, wrote it. | `TextureCarryGuardsTest` |
| Every texture format round-trips one texel at a time. | `TextureCodecTest` |
| A clashing texture pack import is resolved, not silently overwritten. | `TexturePackImportTest` |
| An imported brush keeps its UV scale. | `UvScaleTest` |
| A stamp keeps the vertex colour format the engine expects. | `PrefabColourTest` |
| A terrain import that fails aborts the edit rather than reporting and continuing. | `TerrainImportNoiseTest` |
| Any brush can be imported anywhere a brush is valid. | `TerrainImportTest` |
| A BCH map model imports without losing what it carried. | `MapModelImportTest` |

## 7. A saved entity

| Must be true | Guard |
|---|---|
| An NPC's altitude, uid, count and script survive a save. | `NpcEntityGuardsTest` |
| The motion dropdowns write back the code they showed. | `NpcMoveCodesTest` |
| The warp transition dropdown writes back the transition it showed. | `WarpTransitionsTest` |
| Save, Remove, New, the overlay and the drag all agree about which NPC is selected. | `NpcEditFormGuardsTest` |
| Each edit form writes what it displayed and nothing else. | `PropEditFormGuardsTest`, `MatrixEditFormGuardsTest`, `TriggerEditFormGuardsTest`, `GeoEditFormGuardsTest` |
| A template fits the corpus it claims to fit. | `NpcTemplatesTest` |
| Stale state, a stale script, a stale warp or a worker still running cannot reach a save. | `DataSafetyGuardsTest` |
| An edit form that has refused once is usable again; nothing leaves it permanently inert. | `PropEditFormGuardsTest` |
| A field that states its range accepts both ends of it. | `NpcEditFormGuardsTest` |

## 8. A script

| Must be true | Guard |
|---|---|
| The assembler refuses what it cannot assemble, and says which line. | `ScriptAssemblerGuardTest` |
| A dispatch case is one the engine can return from. | `DispatchTrampolineTest` |
| The freeze guards refuse the shapes that freeze the game. | `DispatchGuardsTest` |
| Talker dispatch is read from the script, not assumed. | `ZoneScriptAnalyzerTest` |
| An injected message wrapper round-trips across the whole corpus. | `MsgWrapperInjectTest`, `GFMessageFileRoundTripTest` |
| Hostile input to a message file is refused rather than parsed into nonsense. | `GFMessageFileHostileTest` |
| A Maison pool edit stays vanilla-safe. | `MaisonPoolGuardTest` |

## 9. What the program tells the user

| Must be true | Guard |
|---|---|
| A message the user should see is reachable — printed *and* shown. | `UiOutputTest` |
| Only the dialog seam opens a dialog, and one opened without naming a window is parented correctly. | `DialogSeamTest` |
| The main window says what it actually did. | `MainframeReportsTest` |
| Every menu action does what it says and refuses what it cannot do, in words. | `MainframeActionGuardsTest` |
| No suite opens a dialog under test. | `BatteryHygieneTest` |

## 10. The program's own shape

These are properties of the source rather than of the output, and they are here
because they are the ones that decay quietly.

| Must be true | Guard |
|---|---|
| No game-detected constant lives outside `ctrmap.gamedef`. | `SourceSeamTest` |
| The format layer never reaches the global, the UI, the window or the dialog seam. | `SourceSeamTest`, `GameFilesSeamTest` |
| The editing tools never reach the window. | `SourceSeamTest` |
| Nothing the window builds is handed a static that method has not assigned yet. | `MainframeEdgesTest` |
| Nothing outside the window reads a window static: the count is zero and may not rise. | `MainframeEdgesTest` |
| Every editor, tool and panel is HANDED what it works on, so a suite can hand it a different one. | `EditorBench`, and the guard suites built on it |
| The bytecode scanner never sees fewer readers of a class than a source grep does. | `ClassFileScannerTest` |
| The public-static count only ever falls. | `GlobalStateTest` |
| A digest ignores line endings for text and never for anything else. | `BatteryHygieneTest` |
| `build/classes` is exactly what `build.ps1` made from `src/`. | `BatteryHygieneTest`, and `test.ps1` refuses otherwise |
| A build works in a directory that is not a git repository. | `BatteryHygieneTest` |
| A commit that fixes something carries something that would notice a second one. | `CommitGuardTest`, and the `commit-msg` hook |
| Every guard the battery relies on is still measured — a line nothing asserts is named. | `MutationBaselineTest`, `tools/mutate2.py` |

---

## What is OPEN, collected

1. **A packed GARC is byte-identical when nothing was edited.** Per-record
   fidelity is covered; whole-archive identity of an untouched pack is not.
2. **Anything only the running game can answer** — a placed building being
   reachable and collidable, a script actually firing, a warp actually landing.
   That is [TESTZONE.md](TESTZONE.md)'s job and it is a person's, not a suite's.
3. **The plant ledger is not full.** "Proven by breaking" is real here but was
   one-time: it lived in commit messages and could not be re-executed, so a
   guard proven in one month and hollowed out in the next looked identical to
   one that still works. `tools/guard/plants.json` and `tools/guard/replant.py`
   are the mechanism and they run. What is OPEN is the COVERAGE: most suites
   still have no plant, so for those the ledger proves nothing. Two ceilings in
   `plants.json` are the measure and both may only fall - `owed_ceiling`, the
   suites with no plant at all, and `owed_generalisation_ceiling`, the plants
   that break one line rather than the property.
4. **What the fixes look like on screen.** Every defect fixed this campaign has
   a guard that fails without it, but a guard asserts what the code returns, not
   what a person sees. Whether the repaired matrix scroll pane, the re-enabled
   edit forms and the corrected tileset repaint LOOK right is a person's check
   and has not been done.

## How to use this file

When you fix something, find the statement it belongs under. If there is none,
write one. If the statement is there and marked OPEN and your fix closes it,
name the guard. A fix that fits under no statement is worth a second look: it
may be fixing an instance of something nobody has stated yet.
