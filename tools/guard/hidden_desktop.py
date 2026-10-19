#!/usr/bin/env python3
"""UNATTENDED SUITES OPEN THEIR WINDOWS WHERE NOBODY IS WORKING.

PAID FOR 2026-10-11: plant drives and test runs started the editor's real suites, and each opened the
editor's own windows - the zone loading dialog above all - in the middle of the owner's screen while
they worked in another program. One took the keyboard at the wrong moment, a window the owner was
working in was closed, and every edit in it was lost.

Windows can make a second desktop in the same session. A program started on it draws every window
there - real windows, real painting, the same Swing - and so does every program IT starts, because a
child is created on its parent's desktop. Nothing on it is ever shown on the owner's screen or given
their keyboard. `run` starts one command there and waits for it, its output captured and its whole
tree ended on the deadline (a job object, as bounded.run does).

    python -B tools/guard/hidden_desktop.py --log FILE -- <command...>   run it there, output to FILE

Off Windows there is no owner's desktop to keep clear here: `run` is bounded.run.
"""
import ctypes
import io
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bounded  # noqa: E402  - a deadline ends the child's whole tree

#: The desktop unattended suites draw on - in the owner's own window station, so windows are real.
DESKTOP = "ctrmap-unattended"
LF = chr(10)

_GENERIC_ALL = 0x10000000
_CREATE_SUSPENDED = 0x00000004
_CREATE_UNICODE_ENVIRONMENT = 0x00000400
_CREATE_NO_WINDOW = 0x08000000
_CREATE_BREAKAWAY_FROM_JOB = 0x01000000
_STARTF_USESTDHANDLES = 0x00000100
_HANDLE_FLAG_INHERIT = 0x00000001
_INFINITE = 0xFFFFFFFF
_WAIT_TIMEOUT = 0x00000102
_KILL_ON_JOB_CLOSE = 0x00002000
_EXTENDED_LIMIT_INFORMATION = 9


class _StartupInfo(ctypes.Structure):
    _fields_ = [("cb", ctypes.c_ulong), ("lpReserved", ctypes.c_wchar_p), ("lpDesktop", ctypes.c_wchar_p),
                ("lpTitle", ctypes.c_wchar_p), ("dwX", ctypes.c_ulong), ("dwY", ctypes.c_ulong),
                ("dwXSize", ctypes.c_ulong), ("dwYSize", ctypes.c_ulong), ("dwXCountChars", ctypes.c_ulong),
                ("dwYCountChars", ctypes.c_ulong), ("dwFillAttribute", ctypes.c_ulong), ("dwFlags", ctypes.c_ulong),
                ("wShowWindow", ctypes.c_ushort), ("cbReserved2", ctypes.c_ushort), ("lpReserved2", ctypes.c_void_p),
                ("hStdInput", ctypes.c_void_p), ("hStdOutput", ctypes.c_void_p), ("hStdError", ctypes.c_void_p)]


class _ProcessInformation(ctypes.Structure):
    _fields_ = [("hProcess", ctypes.c_void_p), ("hThread", ctypes.c_void_p),
                ("dwProcessId", ctypes.c_ulong), ("dwThreadId", ctypes.c_ulong)]


class _IoCounters(ctypes.Structure):
    _fields_ = [(n, ctypes.c_ulonglong) for n in ("r", "w", "o", "rb", "wb", "ob")]


class _BasicLimits(ctypes.Structure):
    _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                ("LimitFlags", ctypes.c_ulong), ("MinimumWorkingSetSize", ctypes.c_size_t),
                ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", ctypes.c_ulong),
                ("Affinity", ctypes.c_size_t), ("PriorityClass", ctypes.c_ulong), ("SchedulingClass", ctypes.c_ulong)]


class _ExtendedLimits(ctypes.Structure):
    _fields_ = [("Basic", _BasicLimits), ("Io", _IoCounters), ("ProcessMemoryLimit", ctypes.c_size_t),
                ("JobMemoryLimit", ctypes.c_size_t), ("PeakProcessMemoryUsed", ctypes.c_size_t),
                ("PeakJobMemoryUsed", ctypes.c_size_t)]


