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
# REFUSE TO BUILD UNDER A RUNNING BATTERY.
#
# The next line deletes build\classes, and test.ps1 runs 129 suites out of it over about
# forty minutes. A build started in the middle of that does not slow the battery down, it
# invents failures: sixteen suites came back "Could not find or load main class", which
# reads exactly like a regression in the code under test and is not one. That has now
# happened twice in one afternoon, both times while waiting for a battery and "just"
# rebuilding something else. A rule that has to be remembered mid-wait is not a rule.
#
# The lock is the running battery own file. A battery that was killed leaves a stale one,
# so this only refuses while it is fresh - ninety minutes, comfortably longer than a full
# run - and says what it is when it ignores an old one.
$lock = Join-Path $root "build\.battery-running"
if ((Test-Path $lock) -and -not $env:CTRMAP_BUILD_ANYWAY) {
  $age = (Get-Date) - (Get-Item $lock).LastWriteTime
  if ($age.TotalMinutes -lt 90) {
    $mins = [int]$age.TotalMinutes
    Write-Host ("REFUSING to build: a battery has been running out of build\classes for " + $mins + " minute(s).") -ForegroundColor Red
    Write-Host '  Deleting the classes under it turns its suites into failures that are'
    Write-Host '  this build fault rather than the tree - it has happened twice.'
    Write-Host ("  Wait for it, stop it, or if it is long dead:  Remove-Item " + $lock)
    exit 1
  }
  Write-Host ("(ignoring a stale battery lock, " + [int]$age.TotalMinutes + " minutes old)")
}

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

# THE STRUCTURAL RULES RUN HERE, not only in the battery.
#
# They are source-only and take seconds: which classes may open a top-level window,
# which may implement a capability another class already owns, and whether a class
# written since the rule says what it is NOT. Every one of them was a rule this
# project had in words for weeks and enforced nowhere until something shipped wrong.
#
# Putting them in test.ps1 was not enough and that is the point: the battery runs for
# forty minutes, so a rule checked there lets the wrong thing be written, run, shown
# to the owner and shipped first. The point of action for writing code is the build.
# A tree that breaks one of these does not produce a usable build at all - the classes
# are deleted again, deliberately, so nothing can be run out of them.
$gates = @(
  @{ n = "Structure: one implementation per capability"; c = "ctrmap.tests.DuplicateWorkTest" },
  @{ n = "Structure: windows, dialogs and the seam";     c = "ctrmap.tests.DialogSeamTest" }
)
if (-not $env:CTRMAP_SKIP_STRUCTURE_GATE) {
  foreach ($g in $gates) {
    $out = & java -cp "build\classes;lib\jogl-all.jar;lib\gluegen-rt.jar" $g.c src 2>&1
    if ($LASTEXITCODE -ne 0) {
      Write-Host ("BUILD REFUSED - " + $g.n) -ForegroundColor Red
      $out | Where-Object { $_ -match "FAIL|REFUS" } | ForEach-Object { Write-Host ("  " + $_) }
      Write-Host "  These are structural rules, checked here because checking them in the"
      Write-Host "  battery let three of them be broken and shipped first."
      Write-Host "  build\classes has been removed: a tree that breaks one of these does not"
      Write-Host "  produce something you can run."
      if (Test-Path build\classes) { Remove-Item -Recurse -Force build\classes }
      exit 1
    }
  }
}

Write-Host "Build OK -> build\classes  (run with run.bat or:"
Write-Host "  java -Xmx1024m -cp `"build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar;lib/jogl-all-natives-windows-amd64.jar;lib/gluegen-rt-natives-windows-amd64.jar`" ctrmap.CtrmapMainframe )"
