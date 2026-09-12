# CTRMap-F5 regression battery - runs every corpus-validated test suite
# against the pristine RomFS dumps. All suites must print ALL PASS / PASS.
# Usage: powershell -ExecutionPolicy Bypass -File test.ps1 [-Quick]
#                                   [-Pristine <dump>] [-GameDir <romfs title>]
#
# -Pristine and -GameDir point the battery at a dump anywhere on disk. Without
# them it looks beside the repo, as it always has - which is why the battery
# could not run from a worktree or a fresh clone: six suites resolved the dump
# relative to the repo's parent and failed for a reason that had nothing to do
# with the code under test.
param([switch]$Quick, [string]$Pristine, [string]$GameDir, [string]$Code, [ValidateSet("asc","desc","shuffle")][string]$Order = "asc", [int]$Seed = 0, [string]$Anyway)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# MEASURE LAST, ENFORCED. Forty minutes of suites measure the tree as it is when they
# start. Run them with work still queued and the result is void the moment the next
# edit lands - which is not a slow battery, it is a discarded one. The mutation sweep
# has refused this since the rule was written down; this one never asked, and was
# started in front of queued work twice in one afternoon by someone who knew better.
#
# The queue is OUTSTANDING.md. The escape is -Anyway "<reason>", which is printed into
# the run rather than swallowed: a decision that is visible is a different thing from
# a rule nobody applied.
$py = Get-Command python -ErrorAction SilentlyContinue
if ($py) {
  $gateArgs = @((Join-Path $root "tools\guard\work_order.py"), "--gate", "the battery")
  if ($Anyway) { $gateArgs += @("--anyway", $Anyway) }
  $gateOut = & $py.Source @gateArgs 2>&1
  $gateOut | ForEach-Object { Write-Host $_ }
  if ($LASTEXITCODE -ne 0) { exit 1 }
} else {
  Write-Host "  (no python on PATH - the work order cannot be asked)"
}

$jdk = $env:CTRMAP_JDK
if (-not $jdk) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -like "jdk-*" | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
$cls = "build\classes"
$libs = "lib\jogl-all.jar;lib\gluegen-rt.jar"

# Refuse to test anything build.ps1 did not build from these sources. A
# harness once measured a hand-compiled tree with a stale catalogue and
# reported confidently about code the battery never runs; the stamp makes
# that unrepresentable for anything that starts here.
. (Join-Path $root "stamp.ps1")
$stampProblem = Test-BuildStamp $root
if ($stampProblem) {
    Write-Host "REFUSING TO RUN: $stampProblem" -ForegroundColor Red
    Write-Host "  powershell -ExecutionPolicy Bypass -File build.ps1   then run the battery again."
    exit 2
}
$pristine = if ($Pristine) { $Pristine } else { Join-Path (Split-Path -Parent $root) "RomFS_original_garcs" }
$a039 = Join-Path $pristine "a\0\3\9"
$a013 = Join-Path $pristine "a\0\1\3"
$a040 = Join-Path $pristine "a\0\4\0"

# A COMPLETE dump, not the partial GARC set: the setup suites validate real
# folder layouts (sound archive, the "a" folder, wrong-pick detection), so they
# need the whole thing. Both suites skip themselves when it is not there.
$gamedir = if ($GameDir) { $GameDir } else { Join-Path (Split-Path -Parent $root) "RomFS\000400000011C400" }

# THE LOCK build.ps1 REFUSES ON, so nobody deletes build\classes out from under this run.
# Written here because this is the thing that is running; removed at the bottom, and left
# behind on a kill, where build.ps1 ages it out after ninety minutes.
$batteryLock = Join-Path $root "build\.battery-running"
New-Item -ItemType Directory -Force -Path (Join-Path $root "build") | Out-Null
Set-Content -Path $batteryLock -Encoding utf8 -Value ("started " + (Get-Date -Format o))

# The decompressed code.bin, for the suites that check the executable patches.
# Two candidates because the repo is checked out in two shapes: beside the dump
# (the author's layout) and one level deeper in a worktree. Registering the
# suites with a path is what stops them printing SKIP from a worktree and being
# counted as green - the hole BatteryHygieneTest exists to catch.
$code = if ($Code) { $Code } else {
    $c1 = Join-Path (Split-Path -Parent $root) "code.bin"
    $c2 = Join-Path (Split-Path -Parent (Split-Path -Parent $root)) "code.bin"
    if (Test-Path $c1) { $c1 } elseif (Test-Path $c2) { $c2 } else { $c1 }
}
if (-not (Test-Path $code)) {
    Write-Host "No decompressed code.bin at $code - the executable-patch suites will skip." -ForegroundColor Yellow
}

