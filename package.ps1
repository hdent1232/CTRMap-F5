# Builds a releasable CTRMap-F5: a runnable jar, the zip users download, and the
# SHA-256 the in-app updater verifies before it installs anything.
#
# Usage:  powershell -ExecutionPolicy Bypass -File package.ps1 [-Version 1.1.0 | -Version 1.0.3-nightly.1]
#
# TWO CHANNELS (2026-10-12): a core version, 1.1.0, is a release every copy's updater is offered; a nightly,
# named after the last core release it is ahead of (1.0.3-nightly.1), goes out as a GitHub PRE-release that no
# updater is offered, for whoever downloads it by hand. tools/release_channel.py decides which, and prints the
# commands that publish it.
#
# Without -Version it packages whatever src\ctrmap\resources\version.properties
# says. With -Version it rewrites that file first, so the number in the jar, the
# zip name and the release tag can never disagree - a mismatch there is exactly
# what makes an updater offer an update that installs the same build again.
#
# To publish: THIS SCRIPT PRINTS THE COMMANDS WHEN IT FINISHES. They are not
# repeated here, and that is deliberate. This comment used to carry its own copy
# and the two drifted: the copy here named ONE zip, under a name the packager
# stopped producing two releases ago - and `CTRMap-F5-1.0.3.zip` ends with
# neither of the suffixes Updater.Flavour selects on, so a release cut by
# following it would have offered nothing to ANY user, of either build. The
# printed version is generated from the same variables that name the files, so
# it cannot describe files this run did not make.
#
# GitHub publishes its own SHA-256 for every asset and the updater checks it, so
# the .sha256 file beside the zip is only for people verifying by hand.

param([string]$Version = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
. (Join-Path $root "native.ps1")   #every native command runs through Invoke-Native: its exit code is the verdict

# ON THE UNATTENDED DESKTOP (2026-10-12): packaging runs the JDK's jar and jpackage, and builds before them.
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
        Write-Host "REFUSING: no python on PATH to package a release on the unattended desktop - its windows would open on yours" -ForegroundColor Red
        exit 1
    }
    Invoke-Native { & $hpy.Source -B (Join-Path $root "tools\guard\hidden_desktop.py") --stream -- powershell @again }
    exit $LASTEXITCODE
}

$verFile = "src\ctrmap\resources\version.properties"
if ($Version -ne "") {
    if ($Version -notmatch '^\d+\.\d+\.\d+(-nightly\.\d+)?$') { throw "Version must look like 1.2.3 (core) or 1.2.3-nightly.4 (nightly), got '$Version'" }
    # WRITTEN WITH LF, AS .gitattributes GIVES EVERY TEXT FILE (2026-10-12): Set-Content wrote CRLF, so the first
    # nightly's packaging left the tracked version.properties checked out against it, and lf_checkout said so.
    $verLines = (Get-Content $verFile) -replace '^version=.*', "version=$Version"
    [System.IO.File]::WriteAllText((Join-Path $root $verFile), (($verLines -join "`n") + "`n"), [System.Text.Encoding]::ASCII)
}
$Version = ((Get-Content $verFile) | Where-Object { $_ -match '^version=' }) -replace '^version=', ''
$Version = $Version.Trim()
if ($Version -eq "") { throw "No version in $verFile" }
Write-Host "Packaging CTRMap-F5 $Version"

& "$root\build.ps1"
# A REFUSED BUILD IS NOT PACKAGED. build.ps1 refuses with `exit 1` (a structure gate, a running
# battery), which ends that script and not this one, and nothing here read it.
if ($LASTEXITCODE -ne 0) { throw "build.ps1 refused (exit $LASTEXITCODE) - nothing was packaged" }

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

# THE SUITES DO NOT SHIP. Jarring all of build\classes put the whole battery into every
# release - roughly 40% of the jar - and they are not only dead weight: they are runnable
# out of the shipped artifact, against the user's own game folder, by anyone who reads the
# class list. A release is the program, not the program and its proofs.
$shipDir = Join-Path $env:TEMP ("ctrmap-ship-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $shipDir | Out-Null
Copy-Item -Recurse -Force (Join-Path $root "build\classes\*") $shipDir
Remove-Item -Recurse -Force (Join-Path $shipDir "ctrmap\tests") -ErrorAction SilentlyContinue
# NOR DO THE HARVESTERS (F133). ctrmap\tools is the developer's: four harvesters, each with a main,
# each reading a dump and writing tables, and nothing in the program calls one. The line above was
# one name for what is a class - "runs, and is not the program" - so BatteryHygieneTest now holds
# every class with a main to it: the manifest's Main-Class, a class a shipped launcher runs, or a
# package stripped here.
Remove-Item -Recurse -Force (Join-Path $shipDir "ctrmap\tools") -ErrorAction SilentlyContinue
# A SUITE IS A TOP-LEVEL CLASS. This matched "*Test.class" and nothing else, so it also
# caught H3DMaterial$AlphaTest - a nested enum about alpha testing in the graphics sense,
# which has been production code since before this check existed. The check was written and
# never run against a real package, so the first release after it would have been refused
# for a class that belongs in the jar. A nested class carries a $ in its file name and
# cannot be a suite entry point; a suite is a top-level class with a main method.
$leftOver = @(Get-ChildItem -Recurse -Filter "*Test.class" $shipDir -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "*`$*" })
if ($leftOver.Count -gt 0) {
    throw ("suite classes are still staged: " + ($leftOver | ForEach-Object { $_.Name }) -join ", ")
}

