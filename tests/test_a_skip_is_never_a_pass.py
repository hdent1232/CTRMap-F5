"""A suite that skipped is never counted as one that passed (F144).

MEASURED 2026-10-06 by running all 136 registered suites with no game data: 50 exited 0 after
printing that they had skipped their data checks - a skip line, then ALL PASS, then exit 0 - and
test.ps1 counted every one as a pass; six of them carried a javadoc promising the opposite. Every
skip now goes through ctrmap.tests.Skip: a whole-suite skip exits Skip.EXIT, a skipped section
prints Skip.MARK, test.ps1 counts both and never says ALL SUITES PASS over them, and
tools/guard/record_run.py does not count a skipped suite in `ran`.

Asked here, by shape: no suite prints a skip any other way; the constants test.ps1 reads are the
ones Skip writes; and the recorder, handed a battery's output, leaves a skipped suite out.
"""
import importlib.util
import io
import os
import re
import sys
import types
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTS = os.path.join(HERE, "src", "ctrmap", "tests")

#: A print whose message reads as "I did not run this": a `skip` opening it, or the phrasings the
#: audit found carrying the same meaning without the word.
SKIP_SAID = re.compile(r'System\.out\.print(?:ln|f)?\(\s*"\(?\s*(?:skip(?:ped)?\b|SKIP\b)'
                       r'|System\.out\.print(?:ln|f)?\([^;]*"[^"]*(?:suite skipped|checks skipped|'
                       r'not exercised|cases only)', re.I)


def printed_skips(source):
    """[line] of every print in `source` that says a skip without going through Skip."""
    return [n + 1 for n, line in enumerate(source.splitlines()) if SKIP_SAID.search(line)]


class NoSuiteSaysASkipAnyOtherWay(unittest.TestCase):

    def test_the_scan_finds_a_printed_skip_and_passes_one_through_Skip(self):
        self.assertTrue(printed_skips('    System.out.println("  skip: no dump at " + d);'),
                        "CONTROL: a printed skip was not found, so the scan below finds nothing")
        self.assertTrue(printed_skips('    System.out.println("(corpus not found - synthetic cases only)");'))
        self.assertFalse(printed_skips('    Skip.section("no dump at " + d);'),
                         "CONTROL: a skip through Skip was reported")
        self.assertFalse(printed_skips('    System.out.println("  ok: 3 meshes, 2 skipped (no pos)");'),
                         "CONTROL: a count of skipped cases inside a check was reported as a skip")

    def test_every_suite_skips_through_Skip(self):
        found, scanned, through = [], 0, 0
        for name in sorted(os.listdir(TESTS)):
            if name.endswith(".java") and name != "Skip.java":
                with io.open(os.path.join(TESTS, name), encoding="utf-8") as handle:
                    source = handle.read()
                scanned += 1
                through += "Skip.s" in source
                found += ["%s:%d" % (name, n) for n in printed_skips(source)]
        self.assertTrue(scanned > 100 and through > 40,
                        "CONTROL: %d suite sources read, %d skipping through Skip - a scan of the "
                        "wrong folder passes by reading nothing" % (scanned, through))
        self.assertEqual(found, [], "SKIP: these suites print a skip that test.ps1 cannot count, "
                         "so the suite can pass having asserted nothing: %s" % found)


class TheBatteryReadsWhatSkipWrites(unittest.TestCase):

    def constants(self):
        with io.open(os.path.join(TESTS, "Skip.java"), encoding="utf-8") as handle:
            text = handle.read()
        code = re.search(r"static final int EXIT = (\d+);", text)
        mark = re.search(r'static final String MARK = "([^"]+)";', text)
        self.assertTrue(code and mark, "Skip.java no longer declares EXIT and MARK where this reads")
        return int(code.group(1)), mark.group(1).strip()

    def test_test_ps1_counts_the_exit_code_and_the_marker(self):
        code, mark = self.constants()
        with io.open(os.path.join(HERE, "test.ps1"), encoding="utf-8") as handle:
            battery = handle.read()
        self.assertIn("$exit -eq %d" % code, battery,
                      "BATTERY: test.ps1 does not count exit %d as a skipped suite" % code)
        self.assertIn("'^\\s*%s " % mark, battery,
                      "BATTERY: test.ps1 does not read the marker %r a skipped section prints" % mark)
        self.assertIn('Write-Host ("SKIPPED: "', battery,
                      "BATTERY: test.ps1 does not name the skipped suites where record_run reads them")


class TheRecordLeavesASkippedSuiteOut(unittest.TestCase):

    BATTERY = "\n".join([
        "--- Alpha suite", "    ALL PASS",
        "--- Beta suite", "    SKIPPED: no dump at x", "    SKIPPED - UNKNOWN, NOT PASSED",
        "--- Gamma suite", "    ALL PASS", "    1 section(s) did not run - SKIPPED: no display",
        "--- Delta suite", "    ALL PASS",
        "", "SKIPPED: Beta suite", "PARTLY SKIPPED: Gamma suite",
        "NO FAILURES, AND NOT ALL RAN: 1 suite(s) skipped, 1 partly  (9s)", ""])

    def record(self):
        spec = importlib.util.spec_from_file_location(
            "record_run_for_skips", os.path.join(HERE, "tools", "guard", "record_run.py"))
        recorder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(recorder)
        battery = self.BATTERY

        class Done(object):
            returncode, stdout, stderr = 0, battery, ""

        recorder.subprocess = types.SimpleNamespace(run=lambda *a, **k: Done(),
                                                    TimeoutExpired=Exception)
        fakes = {"commit_guard": types.SimpleNamespace(source_digest=lambda *a, **k: "x" * 64),
                 "magnitude": types.SimpleNamespace(SERIES=os.path.join(HERE, "nowhere.json"))}
        held = {name: sys.modules.get(name) for name in fakes}
        sys.modules.update(fakes)
        try:
            return recorder.run()
        finally:
            for name, module in held.items():
                if module is None:
                    sys.modules.pop(name, None)
                else:
                    sys.modules[name] = module

    def test_a_skipped_suite_is_not_counted_and_a_partly_skipped_one_is_named(self):
        record = self.record()
        self.assertIsNotNone(record, "the recorder refused a run it should have read")
        self.assertEqual(record["suites_skipped"], ["Beta suite"])
        self.assertEqual(record["suites_partly_skipped"], ["Gamma suite"])
        self.assertEqual(record["ran"], 3, "RECORD: a skipped suite was counted as passed - ran %s"
                         % record["ran"])
        self.assertFalse(record["complete"], "a run with skips was recorded as complete")


if __name__ == "__main__":
    unittest.main()
