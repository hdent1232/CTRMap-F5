"""AN UNATTENDED SUITE'S WINDOWS ARE NEVER DRAWN ON THE OWNER'S SCREEN.

PAID FOR 2026-10-11: plant drives ran the editor's real suites on the owner's desktop, their zone loading
dialogs opened in the middle of the screen while the owner worked elsewhere, one took the keyboard, and a
window the owner was working in was closed with every edit in it lost.

tools/guard/hidden_desktop.run starts a program on a second desktop of the same session. Measured here
with a real window: a probe opens one and waits. Started the ordinary way (the CONTROL), its window is
among the visible top-level windows of this desktop - the owner's; started through hidden_desktop, it is
not, and the probe still ran to its end, so the window was drawn somewhere rather than never made.
"""
import ast
import ctypes
import io
import os
import re
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded  # noqa: E402  - a deadline ends the child's whole tree

HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import hidden_desktop  # noqa: E402  - where unattended suites draw

LF = chr(10)

#: A real, visible window that stays up long enough to be looked for - and one that can NEVER take the
#: keyboard, wherever it lands: WS_EX_NOACTIVATE, shown with SW_SHOWNOACTIVATE, off every screen. A Tk
#: window was the first probe, and Tk activates what it opens: when a plant puts the probe on the owner's
#: desktop on purpose, it must still not take their foreground.
PROBE = LF.join([
    "import ctypes, os, sys, time",
    "u = ctypes.WinDLL('user32')",
    "u.CreateWindowExW.restype = ctypes.c_void_p",
    "u.CreateWindowExW.argtypes = [ctypes.c_ulong, ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_ulong,"
    " ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p,"
    " ctypes.c_void_p, ctypes.c_void_p]",
    "u.ShowWindow.argtypes = [ctypes.c_void_p, ctypes.c_int]",
    "u.DestroyWindow.argtypes = [ctypes.c_void_p]",
    "NOACTIVATE, TOOL, POPUP, SHOW_NOACTIVATE = 0x08000000, 0x00000080, 0x80000000, 4",
    "w = u.CreateWindowExW(NOACTIVATE | TOOL, 'STATIC', 'ctrmap-hidden-desktop-probe-%d' % os.getpid(), POPUP,"
    " -32000, -32000, 8, 8, None, None, None, None)",
    "u.ShowWindow(w, SHOW_NOACTIVATE)",
    "open(sys.argv[1], 'w').write(str(os.getpid()))",
    "time.sleep(4)",
    "u.DestroyWindow(w)",
    "print('PROBE RAN TO ITS END' if w else 'PROBE MADE NO WINDOW')",
    ""])


#: The access OpenDesktopW is asked for: to read the desktop's objects and enumerate its windows, nothing more.
DESKTOP_READOBJECTS, DESKTOP_ENUMERATE = 0x0001, 0x0040


def owners_desktop_windows():
    """(pid, class, title, visible) of every top-level window on the OWNER's desktop - "Default", asked by name -
    wherever this runs. It asked this thread's desktop, and on the unattended desktop, where test.ps1 runs the
    battery, that is not the owner's: the control found no taskbar there (2026-10-12). A desktop that cannot be
    opened is an error, never an empty answer."""
    user32 = ctypes.WinDLL("user32")
    user32.OpenDesktopW.restype = ctypes.c_void_p
    user32.OpenDesktopW.argtypes = [ctypes.c_wchar_p, ctypes.c_ulong, ctypes.c_bool, ctypes.c_ulong]
    desk = user32.OpenDesktopW("Default", 0, False, DESKTOP_READOBJECTS | DESKTOP_ENUMERATE)
    if not desk:
        raise OSError("the owner's desktop could not be opened to look at (error %d)" % ctypes.GetLastError())
    found = []
    proc = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

    def each(hwnd, _):
        owner = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(ctypes.c_void_p(hwnd), ctypes.byref(owner))
        cls, title = ctypes.create_unicode_buffer(256), ctypes.create_unicode_buffer(256)
        user32.GetClassNameW(ctypes.c_void_p(hwnd), cls, 256)
        user32.GetWindowTextW(ctypes.c_void_p(hwnd), title, 256)
        found.append((owner.value, cls.value, title.value, bool(user32.IsWindowVisible(ctypes.c_void_p(hwnd)))))
        return True
    try:
        user32.EnumDesktopWindows(ctypes.c_void_p(desk), proc(each), None)
    finally:
        user32.CloseDesktop(ctypes.c_void_p(desk))
    return found


def visible_windows_of(pid):
    """Titles of the visible top-level windows of `pid` on the owner's desktop."""
    return [title for owner, _cls, title, visible in owners_desktop_windows() if owner == pid and visible]


def wait_for_pid(path, seconds=30):
    for _ in range(seconds * 10):
        if os.path.isfile(path) and os.path.getsize(path):
            with io.open(path, encoding="utf-8") as handle:
                return int(handle.read())
        time.sleep(0.1)
    return None


#: Every way the hooks, tools and tests start a process, and the one that starts it on the unattended desktop.
STARTERS = {"subprocess.run", "subprocess.Popen", "subprocess.call", "subprocess.check_call", "subprocess.check_output",
            "bounded.run", "os.system", "os.startfile"}
HIDDEN = {"hidden_desktop.run", "hidden_desktop.stream"}


def process_starts(source):
    """[(line, verdict)] of each process start in `source`: 'clear' (creationflags given, directly or by a dict it
    spreads that names them), 'hidden' (through hidden_desktop), 'OPEN', or 'UNKNOWN' (spread from something this
    cannot read - never counted as clear)."""
    import ast
    tree = ast.parse(source)
    flagged_dicts = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            value = node.value
            if (isinstance(value, ast.Call) and ast.unparse(value.func) == "dict"
                    and any(k.arg == "creationflags" for k in value.keywords)):
                flagged_dicts.add(node.targets[0].id)
            elif isinstance(value, ast.Dict) and any(
                    isinstance(k, ast.Constant) and k.value == "creationflags" for k in value.keys):
                flagged_dicts.add(node.targets[0].id)
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        name = ast.unparse(node.func)
        if name in HIDDEN:
            out.append((node.lineno, "hidden"))
        elif name in STARTERS:
            given = {k.arg for k in node.keywords if k.arg}
            spread = [k.value for k in node.keywords if k.arg is None]
            if "creationflags" in given:
                out.append((node.lineno, "clear"))
            elif spread and all(isinstance(s, ast.Name) and s.id in flagged_dicts for s in spread):
                out.append((node.lineno, "clear"))
            elif spread:
                out.append((node.lineno, "UNKNOWN"))
            else:
                out.append((node.lineno, "OPEN"))
    return out


