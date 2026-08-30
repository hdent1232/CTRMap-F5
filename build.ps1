# CTRMap build script (replaces the NetBeans/Ant build for CLI use)
# Requires a JDK (17+ works, targets Java 8 bytecode) and the JOGL jars in lib/.
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$jdk = $env:CTRMAP_JDK
if (-not $jdk) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -like "jdk-*" | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk) { throw "No JDK found. Set CTRMAP_JDK to a JDK install path." }

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

Write-Host "Build OK -> build\classes  (run with run.bat or:"
Write-Host "  java -Xmx1024m -cp `"build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar;lib/jogl-all-natives-windows-amd64.jar;lib/gluegen-rt-natives-windows-amd64.jar`" ctrmap.CtrmapMainframe )"
