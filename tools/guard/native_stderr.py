#!/usr/bin/env python3
"""EVERY NATIVE COMMAND A .ps1 RUNS GOES THROUGH Invoke-Native, SO ITS EXIT CODE IS THE VERDICT.

Under Windows PowerShell 5.1 a native command's redirected stderr is an ErrorRecord, and
$ErrorActionPreference = "Stop" makes it a terminating error: a warning ends the script whatever the
command exited with. PAID FOR SEVEN TIMES in this tree's five scripts, each fixed where it bit and
nowhere else - javac and git in the build, a suite's warning ending the battery with every later
suite unrun, jar and jpackage in the release, and on 2026-10-10 a structure gate's Swing warning
ending build.ps1 between its stamp and the gates' verdict: exit 1, no refusal named, and a stamped
build\\classes left for the battery that the gates had never finished judging. The work-order gate at
the top of test.ps1 was the seventh, still live when it was found.

native.ps1 defines Invoke-Native, the one way to run one. This rule refuses, AT THE WRITE, a .ps1 that
runs a native command anywhere else. It asks PowerShell's own parser, not a pattern: every command in
the file, by the call operator or by name, and a name is native unless it is a cmdlet, an alias, or a
function some .ps1 in this tree defines - a name it cannot place is taken for native, never for safe.
A PowerShell that cannot be asked is a refusal (the rule raises), never a clean file.

    python -B tools/guard/native_stderr.py [root]     every .ps1 under root, walked; exit 1 naming each

`ctrmap.tests.RuleRefusalsTest` holds it to known answers both ways, and to this tree.
"""
import base64
import io
import os
import re
import subprocess
import sys

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
ROOT = _tree_root.require(__file__)

FLAGS = ()
UNKNOWN = 2

AT_WRITE = "findings"
AT_SCOPE = r"\.ps1$"

#: The parse, in PowerShell: the text on stdin, the tree's function names in NATIVE_STDERR_FUNCTIONS.
#: One line per native command outside Invoke-Native, then DONE - a run with no DONE is no answer.
PARSE = r"""
$ErrorActionPreference = 'Stop'
$text = [Console]::In.ReadToEnd()
$known = @(($env:NATIVE_STDERR_FUNCTIONS -split ',') | Where-Object { $_ })
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) {
    'UNPARSED' + "`t" + $errors[0].Extent.StartLineNumber + "`t" + $errors[0].Message
    'DONE'
    return
}
foreach ($f in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)) {
    $known += $f.Name
}
$calls = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.CommandAst] }, $true)
foreach ($c in $calls) {
    $first = $c.CommandElements[0]
    $why = $null
    if ($c.InvocationOperator -eq 'Ampersand') {
        if ($first -is [System.Management.Automation.Language.ScriptBlockExpressionAst]) { continue }
        $literal = $null
        if ($first -is [System.Management.Automation.Language.StringConstantExpressionAst] -or
            $first -is [System.Management.Automation.Language.ExpandableStringExpressionAst]) { $literal = $first.Value }
        if ($literal -and $literal -match '\.ps1$') { continue }
        $why = 'the call operator runs it'
    } elseif ($c.InvocationOperator -eq 'Dot') {
        continue
    } else {
        $name = $c.GetCommandName()
        if (-not $name -or $known -contains $name) { continue }
        $cmd = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd -and $cmd.CommandType -ne 'Application') { continue }
        if ($cmd) { $why = $name + ' is a program on PATH' } else { $why = $name + ' is no cmdlet, alias or function here, so it is taken for a program' }
    }
    $p = $c.Parent
    $inside = $false
    while ($p) {
        if ($p -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $p.Name -eq 'Invoke-Native') { $inside = $true; break }
        if ($p -is [System.Management.Automation.Language.CommandAst] -and $p.GetCommandName() -eq 'Invoke-Native') { $inside = $true; break }
        $p = $p.Parent
    }
    if (-not $inside) {
        'NATIVE' + "`t" + $c.Extent.StartLineNumber + "`t" + (($c.Extent.Text -split "`n")[0].Trim()) + "`t" + $why
    }
}
'DONE'
"""

