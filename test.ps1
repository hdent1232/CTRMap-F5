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

# suite name -> {main class, args}; -Quick raises sampling steps
$step = if ($Quick) { "60" } else { "20" }
$suites = @(
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
    @{ n = "MapResizer";                  c = "ctrmap.tests.MapResizerTest";         a = @($a040) },
    @{ n = "LZ11 codec + ratio";          c = "ctrmap.tests.LZ11Test";               a = @($a039) },
    @{ n = "EncounterTable";              c = "ctrmap.tests.EncounterTableTest";     a = @($a013) },
    @{ n = "TrainerData";                 c = "ctrmap.tests.TrainerDataTest";        a = @() },
    @{ n = "GfHash (native names)";       c = "ctrmap.tests.GfHashTest";             a = @() },
    @{ n = "MaisonSet (opponents)";       c = "ctrmap.tests.MaisonSetTest";          a = @() },
    @{ n = "ZoneAppend";                  c = "ctrmap.tests.ZoneAppendTest";         a = @() },
    @{ n = "ZoneLimitPatch";              c = "ctrmap.tests.ZoneLimitPatchTest";     a = @() }
)

$failed = @()
$sw = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($s in $suites) {
    Write-Host ("--- " + $s.n) -ForegroundColor Cyan
    & "$jdk\bin\java.exe" -Xmx4g -cp "$cls;$libs" $s.c @($s.a) 2>&1 | Select-Object -Last 2 | ForEach-Object { Write-Host ("    " + $_) }
    if ($LASTEXITCODE -ne 0) { $failed += $s.n }
}
$sw.Stop()
Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host ("ALL SUITES PASS  (" + [int]$sw.Elapsed.TotalSeconds + "s)") -ForegroundColor Green
} else {
    Write-Host ("FAILED: " + ($failed -join ", ")) -ForegroundColor Red
    exit 1
}
