"""NOTHING THE SESSION STARTS TAKES THE OWNER'S FOREGROUND - .claude/hooks/guard_foreground.py, asked.

PAID FOR 2026-10-11: suites, a test's own window and a detached console came up on the owner's desktop
while they worked; seen or not, each took the keyboard - a video paused, a screenshot was deleted, a
window and its edits were lost. The guard refuses, before it runs, a command that starts the editor's
Java on the owner's desktop, makes a process through WMI, or starts a program in a window of its own -
and lets the same command through hidden_desktop.py, and the ordinary commands, alone.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)

HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, ".claude", "hooks"))
import guard_foreground  # noqa: E402  - the guard under test


def asked(command, tool="Bash"):
    deny, _why = guard_foreground.decide({"tool_name": tool, "tool_input": {"command": command}})
    return deny


class NothingStartedTakesTheOwnersForeground(unittest.TestCase):

    def setUp(self):
        os.environ.pop(guard_foreground.BYPASS, None)

    def test_the_editors_java_started_directly_is_refused(self):
        for command in ('java -cp "build/classes;lib/jogl-all.jar" ctrmap.tests.ForkGuardsTest x',
                        'javaw -cp build/classes ctrmap.CtrmapMainframe',
                        '"C:/Program Files/Eclipse Adoptium/jdk-17/bin/java.exe" -cp build/classes ctrmap.tests.GfHashTest',
                        'cd /c/x && java -jar dist/CTRMap.jar'):
            with self.subTest(command):
                self.assertTrue(asked(command), "the editor's Java was let start on the owner's desktop: %s" % command)

    def test_the_editors_java_named_ANY_way_the_shell_accepts_is_refused(self):
        """PAID FOR 2026-10-14: the program was read only as the literal word java at the start of a command, so
        `J=java; "$J" -cp build/classes ctrmap.tests.HandedGameTest` - run by this session, several times - started
        the editor's suites on the owner's desktop, and so would an assignment or a wrapper in front of java. A
        program the guard cannot read is not one it may call harmless."""
        for command in ('J=java; "$J" -cp build/classes ctrmap.tests.HandedGameTest',
                        '"$JAVA" -cp build/classes ctrmap.tests.GfHashTest', '${J} -cp build/classes ctrmap.tests.GfHashTest',
                        '$(which java) -cp build/classes ctrmap.tests.GfHashTest', '`which java` -cp build/classes ctrmap.CtrmapMainframe',
                        'FOO=1 java -cp build/classes ctrmap.tests.GfHashTest', 'X="a b" java -cp build/classes ctrmap.tests.GfHashTest',
                        'exec java -cp build/classes ctrmap.tests.GfHashTest', 'env X=1 java -cp build/classes ctrmap.tests.GfHashTest',
                        'nohup java -cp build/classes ctrmap.tests.GfHashTest', '& $java -cp build/classes ctrmap.tests.GfHashTest',
                        'python -B tools/guard/hidden_desktop.py --stream -- x; "$J" -cp build/classes ctrmap.tests.GfHashTest'):
            with self.subTest(command):
                self.assertTrue(asked(command), "the editor's Java, named another way, was let start: %s" % command)

    def test_a_variable_program_through_the_unattended_desktop_or_running_no_editor_class_is_not(self):
        for command in ('python -B tools/guard/hidden_desktop.py --stream -- "$J" -cp build/classes ctrmap.tests.GfHashTest',
                        '"$PY" -B tools/x.py', 'J=java; "$J" -version', 'FOO=1 python -B x.py ctrmap'):
            with self.subTest(command):
                self.assertFalse(asked(command), "a command that starts no editor Java here was refused: %s" % command)

    def test_the_same_java_through_the_unattended_desktop_is_not(self):
        self.assertFalse(asked('python -B tools/guard/hidden_desktop.py --stream -- java -cp build/classes'
                               ' ctrmap.tests.ForkGuardsTest x'),
                         "the editor's Java started through hidden_desktop.py was refused")

    def test_a_process_made_through_wmi_is_refused(self):
        self.assertTrue(asked("$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments"
                              " @{ CommandLine = 'cmd.exe /c python x.py' }", tool="PowerShell"),
                        "a process made through WMI was let start on the owner's desktop")

    def test_a_program_started_in_a_window_of_its_own_is_refused(self):
        for command in ('Start-Process notepad', 'cmd /c start "" /wait notepad'):
            with self.subTest(command):
                self.assertTrue(asked(command, tool="PowerShell"),
                                "a program started in a window of its own was let through: %s" % command)

    def test_a_window_started_WHEREVER_a_command_begins_is_refused(self):
        """Until 2026-10-12 a command that BEGAN with git or python was let through whatever followed it,
        so `python x.py & start notepad` - a window - went through unasked. Every place a command can
        begin is asked: after a separator, a bracket or `=`, on a new line, and at the head of a command
        line handed to another shell."""
        for command in ('python -B a.py & start notepad', 'git status; start notepad',
                        'git status\nstart notepad', 'cmd.exe /k start notepad', '$p = start notepad -PassThru',
                        'powershell -NoProfile -Command "Start-Process notepad"', "bash -c 'start notepad'",
                        'Get-Item x | % { Start-Process $_ }', 'start notepad'):
            with self.subTest(command):
                self.assertTrue(asked(command, tool="PowerShell"),
                                "a window started after a git or python command was let through: %s" % command)

    def test_the_WORD_start_in_text_a_command_carries_goes_through(self):
        """MEASURED 2026-10-12: a ledger note quoting "4 of 4 scripts that start the JDK" was refused as a
        program started in a window, because `start` was matched after any space. It is a command only
        where a command begins; in a message, a note or a pattern it is a word."""
        for command in ('cd /c/x && python -B tool.py done id "4 of 4 scripts that start the JDK move"',
                        'cd /c/x && git commit -m "the sweep must start again on the final tree"',
                        'echo the run will start now', 'grep -n "Start-Process" tools/guard/x.py'):
            with self.subTest(command):
                self.assertFalse(asked(command), "prose naming the word start was refused: %s" % command)

    def test_ordinary_commands_go_through(self):
        for command in ("python -B -m unittest test_one_battery", "git status --short",
                        "powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1",
                        "grep -n java src/ctrmap/Ui.java", "javac -version"):
            with self.subTest(command):
                self.assertFalse(asked(command), "an ordinary command was refused: %s" % command)


if __name__ == "__main__":
    unittest.main()