# REFUSE A CORPUS THAT IS NOT THERE, rather than sweeping it.
#
# A corpus suite that sweeps nothing prints its count and then ALL PASS, and
# exits 0. Measured on this tree by pointing every registered suite at an empty
# folder: 46 refused, but FOURTEEN went green having examined nought records -
# "GfColl: 0 collision files, verbatim=0, rebuild=0, failures=0" / ALL PASS,
# and the same for the OBJ, geometry, region, resize, ground, LZ11, script-emit
# and Maison-list sweeps. GARC.parse logs a FileNotFoundException and hands
# back an archive of length 0, so every one of them is downstream of one silent
# failure and none of them can tell "the corpus is fine" from "there was no
# corpus". That is the shape that already cost this project twice
# (BchMapModelTest, MaisonClassListTest - see BatteryHygieneTest).
#
# A wholly absent dump is caught today by the other 46 going red. A PARTIAL one
# is not: point the battery at a folder holding some archives and not others
# and it reports ALL SUITES PASS with those fourteen asserting nothing. So the
# three archives the battery hands around are checked here, before anything
# runs, and a missing one is a refusal with the same weight as an unstamped
# build - not a warning above nine hundred lines of output that nobody reads.
$needed = @($a013, $a039, $a040)
$absent = @($needed | Where-Object { -not (Test-Path $_) })
if ($absent.Count -gt 0) {
    Write-Host "REFUSING TO RUN: the pristine dump is missing $($absent.Count) of its $($needed.Count) archives:" -ForegroundColor Red
    foreach ($a in $absent) { Write-Host "    $a" }
    Write-Host "  Every corpus suite would sweep nought records and print ALL PASS."
    Write-Host "  powershell -ExecutionPolicy Bypass -File test.ps1 -Pristine <your dump> -GameDir <your romfs title folder>"
    exit 2
}

