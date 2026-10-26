# -*- coding: utf-8 -*-
"""A RELEASE IS DRIVEN THROUGH ITS OWN WINDOW BEFORE IT SHIPS.

    python -B tools/window_check.py <app> [--workspace <folder>] [--game <folder>] [--keep]
    python -B tools/window_check.py --release-commit        (the commit-msg hook: a version change needs a passed check)

<app> is a folder holding CTRMap-F5.jar and lib/ - the portable zip's layout, as package.ps1 stages it - or a
classes folder (build/classes), which runs with this tree's lib/. --workspace and --game name the folders to COPY;
by default, the ones the editor's own settings name, read from where Java keeps them. They are read and never
written: everything runs on copies in a scratch folder, which --keep leaves behind for reading.

WHY. 1.0.4 passed 219 suites and could not be used: a zone tool covered the zone list with no visible way back, a
refusal dialog opened on every launch, and every refill of the zone list froze the window 13.8 s - measured through
the real window on copies of the owner's workspace and game. The owner found all three by downloading the release:
no suite started the packaged program on a real workspace, loaded a zone, opened a tool and came back. The harness
that found them, and then measured 1.0.5 and the start-up fixes after it, lived in a session's scratchpad. This is
it, run by package.ps1 over the build it packaged, and asked by the commit that changes the version.

WHAT IT DOES. Copies the workspace and game into a scratch folder; compiles tools/window_check/*.java against the
build; launches the build twice on the unattended desktop - first on the fresh copies, loading a zone (`load`), then
again on what that launch left, first opening a zone tool over the zone list and coming back by the way back on
screen (`trap`). The harness answers each dialog with its safe answer and samples every 50 ms whether a window of
the editor is on screen while the event thread is stalled. `judge` reads what it printed and REFUSES the release on
any of: no window on screen; the harness failing, timing out or reaching its deadline; a freeze on screen of more
than FREEZE_MS before the zone load or during it; Load never pressed or no zone opened; a zone list that never
filled to the zone count; an answer the harness could not read; a dialog on the second launch that is not one this
check itself causes; and, in trap mode, a zone tool with no way back to the zone list on screen.

WHAT IT RECORDS, AND AGAINST WHAT. A pass is written to RECORD with the version and the SUBJECT it was measured
against - every file of src/ and lib/ by its git blob id, read from the working tree - because a number in a file is
a fact about some tree, and which one is exactly what a bare verdict does not say. The commit-msg hook asks
--release-commit: a commit that changes the version is refused unless RECORD passed for that version over exactly
the src/ and lib/ the commit stages (read from the index). A subject that cannot be read is UNKNOWN, and refused.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded     # noqa: E402  - a deadline ends the child's whole tree
import cli_words   # noqa: E402  - a word this does not read is refused, never ignored

ROOT = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(ROOT, "tools", "guard"))
import hidden_desktop  # noqa: E402  - nothing this starts draws on the owner's desktop
import replant         # noqa: E402  - the JDK the battery uses: replant.jdk, the one search

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

#: The longest freeze on screen a release may show, ms. 1.0.4 froze 13,807 ms on every fill of the zone list and
#: 1.0.5 250-1,186 ms on every launch; the fixed start-up measured 0-63 ms (2026-10-24, 3 launches, interleaved with
#: 1.0.3's 0). A freeze is only seen at all once the event thread is 300 ms late, so what is caught under this is the
#: tail of a stall that began before the window showed.
FREEZE_MS = 150

#: Dialogs this check causes itself, which a second launch may show: the workspace's backup names the folder it was
#: taken from, and the copy is opened from another - so a copy always says so; and the untitled progress window.
OWN_DIALOGS = ("Backup belongs to another game", "")

#: Where the editor keeps its settings on Windows: java.util.prefs, node "ctrmap.Workspace".
PREFS_KEY = "Software\\JavaSoft\\Prefs\\ctrmap./Workspace"

#: The record of the last pass, and the folders that are its subject.
RECORD = "tools/guard/window_check.json"
SUBJECT = ("src", "lib")
VERSION_FILE = "src/ctrmap/resources/version.properties"


def from_java_prefs(text):
    """A value as java.util.prefs wrote it to the Windows registry, read back: "/X" is the capital X, "//" a
    backslash, a lone backslash a slash, "/uXXXX" the character XXXX."""
    out, at = [], 0
    while at < len(text):
        c = text[at]
        if c == "/" and at + 1 < len(text):
            following = text[at + 1]
            if following == "/":
                out.append(chr(92))
                at += 2
            elif following == "u" and re.match(r"[0-9a-fA-F]{4}$", text[at + 2:at + 6]):
                out.append(chr(int(text[at + 2:at + 6], 16)))
                at += 6
            else:
                out.append(following)
                at += 2
        elif c == chr(92):
            out.append("/")
            at += 1
        else:
            out.append(c)
            at += 1
    return "".join(out)


def to_java_prefs(text):
    """The other way: a name or value as java.util.prefs writes it to the registry."""
    out = []
    for c in text:
        if c == chr(92):
            out.append("//")
        elif c == "/":
            out.append(chr(92))
        elif "A" <= c <= "Z":
            out.append("/" + c)
        elif ord(c) < 0x20 or ord(c) > 0x7e:
            out.append("/u%04x" % ord(c))
        else:
            out.append(c)
    return "".join(out)


def owners_folders():
    """((workspace, game), None) as the editor's own settings name them, or (None, why they cannot be read)."""
    if os.name != "nt":
        return None, "the editor's settings are read from the Windows registry, and this is not Windows"
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, PREFS_KEY) as key:
            found = {}
            for name in ("WORKSPACE_PATH", "GAMEDIR_PATH"):
                found[name] = from_java_prefs(winreg.QueryValueEx(key, to_java_prefs(name))[0])
    except OSError as missing:
        return None, "the editor's settings could not be read at HKCU\\%s: %s" % (PREFS_KEY, missing)
    return (found["WORKSPACE_PATH"], found["GAMEDIR_PATH"]), None


