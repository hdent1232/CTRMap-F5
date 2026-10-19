# Builds the in-game test world (F138) into a folder of its own: romfs\ holds only the archives the world
# changed, at their RomFS paths, and BUILT.txt says which commit and tree built it and whether every archive
# was cut from the retail one. Nothing is written anywhere else - not the game folder, not the workspace.
#
# Usage:  powershell -ExecutionPolicy Bypass -File testworld.ps1 -Out <folder> [-Image <decrypted .cci>] [-NotVanilla]
#
# Sources, in order: RomFS_original_garcs beside this repository, the decrypted cartridge image when given
# (it holds the story text, trainers and scripts the pristine copy lacks), then the live game folder. Each
# archive is taken from the first that holds the RETAIL one, by its SHA-256; when none does the build refuses,
# naming it. -NotVanilla builds anyway and the stamp says NOT CANONICAL, naming what was not retail.
#
# Run build.ps1 first: the world names the commit build.ps1 stamped, and refuses classes it did not sign.

param([Parameter(Mandatory = $true)][string]$Out, [string]$Image = "", [switch]$NotVanilla)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
. (Join-Path $root "native.ps1")   #every native command runs through Invoke-Native: its exit code is the verdict

# ON THE UNATTENDED DESKTOP (2026-10-12): the world is built by the editor's own Java, which loads zones - the dialogs that came up mid-screen.
# It runs itself again on a second desktop of the same session (tools/guard/hidden_desktop.py), as build.ps1
# and test.ps1 do, its parameters handed through; what it prints comes back here as it prints it.
if ($env:CTRMAP_ON_UNATTENDED_DESKTOP -ne "1" -and $env:OS -eq "Windows_NT") {
    $again = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $MyInvocation.MyCommand.Path)
    foreach ($k in $PSBoundParameters.Keys) {
        $v = $PSBoundParameters[$k]
        if ($v -is [System.Management.Automation.SwitchParameter]) {
            if ($v.IsPresent) { $again += "-$k" }
        } else {
            $again += @("-$k", "$v")
        }
    }
    $hpy = Get-Command python -ErrorAction SilentlyContinue
    if (-not $hpy) {
        Write-Host "REFUSING: no python on PATH to build the test world on the unattended desktop - its windows would open on yours" -ForegroundColor Red
        exit 1
    }
    Invoke-Native { & $hpy.Source -B (Join-Path $root "tools\guard\hidden_desktop.py") --stream -- powershell @again }
    exit $LASTEXITCODE
}

$jdk = $env:CTRMAP_JDK
if (-not $jdk) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -like "jdk-*" | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk) {
    Write-Host "REFUSING: no JDK - set CTRMAP_JDK or install one under C:\Program Files\Eclipse Adoptium" -ForegroundColor Red
    exit 2
}
. (Join-Path $root "stamp.ps1")
$stampProblem = Test-BuildStamp $root
if ($stampProblem) {
    Write-Host "REFUSING: $stampProblem" -ForegroundColor Red
    Write-Host "  powershell -ExecutionPolicy Bypass -File build.ps1   then build the world again."
    exit 2
}

$sources = @((Join-Path (Split-Path -Parent $root) "RomFS_original_garcs"))
if ($Image) {
    $sources += $Image
}
$sources += (Join-Path (Split-Path -Parent $root) "RomFS\000400000011C400")
$flags = @()
if ($NotVanilla) {
    $flags += "--not-vanilla"
}

Invoke-Native { & "$jdk\bin\java.exe" -Xmx4g -cp "build\classes;lib\jogl-all.jar;lib\gluegen-rt.jar" ctrmap.tests.TestWorldBuilder $Out @sources @flags }
exit $LASTEXITCODE