#: WHAT creationflags DOES NOT DO. CREATE_NO_WINDOW keeps a CONSOLE program from opening a console; a program with
#: windows of its own - a JVM showing a Swing dialog - opens them anyway, on whatever desktop it was started on. So
#: a start the walk calls 'clear' opens no window only if the PROGRAM has none, and that is asked of the program.
#: Programs known to have none; one not named here is not assumed to be one - the walk fails on it (fails closed).
CONSOLE_PROGRAMS = {"git", "python", "powershell", "pwsh", "taskkill", "javac", "ps", "lsof", "sh", "bash", "cygpath"}
#: The program this tree starts that DOES open windows: the editor's own Java, whose dialogs were the pop ups.
JVM_PROGRAMS = {"java", "javaw"}
#: Ways a Python file can put a window up without starting a program: a GUI toolkit, or the shell opening a file.
GUI_MODULES = {"tkinter", "Tkinter", "webbrowser", "wx", "PyQt4", "PyQt5", "PyQt6", "PySide", "PySide2", "PySide6"}
GUI_CALLS = {"startfile", "MessageBoxW", "MessageBoxA", "ShellExecuteW", "ShellExecuteA"}
#: The same, in a script: PowerShell's and cmd's ways of handing a file or program to the desktop.
SCRIPT_OPENS = re.compile(r"Start-Process|Invoke-Item|\bexplorer(?:\.exe)?\b|^\s*start\s|^\s*ii\s", re.I | re.M)


def program_name(text):
    """'java' for 'C:/jdk/bin/java.exe', 'git' for 'git'."""
    name = re.split(r"[\\/]", text)[-1].lower()
    return name[:-4] if name.endswith(".exe") else name


def _elements(node):
    """The element expressions of a literal sequence - or of either literal a conditional picks - or None."""
    if isinstance(node, (ast.Tuple, ast.List, ast.Set)):
        return list(node.elts)
    if isinstance(node, ast.IfExp):
        body, orelse = _elements(node.body), _elements(node.orelse)
        return None if body is None or orelse is None else body + orelse
    return None


def _union(parts):
    """The union of the sets, or None - COULD NOT READ - when there are none or any one is None."""
    if not parts or any(p is None for p in parts):
        return None
    return set().union(*parts)


class _Reader(object):
    """Which program a start runs, read from its file alone. A name is followed to its assignments in each function
    that encloses it and then the module; a parameter to what this file's own calls pass it; a placeholder a list
    comprehension replaces to what replaces it. A call's return value, and anything from another file, are not read
    - None, which is never taken for a program with no window."""

    FUNCTIONS = (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)

    def __init__(self, tree):
        self.tree = tree
        self.parent = {}
        for node in ast.walk(tree):
            for child in ast.iter_child_nodes(node):
                self.parent[id(child)] = node
        self.calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call)]
        self.defs = {}
        for n in ast.walk(tree):
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)):
                self.defs.setdefault(n.name, []).append(n)
        #(function, parameter) pairs a start's program came from that no call in THIS file passes: a forwarder,
        #asked again at its callers in the rest of the tree - see `handed`
        self.open = []

    def chain(self, node):
        """The functions enclosing `node`, innermost first."""
        out, n = [], self.parent.get(id(node))
        while n is not None:
            if isinstance(n, self.FUNCTIONS):
                out.append(n)
            n = self.parent.get(id(n))
        return out

    def assigned(self, where, name):
        """The values `name` is given directly in `where` (nested functions and classes are their own scope); a None
        in the list is a binding whose value cannot be read - a loop variable, a `with ... as`, an unpacked call."""
        found, stack = [], list(ast.iter_child_nodes(where))
        while stack:
            n = stack.pop()
            if isinstance(n, self.FUNCTIONS + (ast.ClassDef,)):
                continue
            if isinstance(n, ast.Assign):
                for target in n.targets:
                    if isinstance(target, ast.Name) and target.id == name:
                        found.append(n.value)
                    elif isinstance(target, (ast.Tuple, ast.List)):
                        paired = isinstance(n.value, (ast.Tuple, ast.List)) and len(n.value.elts) == len(target.elts)
                        for k, sub in enumerate(target.elts):
                            if isinstance(sub, ast.Name) and sub.id == name:
                                found.append(n.value.elts[k] if paired else None)
            elif isinstance(n, (ast.AnnAssign, ast.AugAssign, ast.NamedExpr)) and \
                    isinstance(n.target, ast.Name) and n.target.id == name:
                found.append(n.value if isinstance(n, (ast.AnnAssign, ast.NamedExpr)) else None)
            elif isinstance(n, (ast.For, ast.AsyncFor)) and name in {t.id for t in ast.walk(n.target)
                                                                      if isinstance(t, ast.Name)}:
                #a loop over a literal - `for name in ("javac.exe", "javac")` - holds its elements
                items = _elements(n.iter) if isinstance(n.target, ast.Name) else None
                found.extend(items if items else [None])
            elif isinstance(n, ast.withitem) and n.optional_vars is not None and name in {
                    t.id for t in ast.walk(n.optional_vars) if isinstance(t, ast.Name)}:
                found.append(None)
            stack.extend(ast.iter_child_nodes(n))
        return found

    def values(self, name, at, kind):
        """The expressions `name` can hold where `at` reads it, or None when that cannot be read from this file.
        `kind` says how they will be read - "heads" (an argument list) or "program" (one program)."""
        for fn in self.chain(at):
            spec = fn.args
            if name in {a.arg for a in spec.posonlyargs + spec.args + spec.kwonlyargs} | {
                    a.arg for a in (spec.vararg, spec.kwarg) if a is not None}:
                return self.passed(fn, name, kind)
            got = self.assigned(fn, name)
            if got:
                return None if None in got else got
        got = self.assigned(self.tree, name)
        return None if not got or None in got else got

    def passed(self, fn, name, kind):
        """What this file's own calls pass `fn` for its parameter `name` - None if any call cannot be read, or none
        is made here (the value then comes from another file, and the pair is recorded in `open` to be asked there)."""
        if isinstance(fn, ast.Lambda):
            return None
        spec = fn.args
        positional = [a.arg for a in spec.posonlyargs + spec.args]
        method = bool(positional) and positional[0] in ("self", "cls")
        out = []
        for call in self.calls:
            if isinstance(call.func, ast.Name) and call.func.id == fn.name:
                shift = 0
            elif isinstance(call.func, ast.Attribute) and call.func.attr == fn.name:
                shift = 1 if method else 0
            else:
                continue
            value = next((k.value for k in call.keywords if k.arg == name), None)
            if value is None and name in positional:
                index = positional.index(name) - shift
                if 0 <= index < len(call.args) and not any(isinstance(a, ast.Starred) for a in call.args[:index + 1]):
                    value = call.args[index]
            if value is None:
                return None
            out.append(value)
        if not out:
            self.open.append((fn, name, kind))
        return out or None

    def argument(self, call, fn_name, name, index, method):
        """What `call` - a call of another file's `fn_name` - passes for its parameter `name` (position `index`), or
        None when that cannot be read."""
        value = next((k.value for k in call.keywords if k.arg == name), None)
        if value is None and index is not None:
            at = index - (1 if method and isinstance(call.func, ast.Attribute) else 0)
            if 0 <= at < len(call.args) and not any(isinstance(a, ast.Starred) for a in call.args[:at + 1]):
                value = call.args[at]
        return value

    def returned(self, name, depth):
        """The programs a call to this file's own function `name` returns, or None when that cannot be read."""
        defs = self.defs.get(name)
        if not defs:
            return None
        parts = []
        for fn in defs:
            stack = list(ast.iter_child_nodes(fn))
            while stack:
                n = stack.pop()
                if isinstance(n, self.FUNCTIONS + (ast.ClassDef,)):
                    continue
                if isinstance(n, ast.Return):
                    parts.append(self.program(n.value, depth + 1))
                stack.extend(ast.iter_child_nodes(n))
        return _union(parts)

    def heads(self, node, depth=0):
        """The programs an argument list can start with, or None when that cannot be read from this file."""
        if node is None or depth > 12:
            return None
        if isinstance(node, (ast.List, ast.Tuple)):
            return self.program(node.elts[0], depth + 1) if node.elts else None
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            return self.heads(node.left, depth + 1)
        if isinstance(node, ast.ListComp) and len(node.generators) == 1:
            inner = self.heads(node.generators[0].iter, depth + 1)
            loop, elt = node.generators[0].target, node.elt
            if inner is None or not isinstance(loop, ast.Name):
                return None
            if isinstance(elt, ast.Name) and elt.id == loop.id:
                return inner
            if (isinstance(elt, ast.Call) and isinstance(elt.func, ast.Attribute) and elt.func.attr == "replace"
                    and isinstance(elt.func.value, ast.Name) and elt.func.value.id == loop.id
                    and len(elt.args) == 2 and isinstance(elt.args[0], ast.Constant)
                    and isinstance(elt.args[0].value, str)):
                old = program_name(elt.args[0].value)
                if old not in inner:
                    return inner
                new = self.program(elt.args[1], depth + 1)
                return None if new is None else (inner - {old}) | new
            return None
        if isinstance(node, ast.Name):
            values = self.values(node.id, node, "heads")
            return None if values is None else _union([self.heads(v, depth + 1) for v in values])
        return None

    def program(self, node, depth=0):
        """The program one argument names, or None when that cannot be read from this file."""
        if node is None or depth > 12:
            return None
        if isinstance(node, ast.Constant) and node.value is None:
            return set()                        #`return None` - no program; a start handed it starts nothing
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return {program_name(node.value)}
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in self.defs:
            return self.returned(node.func.id, depth)
        if ast.unparse(node) == "sys.executable":
            return {"python"}
        if isinstance(node, ast.BoolOp):
            return _union([self.program(v, depth + 1) for v in node.values])
        if isinstance(node, ast.IfExp):
            return _union([self.program(node.body, depth + 1), self.program(node.orelse, depth + 1)])
        if isinstance(node, ast.Call) and node.args:
            callee = ast.unparse(node.func)
            if callee in ("shutil.which", "which"):
                return self.program(node.args[0], depth + 1)
            if callee in ("os.path.join", "join"):
                return self.program(node.args[-1], depth + 1)
        if isinstance(node, ast.Name):
            values = self.values(node.id, node, "program")
            return None if values is None else _union([self.program(v, depth + 1) for v in values])
        return None