def _api():
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    u32 = ctypes.WinDLL("user32", use_last_error=True)
    u32.CreateDesktopW.restype = ctypes.c_void_p
    u32.CreateDesktopW.argtypes = [ctypes.c_wchar_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ulong,
                                   ctypes.c_ulong, ctypes.c_void_p]
    u32.CloseDesktop.argtypes = [ctypes.c_void_p]
    k32.CreateFileW.restype = ctypes.c_void_p
    k32.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong,
                                ctypes.c_ulong, ctypes.c_void_p]
    k32.CreateProcessW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_void_p, ctypes.c_void_p,
                                   ctypes.c_int, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_wchar_p,
                                   ctypes.POINTER(_StartupInfo), ctypes.POINTER(_ProcessInformation)]
    k32.CreateJobObjectW.restype = ctypes.c_void_p
    k32.CreateJobObjectW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p]
    k32.SetInformationJobObject.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_ulong]
    k32.AssignProcessToJobObject.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    k32.ResumeThread.argtypes = [ctypes.c_void_p]
    k32.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    k32.WaitForSingleObject.restype = ctypes.c_ulong
    k32.GetExitCodeProcess.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
    k32.TerminateJobObject.argtypes = [ctypes.c_void_p, ctypes.c_uint]
    k32.CloseHandle.argtypes = [ctypes.c_void_p]
    k32.SetHandleInformation.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong]
    return k32, u32


def _show_new(log, shown, stream, final=False):
    """Hands `stream` what the log holds past byte `shown`, and answers how far it has now been shown.

    A CHARACTER IS SHOWN WHOLE (2026-10-15): each read was decoded on its own, so a UTF-8 character the writer had
    half written when the log was read came out as two replacement characters. The bytes of a character not yet
    complete wait for the next read - unless this is the `final` one, after the process has ended, which shows all."""
    try:
        with io.open(log, "rb") as said:
            said.seek(shown)
            new = said.read()
    except OSError:
        return shown
    if not final:
        new = new[:len(new) - _incomplete_tail(new)]
    if new:
        stream(new.decode("utf-8", "replace"))
    return shown + len(new)


def _incomplete_tail(data):
    """How many bytes at the end of `data` begin a UTF-8 character that is not complete yet: 0 to 3."""
    for back in range(1, min(4, len(data)) + 1):
        byte = data[-back]
        if byte & 0xC0 != 0x80:   # not a continuation byte: the start of the last character
            needs = 2 if byte & 0xE0 == 0xC0 else 3 if byte & 0xF0 == 0xE0 else 4 if byte & 0xF8 == 0xF0 else 1
            return back if needs > back else 0
    return 0


def _environment_block(env):
    if env is None:
        return None
    return ctypes.create_unicode_buffer("".join("%s=%s\0" % (k, v) for k, v in sorted(env.items())) + "\0")