def judge(text, second):
    """Every reason one launch's harness output refuses the release; empty when it does not. `second`: the launch ran
    on what an earlier launch left, where only the dialogs this check causes may come up."""
    if not text.strip():
        return ["the harness said nothing at all - nothing was measured"]
    problems = []
    for said, why in (("HARNESS FAILED", "the harness failed"),
                      ("HARNESS DEADLINE", "the run reached the harness's 360 s deadline"),
                      ("HARNESS TIMED OUT", "the window never settled and loaded a zone within 300 s"),
                      ("NO SAFE ANSWER", "a dialog came up that has no safe answer to give it")):
        if said in text:
            problems.append(why)
    if "HARNESS DONE" not in text and not problems:
        problems.append("the harness ended without saying it was done - it did not finish")
    window = re.search(r"WINDOW FIRST ON SCREEN at (-?\d+) ms;", text)
    if not window or int(window.group(1)) < 0:
        problems.append("no window of the editor came on screen")
    freezes = re.search(r"WORST FREEZE ON SCREEN BEFORE LOAD (\d+) ms; IN THE ZONE LOAD (\d+) ms", text)
    if not freezes:
        problems.append("the harness gave no freeze measurement - UNKNOWN, not none")
    else:
        for value, when in ((int(freezes.group(1)), "before the zone load"), (int(freezes.group(2)), "in the zone load")):
            if value > FREEZE_MS:
                problems.append("the window froze on screen %d ms %s - over the %d ms a release may" % (value, when, FREEZE_MS))
    if "Load was never pressed" in text or "LOAD ATTEMPT: nothing to pick" in text:
        problems.append("no zone could be picked and loaded")
    if "NO ZoneBrowserPane" in text:
        problems.append("the zone list is not in the window")
    counts = [int(n) for n in re.findall(r"zone panel: dropdown items=(\d+)", text)]
    rows = [int(n) for n in re.findall(r"ZONE LIST has (-?\d+) rows", text)]
    if not counts or not counts[0]:
        problems.append("the editor named no zones to list")
    elif counts[0] not in rows:
        problems.append("the zone list never held all %d zones (it showed %s)" % (counts[0], rows[-3:] or "nothing"))
    if "open zone=UNREADABLE" in text:
        problems.append("which zone is open could not be read - UNKNOWN, not open")
    elif not re.search(r"AFTER LOAD settled.*?open zone=index \d+", text, re.S):
        problems.append("no zone was open after Load was pressed")
    if second:
        for title in re.findall(r"^DIALOG at \d+s, '(.*)':$", text, re.M):
            if title not in OWN_DIALOGS:
                problems.append("a dialog came up on a second launch: '%s' - one on every launch is what 1.0.4 did" % title)
    if "TRAP CHECK" in text:
        if "NO visible 'Re-link zone'" in text:
            problems.append("no zone tool could be opened from the zone actions bar")
        if "NO 'Back to the zone list'" in text or "way back showing=false" in text:
            problems.append("a zone tool covered the zone list with no way back on screen - the 1.0.4 trap")
        if "TRAP CHECK after pressing it: zone list showing=true, load showing=true" not in text:
            problems.append("the way back did not put the zone list and Load back on screen")
    return problems


