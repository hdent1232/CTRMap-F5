# Builds a releasable CTRMap-F5: a runnable jar, the zip users download, and the
# SHA-256 the in-app updater verifies before it installs anything.
#
# Usage:  powershell -ExecutionPolicy Bypass -File package.ps1 [-Version 1.1.0]
#
# Without -Version it packages whatever src\ctrmap\resources\version.properties
# says. With -Version it rewrites that file first, so the number in the jar, the
# zip name and the release tag can never disagree - a mismatch there is exactly
# what makes an updater offer an update that installs the same build again.
#
# To publish (the tag MUST match the version, that is what older copies compare):
#   git commit -am "Release 1.1.0" ; git tag v1.1.0 ; git push --tags
#   gh release create v1.1.0 dist\CTRMap-F5-1.1.0.zip --notes "..."
# GitHub publishes its own SHA-256 for every asset and the updater checks it, so
# the .sha256 file beside the zip is only for people verifying by hand.

param([string]$Version = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$verFile = "src\ctrmap\resources\version.properties"
if ($Version -ne "") {
    if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Version must look like 1.2.3, got '$Version'" }
    (Get-Content $verFile) -replace '^version=.*', "version=$Version" | Set-Content $verFile -Encoding ascii
}
$Version = ((Get-Content $verFile) | Where-Object { $_ -match '^version=' }) -replace '^version=', ''
$Version = $Version.Trim()
if ($Version -eq "") { throw "No version in $verFile" }
Write-Host "Packaging CTRMap-F5 $Version"

& "$root\build.ps1"

$jdk = $env:CTRMAP_JDK
if (-not $jdk) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -like "jdk-*" | Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk) { throw "No JDK found. Set CTRMAP_JDK to a JDK install path." }

$stage = "dist\CTRMap-F5-$Version"
if (Test-Path "dist") { Remove-Item -Recurse -Force "dist" }
New-Item -ItemType Directory -Force $stage | Out-Null

# The jar declares its own Class-Path so a double-clicked jar finds the JOGL
# natives exactly like run.bat does.
$mf = "dist\package.mf"
@"
Manifest-Version: 1.0
Main-Class: ctrmap.CtrmapMainframe
Class-Path: lib/jogl-all.jar lib/gluegen-rt.jar lib/jogl-all-natives-windows-amd64.jar lib/gluegen-rt-natives-windows-amd64.jar
Implementation-Version: $Version

"@ | Set-Content $mf -Encoding ascii

$ErrorActionPreference = "Continue"
& "$jdk\bin\jar.exe" --create --file "$stage\CTRMap-F5.jar" --manifest $mf -C build\classes .
$jarExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($jarExit -ne 0) { throw "jar failed with exit code $jarExit" }

Copy-Item -Recurse lib "$stage\lib"
Copy-Item run.bat, run.sh, README.md, LICENSE, QUICKSTART.md "$stage\"

$zip = "dist\CTRMap-F5-$Version.zip"
Compress-Archive -Path "$stage\*" -DestinationPath $zip -CompressionLevel Optimal
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
"$hash  CTRMap-F5-$Version.zip" | Set-Content "$zip.sha256" -Encoding ascii

Write-Host ""
Write-Host "  $zip"
Write-Host "  sha256 $hash"
Write-Host ""
Write-Host "Publish with:"
Write-Host "  git tag v$Version ; git push --tags"
Write-Host "  gh release create v$Version $zip --title `"CTRMap-F5 $Version`" --notes `"...`""
