# CTRMap build script (replaces the NetBeans/Ant build for CLI use)
# Requires a JDK (17+ works, targets Java 8 bytecode) and the JOGL jars in lib/.
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
. (Join-Path $root "stamp.ps1")

$jdk = $env:CTRMAP_JDK
if (-not $jdk) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -like "jdk-*" | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk) { throw "No JDK found. Set CTRMAP_JDK to a JDK install path." }

# Start from an empty output directory. javac only ever ADDS to -d, so a class
# whose source has been deleted survives every later build: this tree carried 76
# such orphans out of 906 class files, 68 of them CtrmapMainframe$N anonymous
# listeners a structure sweep had removed, and three ($26, $30, $42) still read
# Workspace.valid and .persist_paths - fields the source no longer declares.
# Write-BuildStamp below then signed those ghosts as "exactly what build.ps1
# produced from these sources", and any guard that walks the directory counted
# them. Deleting the tree is the only way the stamp can mean this compile alone.
# build\ itself stays: sources.txt is written into it a few lines down.
# BatteryHygieneTest.noOrphanClassFiles fails if this clean stops happening.
if (Test-Path build\classes) { Remove-Item -Recurse -Force build\classes }
New-Item -ItemType Directory -Force build\classes | Out-Null

# javac @argfile entries must be quoted because the repo path may contain spaces
Get-ChildItem -Recurse src -Filter *.java |
    ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
    Out-File -Encoding ascii build\sources.txt

# javac writes warnings (e.g. "Note: Some input files use unchecked...") to
# stderr even on success, and with ErrorActionPreference=Stop PowerShell turns
# that into a terminating error - which used to abort the build here, AFTER
# compiling but BEFORE copying resources, silently leaving stale .tsv tables in
# build\classes. Judge success by the exit code, not by stderr.
$ErrorActionPreference = "Continue"
& "$jdk\bin\javac.exe" --release 8 -encoding UTF-8 `
    -cp "lib\jogl-all.jar;lib\gluegen-rt.jar" `
    -d build\classes "@build\sources.txt"
$javacExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($javacExit -ne 0) { throw "javac failed with exit code $javacExit" }

Copy-Item -Recurse -Force src\ctrmap\resources build\classes\ctrmap\

# Sign what was just built. test.ps1, the mutation sweeps and BatteryHygieneTest
# all recompute this and refuse a build\classes that is not exactly this
# script's output from exactly these sources - see stamp.ps1 for why.
Write-BuildStamp $root

Write-Host "Build OK -> build\classes  (run with run.bat or:"
Write-Host "  java -Xmx1024m -cp `"build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar;lib/jogl-all-natives-windows-amd64.jar;lib/gluegen-rt-natives-windows-amd64.jar`" ctrmap.CtrmapMainframe )"
