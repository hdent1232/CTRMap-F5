# The one definition of "this build came from these sources", shared by
# build.ps1 (which writes it) and test.ps1 (which refuses to run without it).
#
# WHY. A verification harness once compiled this tree with bare javac, which
# copies no resources, and measured a build/classes whose catalogue was stale.
# A guard suite failed there and passed everywhere else, and the harness
# reported confidently about a tree the battery never runs. "Build it the same
# way" as advice lasts until the next harness; a stamp every consumer checks
# does not.
#
# The stamp records two digests. Recomputing them tells a consumer whether
# build/classes is exactly what build.ps1 produced from exactly these sources:
#   src      over every file under src\        - edited a source since building?
#   classes  over every file under build\classes, the stamp itself excluded
#            - hand-compiled, resource missing, class swapped in?
#
# Algorithm, identical in stamp.ps1 / wt/_state/require_build.py /
# BatteryHygieneTest.builtByTheBattery - change one, change all three:
#   files sorted by forward-slash relative path, ordinal order;
#   one line per file: "<relpath>:<lowercase hex sha256 of content>\n";
#   digest = lowercase hex sha256 of the UTF-8 manifest.

$script:StampName = ".built-by-build-ps1"

function Get-TreeDigest([string]$Root, [string]$Exclude) {
    # Kept deliberately plain: one array of "relpath:hex" lines, one ordinal
    # sort, one join. A first version sorted newline-terminated entries by
    # culture, joined, split and re-sorted, and produced a digest that matched
    # neither Python's nor Java's on a byte-identical manifest. This body is
    # the pipeline that was checked against both, line for line.
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $rootFull = (Resolve-Path $Root).Path
    $lines = @(Get-ChildItem -Path $rootFull -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($rootFull.Length).TrimStart('\', '/') -replace '\\', '/'
        if ($rel -ne $Exclude) {
            $hex = ([System.BitConverter]::ToString($sha.ComputeHash([System.IO.File]::ReadAllBytes($_.FullName))) -replace '-', '').ToLowerInvariant()
            $rel + ":" + $hex
        }
    })
    [System.Array]::Sort($lines, [System.StringComparer]::Ordinal)
    $manifest = ($lines -join "`n") + "`n"
    $digest = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($manifest))
    return ([System.BitConverter]::ToString($digest) -replace '-', '').ToLowerInvariant()
}

function Write-BuildStamp([string]$Root) {
    $head = (& git -C $Root rev-parse HEAD 2>$null)
    if (-not $head) { $head = "unknown" }
    $src = Get-TreeDigest (Join-Path $Root "src") ""
    $classes = Get-TreeDigest (Join-Path $Root "build\classes") $script:StampName
    $stamp = "sha=$head`nsrc=$src`nclasses=$classes`n"
    [System.IO.File]::WriteAllText((Join-Path $Root "build\classes\$script:StampName"), $stamp, (New-Object System.Text.UTF8Encoding $false))
}

# Returns $null when build\classes is exactly what build.ps1 made from src\,
# otherwise one sentence saying which check failed.
function Test-BuildStamp([string]$Root) {
    $path = Join-Path $Root "build\classes\$script:StampName"
    if (-not (Test-Path $path)) {
        return "build\classes carries no stamp - it was not produced by build.ps1"
    }
    $kv = @{}
    foreach ($line in (Get-Content $path)) {
        if ($line -match '^(\w+)=(.*)$') { $kv[$matches[1]] = $matches[2] }
    }
    $src = Get-TreeDigest (Join-Path $Root "src") ""
    if ($src -ne $kv["src"]) {
        return "src\ has changed since build.ps1 last ran - rebuild before measuring anything"
    }
    $classes = Get-TreeDigest (Join-Path $Root "build\classes") $script:StampName
    if ($classes -ne $kv["classes"]) {
        return "build\classes is not what build.ps1 produced (a file added, removed or replaced since) - rebuild"
    }
    return $null
}
