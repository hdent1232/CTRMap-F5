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
# Algorithm, identical in stamp.ps1 / tools/require_build.py /
# BatteryHygieneTest.builtByTheBattery, and its per-file half is mirrored again
# in MutationBaselineTest and tools/mutate2.py - change one, change all five:
#   files sorted by forward-slash relative path, ordinal order;
#   one line per file: "<relpath>:<lowercase hex sha256 of content>\n";
#   digest = lowercase hex sha256 of the UTF-8 manifest.
#
# CONTENT means the file's bytes, EXCEPT that a file whose name ends in one of
# .java .form .properties .tsv .md .txt is hashed with every CR byte (0x0D)
# removed. Line endings are not content here: git normalises them on commit and
# restores them per checkout, so the same commit hashes differently on two
# machines, and a tool that rewrites a file with LF endings changes a digest
# without changing a line. That is not theoretical - seven files a sed pass left
# as LF failed MutationBaselineTest on 2026-09-07 for a reason that had nothing
# to do with the code, and the hour spent reading it as a regression is the
# whole cost this rule removes.
# Binary files are hashed byte for byte, because stripping 0x0D out of a .class
# or a .png would weaken the digest for no gain: the point is to ignore a
# difference that is not a difference, not to ignore bytes.

$script:StampName = ".built-by-build-ps1"

# The extensions whose CRs are not content. Kept as a literal list rather than a
# heuristic, so the same six answers come out of PowerShell, Python and Java.
$script:TextDigestExt = @(".java", ".form", ".properties", ".tsv", ".md", ".txt")

function Get-DigestBytes([string]$Path) {
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($script:TextDigestExt -contains [System.IO.Path]::GetExtension($Path).ToLowerInvariant()) {
        # ISO-8859-1 maps every byte 0x00-0xFF to the same code point and back,
        # so this is a byte edit performed with a native string replace. The
        # obvious per-byte PowerShell loop is correct too and about a thousand
        # times slower; build.ps1 digests the tree twice on every build.
        $enc = [System.Text.Encoding]::GetEncoding(28591)
        return $enc.GetBytes($enc.GetString($bytes).Replace("`r", ""))
    }
    return $bytes
}

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
            $hex = ([System.BitConverter]::ToString($sha.ComputeHash((Get-DigestBytes $_.FullName))) -replace '-', '').ToLowerInvariant()
            $rel + ":" + $hex
        }
    })
    [System.Array]::Sort($lines, [System.StringComparer]::Ordinal)
    $manifest = ($lines -join "`n") + "`n"
    $digest = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($manifest))
    return ([System.BitConverter]::ToString($digest) -replace '-', '').ToLowerInvariant()
}

function Write-BuildStamp([string]$Root) {
    # A COPY IS NOT A REPOSITORY. Anyone who downloads the source as a zip, or
    # exports it with git archive, builds from a directory with no .git - the
    # ordinary path for a non-expert. git then writes to stderr, and by this
    # point build.ps1 has restored $ErrorActionPreference = "Stop", which turns
    # a native command's stderr into a TERMINATING error. build.ps1 died here,
    # AFTER compiling 834 classes and BEFORE stamping, never printed "Build OK",
    # and test.ps1 then said "build\classes carries no stamp" - which points at
    # the stamp, not at the missing repository. build.ps1 already documents this
    # exact PowerShell trap for javac's stderr and works around it there; the
    # same trap then bit the next external command in the same script.
    # "unknown" is a branch this stamp already had; it just could not be reached.
    $head = "unknown"
    $prev = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $found = (& git -C $Root rev-parse HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $found) { $head = ([string]$found).Trim() }
    } catch {
        $head = "unknown"
    } finally {
        $ErrorActionPreference = $prev
    }
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