Invoke-Native { & "$jdk\bin\jar.exe" --create --file "$stage\CTRMap-F5.jar" --manifest $mf -C $shipDir . }
$jarExit = $LASTEXITCODE
if ($jarExit -ne 0) { throw "jar failed with exit code $jarExit" }
Remove-Item -Recurse -Force $shipDir -ErrorAction SilentlyContinue

Copy-Item -Recurse lib "$stage\lib"
Copy-Item run.bat, run.sh, README.md, LICENSE, QUICKSTART.md "$stage\"

# A RELEASE IS DRIVEN THROUGH ITS OWN WINDOW BEFORE IT SHIPS (2026-10-24). 1.0.4 passed 219 suites and could not be
# used - a zone tool covered the zone list with no visible way back, a refusal dialog came up on every launch, every
# fill of the zone list froze the window 13.8 s - and the owner found all three by downloading it. tools\window_check.py
# starts the staged program twice on copies of the owner's workspace and game, loads a zone, opens a zone tool and comes
# back. Nothing is zipped unless it holds, and the pass it records is what the commit changing the version is asked for.
$wpy = Get-Command python -ErrorAction SilentlyContinue
if (-not $wpy) { throw "no python on PATH to drive the release through its own window - nothing was zipped" }
Invoke-Native { & $wpy.Source -B (Join-Path $root "tools\window_check.py") $stage }
if ($LASTEXITCODE -ne 0) { throw "tools\window_check.py refused this release (exit $LASTEXITCODE) - nothing was zipped" }

# THE ZIPS ARE WRITTEN BY THE JDK'S jar (2026-10-23), whose names always use "/". Compress-Archive in Windows
# PowerShell 5.1 wrote every one with a backslash ("lib\jogl-all.jar", measured on both 1.0.4 zips), and off Windows
# the updater unpacks that as one file beside an untouched lib\. tools\release_channel.py refuses such a zip, from
# whatever made it, before it says how to publish.
$zip = "dist\CTRMap-F5-$Version-portable.zip"
Invoke-Native { & "$jdk\bin\jar.exe" --create --file $zip --no-manifest -C $stage . }
if ($LASTEXITCODE -ne 0) { throw "jar failed to write $zip (exit $LASTEXITCODE)" }
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
# jpackage takes NUMBERS ONLY for --app-version on Windows; a nightly's suffix stays in the jar's own
# version.properties and in the zip's name, where the updater and the user read it.
$appVersion = $Version -replace '-.*$', ''
Invoke-Native {
    & "$jdk\bin\jpackage.exe" --type app-image `
        --name CTRMap-F5 --app-version $appVersion --vendor "CTRMap-F5" `
        --description "Pokemon X/Y and Omega Ruby/Alpha Sapphire world editor" `
        --input $appIn --main-jar CTRMap-F5.jar --main-class ctrmap.CtrmapMainframe `
        --icon ctrmap.ico --java-options "-Xmx1024m" `
        --add-modules java.base,java.desktop,java.logging,java.prefs,jdk.crypto.ec,jdk.unsupported `
        --dest dist\app
}
$jpExit = $LASTEXITCODE
if ($jpExit -ne 0) { throw "jpackage failed with exit code $jpExit" }

Copy-Item README.md, LICENSE, QUICKSTART.md "dist\app\CTRMap-F5\"
Remove-Item -Recurse -Force $appIn

# archive the FOLDER, not its contents, so extracting cannot spray 150 files
# into whatever directory the user happened to be in
$winZip = "dist\CTRMap-F5-$Version-windows-x64.zip"
Invoke-Native { & "$jdk\bin\jar.exe" --create --file $winZip --no-manifest -C "dist\app" CTRMap-F5 }
if ($LASTEXITCODE -ne 0) { throw "jar failed to write $winZip (exit $LASTEXITCODE)" }
$winHash = (Get-FileHash $winZip -Algorithm SHA256).Hash.ToLower()
"$winHash  $(Split-Path -Leaf $winZip)" | Set-Content "$winZip.sha256" -Encoding ascii

Write-Host ""
Write-Host "  $winZip   (double-click, no Java needed)"
Write-Host "  sha256 $winHash"
Write-Host "  $zip   (needs Java; this is what the in-app updater installs)"
Write-Host "  sha256 $hash"
Write-Host ""
Write-Host "Publish with:"
# ONE PLACE SAYS HOW A VERSION IS PUBLISHED: tools/release_channel.py, which names the tag in the push (a
# `git push --follow-tags` left the 1.0.3 tag off the remote - every tag here is lightweight), puts both
# builds on the release, and sends a nightly to the nightly branch as a PRE-release no updater is offered.
$rpy = Get-Command python -ErrorAction SilentlyContinue
if (-not $rpy) { throw "no python on PATH to say how $Version is published" }
Invoke-Native { & $rpy.Source -B (Join-Path $root "tools\release_channel.py") $Version $winZip $zip }
if ($LASTEXITCODE -ne 0) { throw "tools\release_channel.py refused $Version (exit $LASTEXITCODE)" }