def run(argv, cwd=None, env=None, timeout=None, stream=None):
    """(exit code, or None when the deadline ended it; everything it said) for `argv` run on the unattended
    desktop, handing what it says to `stream` as it says it when one is given. Raises OSError when the
    desktop or the process cannot be made - it is never run on the owner's."""
    if os.name != "nt":
        try:
            done = bounded.run(argv, cwd=cwd, env=env, capture_output=True, text=True, errors="replace",
                               timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except subprocess.TimeoutExpired as late:
            out = late.stdout or ""
            return None, out.decode("utf-8", "replace") if isinstance(out, bytes) else out
        return done.returncode, (done.stdout or "") + (done.stderr or "")
    k32, u32 = _api()
    desk = u32.CreateDesktopW(DESKTOP, None, None, 0, _GENERIC_ALL, None)
    if not desk:
        raise OSError("the unattended desktop could not be made (error %d)" % ctypes.get_last_error())
    fd, log = tempfile.mkstemp(prefix="hidden_desktop_", suffix=".out")
    os.close(fd)
    handle = job = None
    pi = _ProcessInformation()
    try:
        #GENERIC_WRITE, shared for reading and writing, CREATE_ALWAYS, normal
        handle = k32.CreateFileW(log, 0x40000000, 0x3, None, 2, 0x80, None)
        if not handle or handle == ctypes.c_void_p(-1).value:
            raise OSError("its output file could not be opened (error %d)" % ctypes.get_last_error())
        k32.SetHandleInformation(handle, _HANDLE_FLAG_INHERIT, _HANDLE_FLAG_INHERIT)
        si = _StartupInfo()
        si.cb = ctypes.sizeof(_StartupInfo)
        si.lpDesktop = "WinSta0" + chr(92) + DESKTOP
        si.dwFlags = _STARTF_USESTDHANDLES
        si.hStdOutput = si.hStdError = handle
        flags = _CREATE_SUSPENDED | _CREATE_UNICODE_ENVIRONMENT | _CREATE_NO_WINDOW
        #EVERYTHING STARTED HERE KNOWS IT IS HERE (2026-10-12). Two callers marked the child and replant did not, so
        #a suite it ran started build.ps1 and package.ps1 in a scratch tree, each moved itself here AGAIN through a
        #hidden_desktop.py the scratch tree has not got, and RuleRefusalsTest failed only outside the battery
        block = _environment_block(dict(os.environ if env is None else env, CTRMAP_ON_UNATTENDED_DESKTOP="1"))
        made = False
        for extra in (_CREATE_BREAKAWAY_FROM_JOB, 0):
            if k32.CreateProcessW(None, subprocess.list2cmdline(argv), None, None, True, flags | extra,
                                  ctypes.cast(block, ctypes.c_void_p) if block is not None else None, cwd,
                                  ctypes.byref(si), ctypes.byref(pi)):
                made = True
                break
        if not made:
            raise OSError("%s could not be started on the unattended desktop (error %d)"
                          % (argv[0], ctypes.get_last_error()))
        job = k32.CreateJobObjectW(None, None)
        limits = _ExtendedLimits()
        limits.Basic.LimitFlags = _KILL_ON_JOB_CLOSE
        k32.SetInformationJobObject(job, _EXTENDED_LIMIT_INFORMATION, ctypes.byref(limits), ctypes.sizeof(limits))
        k32.AssignProcessToJobObject(job, pi.hProcess)
        k32.ResumeThread(pi.hThread)
        import time
        deadline = None if timeout is None else time.time() + timeout
        shown = 0
        code = None
        while True:
            left = _INFINITE if stream is None and deadline is None else 500
            if stream is None and deadline is not None:
                left = max(0, int((deadline - time.time()) * 1000))
            ended = k32.WaitForSingleObject(pi.hProcess, left) != _WAIT_TIMEOUT
            if stream is not None:
                shown = _show_new(log, shown, stream, final=ended)
            if ended:
                status = ctypes.c_ulong()
                k32.GetExitCodeProcess(pi.hProcess, ctypes.byref(status))
                code = status.value
                break
            if deadline is not None and time.time() >= deadline:
                k32.TerminateJobObject(job, 1)
                k32.WaitForSingleObject(pi.hProcess, 30000)
                break
    finally:
        for h in (pi.hThread, pi.hProcess, handle, job):
            if h:
                k32.CloseHandle(h)
        u32.CloseDesktop(desk)
    with io.open(log, "rb") as said:
        out = said.read().decode("utf-8", "replace")
    os.remove(log)
    return code, out


def _write(text):
    """Shows `text` on this process's own output. WHAT THAT OUTPUT CANNOT HOLD IS REPLACED, NOT FATAL (2026-10-15):
    the release battery, its output sent to a file in the console's code page, died at its 156th suite when one printed
    a character cp1252 has no byte for - a UnicodeEncodeError out of here, the battery's exit code 1 with no suite
    failed. A relay that dies on a character it was handed is a battery that stops for nothing."""
    encoding = getattr(sys.stdout, "encoding", None) or "utf-8"
    try:
        sys.stdout.write(text)
    except UnicodeEncodeError:
        sys.stdout.write(text.encode(encoding, "backslashreplace").decode(encoding, "replace"))
    sys.stdout.flush()


def on_the_unattended_desktop(script, argv):
    """None when this process already runs on the unattended desktop, or there is no owner's desktop to keep
    clear; otherwise runs `script` with `argv` again THERE - everything it starts drawing there too - showing
    what it says as it says it, and answers its exit code for the caller to end with."""
    if os.name != "nt" or os.environ.get("CTRMAP_ON_UNATTENDED_DESKTOP") == "1":
        return None
    env = dict(os.environ, CTRMAP_ON_UNATTENDED_DESKTOP="1")
    code, _said = run([sys.executable, "-B", os.path.abspath(script)] + list(argv), cwd=os.getcwd(), env=env,
                      stream=_write)
    return 3 if code is None else code


def main(argv):
    #a word this does not read stops the run, before anything runs (cli_words, 2026-10-10)
    import _cli
    head = argv[1:argv.index("--")] if "--" in argv else argv[1:]
    unread_words = _cli.refused(head, flags=("--log", "--stream"), valued=("--log",), most=0)
    if unread_words or "--" not in argv or ("--log" not in head and "--stream" not in head):
        print(unread_words or "usage: hidden_desktop.py (--log FILE | --stream) -- <command...>")
        return 2

    env = dict(os.environ, CTRMAP_ON_UNATTENDED_DESKTOP="1")
    code, said = run(argv[argv.index("--") + 1:], cwd=os.getcwd(), env=env,
                     stream=_write if "--stream" in head else None)
    if "--log" in head:
        with io.open(head[head.index("--log") + 1], "w", encoding="utf-8", newline=LF) as out:
            out.write(said)
    return 3 if code is None else code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
