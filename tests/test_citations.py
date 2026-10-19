"""The citation guard as the plant ledger sees it: tools/guard/citations.py, judged from outside.

CTRMap's battery already drives this guard from CommitGuardTest, in Java. This file exists because
the bundle's commit gate proves a guard through tests/plants.json, whose plants are driven by
Python's unittest - and a plant that is driven again on every change to its own file is a stronger
proof than one made once by hand, which is all the Java section can offer.

ONE TEST PER PLANT SITE, because the ledger holds a guard to three:

  instance   the fix itself undone: a tree this test builds, citing a session-folder path nothing
             declares, must be refused.
  elsewhere  the same defect arriving somewhere other than the code that was fixed: THIS tree must
             hold no undeclared citation, so a declaration renamed away in the manifest is caught.
  evasion    the cheapest way past a guard like this is to look at less, and a scanner that reads
             nothing reports a clean tree - so the scan must still see citations this tree is
             known to hold.

Paths are built from the guard's own SESSION_DIR so that renaming the session folder cannot strand
this file on the old name while it goes on passing.
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded         # noqa: E402  - a deadline ends the child's whole tree
HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools", "guard"))
import citations  # noqa: E402  - the guard under test

LF = chr(10)
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
SESSION = citations.SESSION_DIR


class ACitationACheckoutCannotOpenIsRefused(unittest.TestCase):
    """A path this repository cites into the session folder must be declared, or it is refused."""

    def _tree(self, cited, declared):
        """A throwaway git repository whose one document cites `cited`, under `declared`."""
        base = tempfile.mkdtemp(prefix="citations-test-")
        self.addCleanup(shutil.rmtree, base, True)
        root = os.path.join(base, "repo")
        os.makedirs(os.path.join(root, "tools", "guard"))
        os.makedirs(os.path.join(root, "docs"))
        bounded.run(["git", "init", "-q", root], capture_output=True, timeout=120,
                    creationflags=NO_WINDOW)
        with io.open(os.path.join(root, "docs", "note.md"), "w", encoding="utf-8") as handle:
            handle.write("The evidence is in `%s`.%s" % (cited, LF))
        with io.open(citations.manifest_path(root), "w", encoding="utf-8") as handle:
            handle.write(json.dumps(declared) + LF)
        return root

    def test_an_undeclared_citation_in_a_built_tree_is_refused(self):
        path = SESSION + "/_state/nothing-declares-this.json"
        problems, _unknowns = citations.judge(self._tree(path, {}))
        self.assertTrue(any("UNDECLARED" in p and path in p for p in problems),
                        "an undeclared citation PASSED in a tree built to contain one: %r"
                        % (problems,))

    def test_a_document_says_on_the_line_that_an_outside_citation_is_outside(self):
        """F139: three docs cited probes from the owner's dump as if a clone could re-run them."""
        path = SESSION + "/_state/probe-output.json"
        declared = {path: {"status": "outside", "why": "scan output from the owner's dump, never committed"}}
        problems, _unknowns = citations.judge(self._tree(path, declared))
        self.assertTrue(any("UNMARKED" in p and path in p and "note.md:1" in p for p in problems),
                        "a doc citing an outside path without saying so PASSED: %r" % (problems,))
        root = self._tree(path, declared)
        with io.open(os.path.join(root, "docs", "note.md"), "w", encoding="utf-8") as handle:
            handle.write("The evidence is in `%s`, outside the repository.%s" % (path, LF))
        problems, _unknowns = citations.judge(root)
        self.assertFalse(any("UNMARKED" in p for p in problems),
                         "a doc that says so was refused anyway: %r" % (problems,))

    def test_a_citation_in_any_text_file_is_seen_whatever_its_name(self):
        """F185: the scan kept nine suffixes, so a citation in a git hook - which has no suffix -
        or in a `.form` was never looked for. What it reads is decided by the file's own bytes."""
        root = self._tree(SESSION + "/_state/declared-nowhere.json", {})
        hidden = {".githooks/pre-push": SESSION + "/_state/cited-in-a-hook.json",
                  "src/Panel.form": SESSION + "/_state/cited-in-a-form.json"}
        for rel, path in hidden.items():
            full = os.path.join(root, rel)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with io.open(full, "w", encoding="utf-8") as handle:
                handle.write("see `%s`%s" % (path, LF))
        problems, _unknowns = citations.judge(root)
        for rel, path in sorted(hidden.items()):
            self.assertTrue(any("UNDECLARED" in p and path in p for p in problems),
                            "a citation in %s was never looked for: %r" % (rel, problems))

    def test_this_tree_holds_no_undeclared_citation(self):
        problems, _unknowns = citations.judge(HERE)
        #: A FACT ABOUT THE TREE ONLY. Whether a declared path is on the disk depends on the
        #: machine - a checkout has no session folder at all - so those verdicts are left to the
        #: command line, and this asks only what every clone can answer the same way.
        undeclared = [p for p in problems if p.startswith("UNDECLARED")]
        self.assertEqual(undeclared, [],
                         "this tree cites a session-folder path its manifest does not declare: %s"
                         % "; ".join(undeclared))

    def test_the_scan_sees_the_citations_this_tree_is_known_to_hold(self):
        found = citations.citations(HERE)
        self.assertIsNotNone(found, "this tree could not be enumerated, which is not a clean tree")
        for known in (SESSION + "/_state/mutation_baseline.json",
                      SESSION + "/_state/queue2/runtime/"):
            self.assertIn(known, found,
                          "the scan no longer sees %s, which this tree is known to cite - a scan "
                          "that looks at less reports a clean tree" % known)


if __name__ == "__main__":
    unittest.main()
