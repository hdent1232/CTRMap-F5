"""A NetBeans generated block is refused by WHAT CHANGED, whatever wrote it (F194).

The owner's rule: never hand-edit a NetBeans initComponents block. `.claude/hooks/
guard_generated_code.py` read only an Edit's or a Write's text, so `sed -i` over a form, a script
handed the file and an `edits[]` batch all passed. `tools/guard/generated_code.py` now declares
the rule AT_WRITE_CHANGE, and `bundle_rules.ask_changes` - the one door the write channel and the
held-writes channel both ask - judges the file before and after. Asked here with the real
AboutDialog.java form, through that door:

  instance   a change inside initComponents is a finding of `generated_code`
  elsewhere  so is a block that would be gone, and the hook refuses an edits[] batch inside it
  evasion    a change OUTSIDE the blocks, or one of line endings only, is not - a rule that
             refuses every edit to a form file would be switched off within the day
"""
import importlib.util
import io
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FORM = "src/ctrmap/humaninterface/AboutDialog.java"


def load(name, rel):
    """A module by its path in THIS repository - never by a name another copy may answer."""
    folder = os.path.dirname(os.path.join(HERE, rel))
    if folder not in sys.path:
        sys.path.insert(0, folder)
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, rel))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AGeneratedBlockIsJudgedByWhatChanged(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.rules = load("ctrmap_bundle_rules", ".claude/bundle-hooks/bundle_rules.py")
        with io.open(os.path.join(HERE, FORM), encoding="utf-8", newline="") as handle:
            cls.before = handle.read()
        assert "//GEN-BEGIN:initComponents" in cls.before, "control: the form has its block"

    def said(self, after):
        worse = self.rules.ask_changes(HERE, {FORM: (self.before, after)})
        return [line for name, (_w, _n, found) in worse.items() if name == "generated_code" for line in found]

    def test_a_change_inside_the_block_is_refused_whatever_wrote_it(self):
        inside = self.before.replace("versionLabel = new javax.swing.JLabel();",
                                     "versionLabel = new javax.swing.JLabel(\"x\");", 1)
        self.assertNotEqual(inside, self.before, "control: the probe changed the block")
        said = self.said(inside)
        self.assertTrue(said and "initComponents block would change" in said[0],
                        "a change inside initComponents passed the change door: %r" % said)

    def test_a_block_that_would_be_gone_is_refused(self):
        start = self.before.index("//GEN-BEGIN:initComponents")
        end = self.before.index("//GEN-END:initComponents") + len("//GEN-END:initComponents")
        said = self.said(self.before[:start] + self.before[end:])
        self.assertTrue(any("initComponents block would be gone" in s for s in said), said)

    def test_the_hook_refuses_an_edits_batch_inside_the_block(self):
        hook = load("ctrmap_guard_generated_code", ".claude/hooks/guard_generated_code.py")
        deny, why = hook.decide({"cwd": HERE, "tool_input": {"file_path": FORM, "edits": [
            {"old_string": "versionLabel = new javax.swing.JLabel();", "new_string": "x();"}]}})
        self.assertTrue(deny, "an edits[] batch inside initComponents, its path relative to the "
                              "call's cwd, was allowed")

    def test_a_change_outside_the_blocks_or_of_line_endings_only_passes(self):
        outside = self.before + "\n// a comment after the class\n"
        self.assertEqual(self.said(outside), [], "an edit outside every block was refused")
        flipped = self.before.replace("\r\n", "\n").replace("\n", "\r\n")
        self.assertEqual(self.said(flipped), [], "line endings alone were taken for an edit")


if __name__ == "__main__":
    unittest.main()
