# WHAT THE BATTERY SHOWS OF A SUITE THAT FAILED (2026-10-15). It showed the last two lines of every suite, failed or
# not, so a failure kept nothing but its tail: ContainerBytes failed once in a release battery with "... 6 more" and
# the JDK's "Exception while removing reference." as all it said, then passed 8 runs of 8 alone - and the lines that
# said why were gone. The Python suite's failures and the mutation selftest's were shown the same way. A failed
# suite now shows every earlier line that names a failure or an exception (the first 60 of them), then its last 30.
# Dot-sourced by test.ps1; tests/test_battery_shows_a_failure.py runs it.

$SaidOfAFailure = '(?i)FAIL|Exception|Error|Caused by|Traceback'

function Show-Failed([object[]]$Said) {
    $all = @($Said | ForEach-Object { "$_" })
    $tail = 30
    $before = [Math]::Max(0, $all.Count - $tail)
    $named = @($all | Select-Object -First $before | Where-Object { $_ -match $SaidOfAFailure } | Select-Object -First 60)
    foreach ($line in $named) { Write-Host ("    " + $line) }
    if ($named.Count -gt 0) { Write-Host "    ..." }
    foreach ($line in @($all | Select-Object -Last $tail)) { Write-Host ("    " + $line) }
}
