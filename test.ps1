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
param([switch]$Quick, [string]$Pristine, [string]$GameDir)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

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

if (-not (Test-Path $pristine)) {
    Write-Host "No dump at $pristine - pass -Pristine <path>." -ForegroundColor Yellow
}

# suite name -> {main class, args}; -Quick raises sampling steps
$step = if ($Quick) { "60" } else { "20" }
$suites = @(
    @{ n = "Source seam guard (gamedef)"; c = "ctrmap.tests.SourceSeamTest";        a = @("src") },
    @{ n = "Battery hygiene (temp paths, corpus args)"; c = "ctrmap.tests.BatteryHygieneTest"; a = @("src") },
    @{ n = "Ui output paths (printed, and shown)"; c = "ctrmap.tests.UiOutputTest";           a = @() },
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
    @{ n = "PlacementGuards (what a placed building did)"; c = "ctrmap.tests.PlacementGuardsTest"; a = @($a039, $step) },
    @{ n = "PaintApplyGuards (Apply writes nothing it cannot finish)"; c = "ctrmap.tests.PaintApplyGuardsTest"; a = @($pristine, "src") },
    @{ n = "PaintFormGuards (the painter's document)"; c = "ctrmap.tests.PaintFormGuardsTest"; a = @($pristine) },
    @{ n = "InteriorWirer (round trip)";  c = "ctrmap.tests.InteriorWirerTest";       a = @($a013) },
    @{ n = "AreaEnv (fog/ambient)";       c = "ctrmap.tests.AreaEnvTest";             a = @((Join-Path $pristine "a\0\1\4")) },
    @{ n = "AnimSplice (water scroll)";   c = "ctrmap.tests.AnimSpliceTest";          a = @((Join-Path $pristine "a\0\1\4")) },
    @{ n = "TexturePackImport (carry/clash)"; c = "ctrmap.tests.TexturePackImportTest"; a = @((Join-Path $pristine "a\0\1\4"), (Join-Path $pristine "a\0\2\3")) },
    @{ n = "TextureCarryGuards (a carry that says it wrote, wrote)"; c = "ctrmap.tests.TextureCarryGuardsTest"; a = @($pristine) },
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
    @{ n = "ScriptAssembler (refuse/report)"; c = "ctrmap.tests.ScriptAssemblerGuardTest"; a = @($a013) },
    @{ n = "GiveBP script emit";          c = "ctrmap.tests.GiveBpScriptTest";       a = @($a013) },
    @{ n = "Gauntlet script emit";        c = "ctrmap.tests.GauntletScriptTest";     a = @($a013) },
    @{ n = "Talker wizard dry-run";       c = "ctrmap.tests.TalkerWizardDryRunTest"; a = @($a013) },
    @{ n = "DispatchTrampoline (a case the engine can return from)"; c = "ctrmap.tests.DispatchTrampolineTest"; a = @($a013) },
    @{ n = "SignWrapperInject (corpus)";  c = "ctrmap.tests.SignWrapperInjectTest";  a = @($a013) },
    @{ n = "Facility clone source";       c = "ctrmap.tests.FacilitySourceTest";     a = @($a013) },
    @{ n = "PokeData (preview data)";      c = "ctrmap.tests.PokeDataTest";           a = @($gamedir) },
    @{ n = "MaisonSet (opponents)";       c = "ctrmap.tests.MaisonSetTest";          a = @($gamedir) },
    @{ n = "MaisonClassList (teams)";     c = "ctrmap.tests.MaisonClassListTest";    a = @($gamedir) },
    @{ n = "MaisonPoolGuard (vanilla-safe)"; c = "ctrmap.tests.MaisonPoolGuardTest"; a = @() },
    @{ n = "ZoneAppend";                  c = "ctrmap.tests.ZoneAppendTest";         a = @($a013) },
    @{ n = "ZoneRemove (GARC shrink)";    c = "ctrmap.tests.ZoneRemoveTest";         a = @($a013) },
    @{ n = "ZoneCloner (fork a whole zone)"; c = "ctrmap.tests.ZoneClonerTest";     a = @($a013) },
    @{ n = "ZoneAppendMulti (several at once)"; c = "ctrmap.tests.ZoneAppendMultiTest"; a = @($a013) },
    @{ n = "ZoneLimitPatch";              c = "ctrmap.tests.ZoneLimitPatchTest";     a = @() },
    @{ n = "ShopData (mart inventories)"; c = "ctrmap.tests.ShopDataTest";           a = @() },
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
    @{ n = "MainframeReports (what the main window says it did)"; c = "ctrmap.tests.MainframeReportsTest"; a = @($pristine) },
    @{ n = "MisplacedRegistry (damage an old fork left)"; c = "ctrmap.tests.MisplacedRegistryTest"; a = @($pristine) }
)

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
if ($failed.Count -eq 0) {
    Write-Host ("ALL SUITES PASS  (" + [int]$sw.Elapsed.TotalSeconds + "s)") -ForegroundColor Green
} else {
    Write-Host ("FAILED: " + ($failed -join ", ")) -ForegroundColor Red
    exit 1
}