def summary(name, text):
    """One line of what a launch measured."""
    def grab(pattern):
        found = re.search(pattern, text)
        return found.group(1) if found else "?"
    rows = re.findall(r"ZONE LIST has (\d+) rows at (\d+)s", text)
    return ("%s: window on screen at %s ms, frozen on screen %s ms before the zone load and %s ms in it, zone list "
            "%s rows at %s s, dialogs %s" % (name, grab(r"WINDOW FIRST ON SCREEN at (\d+) ms;"),
                                            grab(r"BEFORE LOAD (\d+) ms;"), grab(r"IN THE ZONE LOAD (\d+) ms"),
                                            rows[-1][0] if rows else "?", rows[-1][1] if rows else "?",
                                            re.findall(r"^DIALOG at \d+s, '(.*)':$", text, re.M)))


def git(root, *args):
    """git's output, or raises OSError saying why there is none - an unanswered question is not an empty answer."""
    done = bounded.run(["git"] + list(args), cwd=root, capture_output=True, timeout=120, creationflags=NO_WINDOW)
    if done.returncode != 0:
        raise OSError("git %s answered %d: %s" % (" ".join(args), done.returncode,
                                                   done.stderr.decode("utf-8", "replace").strip()))
    return done.stdout.decode("utf-8", "replace")


def blob_id(data):
    """The id git gives a file's bytes."""
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def subject(root, staged):
    """The digest of every file of SUBJECT by git blob id: from the index (`staged`) or from the working tree, every
    file git would commit (tracked, or untracked and not ignored). Raises OSError when it cannot be read."""
    entries = []
    if staged:
        for line in git(root, "ls-files", "-s", "--", *SUBJECT).splitlines():
            meta, path = line.split("\t", 1)
            entries.append("%s %s" % (path, meta.split()[1]))
    else:
        for path in git(root, "ls-files", "--cached", "--others", "--exclude-standard", "--", *SUBJECT).splitlines():
            full = os.path.join(root, path)
            if os.path.isfile(full):
                with open(full, "rb") as handle:
                    entries.append("%s %s" % (path, blob_id(handle.read())))
    if not entries:
        raise OSError("no file of %s was found to measure" % ", ".join(SUBJECT))
    return hashlib.sha256("\n".join(sorted(entries)).encode("utf-8")).hexdigest()


def version_in(text):
    found = re.search(r"^version=(.*)$", text or "", re.M)
    return found.group(1).strip() if found else None


#: The owner's own switch for a release made while OUTSTANDING.md still lists open work - read through bundle_env, as
#: every override is, never a flag a commit can carry. The owner has made that call before, knowingly (the emergency
#: 1.0.5); it is theirs to make, and this is the one place it is made.
RELEASE_WITH_OPEN_WORK = "ALLOW_RELEASE_WITH_OPEN_WORK"


def open_work(root):
    """(the open items of the OUTSTANDING.md this commit stages, None), or (None, why they could not be read)."""
    try:
        text = git(root, "show", ":OUTSTANDING.md")
    except OSError as unread:
        return None, str(unread)
    import work_order
    items = work_order.open_items(text)
    if items is None:
        return None, "it has no `## Open` section to read"
    return items, None


def _owner_switch():
    """bundle_env, from the project's hooks folder - the one reader of every owner override."""
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    import bundle_hooks
    sys.path.insert(0, bundle_hooks.path(ROOT))
    import bundle_env
    return bundle_env