# suite name -> {main class, args}; -Quick raises sampling steps
$step = if ($Quick) { "60" } else { "20" }
$suites = @(
    @{ n = "Source seam guard (gamedef)"; c = "ctrmap.tests.SourceSeamTest";        a = @("src", "build\classes") },
    @{ n = "GameProfile (an unmeasured game is refused, not guessed)"; c = "ctrmap.tests.GameProfileTest"; a = @($pristine) },
    @{ n = "GarcSniff (what a stored entry IS)"; c = "ctrmap.tests.GarcSniffTest"; a = @($gamedir) },
    @{ n = "VaultGuards (a pristine copy that can be put back)"; c = "ctrmap.tests.VaultGuardsTest"; a = @() },
    @{ n = "ItemData (776 retail records round-trip)"; c = "ctrmap.tests.ItemDataTest"; a = @($gamedir) },
    @{ n = "RecordSchema (the registry reads what ItemData reads)"; c = "ctrmap.tests.RecordSchemaTest"; a = @($gamedir) },
    @{ n = "ItemEdit (in place, four free slots)"; c = "ctrmap.tests.ItemEditTest"; a = @($gamedir) },
    @{ n = "ItemIconPatch (code.bin, zero slack)"; c = "ctrmap.tests.ItemIconPatchTest"; a = @($code) },
    @{ n = "Battery hygiene (temp paths, corpus args)"; c = "ctrmap.tests.BatteryHygieneTest"; a = @("src") },
    @{ n = "Commit gate (the hook refuses what it says it refuses)"; c = "ctrmap.tests.CommitGuardTest"; a = @(".") },
    @{ n = "Plant ledger (the proofs still match the tree)"; c = "ctrmap.tests.PlantLedgerTest"; a = @(".") },
    @{ n = "ClassFileScanner (bytecode sees what a grep cannot)"; c = "ctrmap.tests.ClassFileScannerTest"; a = @("src", "build\classes") },
    @{ n = "GameFilesSeam (the format layer is handed its game, never fetches it)"; c = "ctrmap.tests.GameFilesSeamTest"; a = @("src", "build\classes") },
    @{ n = "HandedGame (two more format classes handed their game)"; c = "ctrmap.tests.HandedGameTest"; a = @() },
    @{ n = "GlobalState (the public-static ceiling, and Workspace.reset)"; c = "ctrmap.tests.GlobalStateTest"; a = @("src", "build\classes") },
    @{ n = "MainframeShape (menus and toolbars, built headless)"; c = "ctrmap.tests.MainframeShapeTest"; a = @("src") },
    @{ n = "Ui output paths (printed, and shown)"; c = "ctrmap.tests.UiOutputTest";           a = @() },
    @{ n = "Dialog seam (only Ui opens one)"; c = "ctrmap.tests.DialogSeamTest";     a = @("src") },
    @{ n = "Duplicate work (one implementation per capability)"; c = "ctrmap.tests.DuplicateWorkTest"; a = @("src") },
    @{ n = "LittleEndian (the one byte[] codec)"; c = "ctrmap.tests.LittleEndianTest"; a = @() },
    @{ n = "ContainerBytes (the one in-memory mini-pack reader)"; c = "ctrmap.tests.ContainerBytesTest"; a = @() },
    @{ n = "Mutation baseline (guards still measured)"; c = "ctrmap.tests.MutationBaselineTest"; a = @("src") },
    @{ n = "BchMapModel (engine)";        c = "ctrmap.tests.BchMapModelTest";       a = @($a039) },
    @{ n = "OBJ export round-trip";       c = "ctrmap.tests.MapModelObjTest";        a = @($a039) },
    @{ n = "OBJ import";                  c = "ctrmap.tests.MapModelObjImportTest";  a = @($a039) },
    @{ n = "OBJ v2 (UV/normal/template)"; c = "ctrmap.tests.MapModelObjV2Test";      a = @($a039, $step) },
    @{ n = "GeoBoxOps (move/dup/del)";    c = "ctrmap.tests.GeoBoxOpsTest";          a = @($a039, $step) },
    @{ n = "GfColl (collision codec)";    c = "ctrmap.tests.GfCollTest";             a = @($a039) },
    @{ n = "GfColl box ops";              c = "ctrmap.tests.GfCollBoxOpsTest";       a = @($a039, $step) },
    @{ n = "GfColl legacy bridge";        c = "ctrmap.tests.GfCollLegacyBridgeTest"; a = @($a039, $(if ($Quick) { "10" } else { "1" })) },
    @{ n = "Model appender gate";         c = "ctrmap.tests.BchModelAppenderTest";   a = @($pristine, $step) },
    @{ n = "Prefabs";                     c = "ctrmap.tests.MapPrefabTest";          a = @($a039, $step) },
    @{ n = "RegionFactory (blank maps)";  c = "ctrmap.tests.RegionFactoryTest";      a = @($a039, $step) },
    @{ n = "PaintedRegion (tile editor)"; c = "ctrmap.tests.PaintedRegionTest";       a = @($a039) },
    @{ n = "PaintedFloor (slopes, water, seeded ground)"; c = "ctrmap.tests.PaintedFloorTest"; a = @($a039) },
    @{ n = "CompositeBuild (edit-in-place)"; c = "ctrmap.tests.CompositeBuildTest";   a = @($a039) },
    @{ n = "Composite leftovers (nothing left standing)"; c = "ctrmap.tests.CompositeLeftoverTest"; a = @($a039) },
    @{ n = "TerrainImport (any brush anywhere)"; c = "ctrmap.tests.TerrainImportTest"; a = @($a039) },
    @{ n = "TerrainImportNoise (quiet when early, loud when broken)"; c = "ctrmap.tests.TerrainImportNoiseTest"; a = @($a039) },
    @{ n = "BuildingCatalog (palette)";   c = "ctrmap.tests.BuildingCatalogTest";     a = @($a039) },
    @{ n = "HarvesterGuards (naming rule, footing)"; c = "ctrmap.tests.HarvesterGuardsTest"; a = @($pristine) },
    @{ n = "HarvesterRanking (what a cut is called)"; c = "ctrmap.tests.HarvesterRankingTest"; a = @() },
    @{ n = "PlacementGuards (what a placed building did)"; c = "ctrmap.tests.PlacementGuardsTest"; a = @($a039, $step) },
    @{ n = "PaintApplyGuards (Apply writes nothing it cannot finish)"; c = "ctrmap.tests.PaintApplyGuardsTest"; a = @($pristine, "src") },
    @{ n = "PaintFormGuards (the painter's document)"; c = "ctrmap.tests.PaintFormGuardsTest"; a = @($pristine) },
    @{ n = "InteriorWirer (round trip)";  c = "ctrmap.tests.InteriorWirerTest";       a = @($a013) },
    @{ n = "AreaEnv (the fog channels, over every area)";       c = "ctrmap.tests.AreaEnvTest";             a = @((Join-Path $pristine "a\0\1\4")) },
    @{ n = "AnimSplice (water scroll)";   c = "ctrmap.tests.AnimSpliceTest";          a = @((Join-Path $pristine "a\0\1\4")) },
    @{ n = "TexturePackImport (carry/clash)"; c = "ctrmap.tests.TexturePackImportTest"; a = @((Join-Path $pristine "a\0\1\4"), (Join-Path $pristine "a\0\2\3")) },
    @{ n = "TextureCarryGuards (a carry that says it wrote, wrote)"; c = "ctrmap.tests.TextureCarryGuardsTest"; a = @($pristine) },
    @{ n = "TextureCodec (every format, one texel at a time)"; c = "ctrmap.tests.TextureCodecTest"; a = @((Join-Path $pristine "a\0\1\4")) },
    @{ n = "MapResizer";                  c = "ctrmap.tests.MapResizerTest";         a = @($a040) },
    @{ n = "MatrixFork (zone-switch layer)"; c = "ctrmap.tests.MatrixForkTest";      a = @($a013, $a040) },
    @{ n = "AreaShareGuard (self-conflict)"; c = "ctrmap.tests.AreaShareGuardTest";  a = @($a013) },
    @{ n = "GroundResolve (no cliffs as floor)"; c = "ctrmap.tests.GroundResolveTest"; a = @($a039) },
    @{ n = "UvScale (imported brush scale)"; c = "ctrmap.tests.UvScaleTest";           a = @($a039) },
    @{ n = "PrefabColour (stamp vertex format)"; c = "ctrmap.tests.PrefabColourTest";  a = @($a039) },
    @{ n = "SnapshotIntegrity (pristine copy)"; c = "ctrmap.tests.SnapshotIntegrityTest"; a = @("src") },
    @{ n = "DataSafetyGuards (stale/script/warp/worker)"; c = "ctrmap.tests.DataSafetyGuardsTest"; a = @($a040, $pristine) },
    @{ n = "NpcEntityGuards (altitude/uid/count/script)"; c = "ctrmap.tests.NpcEntityGuardsTest"; a = @($a013) },
    @{ n = "NpcEditFormGuards (the NPC form: Save/Remove/New/overlay/drag)"; c = "ctrmap.tests.NpcEditFormGuardsTest"; a = @($pristine) },
    @{ n = "NpcMoveCodes (the motion dropdowns write back what they showed)"; c = "ctrmap.tests.NpcMoveCodesTest"; a = @($pristine) },
    @{ n = "WarpTransitions (the transition dropdown writes back what it showed)"; c = "ctrmap.tests.WarpTransitionsTest"; a = @($pristine) },
    @{ n = "ZoneEntities round-trip";      c = "ctrmap.tests.ZoneEntitiesRoundTripTest"; a = @() },
    @{ n = "Integrity (cross-archive refs)"; c = "ctrmap.tests.IntegrityTest";         a = @($pristine) },
    @{ n = "Updater (in-place, lossless)"; c = "ctrmap.tests.UpdaterTest";           a = @() },
    @{ n = "DumpCheck (setup validation)"; c = "ctrmap.tests.DumpCheckTest";         a = @($gamedir) },
    @{ n = "SetupWizard (first run)";     c = "ctrmap.tests.SetupWizardTest";        a = @($gamedir) },
    @{ n = "LZ11 codec + ratio";          c = "ctrmap.tests.LZ11Test";               a = @($a039) },
    @{ n = "EncounterTable";              c = "ctrmap.tests.EncounterTableTest";     a = @($a013) },
    @{ n = "TrainerData";                 c = "ctrmap.tests.TrainerDataTest";        a = @($gamedir) },
    @{ n = "GfHash (native names)";       c = "ctrmap.tests.GfHashTest";             a = @() },
    @{ n = "SYSREQ-by-name disasm";       c = "ctrmap.tests.SysreqNameTest";         a = @($a013) },
    @{ n = "PartyParam selectors";        c = "ctrmap.tests.PartyParamTest";         a = @($a013) },
    @{ n = "ScriptAssembler (refuse/report)"; c = "ctrmap.tests.ScriptAssemblerGuardTest"; a = @($a013) },
    @{ n = "GiveBP script emit";          c = "ctrmap.tests.GiveBpScriptTest";       a = @($a013) },
    @{ n = "Gauntlet script emit";        c = "ctrmap.tests.GauntletScriptTest";     a = @($a013) },
    @{ n = "Talker wizard dry-run";       c = "ctrmap.tests.TalkerWizardDryRunTest"; a = @($a013) },
    @{ n = "DispatchTrampoline (a case the engine can return from)"; c = "ctrmap.tests.DispatchTrampolineTest"; a = @($a013) },
    @{ n = "DispatchGuards (the freeze guards refuse)"; c = "ctrmap.tests.DispatchGuardsTest"; a = @($a013) },
    @{ n = "SignWrapperInject (corpus)";  c = "ctrmap.tests.SignWrapperInjectTest";  a = @($a013) },
    @{ n = "Facility clone source";       c = "ctrmap.tests.FacilitySourceTest";     a = @($a013) },
    @{ n = "PokeData (preview data)";      c = "ctrmap.tests.PokeDataTest";           a = @($gamedir) },
    @{ n = "MaisonSet (opponents)";       c = "ctrmap.tests.MaisonSetTest";          a = @($gamedir) },
    @{ n = "MaisonClassList (teams)";     c = "ctrmap.tests.MaisonClassListTest";    a = @($gamedir) },
    @{ n = "MaisonPoolGuard (vanilla-safe)"; c = "ctrmap.tests.MaisonPoolGuardTest"; a = @() },
    @{ n = "DressUpIndex (which slot a part fills)"; c = "ctrmap.tests.DressUpIndexTest"; a = @($gamedir) },
    @{ n = "ZoneAppend";                  c = "ctrmap.tests.ZoneAppendTest";         a = @($a013) },
    @{ n = "WorkflowGuards (do it, pack, do it again)"; c = "ctrmap.tests.WorkflowGuardsTest"; a = @($gamedir) },
    @{ n = "ZoneRemove (GARC shrink)";    c = "ctrmap.tests.ZoneRemoveTest";         a = @($a013) },
    @{ n = "ZoneCloner (fork a whole zone)"; c = "ctrmap.tests.ZoneClonerTest";     a = @($a013) },
    @{ n = "ZoneAppendMulti (several at once)"; c = "ctrmap.tests.ZoneAppendMultiTest"; a = @($a013) },
    @{ n = "ZoneLimitPatch";              c = "ctrmap.tests.ZoneLimitPatchTest";     a = @($code) },
    @{ n = "ShopData (mart inventories)"; c = "ctrmap.tests.ShopDataTest";           a = @($code) },
    @{ n = "ADPropRegistryOrder (prop registry order)"; c = "ctrmap.tests.ADPropRegistryOrderTest"; a = @((Join-Path $pristine "a\0\1\4")) },
    @{ n = "MapModelImport (BCH map import)"; c = "ctrmap.tests.MapModelImportTest"; a = @($a039) },
    @{ n = "MsgWrapperInject (corpus)"; c = "ctrmap.tests.MsgWrapperInjectTest"; a = @($a013) },
    @{ n = "NpcTemplates (templates fit the corpus)"; c = "ctrmap.tests.NpcTemplatesTest"; a = @($a013) },
    @{ n = "PropDatabase (building models)"; c = "ctrmap.tests.PropDatabaseTest"; a = @((Join-Path $pristine "a\0\2\3"), (Join-Path $pristine "a\0\1\4")) },
    @{ n = "ZoneScriptAnalyzer (talker dispatch)"; c = "ctrmap.tests.ZoneScriptAnalyzerTest"; a = @($a013) },
    @{ n = "GFMessageFile hostile input"; c = "ctrmap.tests.GFMessageFileHostileTest"; a = @() },
    @{ n = "GFMessageFile round-trip"; c = "ctrmap.tests.GFMessageFileRoundTripTest"; a = @() }
    @{ n = "PackReport (warnings reach the user)"; c = "ctrmap.tests.PackReportTest"; a = @($pristine) },
    @{ n = "DoorPropGuards (a door names what it will draw)"; c = "ctrmap.tests.DoorPropGuardsTest"; a = @($pristine) },
    @{ n = "ForkGuards (area/geometry forks)"; c = "ctrmap.tests.ForkGuardsTest";     a = @($pristine) },
    @{ n = "PackRollback (a refused pack keeps its table)"; c = "ctrmap.tests.PackRollbackTest"; a = @($pristine) },
    @{ n = "PackScope (a pack writes what was edited)"; c = "ctrmap.tests.PackScopeTest"; a = @($pristine) },
    @{ n = "WorkspaceRepoint (the backup when the game folder changes)"; c = "ctrmap.tests.WorkspaceRepointTest"; a = @($pristine) },
    @{ n = "WorkspaceSession (the open game is an instance, not statics)"; c = "ctrmap.tests.WorkspaceSessionTest"; a = @($gamedir, "src") },
    @{ n = "MainframeReports (what the main window says it did)"; c = "ctrmap.tests.MainframeReportsTest"; a = @($pristine) },
    @{ n = "MapDefaults (which region, and which mesh is the ground)"; c = "ctrmap.tests.MapDefaultsTest"; a = @($pristine) },
    @{ n = "MisplacedRegistry (damage an old fork left)"; c = "ctrmap.tests.MisplacedRegistryTest"; a = @($pristine) },
# Characterization suites, added 2026-09-07 before the decoupling steps that move this code.
# They are not here to say the behaviour below is RIGHT. They are here to say it does not CHANGE
# while a later step moves it, which is why several of them deliberately pin things their authors
# believe are defects (each says so in its javadoc). Measured before they existed: the classes
# they cover ran between 0% and 16% of their branches during a full battery, so a refactor could
# have altered any of it without one suite noticing. Every one was proven by breaking the class it
# covers and watching it fail.
    @{ n = "ZoneManager (clear, rename, repoint - by bytes)"; c = "ctrmap.tests.ZoneManagerTest"; a = @($pristine) },
    @{ n = "ZoneRepurposeScanner (which zones are reusable)"; c = "ctrmap.tests.ZoneRepurposeScannerTest"; a = @($pristine) },
    @{ n = "ModDeployer (only edited archives ship)"; c = "ctrmap.tests.ModDeployerTest"; a = @($pristine) },
    @{ n = "Helpers (the five groups Utils was split into)"; c = "ctrmap.tests.UtilsTest"; a = @() },
    @{ n = "PropEditForm (what the prop form writes)"; c = "ctrmap.tests.PropEditFormGuardsTest"; a = @($pristine) },
    @{ n = "MatrixEditForm (what the matrix form writes)"; c = "ctrmap.tests.MatrixEditFormGuardsTest"; a = @($pristine) },
    @{ n = "TriggerEditForm (what the trigger form writes)"; c = "ctrmap.tests.TriggerEditFormGuardsTest"; a = @($pristine) },
    @{ n = "GeoEditForm (what the geometry form writes)"; c = "ctrmap.tests.GeoEditFormGuardsTest"; a = @($pristine) },
    @{ n = "EditTools (the ten tools, headless)"; c = "ctrmap.tests.EditToolGuardsTest"; a = @($pristine) },
    # No dump, no game, no display: EditorBench hands the tile inspector Workspace's default
    # tileset and a bench map of one region, which is everything its constructor asks for. The
    # reason this suite did not exist - "its constructor needs a tileset and a map view" - expired
    # when that bench was written.
    @{ n = "TileEditForm (the palette, and a cursor left over from another map)"; c = "ctrmap.tests.TileEditFormGuardsTest"; a = @() },
    @{ n = "TilemapInputRouter (mouse to tool)"; c = "ctrmap.tests.TilemapInputRouterTest"; a = @() },
    @{ n = "MainframeActions (what each action does and refuses)"; c = "ctrmap.tests.MainframeActionGuardsTest"; a = @($pristine) },
    @{ n = "TileMapPanelState (lookups, save, refresh)"; c = "ctrmap.tests.TileMapPanelStateTest"; a = @($pristine) },
    @{ n = "ZoneLoadingState (loaded-zone transitions)"; c = "ctrmap.tests.ZoneLoadingStateTest"; a = @($pristine) },
    @{ n = "LoadedZone (one owner, handed to every reader)"; c = "ctrmap.tests.LoadedZoneTest"; a = @($pristine, "build\classes") },
    @{ n = "MainframeEdges (what still reaches into the window)"; c = "ctrmap.tests.MainframeEdgesTest"; a = @("build\classes") },
    @{ n = "OpenEditors (one flush, one order, one refusal)"; c = "ctrmap.tests.OpenEditorsTest"; a = @() }
)

