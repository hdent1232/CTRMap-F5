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

$zip = "dist\CTRMap-F5-$Version-portable.zip"
Compress-Archive -Path "$stage\*" -DestinationPath $zip -CompressionLevel Optimal
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
"$hash  $(Split-Path -Leaf $zip)" | Set-Content "$zip.sha256" -Encoding ascii

# ---- the one-double-click Windows build ------------------------------------
# For the user who does not have Java and should not have to care. jpackage is
# part of the JDK, so there is nothing extra to install to build this.

# jpackage copies EVERYTHING under --input into the image's app\ folder, so the
# input is staged lean. run.bat must NOT go in: an app-image is started by its
# .exe, run.bat never runs, and the bundled runtime has no java.exe to run its
# staged-update step with anyway.
$appIn = "dist\app-input"
New-Item -ItemType Directory -Force "$appIn\lib" | Out-Null
Copy-Item "$stage\CTRMap-F5.jar" $appIn
Copy-Item "$stage\lib\*" "$appIn\lib"

# --add-modules is load-bearing, not a size optimisation. jdeps reports only
# java.base, java.desktop, java.logging and java.prefs - and an image built from
# exactly that list fails at runtime in two ways that only show up later:
#   jdk.crypto.ec   without it every HTTPS call dies with a TLS handshake_failure,
#                   so the in-app update check silently never works
#   jdk.unsupported without it GlueGen cannot reach sun.misc.Unsafe
# Measured: the default (whole module graph) runtime is 134 MB; this set is 70 MB.
$ErrorActionPreference = "Continue"
& "$jdk\bin\jpackage.exe" --type app-image `
    --name CTRMap-F5 --app-version $Version --vendor "CTRMap-F5" `
    --description "Pokemon X/Y and Omega Ruby/Alpha Sapphire world editor" `
    --input $appIn --main-jar CTRMap-F5.jar --main-class ctrmap.CtrmapMainframe `
    --icon ctrmap.ico --java-options "-Xmx1024m" `
    --add-modules java.base,java.desktop,java.logging,java.prefs,jdk.crypto.ec,jdk.unsupported `
    --dest dist\app
$jpExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($jpExit -ne 0) { throw "jpackage failed with exit code $jpExit" }

Copy-Item README.md, LICENSE, QUICKSTART.md "dist\app\CTRMap-F5\"
Remove-Item -Recurse -Force $appIn

# archive the FOLDER, not its contents, so extracting cannot spray 150 files
# into whatever directory the user happened to be in
$winZip = "dist\CTRMap-F5-$Version-windows-x64.zip"
Compress-Archive -Path "dist\app\CTRMap-F5" -DestinationPath $winZip -CompressionLevel Optimal
$winHash = (Get-FileHash $winZip -Algorithm SHA256).Hash.ToLower()
"$winHash  $(Split-Path -Leaf $winZip)" | Set-Content "$winZip.sha256" -Encoding ascii

Write-Host ""
Write-Host "  $winZip   (double-click, no Java needed)"
Write-Host "  sha256 $winHash"
Write-Host "  $zip   (needs Java; this is what the in-app updater installs)"
Write-Host "  sha256 $hash"
Write-Host ""
Write-Host "Publish with:"
Write-Host "  git tag v$Version ; git push --follow-tags"
Write-Host "  gh release create v$Version $winZip $zip --title `"CTRMap-F5 $Version`" --notes-file NOTES.md"