def release_commit(root):
    """None when the commit being made may go on, else why not: a commit that changes the version must have a
    passed window check of exactly the src/ and lib/ it stages, and no work open in the queue it stages."""
    try:
        staged = git(root, "show", ":" + VERSION_FILE)
    except OSError as unread:
        return "REFUSING: the version this commit stages could not be read (%s) - UNKNOWN, not unchanged" % unread
    try:
        before = version_in(git(root, "show", "HEAD:" + VERSION_FILE))
    except OSError:
        before = None
    now = version_in(staged)
    if now is not None and now == before:
        return None
    #A RELEASE WITH WORK STILL OPEN (2026-10-25). The owner: 1.0.6 "is released only when every line here is closed" -
    #and nothing refused a version change while lines stood open; only the per-push approval stood between. Asked of
    #the queue this commit stages; the owner's own switch is the one way past.
    items, unread = open_work(root)
    if unread is not None:
        return ("REFUSING: this commit makes version %s, and the OUTSTANDING.md it stages could not be read (%s) - "
                "UNKNOWN is not empty" % (now, unread))
    if items:
        switch = _owner_switch()
        if not switch.allowed(RELEASE_WITH_OPEN_WORK):
            return ("REFUSING: this commit makes version %s while the OUTSTANDING.md it stages lists %d open item(s), "
                    "the first: %s. A release ships when that queue is empty; shipping one anyway is the owner's "
                    "decision, made with their own switch %s=1 - never with --no-verify."
                    % (now, len(items), items[0][:100], switch.name(RELEASE_WITH_OPEN_WORK)))
    try:
        with open(os.path.join(root, RECORD), encoding="utf-8") as handle:
            record = json.load(handle)
    except (OSError, ValueError) as unread:
        record, why = None, "there is no readable record of a window check (%s)" % unread
    try:
        measured = subject(root, staged=True)
    except OSError as unread:
        return "REFUSING: this commit makes version %s, and what it stages could not be read (%s)" % (now, unread)
    if record is not None:
        if record.get("verdict") != "passed":
            why = "the last window check did not pass"
        elif record.get("version") != now:
            why = "the last window check passed for version %s, not %s" % (record.get("version"), now)
        elif record.get("subject") != measured:
            why = ("the last window check passed over other src/ and lib/ than this commit stages (it measured %s, "
                   "this is %s)" % (str(record.get("subject"))[:12], measured[:12]))
        else:
            return None
    return ("REFUSING: this commit makes version %s, and %s. A release is driven through its own window before it "
            "ships: package.ps1 runs `python -B tools/window_check.py <the staged build>`, which writes %s when the "
            "build holds - then stage it with this commit." % (now, why, RECORD))


def classpath(app):
    """(the build's jars and classes, None), or (None, why there are none)."""
    jar = os.path.join(app, "CTRMap-F5.jar")
    if os.path.isfile(jar):
        lib = os.path.join(app, "lib")
        main = jar
    elif os.path.isfile(os.path.join(app, "ctrmap", "CtrmapMainframe.class")):
        lib = os.path.join(ROOT, "lib")
        main = app
    else:
        return None, "%s holds neither CTRMap-F5.jar nor ctrmap/CtrmapMainframe.class" % app
    jars = [os.path.join(lib, name) for name in ("jogl-all.jar", "gluegen-rt.jar", "jogl-all-natives-windows-amd64.jar",
                                                "gluegen-rt-natives-windows-amd64.jar")]
    absent = [j for j in jars if not os.path.isfile(j)]
    if absent:
        return None, "the build's libraries are missing: %s" % ", ".join(absent)
    return [main] + jars, None


def built_version(main):
    """The version the build carries, read from its own copy of version.properties, or None."""
    name = "ctrmap/resources/version.properties"
    if os.path.isdir(main):
        try:
            with open(os.path.join(main, name), encoding="utf-8") as handle:
                return version_in(handle.read())
        except OSError:
            return None
    import zipfile
    try:
        with zipfile.ZipFile(main) as jar:
            return version_in(jar.read(name).decode("utf-8", "replace"))
    except (OSError, KeyError, zipfile.BadZipFile):
        return None


