# -*- coding: utf-8 -*-
"""A DEADLINE ENDS THE CHILD'S WHOLE TREE, NOT ONLY THE CHILD.

`subprocess.run(timeout=)` kills the one process it started and nothing that process started. PAID FOR
2026-10-09 on the project this bundle was installed into first: a test handed `bash -c` a 120-second
deadline; bash died on it, and the python launcher bash had started and the hook that launcher ran were
still alive seven hours later, the hook blocked reading a stdin nobody would ever close. That project then
counted 98 such calls in 37 files - every one bounded, as tools/timeouts.py asks, and none of them ending
what its child had started.

`run` is `subprocess.run` with one difference: on the deadline the child's whole tree ends before
`TimeoutExpired` is raised, as `subprocess.run` raises it. Everything else - arguments, capture, input,
check, the CompletedProcess - is `subprocess.run`'s, so moving a call here is changing its name.

HOW THE TREE IS FOUND. On POSIX the child leads a session of its own, and the session's process group is
every process it started that did not leave it. On Windows the child is put in a job object of its own as
soon as it exists - every process it starts afterwards is in the job, even one whose parent has already
died, which is the case `taskkill /T` cannot see - and the job is terminated; `taskkill /T` runs as well, for
anything started in the moment before the child was assigned. Ending the tree is never the empty answer:
when it could not be done the exception it raises says so (`tree_not_ended`).

tools/timeouts.py refuses, by shape, a deadline handed to one of subprocess's own waits. A copy of this file
sits in every folder that imports it - the hooks' and the tests' - held to this one byte for byte
(tools/counted_roots.py), so each imports it from its own folder.

    import bounded
    done = bounded.run(["git", "status"], capture_output=True, text=True, timeout=30)
"""
import os
import signal
import subprocess

WINDOWS = os.name == "nt"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

#: How long ending a tree may take before it is reported as not done.
KILL_DEADLINE = 30


class _Job(object):
    """A Windows job object holding one child and all it starts; nothing anywhere else."""

    def __init__(self):
        self.handle = None
        self.why = None
        try:
            import ctypes
            from ctypes import wintypes
            k32 = ctypes.WinDLL("kernel32", use_last_error=True)
            k32.CreateJobObjectW.restype = wintypes.HANDLE
            k32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
            k32.AssignProcessToJobObject.restype = wintypes.BOOL
            k32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
            k32.TerminateJobObject.restype = wintypes.BOOL
            k32.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
            k32.CloseHandle.argtypes = [wintypes.HANDLE]
            self.k32 = k32
            self.handle = k32.CreateJobObjectW(None, None)
            if not self.handle:
                self.why = "CreateJobObject failed (error %d)" % ctypes.get_last_error()
        except (ImportError, OSError, AttributeError) as ex:
            self.why = "no job object: %s" % ex

    def hold(self, process):
        if self.handle and not self.k32.AssignProcessToJobObject(self.handle, int(process._handle)):
            import ctypes
            self.why = "AssignProcessToJobObject failed (error %d)" % ctypes.get_last_error()

    def end(self):
        if self.handle and self.why is None:
            if not self.k32.TerminateJobObject(self.handle, 1):
                import ctypes
                return "TerminateJobObject failed (error %d)" % ctypes.get_last_error()
            return None
        return self.why or "no job object"

    def close(self):
        if self.handle:
            self.k32.CloseHandle(self.handle)
            self.handle = None


def _taskkill(pid):
    """None when `taskkill /T /F` ended the tree under `pid` or found nothing left to end, else why not."""
    try:
        killer = subprocess.Popen(["taskkill", "/T", "/F", "/PID", str(pid)], stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, creationflags=NO_WINDOW)
    except OSError as ex:
        return "taskkill could not be started: %s" % ex
    try:
        said, _ = killer.communicate(timeout=KILL_DEADLINE)
    except subprocess.TimeoutExpired:
        killer.kill()
        return "taskkill did not finish in %d s" % KILL_DEADLINE
    #128: "not found" - the tree was already gone, which is the answer wanted
    return None if killer.returncode in (0, 128) else "taskkill exit %d: %s" % (
        killer.returncode, said.decode("utf-8", "replace").strip())


def end_tree(process, job=None):
    """Ends `process` and everything it started. None when that was done, else what could not be done."""
    problems = []
    if WINDOWS:
        if job is not None:
            why = job.end()
            if why:
                problems.append(why)
        why = _taskkill(process.pid)
        if why and (job is None or job.why is not None):
            problems.append(why)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except OSError as ex:
            problems.append("killpg: %s" % ex)
    try:
        process.kill()
    except OSError:
        pass
    return "; ".join(problems) or None


def run(*popenargs, input=None, capture_output=False, timeout=None, check=False, **kwargs):
    """`subprocess.run`, ending the child's whole tree on the deadline. See the module's doc."""
    if input is not None:
        if kwargs.get("stdin") is not None:
            raise ValueError("stdin and input arguments may not both be used.")
        kwargs["stdin"] = subprocess.PIPE
    if capture_output:
        if kwargs.get("stdout") is not None or kwargs.get("stderr") is not None:
            raise ValueError("stdout and stderr arguments may not be used with capture_output.")
        kwargs["stdout"] = subprocess.PIPE
        kwargs["stderr"] = subprocess.PIPE
    if WINDOWS:
        kwargs["creationflags"] = kwargs.get("creationflags", 0) | subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    job = _Job() if WINDOWS else None
    process, release = None, True
    try:
        process = subprocess.Popen(*popenargs, **kwargs)
        if job is not None:
            job.hold(process)
        try:
            stdout, stderr = process.communicate(input, timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            exc.tree_not_ended = end_tree(process, job)
            #BOUNDED TOO: a tree that did not end still holds the pipes, and an unbounded read of them
            #is the thirteen-hour wait tools/timeouts.py was written for
            try:
                exc.stdout, exc.stderr = process.communicate(timeout=KILL_DEADLINE)
            except subprocess.TimeoutExpired:
                exc.tree_not_ended = (exc.tree_not_ended or "the tree was ended") + (
                    ", and its output did not close within %d s" % KILL_DEADLINE)
                #LEFT OPEN: closing a pipe another thread is still reading blocks, on Windows, until
                #whatever holds it exits - measured at 120 s, an orphan's whole life, inside a call
                #that had already given up. The reader threads are daemons; nothing is kept alive.
                release = False
            raise
        except BaseException:
            release = end_tree(process, job) is None
            raise
        retcode = process.poll()
        if check and retcode:
            raise subprocess.CalledProcessError(retcode, process.args, output=stdout, stderr=stderr)
        return subprocess.CompletedProcess(process.args, retcode, stdout, stderr)
    finally:
        if process is not None and release:
            _release(process)
        if job is not None:
            job.close()


def _release(process):
    """What leaving `with Popen(...)` does - close the pipes, reap the child - with the wait bounded."""
    for stream in (process.stdout, process.stderr, process.stdin):
        if stream is not None:
            try:
                stream.close()
            except (OSError, ValueError):
                pass
    try:
        process.wait(timeout=KILL_DEADLINE)
    except subprocess.TimeoutExpired:
        pass