# -Order re-runs the same suites in a different sequence. Every suite is its
# own java.exe, so no static field can carry state from one to the next; the
# only channels left are the filesystem and java.util.prefs, and those are
# what a reversed or shuffled run actually tests. If the battery is green in
# one order and red in another, a suite is depending on something an earlier
# suite left lying about, and the order is hiding it.
if ($Order -eq "desc") {
    [array]::Reverse($suites)
} elseif ($Order -eq "shuffle") {
    if ($Seed -eq 0) { $Seed = Get-Random -Minimum 1 -Maximum 999999 }
    Write-Host ("Suite order shuffled with seed " + $Seed + " (re-run with -Seed " + $Seed + ")") -ForegroundColor Yellow
    $rng = New-Object System.Random($Seed)
    $suites = @($suites | Sort-Object { $rng.Next() })
}
if ($Order -ne "asc") { Write-Host ("Suite order: " + $Order) -ForegroundColor Yellow }

$failed = @()
$sw = [System.Diagnostics.Stopwatch]::StartNew()
# A suite that writes to stderr is not a suite that failed. Under
# PowerShell 5.1, "2>&1" on a native exe wraps every stderr line in an
# ErrorRecord, which $ErrorActionPreference = "Stop" then treats as
# terminating - so one warning aborts the whole run at that suite and every
# later one silently goes unrun. The exit code below is the actual verdict.
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
foreach ($s in $suites) {
    Write-Host ("--- " + $s.n) -ForegroundColor Cyan
    & "$jdk\bin\java.exe" -Xmx4g -cp "$cls;$libs" $s.c @($s.a) 2>&1 | Select-Object -Last 2 | ForEach-Object { Write-Host ("    " + $_) }
    if ($LASTEXITCODE -ne 0) { $failed += $s.n }
}