FINDING = ("runs a native command outside Invoke-Native (native.ps1), so a line on its stderr ends the "
           "script under ErrorActionPreference Stop whatever it exited with")


def scripts(root):
    """Every .ps1 under root, build output and .git left out - walked, never listed."""
    found = []
    for folder, dirs, names in os.walk(root):
        dirs[:] = sorted(d for d in dirs if d not in (".git", "build", "dist", "__pycache__"))
        found += [os.path.join(folder, n) for n in sorted(names) if n.lower().endswith(".ps1")]
    return found


def tree_functions(root):
    """Every function name a .ps1 under root defines - the names a script may call that are not
    programs. Walked, so a function a new script adds is known the moment it is written."""
    names = set()
    for path in scripts(root):
        with io.open(path, encoding="utf-8-sig", errors="replace") as handle:
            names.update(re.findall(r"(?im)^\s*function\s+([\w-]+)", handle.read()))
    return sorted(names)


def native_calls(text, functions):
    """[(line, command, why)] for every native command `text` runs outside Invoke-Native.
    Raises RuntimeError when PowerShell cannot answer - never an empty list for a text it did not read."""
    env = dict(os.environ)
    env["NATIVE_STDERR_FUNCTIONS"] = ",".join(functions)
    encoded = base64.b64encode(PARSE.encode("utf-16-le")).decode("ascii")
    try:
        done = bounded.run(["powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                           input=(text or "").encode("utf-8"), capture_output=True, timeout=120,
                           env=env, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError) as exc:
        raise RuntimeError("PowerShell could not be asked (%s), so which native commands run unguarded is UNKNOWN" % exc)
    lines = done.stdout.decode("utf-8", "replace").splitlines()
    if done.returncode != 0 or not lines or lines[-1].strip() != "DONE":
        raise RuntimeError("PowerShell's parse gave no answer (exit %d): %s" % (
            done.returncode, (done.stderr.decode("utf-8", "replace").strip() or "\n".join(lines[-3:]))[:300]))
    out = []
    for line in lines[:-1]:
        parts = line.split("\t")
        if parts[0] == "UNPARSED":
            raise RuntimeError("PowerShell cannot parse it (line %s: %s)" % (parts[1], parts[2] if len(parts) > 2 else ""))
        if parts[0] == "NATIVE" and len(parts) >= 4:
            out.append((int(parts[1]), parts[2], parts[3]))
        elif line.strip():
            raise RuntimeError("PowerShell's parse said a line this cannot read: %r" % line[:200])
    return out


def said(rel, calls):
    return ["%s:%d `%s` %s (%s)" % (rel, line, command[:80], FINDING, why) for line, command, why in calls]


def findings(rel, text):
    """The write rule: one finding per native command `text` runs outside Invoke-Native."""
    return said(rel, native_calls(text, tree_functions(ROOT)))


def main(argv):
    #a word this does not read refuses the run, before anything runs (cli_words, 2026-10-10)
    import _cli
    unread_words = _cli.refused(argv[1:], flags=FLAGS, most=1)
    if unread_words:
        print(unread_words)
        return 2
    words = argv[1:]
    root = os.path.abspath(words[0] if words else ROOT)
    found = scripts(root)
    if not found:
        print("REFUSING: no .ps1 under %s - a walk of nothing is not a clean tree" % root)
        return UNKNOWN
    functions = tree_functions(root)
    bad = []
    try:
        for path in found:
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            with io.open(path, encoding="utf-8-sig", errors="replace") as handle:
                bad += said(rel, native_calls(handle.read(), functions))
    except RuntimeError as exc:
        print("UNKNOWN: %s" % exc)
        return UNKNOWN
    for line in bad:
        print("REFUSING: " + line)
    if not bad:
        print("every native command in the %d .ps1 file(s) under %s runs through Invoke-Native" % (len(found), root))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    raise SystemExit(main(sys.argv))
