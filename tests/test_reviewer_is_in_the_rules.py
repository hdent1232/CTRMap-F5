"""An agent launch the hooks REQUIRE is written in the rule file that forbids agents (F178).

`.claude/bundle-install.json` declares the request ledger's reviewer, and the ledger then keeps a
turn open until a subagent has reviewed it - while CLAUDE.md section 2 forbids an agent without
the owner's yes and a price, and said nothing of the reviewer. A later session told "NO AGENTS"
could not end a turn. The yes and the price are now written beside the rule, with the switch a
no-agent session sets; this holds the two together: while the reviewer is declared, CLAUDE.md
names it and names the switch BY THE NAME THE LEDGER READS, never a copy typed here.
"""
import importlib.util
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def ledger():
    folder = os.path.join(HERE, ".claude", "bundle-hooks")
    if folder not in sys.path:
        sys.path.insert(0, folder)
    spec = importlib.util.spec_from_file_location("ledger_for_the_rules_test",
                                                  os.path.join(folder, "request_ledger.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TheDeclaredReviewerIsWrittenInTheRules(unittest.TestCase):

    def test_a_declared_reviewer_is_named_in_CLAUDE_md_with_its_switch(self):
        with open(os.path.join(HERE, ".claude", "bundle-install.json"), encoding="utf-8") as handle:
            declared = json.load(handle)
        module = ledger()
        if module.REVIEW_KEY not in declared:
            self.skipTest("no reviewer is declared, so no agent launch is required at a turn end")
        with open(os.path.join(HERE, "CLAUDE.md"), encoding="utf-8") as handle:
            rules = handle.read()
        self.assertIn('"%s"' % module.REVIEW_KEY, rules,
                      "RULES: the reviewer is declared and CLAUDE.md never mentions it")
        self.assertIn(module.NO_REVIEW + "=1", rules,
                      "RULES: CLAUDE.md does not name the switch the ledger reads (%s)"
                      % module.NO_REVIEW)


if __name__ == "__main__":
    unittest.main()
