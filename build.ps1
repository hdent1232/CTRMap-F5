# CTRMap build script (replaces the NetBeans/Ant build for CLI use)
# Requires a JDK (17+ works, targets Java 8 bytecode) and the JOGL jars in lib/.
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
. (Join-Path $root "stamp.ps1")
. (Join-Path $root "native.ps1")

# ON THE UNATTENDED DESKTOP (2026-10-11): the build's structure gates are Java programs, and whatever
# opened a window or took the keyboard did it on the owner's screen while they worked - twice that day
# a window of theirs was lost. The build runs itself again on a second desktop of the same session
# (tools/guard/hidden_desktop.py), saying here what it says there.
if ($env:CTRMAP_ON_UNATTENDED_DESKTOP -ne "1" -and $env:OS -eq "Windows_NT") {
    $hpy = Get-Command python -ErrorAction SilentlyContinue
    if (-not $hpy) {
        Write-Host "REFUSING: no python on PATH to build on the unattended desktop - its windows would open on yours" -ForegroundColor Red
        exit 1
    }
    $self = $MyInvocation.MyCommand.Path
    Invoke-Native { & $hpy.Source -B (Join-Path $root "tools\guard\hidden_desktop.py") --stream -- powershell -NoProfile -ExecutionPolicy Bypass -File $self }
    exit $LASTEXITCODE
}

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
# The next line deletes build\classes, and test.ps1 runs the whole battery out of it over about
# forty minutes. A build started in the middle of that does not slow the battery down, it
# invents failures: sixteen suites came back "Could not find or load main class", which
# reads exactly like a regression in the code under test and is not one. That has now
# happened twice in one afternoon, both times while waiting for a battery and "just"
# rebuilding something else. A rule that has to be remembered mid-wait is not a rule.
#
# The lock is the running battery's own file. Whether a battery still holds it is asked of
# tools/guard/battery_lock.py, the one reader test.ps1 and replant.py ask too. This judged it by
# age alone until 2026-10-11: a battery past ninety minutes would have had its classes deleted
# under it, and a killed one blocked every build for ninety. A lock nothing holds is ignored, and
# said; one that cannot be judged is refused like a live one.
# AND THE EDITOR (2026-10-11): run from this checkout it loads its classes from build\classes as it
# needs them, so deleting them takes them from an open window. It declares itself in
# build\.editor-running (ctrmap.RunningFrom), and --classes asks about both.
$lock = Join-Path $root "build\.battery-running"
$editorLock = Join-Path $root "build\.editor-running"
if (((Test-Path $lock) -or (Test-Path $editorLock)) -and -not $env:CTRMAP_BUILD_ANYWAY) {
  $askPy = Get-Command python -ErrorAction SilentlyContinue
  $heldOut = @("no python on PATH to ask whether what wrote the lock still runs")
  $heldExit = 2
  if ($askPy) {
    $heldOut = @(Invoke-Native { & $askPy.Source -B (Join-Path $root "tools\guard\battery_lock.py") --classes 2>&1 })
    $heldExit = $LASTEXITCODE
  }
  if ($heldExit -ne 0) {
    Write-Host "REFUSING to build: a battery or the editor may be running out of build\classes." -ForegroundColor Red
    $heldOut | ForEach-Object { Write-Host ("  " + $_) }
    Write-Host '  Deleting the classes under it turns a battery''s suites into failures that are this'
    Write-Host '  build''s fault, and takes them from an open editor window.'
    Write-Host '  Close it or wait for it: a lock nothing live holds is ignored.'
    exit 1
  }
  Write-Host ("(ignoring a lock nothing live holds: " + ($heldOut -join " ") + ")")
}

if (Test-Path build\classes) { Remove-Item -Recurse -Force build\classes }
New-Item -ItemType Directory -Force build\classes | Out-Null

# javac @argfile entries must be quoted because the repo path may contain spaces
Get-ChildItem -Recurse src -Filter *.java |
    ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
    Out-File -Encoding ascii build\sources.txt

# javac writes warnings (e.g. "Note: Some input files use unchecked...") to
# stderr even on success - judged by its exit code, through Invoke-Native (native.ps1).
Invoke-Native {
    & "$jdk\bin\javac.exe" --release 8 -encoding UTF-8 `
        -cp "lib\jogl-all.jar;lib\gluegen-rt.jar" `
        -d build\classes "@build\sources.txt"
}
$javacExit = $LASTEXITCODE
if ($javacExit -ne 0) { throw "javac failed with exit code $javacExit" }

Copy-Item -Recurse -Force src\ctrmap\resources build\classes\ctrmap\

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
    $out = Invoke-Native { & "$jdk\bin\java.exe" -cp "build\classes;lib\jogl-all.jar;lib\gluegen-rt.jar" $g.c src 2>&1 }
    if ($LASTEXITCODE -ne 0) {
      # A REFUSAL IS A FAILED CHECK. On 2026-10-14 DialogSeamTest exited non-zero under eight sweep JVMs having
      # failed nothing, and the old filter (FAIL|REFUS, case-blind) showed only an ok line that said "refused":
      # the crash was thrown away, and a mutation sweep reads BUILD REFUSED as a kill by the gate's suite.
      $failed = @($out | ForEach-Object { "$_" } | Where-Object { $_ -cmatch "FAIL" })
      if ($failed.Count -eq 0) {
        Write-Host ("BUILD FAILED - " + $g.n + ": its gate stopped (exit " + $LASTEXITCODE + ") without failing a check, so it judged nothing. Its last lines:") -ForegroundColor Red
        @($out | ForEach-Object { "$_" }) | Select-Object -Last 25 | ForEach-Object { Write-Host ("  " + $_) }
        if (Test-Path build\classes) { Remove-Item -Recurse -Force build\classes }
        exit 1
      }
      Write-Host ("BUILD REFUSED - " + $g.n) -ForegroundColor Red
      $failed | ForEach-Object { Write-Host ("  " + $_) }
      Write-Host "  These are structural rules, checked here because checking them in the"
      Write-Host "  battery let three of them be broken and shipped first."
      Write-Host "  build\classes has been removed: a tree that breaks one of these does not"
      Write-Host "  produce something you can run."
      if (Test-Path build\classes) { Remove-Item -Recurse -Force build\classes }
      exit 1
    }
  }
}

# SIGNED LAST, once the gates have judged it. Signed before them, a build the gates never finished
# judging - one that died between the two, as a gate's stderr warning made it do on 2026-10-10 -
# kept a valid stamp, and test.ps1 ran the battery out of it. Unsigned, test.ps1 refuses it.
# test.ps1, the mutation sweeps and BatteryHygieneTest all recompute this and refuse a
# build\classes that is not exactly this script's output from exactly these sources - see
# stamp.ps1 for why.
Write-BuildStamp $root

Write-Host "Build OK -> build\classes  (run with run.bat or:"
Write-Host "  java -Xmx1024m -cp `"build/classes;lib/jogl-all.jar;lib/gluegen-rt.jar;lib/jogl-all-natives-windows-amd64.jar;lib/gluegen-rt-natives-windows-amd64.jar`" ctrmap.CtrmapMainframe )"