def names_a_jvm(tree):
    """Does this file name the JVM - a string naming it as a program ('java', 'javaw.exe', '.../bin/java.exe'), or a
    name in the code that does (`java`, `JAVA_EXE`, `toolchain.java_path()`)? A start whose program this file cannot
    read is refused here: a JVM fetched from another file's function names no string, only the name it is fetched by."""
    for n in ast.walk(tree):
        if isinstance(n, ast.Constant) and isinstance(n.value, str):
            word = n.value.strip()
            if word and " " not in word and program_name(word) in JVM_PROGRAMS:
                return True
        word = n.id if isinstance(n, ast.Name) else n.attr if isinstance(n, ast.Attribute) else \
            n.name if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) else n.arg if isinstance(n, ast.arg) else None
        if word and set(word.lower().split("_")) & JVM_PROGRAMS:
            return True
    return False


def _judged(heads, jvm_named):
    """The verdict on a program set read for a start (None: could not be read)."""
    if heads is None:
        return "JVM FROM A CALLER" if jvm_named else "caller", ""
    if heads & JVM_PROGRAMS:
        return "JVM", ",".join(sorted(heads))
    if heads <= CONSOLE_PROGRAMS:
        return "console", ",".join(sorted(heads))
    return "UNKNOWN PROGRAM", ",".join(sorted(heads - CONSOLE_PROGRAMS))


def _moves_itself(tree):
    return any(isinstance(n, ast.Call) and ast.unparse(n.func).endswith("on_the_unattended_desktop")
               for n in ast.walk(tree))


def window_verdicts(source, forwarders=None):
    """[(line, verdict, detail)] for each process start in `source`, asked of the PROGRAM it starts: 'hidden'
    (through hidden_desktop), 'moved' (the file moves itself to the unattended desktop first), 'console' (a program
    with no window of its own), 'caller' (the program comes from a caller, in a file that names no JVM), or one that
    can put a window on the owner's desktop: 'JVM', 'UNKNOWN PROGRAM', or 'JVM FROM A CALLER' (unreadable, in a file
    that names the JVM). A start whose program is a parameter no call in this file passes is a FORWARDER: it is
    added to `forwarders` as (function, parameter, position, method?, kind), to be asked at its callers elsewhere."""
    tree = ast.parse(source)
    moved = _moves_itself(tree)
    jvm_named = names_a_jvm(tree)
    reader = _Reader(tree)
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        name = ast.unparse(node.func)
        if name in HIDDEN:
            out.append((node.lineno, "hidden", ""))
            continue
        if name not in STARTERS:
            continue
        if moved:
            out.append((node.lineno, "moved", ""))
            continue
        first = node.args[0] if node.args else None
        reader.open = []
        heads = None if isinstance(first, ast.Starred) else reader.heads(first)
        if heads and heads & JVM_PROGRAMS and _carries_flag(first, reader, HEADLESS):
            rest = heads - JVM_PROGRAMS                 #a headless JVM opens no window; anything else is still asked
            out.append((node.lineno, "console", "java, headless") if not rest
                       else (node.lineno,) + _judged(rest, jvm_named))
            continue
        verdict, detail = _judged(heads, jvm_named)
        out.append((node.lineno, verdict, detail))
        if heads is None and forwarders is not None:
            for fn, param, kind in reader.open:
                if isinstance(fn, ast.Lambda):
                    continue
                positional = [a.arg for a in fn.args.posonlyargs + fn.args.args]
                forwarders.append((fn.name, param, positional.index(param) if param in positional else None,
                                   bool(positional) and positional[0] in ("self", "cls"), kind))
    return out


