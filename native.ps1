# THE ONE WAY THIS PROJECT'S SCRIPTS RUN A NATIVE COMMAND. Dot-sourced by build.ps1, test.ps1,
# package.ps1 and stamp.ps1.
#
# Under Windows PowerShell 5.1 a native command's stderr, once redirected (2>&1, or the caller's
# *>), becomes an ErrorRecord, and $ErrorActionPreference = "Stop" turns that into a TERMINATING
# error: a warning on stderr ends the script, whatever the command exited with. Paid for six times,
# each fixed where it bit and nowhere else - javac's "Note:" aborted build.ps1 after compiling and
# before copying resources; git's stderr in a tree with no .git ended the stamp; a suite's warning
# ended test.ps1 and left every later suite unrun; jar and jpackage in package.ps1; and on
# 2026-10-10 a structure gate's Swing warning ("row index is bigger than sorter's row count") ended
# build.ps1 between the stamp and the gates' verdict, exit 1 with no refusal named and a stamped
# build\classes left behind that the gates had never finished judging.
#
# Invoke-Native runs the block with the preference at Continue and puts it back, so the command's
# EXIT CODE ($LASTEXITCODE, read by the caller) is the verdict and its stderr is only output.
# tools/guard/native_stderr.py refuses a .ps1 that runs a native command any other way, asked at
# the write.
#
# A COMMAND THAT NEVER RAN IS NOT ONE THAT PASSED. $LASTEXITCODE is the LAST native's code, so a
# block that ran none - its command behind a condition that was false - would answer with whatever
# ran before it, a 0 from javac as readily as anything. It is set to -1 first, so only a command
# that actually ran can answer 0. (A program that cannot be found is no such case: measured, it
# throws CommandNotFoundException even under Continue, and the script stops naming it.)
#
# The preference is set in THIS function's scope, so the caller's is untouched and nothing needs
# putting back - measured: a restore in a finally here was a line whose removal no test could see.
# The block runs in a scope below this function's, and PowerShell scopes are dynamic: a name the
# block reads that this function also defines would read this function's. Hence the unlikely name.
function Invoke-Native([scriptblock]$NativeBlock) {
    $global:LASTEXITCODE = -1
    $ErrorActionPreference = "Continue"
    & $NativeBlock
}
