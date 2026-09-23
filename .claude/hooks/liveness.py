#!/usr/bin/env python3
"""Is that process alive? One answer, and it never says "gone" when it means "I could not look".

WHY THIS FILE EXISTS RATHER THAN THREE COPIES OF THE ANSWER.

`os.kill(pid, 0)` is not a probe on Windows. Python routes every signal through
`OpenProcess(PROCESS_TERMINATE)`, so a process this account may not terminate raises
`[WinError 87]` and reads as DEAD. Measured against the process table on 2026-09-08 it called
three live processes dead, one of them the sweep runner the guard existed to notice.

The same mistake then arrived in a shell command no python checker could see:
`Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match ... }` matched nothing,
because `CommandLine` is EMPTY for a process this account cannot open. Twelve live workers were
reported gone, and acting on that belief discarded 134 verdicts.

Three guards beside this one need the answer. Three copies is three chances to write the wrong
one - and the wrong one is not loud, it is a guard that never fires. So there is one, and
`tools/guard/liveness_check.py` refuses any other spelling anywhere in the project.

THE CONTRACT: `alive(pid)` returns `(is it running, how this knows)`, and the first value is
True whenever the answer could not be established. UNCERTAINTY IS ALIVE, PRESENT, UNKNOWN -
never absent.
"""
import os


def alive(pid):
    """(is it running, how this knows). Anything unestablished counts as RUNNING."""
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return False, "%r is not a process id" % (pid,)
    if pid <= 0:
        return False, "%d is not a process id" % pid
    if os.name != "nt":
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False, "no process %d" % pid
        except PermissionError:
            #: a process this account may not signal is a process that EXISTS
            return True, "process %d exists but may not be signalled by this account" % pid
        except OSError as cannotAsk:
            return True, ("could not ask about process %d (%s), so it is taken to be running"
                          % (pid, cannotAsk))
        return True, "process %d answers a zero signal" % pid

    import ctypes
    try:
        kernel32 = ctypes.windll.kernel32
    except Exception as noKernel:                        # pragma: no cover
        return True, ("could not reach the Windows API to ask about process %d (%s), so it is "
                      "taken to be running" % (pid, type(noKernel).__name__))
    handle = kernel32.OpenProcess(0x1000, False, pid)    # QUERY_LIMITED_INFORMATION
    if not handle:
        #: 87 is ERROR_INVALID_PARAMETER - Windows' answer for a pid that does not exist.
        #: EVERY OTHER FAILURE IS A PERMISSION ANSWER, and permission denied is not absence.
        code = kernel32.GetLastError()
        if code == 87:
            return False, "Windows has no process %d" % pid
        return True, ("process %d could not be opened (error %d), which is a permission answer "
                      "and not an absence" % (pid, code))
    status = ctypes.c_ulong()
    ok = kernel32.GetExitCodeProcess(handle, ctypes.byref(status))
    kernel32.CloseHandle(handle)
    if not ok:
        return True, ("process %d opened but would not report its exit status, so it is taken "
                      "to be running" % pid)
    if status.value == 259:                              # STILL_ACTIVE
        return True, "process %d is still active" % pid
    return False, "process %d has exited (status %d)" % (pid, status.value)
