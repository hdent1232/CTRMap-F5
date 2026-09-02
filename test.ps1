# CTRMap-F5 regression battery - runs every corpus-validated test suite
# against the pristine RomFS dumps. All suites must print ALL PASS / PASS.
# Usage: powershell -ExecutionPolicy Bypass -File test.ps1 [-Quick]
param([switch]$Quick)

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
$pristine = Join-Path (Split-Path -Parent $root) "RomFS_original_garcs"
$a039 = Join-Path $pristine "a\0\3\9"
$a013 = Join-Path $pristine "a\0\1\3"
$a040 = Join-Path $pristine "a\0\4\0"

# A COMPLETE dump, not the partial GARC set: the setup suites validate real
# folder layouts (sound archive, the "a" folder, wrong-pick detection), so they
# need the whole thing. Both suites skip themselves when it is not there.
$gamedir = Join-Path (Split-Path -Parent $root) "RomFS\000400000011C400"

# suite name -> {main class, args}; -Quick raises sampling steps
$step = if ($Quick) { "60" } else { "20" }
$suites = @(
    @{ n = "Source seam guard (gamedef)"; c = "ctrmap.tests.SourceSeamTest";        a = @("src") },
    @{ n = "BchMapModel (engine)";        c = "ctrmap.tests.BchMapModelTest";       a = @() },
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
    @{ n = "CompositeBuild (edit-in-place)"; c = "ctrmap.tests.CompositeBuildTest";   a = @($a039) },
    @{ n = "Composite leftovers (nothing left standing)"; c = "ctrmap.tests.CompositeLeftoverTest"; a = @($a039) },
    @{ n = "TerrainImport (any brush anywhere)"; c = "ctrmap.tests.TerrainImportTest"; a = @($a039) },
    @{ n = "BuildingCatalog (palette)";   c = "ctrmap.tests.BuildingCatalogTest";     a = @($a039) },
    @{ n = "PlacementGuards (what a placed building did)"; c = "ctrmap.tests.PlacementGuardsTest"; a = @($a039, $step) },
    @{ n = "InteriorWirer (round trip)";  c = "ctrmap.tests.InteriorWirerTest";       a = @($a013) },
    @{ n = "AreaEnv (fog/ambient)";       c = "ctrmap.tests.AreaEnvTest";             a = @((Join-Path $pristine "a\0\1\4")) },
    @{ n = "AnimSplice (water scroll)";   c = "ctrmap.tests.AnimSpliceTest";          a = @((Join-Path $pristine "a\0\1\4")) },
    @{ n = "MapResizer";                  c = "ctrmap.tests.MapResizerTest";         a = @($a040) },
    @{ n = "MatrixFork (zone-switch layer)"; c = "ctrmap.tests.MatrixForkTest";      a = @($a013, $a040) },
    @{ n = "AreaShareGuard (self-conflict)"; c = "ctrmap.tests.AreaShareGuardTest";  a = @($a013) },
    @{ n = "GroundResolve (no cliffs as floor)"; c = "ctrmap.tests.GroundResolveTest"; a = @($a039) },
    @{ n = "UvScale (imported brush scale)"; c = "ctrmap.tests.UvScaleTest";           a = @($a039) },
    @{ n = "PrefabColour (stamp vertex format)"; c = "ctrmap.tests.PrefabColourTest";  a = @($a039) },
    @{ n = "SnapshotIntegrity (pristine copy)"; c = "ctrmap.tests.SnapshotIntegrityTest"; a = @() },
    @{ n = "DataSafetyGuards (stale/script/warp)"; c = "ctrmap.tests.DataSafetyGuardsTest"; a = @($a040) },
    @{ n = "Integrity (cross-archive refs)"; c = "ctrmap.tests.IntegrityTest";         a = @($pristine) },
    @{ n = "Updater (in-place, lossless)"; c = "ctrmap.tests.UpdaterTest";           a = @() },
    @{ n = "DumpCheck (setup validation)"; c = "ctrmap.tests.DumpCheckTest";         a = @($gamedir) },
    @{ n = "SetupWizard (first run)";     c = "ctrmap.tests.SetupWizardTest";        a = @($gamedir) },
    @{ n = "LZ11 codec + ratio";          c = "ctrmap.tests.LZ11Test";               a = @($a039) },
    @{ n = "EncounterTable";              c = "ctrmap.tests.EncounterTableTest";     a = @($a013) },
    @{ n = "TrainerData";                 c = "ctrmap.tests.TrainerDataTest";        a = @() },
    @{ n = "GfHash (native names)";       c = "ctrmap.tests.GfHashTest";             a = @() },
    @{ n = "SYSREQ-by-name disasm";       c = "ctrmap.tests.SysreqNameTest";         a = @($a013) },
    @{ n = "GiveBP script emit";          c = "ctrmap.tests.GiveBpScriptTest";       a = @($a013) },
    @{ n = "Gauntlet script emit";        c = "ctrmap.tests.GauntletScriptTest";     a = @($a013) },
    @{ n = "Talker wizard dry-run";       c = "ctrmap.tests.TalkerWizardDryRunTest"; a = @() },
    @{ n = "SignWrapperInject (corpus)";  c = "ctrmap.tests.SignWrapperInjectTest";  a = @($a013) },
    @{ n = "Facility clone source";       c = "ctrmap.tests.FacilitySourceTest";     a = @($a013) },
    @{ n = "PokeData (preview data)";      c = "ctrmap.tests.PokeDataTest";           a = @() },
    @{ n = "MaisonSet (opponents)";       c = "ctrmap.tests.MaisonSetTest";          a = @() },
    @{ n = "MaisonClassList (teams)";     c = "ctrmap.tests.MaisonClassListTest";    a = @() },
    @{ n = "MaisonPoolGuard (vanilla-safe)"; c = "ctrmap.tests.MaisonPoolGuardTest"; a = @() },
    @{ n = "ZoneAppend";                  c = "ctrmap.tests.ZoneAppendTest";         a = @() },
    @{ n = "ZoneRemove (GARC shrink)";    c = "ctrmap.tests.ZoneRemoveTest";         a = @($a013) },
    @{ n = "ZoneLimitPatch";              c = "ctrmap.tests.ZoneLimitPatchTest";     a = @() },
    @{ n = "ShopData (mart inventories)"; c = "ctrmap.tests.ShopDataTest";           a = @() }
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
$ErrorActionPreference = $prevEAP
$sw.Stop()
Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host ("ALL SUITES PASS  (" + [int]$sw.Elapsed.TotalSeconds + "s)") -ForegroundColor Green
} else {
    Write-Host ("FAILED: " + ($failed -join ", ")) -ForegroundColor Red
    exit 1
}