# The mutation harness's own guard, and it is Python because the harness is.
# `python tools/mutate2.py --selftest` re-runs, against synthetic input in
# seconds, the four defects the 2026-09-03 sweep shipped with: survivors counted
# once per branch instead of once, a ratchet that cried wolf off those
# duplicates, an output flood misreported as a hang, and a missing operator that
# left 27 lines unmeasured. A four-hour sweep is not a place to discover any of
# them. Skipped rather than failed where python is absent - the battery must
# still run on a machine that has only the JDK.
$hname = "Mutation harness selftest"
Write-Host ("--- " + $hname) -ForegroundColor Cyan
$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) {
    Write-Host "    skip: no python on PATH - run 'python tools/mutate2.py --selftest' by hand"
} else {
    & $py.Source (Join-Path $root "tools\mutate2.py") --selftest |
        Select-Object -Last 2 | ForEach-Object { Write-Host ("    " + $_) }
    if ($LASTEXITCODE -ne 0) { $failed += $hname }
}
$ErrorActionPreference = $prevEAP
$sw.Stop()
Write-Host ""
# THE LOCK GOES BEFORE THE VERDICT, not after it. It was removed on the last line, which
# a failing run never reaches - so a red battery left build.ps1 refusing to build for
# ninety minutes, which is to say the guard blocked the fix for the thing it reported.
Remove-Item -Force -ErrorAction SilentlyContinue $batteryLock
if ($failed.Count -eq 0) {
    Write-Host ("ALL SUITES PASS  (" + [int]$sw.Elapsed.TotalSeconds + "s)") -ForegroundColor Green
} else {
    Write-Host ("FAILED: " + ($failed -join ", ")) -ForegroundColor Red
    exit 1
}