def handed(source, module, forwarders):
    """[(line, verdict, detail, more)] for each call in `source` of another file's forwarder - (module, function,
    parameter, position, method?, kind) - asked what it hands that parameter. A call is matched by shape: `m.f(...)`
    where m is the forwarder's module, or `f(...)` in a file that imports f from it. A start or a hidden_desktop
    call is asked as a start already. `more` lists this file's own forwarders the value came through, if any."""
    tree = ast.parse(source)
    if _moves_itself(tree):
        return []
    imported = set()
    for n in ast.walk(tree):
        if isinstance(n, ast.ImportFrom):
            imported |= {(n.module.rsplit(".", 1)[-1] if n.module else "", a.asname or a.name) for a in n.names}
    jvm_named = names_a_jvm(tree)
    reader = _Reader(tree)
    out = []
    for call in reader.calls:
        callee = ast.unparse(call.func)
        if callee in STARTERS or callee in HIDDEN:
            continue
        for owner, fn_name, param, index, method, kind in forwarders:
            if owner == module:
                continue
            if isinstance(call.func, ast.Attribute):
                hit = call.func.attr == fn_name and ast.unparse(call.func.value).rsplit(".", 1)[-1] == owner
            else:
                hit = isinstance(call.func, ast.Name) and (owner, call.func.id) in imported and call.func.id == fn_name
            if not hit:
                continue
            value = reader.argument(call, fn_name, param, index, method)
            reader.open = []
            read = None if value is None else (reader.heads(value) if kind == "heads" else reader.program(value))
            if read and read & JVM_PROGRAMS and kind == "heads" and _carries_flag(value, reader, HEADLESS):
                read = read - JVM_PROGRAMS                 #a headless JVM opens no window
            verdict, detail = _judged(read, jvm_named) if read != set() else ("console", "java, headless")
            more = []
            if read is None:
                for fn, p, k in reader.open:
                    if not isinstance(fn, ast.Lambda):
                        positional = [a.arg for a in fn.args.posonlyargs + fn.args.args]
                        more.append((module, fn.name, p, positional.index(p) if p in positional else None,
                                     bool(positional) and positional[0] in ("self", "cls"), k))
            out.append((call.lineno, verdict, "%s.%s(%s=...) %s" % (owner, fn_name, param, detail), more))
    return out


def gui_uses(source):
    """[(line, what)] for each way `source` puts a window up without starting a program."""
    found = []
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Import):
            found += [(node.lineno, a.name) for a in node.names if a.name.split(".")[0] in GUI_MODULES]
        elif isinstance(node, ast.ImportFrom) and (node.module or "").split(".")[0] in GUI_MODULES:
            found.append((node.lineno, node.module))
        elif isinstance(node, ast.Attribute) and node.attr in GUI_CALLS:
            found.append((node.lineno, ast.unparse(node)))
    return found


def jvm_commands(data, where=""):
    """[where] for each list in this JSON value whose first word is the JVM - a command a tool would start."""
    found = []
    if isinstance(data, dict):
        for key, value in data.items():
            found += jvm_commands(value, "%s/%s" % (where, key))
    elif isinstance(data, list):
        if data and isinstance(data[0], str) and program_name(data[0].strip()) in JVM_PROGRAMS \
                and HEADLESS not in data:
            found.append(where or "/")
        for n, value in enumerate(data):
            found += jvm_commands(value, "%s[%d]" % (where, n))
    return found


#: The flag that keeps a JVM from opening any window: AWT refuses to make one (HeadlessException).
HEADLESS = "-Djava.awt.headless=true"


def _carries_flag(node, reader, flag, depth=0):
    """Does the argument list `node` - a literal, lists joined with +, or a name holding them - carry `flag`?"""
    if node is None or depth > 8:
        return False
    if isinstance(node, (ast.List, ast.Tuple)):
        return any(isinstance(e, ast.Constant) and e.value == flag for e in node.elts)
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        return _carries_flag(node.left, reader, flag, depth + 1) or _carries_flag(node.right, reader, flag, depth + 1)
    if isinstance(node, ast.Name):
        values = reader.values(node.id, node, "heads")
        return bool(values) and all(_carries_flag(v, reader, flag, depth + 1) for v in values)
    return False