def check(app, workspace, game, keep):
    """Drives the build twice and judges it; writes RECORD on a pass. The exit code."""
    cp, why = classpath(app)
    if why:
        print("REFUSING: " + why)
        return 2
    with open(os.path.join(ROOT, VERSION_FILE), encoding="utf-8") as handle:
        tree_version = version_in(handle.read())
    if built_version(cp[0]) != tree_version:
        print("REFUSING: the build carries version %s and this tree %s - a pass would be recorded against a tree "
              "that is not the one measured" % (built_version(cp[0]), tree_version))
        return 2
    for label, folder in (("workspace", workspace), ("game", game)):
        if not folder or not os.path.isdir(folder):
            print("REFUSING: the %s folder %r is not there to copy" % (label, folder))
            return 2
    home = replant.jdk()
    if not home:
        print("REFUSING: no JDK found to compile the harness with")
        return 2
    try:
        measured = subject(ROOT, staged=False)
    except OSError as unread:
        print("REFUSING: what this build was made from could not be read (%s)" % unread)
        return 2
    scratch = tempfile.mkdtemp(prefix="ctrmap_window_check_")
    refused = []
    try:
        print("copying the workspace and the game into %s (read, never written)" % scratch)
        ws = os.path.join(scratch, "ws")
        shutil.copytree(workspace, ws)
        game_copy = os.path.join(scratch, "game", os.path.basename(os.path.normpath(game)))
        shutil.copytree(game, game_copy)
        classes = os.path.join(scratch, "harness")
        sources = [os.path.join(HERE, "window_check", n) for n in ("Driver.java", "MemPrefs.java")]
        done = bounded.run([os.path.join(home, "bin", "javac.exe" if os.name == "nt" else "javac"), "-nowarn", "-encoding", "UTF-8", "-cp",
                            os.pathsep.join(cp), "-d", classes] + sources, capture_output=True, text=True,
                           errors="replace", timeout=600, creationflags=NO_WINDOW)
        if done.returncode != 0:
            print("REFUSING: the harness does not compile against this build:" + chr(10) + done.stdout + done.stderr)
            refused.append("the harness does not compile against this build")
            return 1
        java = os.path.join(home, "bin", "java.exe" if os.name == "nt" else "java")
        said = []
        for mode, second in (("load", False), ("trap", True)):
            code, text = hidden_desktop.run(
                [java, "-Xmx1024m", "-Djava.util.prefs.PreferencesFactory=windowcheck.MemPrefs$Factory", "-cp",
                 os.pathsep.join([classes] + cp), "windowcheck.Driver", scratch, mode, ws, game_copy],
                cwd=scratch, timeout=480)
            with open(os.path.join(scratch, "run-%s.txt" % mode), "w", encoding="utf-8") as handle:
                handle.write(text or "")
            name = "%s launch (%s)" % ("second" if second else "first", mode)
            said.append(summary(name, text or ""))
            print(said[-1])
            problems = judge(text or "", second)
            if code is None:
                problems.append("the launch outlived its 480 s and was ended")
            refused.extend("%s: %s" % (name, p) for p in problems)
        if refused:
            print("REFUSING THE RELEASE - driven through its own window, it did not hold:")
            for line in refused:
                print("  " + line)
            print("what each launch said is kept in %s" % scratch)
            return 1
        with open(os.path.join(ROOT, RECORD), "w", encoding="utf-8", newline="\n") as handle:
            json.dump({"verdict": "passed", "version": tree_version, "subject": measured, "launches": said}, handle,
                      indent=1)
            handle.write("\n")
        print("OK: the window came up, held still, listed every zone and opened one, twice, and a zone tool's way back "
              "was on screen. Recorded in %s against this tree's src/ and lib/ (%s)." % (RECORD, measured[:12]))
        return 0
    finally:
        if not keep and not refused:
            shutil.rmtree(scratch, ignore_errors=True)


def main(argv):
    words = argv[1:]
    unread = cli_words.refused(words, flags=("--workspace", "--game", "--keep", "--release-commit"),
                               valued=("--workspace", "--game"), most=1)
    if unread:
        print(unread)
        return 2
    if "--release-commit" in words:
        if len(words) != 1:
            print("REFUSING: --release-commit takes nothing else - it is the commit's question")
            return 2
        why = release_commit(ROOT)
        if why:
            print(why)
            return 1
        return 0
    named = {flag: words[words.index(flag) + 1] for flag in ("--workspace", "--game") if flag in words}
    bare = [w for at, w in enumerate(words) if not w.startswith("--") and (at == 0 or words[at - 1] not in named)]
    if len(bare) != 1:
        print("REFUSING: name the build to check - a folder holding CTRMap-F5.jar and lib/, or build/classes")
        return 2
    if "--workspace" in named and "--game" in named:
        workspace, game = named["--workspace"], named["--game"]
    else:
        folders, why = owners_folders()
        if why:
            print("REFUSING: " + why + " - name them with --workspace and --game")
            return 2
        workspace, game = named.get("--workspace", folders[0]), named.get("--game", folders[1])
    return check(os.path.abspath(bare[0]), workspace, game, "--keep" in words)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