def window_findings(root, sources):
    """Both halves of the pop-up walk over one tree, as {counts, windowed, opens, console_left, clear, starts, asked,
    hand_offs, sources, scripts, configs}. `sources` are (path under root, code or None to read it) - the tree's
    Python held to git's list, and for this tree the hook launchers settings.json runs too."""
    import json
    counts, windowed, opens, forwarders, codes, console_left = {}, [], [], [], {}, []
    clear = starts = 0
    for rel, code in sources:
        if code is None:
            with io.open(os.path.join(root, *rel.split("/")), encoding="utf-8") as handle:
                code = handle.read()
        codes[rel] = code
        for line, verdict in process_starts(code):
            if rel.rsplit("/", 1)[-1] == "bounded.py" and verdict == "UNKNOWN":
                verdict = "clear"   #the runner forwards its caller's flags; every caller is asked here
            starts += 1
            clear += verdict in ("clear", "hidden")
            if verdict in ("OPEN", "UNKNOWN"):
                console_left.append("%s %s:%d" % (verdict, rel, line))
        found = []
        for line, verdict, detail in window_verdicts(code, found):
            counts[verdict] = counts.get(verdict, 0) + 1
            if verdict not in ("hidden", "moved", "console", "caller"):
                windowed.append("%s %s:%d %s" % (verdict, rel, line, detail))
        forwarders += [(rel.rsplit("/", 1)[-1][:-3],) + f for f in found if rel.endswith(".py")]
        opens += ["GUI %s:%d %s" % (rel, line, what) for line, what in gui_uses(code)]
    #A FORWARDER IS ASKED AT ITS CALLERS: a start whose program is a parameter no call in its own file passes is
    #the cheapest way past the walk above - a helper in a file that names no JVM, called from one that does. So
    #every call of it elsewhere is asked what it hands on, and what a caller hands through a forwarder of its own
    #is asked at THAT one's callers in turn, until nothing new is handed on.
    asked, queue, hand_offs = set(), list(forwarders), 0
    while queue:
        batch = [f for f in set(queue) if f not in asked]
        asked |= set(batch)
        queue = []
        for rel, code in codes.items():
            for line, verdict, detail, more in handed(code, rel.rsplit("/", 1)[-1][:-3], batch):
                hand_offs += 1
                if verdict not in ("console", "caller"):
                    windowed.append("%s %s:%d handed to %s" % (verdict, rel, line, detail))
                queue += more
    scripts = git_files(root, "*.ps1", "*.bat", "*.cmd", "*.sh")
    configs = git_files(root, "*.json")
    if scripts is None or configs is None:
        opens.append("UNREAD %s: git could not be asked which scripts and config files it holds" % root)
        scripts, configs = scripts or [], configs or []
    for rel in scripts:
        with io.open(os.path.join(root, *rel.split("/")), encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        opens += ["OPEN %s:%d %s" % (rel, text.count(LF, 0, m.start()) + 1, m.group(0).strip())
                  for m in SCRIPT_OPENS.finditer(text)]
    for rel in configs:
        try:
            with io.open(os.path.join(root, *rel.split("/")), encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, ValueError) as ex:
            opens.append("UNREAD %s: %s - a config this cannot read may hold a command" % (rel, ex))
            continue
        opens += ["JVM COMMAND %s%s" % (rel, where) for where in jvm_commands(data)]
    return {"counts": counts, "windowed": windowed, "opens": opens, "console_left": console_left, "clear": clear,
            "starts": starts, "asked": len(asked), "hand_offs": hand_offs, "sources": len(sources),
            "scripts": scripts, "configs": configs}


def bootstrap_root(root):
    """(folder, why) of the verification bootstrap this tree installs from - asked of the installer's own reader, the
    one the session-start check asks, never a path typed here."""
    import importlib.util
    path = os.path.join(root, "tools", "bundle_install.py")
    try:
        spec = importlib.util.spec_from_file_location("bundle_install_of_the_window_walk", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except (OSError, ImportError, AttributeError, SyntaxError) as ex:
        return None, "the bundle installer could not be loaded (%s)" % ex
    return module.bundle_path(os.path.join(root, ".claude", "bundle-install.json"))


def git_files(root, *patterns):
    """The files git holds or would add here matching `patterns`, or None when git cannot be asked."""
    try:
        done = bounded.run(["git", "-C", root, "ls-files", "-z", "-co", "--exclude-standard", "--"] + list(patterns),
                           capture_output=True, timeout=120, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0:
        return None
    return sorted(p for p in done.stdout.decode("utf-8", "replace").split(chr(0)) if p)


#: No console window for a child whose output is captured here.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def tree_python_files(root):
    """Every .py of this tree, WALKED, as root-relative paths - no folder named. A folder holding a `.git` of its own
    (a sweep's worktree, a clone) is another tree and is left to its own walk; bytecode caches are skipped."""
    out = []
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in (".git", "__pycache__")
                   and not os.path.exists(os.path.join(base, d, ".git"))]
        out.extend(os.path.relpath(os.path.join(base, n), root).replace(os.sep, "/") for n in names if n.endswith(".py"))
    return sorted(out)


def git_python_files(root):
    """The .py files git holds or would add here (tracked, and untracked but not ignored), or None when git cannot be
    asked - never an empty set for a question nobody answered."""
    try:
        done = bounded.run(["git", "-C", root, "ls-files", "-z", "-co", "--exclude-standard", "--", "*.py"],
                           capture_output=True, timeout=120, creationflags=NO_WINDOW)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0:
        return None
    return {p for p in done.stdout.decode("utf-8", "replace").split(chr(0)) if p}


def settings_launchers(root):
    """The Python each hook command in .claude/settings.json runs inline (`python -c "<code>"`): the launcher that
    starts every hook is a process start too, and lives in no .py file."""
    import json
    import shlex
    with io.open(os.path.join(root, ".claude", "settings.json"), encoding="utf-8") as handle:
        settings = json.load(handle)
    commands = []

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "command" and isinstance(value, str):
                    commands.append(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)
    walk(settings.get("hooks", {}))
    codes = []
    for command in commands:
        words = shlex.split(command)
        if "-c" in words[:4] and words.index("-c") + 1 < len(words):
            codes.append(words[words.index("-c") + 1])
    return codes


@unittest.skipUnless(os.name == "nt", "the owner's desktop this keeps clear is a Windows one")
class AnUnattendedWindowIsNeverOnTheOwnersScreen(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="hidden-desktop-")
        self.probe = os.path.join(self.dir, "probe.py")
        with io.open(self.probe, "w", encoding="utf-8") as handle:
            handle.write(PROBE)

    def test_control_a_window_already_on_this_desktop_is_found(self):
        """The CONTROL opens no window of its own: one that did - transparent and off-screen - still took the
        owner's keyboard when it opened (2026-10-11), the very thing this file exists to stop. It finds the
        taskbar instead, a window this desktop always has, by the same lookup the test below relies on."""
        trays = [owner for owner, cls, _title, _visible in owners_desktop_windows() if cls == "Shell_TrayWnd"]
        self.assertTrue(trays, "CONTROL: the owner's desktop has no taskbar to look for")
        self.assertTrue(visible_windows_of(trays[0]), "CONTROL: a window already on the owner's desktop was not"
                                                      " found, so an empty answer below would prove nothing")

    def watched(self, argv, pidfile):
        """Run `argv` through hidden_desktop while the owner's desktop is watched for the probe's windows:
        (exit code, what it said, the probe's pid or None, the titles of its windows seen there)."""
        seen = []
        import threading

        def look():
            pid = wait_for_pid(pidfile)
            if pid is not None:
                for _ in range(30):
                    seen.extend(visible_windows_of(pid))
                    time.sleep(0.1)
            seen.append(("pid", pid))
        watcher = threading.Thread(target=look)
        watcher.start()
        code, said = hidden_desktop.run(argv, cwd=self.dir, timeout=120)
        watcher.join(timeout=60)
        pid = [s for s in seen if isinstance(s, tuple)]
        return code, said, (pid[0][1] if pid else None), [s for s in seen if not isinstance(s, tuple)]

    def test_a_window_started_through_hidden_desktop_is_not_on_this_desktop(self):
        pidfile = os.path.join(self.dir, "hidden.pid")
        code, said, pid, titles = self.watched([sys.executable, "-B", self.probe, pidfile], pidfile)
        self.assertTrue(pid is not None, "the probe never started on the unattended desktop: %s" % said)
        self.assertEqual(titles, [], "a window started through hidden_desktop was drawn on the owner's screen")
        self.assertEqual((code, "PROBE RAN TO ITS END" in said), (0, True),
                         "the probe did not run to its end on the unattended desktop: %r" % said)

    def test_a_window_a_CHILD_started_on_the_unattended_desktop_opens_is_not_on_this_desktop(self):
        """A DESKTOP IS INHERITED. What a program on the unattended desktop starts - a suite JVM's own ProcessBuilder
        children, the editor's updater and converter under test, a script's next program - starts there too, with
        no flag and no desktop named. Measured with the same real window one process further down: the probe's
        PARENT goes through hidden_desktop and starts the probe the plainest way there is. Asked 2026-10-12, when a
        review found the Java code's process starts outside every count of "all of the pop ups"."""
        launcher = os.path.join(self.dir, "launcher.py")
        with io.open(launcher, "w", encoding="utf-8") as handle:
            handle.write("import subprocess, sys" + LF
                         + "sys.exit(subprocess.call([sys.executable, '-B'] + sys.argv[1:]))" + LF)
        pidfile = os.path.join(self.dir, "child.pid")
        code, said, pid, titles = self.watched([sys.executable, "-B", launcher, self.probe, pidfile], pidfile)
        self.assertTrue(pid is not None, "the child probe never started under the unattended desktop: %s" % said)
        self.assertEqual(titles, [], "a window a child started on the unattended desktop was drawn on the owner's"
                                     " screen")
        self.assertEqual((code, "PROBE RAN TO ITS END" in said), (0, True),
                         "the child probe did not run to its end: %r" % said)

    def test_a_program_started_there_KNOWS_it_is_there_whoever_started_it(self):
        """MEASURED 2026-10-12: replant started its suites through run() without the mark the two other callers set,
        so a suite that runs build.ps1 or package.ps1 in a scratch tree saw each move itself to the unattended
        desktop a second time, through a hidden_desktop.py the scratch tree has not got - three RuleRefusalsTest
        cases red outside the battery and green inside it. The mark is run()'s to give, not each caller's."""
        asker = os.path.join(self.dir, "ask.py")
        with io.open(asker, "w", encoding="utf-8") as handle:
            handle.write("import os" + LF + "print('MARK=%s' % os.environ.get('CTRMAP_ON_UNATTENDED_DESKTOP'))" + LF)
        for given in ("no environment", "an environment without the mark"):
            with mock.patch.dict(os.environ):
                os.environ.pop("CTRMAP_ON_UNATTENDED_DESKTOP", None)
                env = None if given == "no environment" else dict(os.environ)
                code, said = hidden_desktop.run([sys.executable, "-B", asker], cwd=self.dir, env=env, timeout=60)
            self.assertEqual(code, 0, "CONTROL: the asker did not run: %r" % said)
            self.assertIn("MARK=1", said, "a program started on the unattended desktop with %s was not told it is"
                                          " there: %r" % (given, said))

    def test_a_program_started_there_keeps_its_local_data_away_from_the_owners(self):
        """THE OWNER'S LOG HELD PLANTED FAILURES (measured 2026-10-23): %LOCALAPPDATA%\\CTRMap\\logs\\ctrmap.0.log carried
        "planted: a listener that throws" and two NullPointerExceptions from planted runs - a plant takes out the guard
        that keeps a suite away from the real log, and the suite then wrote there. The editor's log and its vault live
        under LOCALAPPDATA, and everything a run starts is started here, so this hands every one of them its own."""
        asker = os.path.join(self.dir, "local.py")
        with io.open(asker, "w", encoding="utf-8") as handle:
            handle.write("import os" + LF + "print('LOCAL=%s' % os.environ.get('LOCALAPPDATA'))" + LF)
        for given in ("no environment", "the caller's own environment"):
            env = None if given == "no environment" else dict(os.environ)
            code, said = hidden_desktop.run([sys.executable, "-B", asker], cwd=self.dir, env=env, timeout=60)
            self.assertEqual(code, 0, "CONTROL: the asker did not run: %r" % said)
            self.assertIn("LOCAL=%s" % hidden_desktop.LOCAL, said,
                          "a program started with %s was handed the owner's local data folder: %r" % (given, said))
        own = os.path.join(self.dir, "a-folder-of-its-own")
        code, said = hidden_desktop.run([sys.executable, "-B", asker], cwd=self.dir,
                                        env=dict(os.environ, LOCALAPPDATA=own), timeout=60)
        self.assertIn("LOCAL=%s" % own, said, "a LOCALAPPDATA the caller chose on purpose was replaced: %r" % said)

    def test_a_desktop_that_cannot_be_made_runs_nothing_rather_than_running_it_on_the_owners(self):
        """The cheapest way past: when the second desktop cannot be made, run the program anyway - on the
        owner's. It is refused, and nothing is started."""
        k32, u32 = hidden_desktop._api()

        class NoDesktop(object):
            def __getattr__(self, name):
                return getattr(u32, name)

            @staticmethod
            def CreateDesktopW(*args):
                return 0
        started = []
        held = hidden_desktop._api
        hidden_desktop._api = lambda: (k32, NoDesktop())
        try:
            with mock.patch.object(hidden_desktop.bounded, "run", lambda *a, **k: started.append(a)):
                with self.assertRaises(OSError, msg="a desktop that could not be made let the program run"):
                    hidden_desktop.run([sys.executable, "-c", "pass"])
        finally:
            hidden_desktop._api = held
        self.assertEqual(started, [], "a desktop that could not be made ran the program on the owner's")


class EveryUnattendedSuiteRunsThere(unittest.TestCase):
    """The plant drives start their suites through replant.run_suite, which must start the JVM through
    hidden_desktop.run - asked with a stand-in that records the call, so no JVM is needed to ask it."""

    def test_every_tool_that_starts_a_suite_jvm_asks_hidden_desktop(self):
        """By shape, over every tool walked: a file that spells the suites' JVM flag starts JVMs, and must go
        through hidden_desktop - a sixth runner written tomorrow is asked too."""
        found, starts = [], []
        for folder, _dirs, names in os.walk(os.path.join(HERE, "tools")):
            for name in names:
                if name.endswith(".py"):
                    path = os.path.join(folder, name)
                    with io.open(path, encoding="utf-8", errors="replace") as handle:
                        text = handle.read()
                    if '"-Xmx4g"' in text:
                        starts.append(name)
                        #an import of it or a call into it - a comment naming it is not asking it
                        if not re.search(r"^\s*import hidden_desktop\b|\bhidden_desktop\.\w+\(", text, re.M):
                            found.append(os.path.relpath(path, HERE))
        self.assertIn("replant.py", starts, "CONTROL: the walk does not see replant start a suite JVM")
        #WHAT IT COVERED, said: a pass with no count reads the same over two tools as over none
        print("%d of %d tools that start suite JVMs go through hidden_desktop: %s"
              % (len(starts) - len(found), len(starts), sorted(starts)))
        self.assertEqual(found, [], "a tool starts suite JVMs without hidden_desktop: %s" % found)

    def test_every_script_that_starts_the_jdk_moves_to_the_unattended_desktop_first(self):
        """By shape, over every .ps1 walked: a script that runs a JDK program (java, javac, jar, jpackage) runs
        itself again on the unattended desktop before the first one. build.ps1 and test.ps1 did; testworld.ps1,
        which starts the editor's own Java to load zones, and package.ps1 did not (2026-10-12)."""
        jdk = re.compile(r"\b(?:javaw?|javac|jar|jpackage)\.exe\b", re.I)
        moves = re.compile(r'CTRMAP_ON_UNATTENDED_DESKTOP -ne "1"[\s\S]{0,1600}?hidden_desktop\.py')
        bare = 'Invoke-Native { & "$jdk\\bin\\java.exe" -cp x Y }'
        moved = 'if ($env:CTRMAP_ON_UNATTENDED_DESKTOP -ne "1") { & python tools\\guard\\hidden_desktop.py }' + LF + bare
        self.assertTrue(jdk.search(bare) and not moves.search(bare) and moves.search(moved),
                        "CONTROL: the walk's two questions do not tell a script that moves from one that does not")
        starts, found = [], []
        for folder, dirs, names in os.walk(HERE):
            dirs[:] = [d for d in dirs if d not in (".git", "build", "wt", "lib", "dist", "__pycache__")]
            for name in names:
                if name.endswith(".ps1"):
                    with io.open(os.path.join(folder, name), encoding="utf-8", errors="replace") as handle:
                        text = handle.read()
                    first = jdk.search(text)
                    if first:
                        starts.append(name)
                        move = moves.search(text)
                        if not move or move.start() > first.start():
                            found.append(os.path.relpath(os.path.join(folder, name), HERE))
        self.assertIn("build.ps1", starts, "CONTROL: the walk does not see build.ps1 start the JDK")
        print("%d of %d scripts that start the JDK move to the unattended desktop first: %s"
              % (len(starts) - len(found), len(starts), sorted(starts)))
        self.assertEqual(found, [], "these start the JDK on whatever desktop they are run from: %s" % found)

    def test_no_process_this_tree_starts_can_open_a_window_on_the_owners_desktop(self):
        """EVERY START, by shape, over EVERY Python file of this tree and every hook launcher settings.json wires: each
        passes creationflags (no console of its own) or runs through hidden_desktop. A child of a process with no
        console - a hook, a tool a hook started - gets a console window of its own unless told not to, captured or
        not. The runner's own start in bounded.py forwards its caller's flags, and every caller is asked here. Asked
        first of "all of the pop ups" on 2026-10-12, when drive_owed's worker start was the one left open; the walk
        then covered three folders named in a list, and the first hooks folder found - .claude/hooks, with
        guard_push's four starts, was never read. The scope is the tree's now, held to git's own list of it."""
        ctl = process_starts("import subprocess, bounded, hidden_desktop\n"
                             "subprocess.run(['a'])\n"
                             "subprocess.run(['a'], creationflags=X)\n"
                             "hidden_desktop.run(['a'])\n"
                             "run = dict(cwd='.', creationflags=X)\n"
                             "subprocess.run(['a'], **run)\n"
                             "bounded.run(['a'], **elsewhere)\n")
        self.assertEqual([v for _, v in ctl], ["OPEN", "clear", "hidden", "clear", "UNKNOWN"],
                         "CONTROL: the shapes are not told apart: %s" % ctl)
        walked = tree_python_files(HERE)
        held = git_python_files(HERE)
        self.assertIsNotNone(held, "CONTROL: git could not be asked which Python files this tree holds - an unread "
                                   "list is not an empty one")
        self.assertEqual(sorted(held - set(walked)), [], "the walk left out Python files git holds")
        launchers = settings_launchers(HERE)
        self.assertTrue(launchers, "CONTROL: settings.json wires no hook launcher this could read")
        counts = {"clear": 0, "hidden": 0, "OPEN": 0, "UNKNOWN": 0}
        left = []
        sources = [(rel, None) for rel in walked] + [(".claude/settings.json#%d" % n, code)
                                                     for n, code in enumerate(launchers)]
        for rel, code in sources:
            if code is None:
                with io.open(os.path.join(HERE, *rel.split("/")), encoding="utf-8") as handle:
                    code = handle.read()
            for line, verdict in process_starts(code):
                if rel.rsplit("/", 1)[-1] == "bounded.py" and verdict == "UNKNOWN":
                    verdict = "clear"   #the runner forwards its caller's flags; every caller is asked here
                counts[verdict] += 1
                if verdict in ("OPEN", "UNKNOWN"):
                    left.append("%s %s:%d" % (verdict, rel, line))
        total = sum(counts.values())
        self.assertGreater(total, 50, "CONTROL: %d process starts in %d files - a walk that read nothing"
                           % (total, len(sources)))
        print("%d of %d process starts in %d Python files and %d hook launchers open no console window on the owner's"
              " desktop" % (counts["clear"] + counts["hidden"], total, len(walked), len(launchers)))
        self.assertEqual(left, [], "these can open a window on the owner's desktop: %s" % left)
        self.no_start_runs_a_program_with_windows_on_the_owners_desktop(sources)

    def no_start_runs_a_program_with_windows_on_the_owners_desktop(self, sources):
        """THE PROGRAM, not only its flags. creationflags keeps a console program's console away and does nothing to a
        program with windows of its own - a JVM's Swing dialogs, the load-zone pop ups themselves - so the half above
        said only "no console window" (reviewed 2026-10-12: its "clear" had been read as "no window"). Each start is
        asked what it runs: hidden, in a tool that moves itself there first, or a program with no window of its own.
        One whose program comes from a caller is asked where the caller builds it - another start in this walk, a
        config file walked below, or a command line the session types, which guard_foreground asks - and is refused
        outright in a file that names the JVM. And the ways to put a window up WITHOUT starting a program: a GUI
        toolkit or a desktop open in Python, a Start-Process or the like in a script, a JVM command in a config."""
        ctl = window_verdicts("import subprocess, os, shutil, hidden_desktop\n"
                              "JAVA = 'java.exe' if os.name == 'nt' else 'java'\n"
                              "def a(argv):\n"
                              "    subprocess.run(['git', 'status'], creationflags=X)\n"
                              "    subprocess.run([os.path.join(h, 'bin', JAVA), '-cp', 'x'], creationflags=X)\n"
                              "    shell = shutil.which('powershell') or shutil.which('pwsh')\n"
                              "    subprocess.run([shell, '-File', 'b.ps1'], creationflags=X)\n"
                              "    subprocess.run(['notepad', 'x.txt'], creationflags=X)\n"
                              "    subprocess.run(argv, creationflags=X)\n"
                              "    hidden_desktop.run([JAVA, '-cp', 'x'])\n")
        self.assertEqual([v for _, v, _ in ctl], ["console", "JVM", "console", "UNKNOWN PROGRAM", "JVM FROM A CALLER",
                                                  "hidden"], "CONTROL: the programs are not told apart: %s" % ctl)
        self.assertEqual([v for _, v, _ in window_verdicts("import subprocess\ndef a(argv):\n"
                                                           "    subprocess.run(argv, creationflags=X)\n")],
                         ["caller"], "CONTROL: a forwarder in a file that names no JVM is not told apart")
        self.assertEqual([v for _, v, _ in window_verdicts("import subprocess, toolchain\n"
                                                           "subprocess.run([toolchain.java_path(), '-cp', 'x'],"
                                                           " creationflags=X)\n")],
                         ["JVM FROM A CALLER"], "CONTROL: a JVM fetched from another file by name is not told apart")
        self.assertEqual([v for _, v, _ in window_verdicts("import subprocess, hidden_desktop\n"
                                                           "hidden_desktop.on_the_unattended_desktop()\n"
                                                           "subprocess.run(['java'], creationflags=X)\n")],
                         ["moved"], "CONTROL: a tool that moves itself first is not told apart")
        self.assertEqual([w for _, w in gui_uses("import tkinter\nfrom webbrowser import open\nos.startfile(p)\n"
                                                 "# import wx\n")], ["tkinter", "webbrowser", "os.startfile"],
                         "CONTROL: the ways to put a window up from Python are not told apart")
        self.assertTrue(SCRIPT_OPENS.search("Start-Process notepad") and not SCRIPT_OPENS.search("$started = 1"),
                        "CONTROL: a script's desktop open is not told apart from a word that holds 'start'")
        #a config's commands come as the TEXT a config holds - written here as a live list, the control would itself
        #be a JVM command in this file, which the bootstrap's own rule refuses (it writes its controls as text too)
        import json
        self.assertEqual(jvm_commands(json.loads('{"run": ["java", "-cp", "x"], "prove": ["python", "-B"],'
                                                 ' "why": "java x"}')),
                         ["/run"], "CONTROL: a JVM command in a config is not told apart")
        found = []
        self.assertEqual([v for _, v, _ in window_verdicts("import bounded\ndef fwd(argv, key):\n"
                                                           "    bounded.run(argv, creationflags=X)\n", found)],
                         ["caller"], "CONTROL: a forwarder in a file that names no JVM is not told apart")
        caller = ("import a\nJAVA = 'java'\ndef b(c):\n    a.fwd([JAVA, '-cp', c], 'k')\n    a.fwd(['git'], 'k')\n"
                  "    a.other([JAVA])\n")
        self.assertEqual([v for _, v, _, _ in handed(caller, "b", [("a",) + f for f in found])], ["JVM", "console"],
                         "CONTROL: what another file hands a forwarder is not asked: %s" % found)
        self.assertEqual([v for _, v, _ in window_verdicts("import subprocess\nJAVA = 'java'\n"
                                                           "subprocess.run([JAVA, '%s', '-cp', 'x'], creationflags=X)\n"
                                                           % HEADLESS)],
                         ["console"], "CONTROL: a headless JVM, which can open no window, is not told apart")
        self.assertEqual(jvm_commands(json.loads('{"run": ["java", "%s", "x"]}' % HEADLESS)), [],
                         "CONTROL: a headless JVM command in a config is not told apart")
        mine = window_findings(HERE, sources)
        self.assertIn("build.ps1", mine["scripts"], "CONTROL: the script walk does not see build.ps1")
        self.assertIn(".claude/settings.json", mine["configs"], "CONTROL: the config walk does not see settings.json")
        #AND EVERY TREE THIS WORK RUNS CODE FROM, not this one alone (reviewed 2026-10-13: "all of the pop ups"
        #was measured over CTRMap, while the session ran the verification bootstrap's suite on the owner's desktop
        #three times that day - and it started `java` there). Found where the installer finds it, never by a typed
        #path; a bootstrap that cannot be found is UNKNOWN, refused, never taken for one with nothing in it.
        where, why = bootstrap_root(HERE)
        self.assertTrue(where, "the bootstrap this tree installs from cannot be found (%s), so what its suite and hooks"
                               " start is UNKNOWN" % why)
        held = git_python_files(where)
        self.assertIsNotNone(held, "CONTROL: git could not be asked which Python files the bootstrap holds")
        theirs = window_findings(where, [(rel, None) for rel in sorted(held)])
        shown = os.path.relpath(where, HERE).replace(os.sep, "/")
        self.assertGreater(theirs["starts"], 50, "CONTROL: the walk of the bootstrap read %d process starts in %d"
                                                 " sources - a walk that read nothing" % (theirs["starts"],
                                                                                          theirs["sources"]))
        for name, found in (("this tree", mine), (shown, theirs)):
            total = sum(found["counts"].values())
            safe = sum(found["counts"].get(v, 0) for v in ("hidden", "moved", "console", "caller"))
            print("%s: %d of %d process starts open no console window; %d of %d run nothing with a window of its own"
                  " on the owner's desktop: %d console programs, %d through hidden_desktop, %d in a tool that moves"
                  " itself there first, %d from a caller in a file that names no JVM; %d forwarders asked at %d calls"
                  " in other files; %d GUI toolkits, desktop opens or JVM commands in %d Python sources, %d scripts"
                  " and %d config files"
                  % (name, found["clear"], found["starts"], safe, total, found["counts"].get("console", 0),
                     found["counts"].get("hidden", 0), found["counts"].get("moved", 0),
                     found["counts"].get("caller", 0), found["asked"], found["hand_offs"], len(found["opens"]),
                     found["sources"], len(found["scripts"]), len(found["configs"])))
        left = mine["windowed"] + mine["opens"] + ["%s/%s" % (shown, f) for f in theirs["console_left"]
                                                   + theirs["windowed"] + theirs["opens"]]
        self.assertEqual(left, [], "these can put a window with a keyboard on the owner's desktop: %s" % left)

    def test_replant_starts_its_suite_on_the_unattended_desktop(self):
        import replant
        asked = []

        def recorded(argv, cwd=None, env=None, timeout=None, stream=None):
            asked.append(argv)
            return 0, "ALL PASS"
        held = hidden_desktop.run
        hidden_desktop.run = recorded
        try:
            code, said = replant.run_suite("ctrmap.tests.SomeSuite", ["x"], "java")
        finally:
            hidden_desktop.run = held
        self.assertEqual(len(asked), 1, "replant started a suite somewhere other than the unattended desktop")
        self.assertIn("ctrmap.tests.SomeSuite", asked[0], "CONTROL: the call recorded is not the suite's")
        self.assertEqual((code, said), (0, "ALL PASS"), "and what it answered is not what replant passed on")

    def test_autoplant_starts_its_suite_on_the_unattended_desktop(self):
        """autoplant kept a copy of replant's runner; the copy would have gone on opening suites on the
        owner's screen when replant's stopped. It asks replant's now."""
        import autoplant
        asked = []

        def recorded(argv, cwd=None, env=None, timeout=None, stream=None):
            asked.append(argv)
            return 0, "ALL PASS"
        held = hidden_desktop.run
        hidden_desktop.run = recorded
        try:
            autoplant.run_suite("ctrmap.tests.SomeSuite", ["x"], "java")
        finally:
            hidden_desktop.run = held
        self.assertEqual(len(asked), 1, "autoplant started a suite somewhere other than the unattended desktop")

    def test_a_suite_that_cannot_reach_the_desktop_is_not_asked_rather_than_asked_on_the_owners(self):
        import replant
        started = []

        def nowhere(*args, **kwargs):
            raise OSError("no desktop")
        held = hidden_desktop.run
        hidden_desktop.run = nowhere
        try:
            with mock.patch.object(replant.bounded, "run", lambda *a, **k: started.append(a)):
                code, said = replant.run_suite("ctrmap.tests.SomeSuite", ["x"], "java")
        finally:
            hidden_desktop.run = held
        self.assertEqual((code, started), (None, []),
                         "a suite that could not reach the unattended desktop was run on the owner's, or scored")


class TheRelayShowsWhatItIsHanded(unittest.TestCase):
    """What a suite prints reaches the caller WHOLE, and a character the caller's output cannot hold stops nothing.

    PAID FOR 2026-10-15: the release battery, its output sent to a file in the console's code page, died at its
    156th suite of 217 when one printed a character cp1252 has no byte for - a UnicodeEncodeError out of the relay,
    the battery's exit code 1, no suite failed. And each read of the log was decoded on its own, so a character the
    writer had half written came out as two replacement characters."""

    def setUp(self):
        self.log = tempfile.NamedTemporaryFile(delete=False).name
        self.addCleanup(os.remove, self.log)

    def append(self, data):
        with io.open(self.log, "ab") as f:
            f.write(data)

    def test_a_character_the_output_cannot_hold_is_replaced_not_fatal(self):
        held = io.BytesIO()
        out = io.TextIOWrapper(held, encoding="cp1252", newline="")
        with mock.patch.object(sys, "stdout", out):
            hidden_desktop._write("before �中 after")
        out.flush()
        said = held.getvalue().decode("cp1252")
        self.assertTrue(said.startswith("before ") and said.endswith(" after"),
                        "the relay did not show what it was handed past a character its output cannot hold: %r" % said)

    def test_a_character_split_across_two_reads_is_shown_whole(self):
        shown = []
        self.append(b"a\xc3")                    # the first byte of an e-acute, its second not written yet
        at = hidden_desktop._show_new(self.log, 0, shown.append)
        self.assertEqual((at, "".join(shown)), (1, "a"), "half a character was shown, or what was whole was held back")
        self.append(b"\xa9b")
        at = hidden_desktop._show_new(self.log, at, shown.append)
        self.assertEqual((at, "".join(shown)), (4, "aéb"), "a character split across two reads was not shown whole")

    def test_the_last_read_shows_what_is_left(self):
        shown = []
        self.append(b"a\xe4\xb8")                # two bytes of a three-byte character, and the writer has ended
        at = hidden_desktop._show_new(self.log, 0, shown.append, final=True)
        self.assertEqual(at, 3, "the last read held bytes back that no later read will show")
        self.assertTrue("".join(shown).startswith("a"), "the last read showed nothing: %r" % shown)


if __name__ == "__main__":
    unittest.main()
