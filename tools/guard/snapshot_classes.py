# -*- coding: utf-8 -*-
"""The production classes that predate the "say what you are not" rule.

Everything in this list is grandfathered; everything added after it has to name the
nearest existing class and why it could not be used. Regenerating the list is a
deliberate act - it forgives every class written since, which is exactly the thing
the rule is trying to stop being automatic.

    python tools/guard/snapshot_classes.py
"""
import io
import os

LF = chr(10)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "src")
OUT = os.path.join(ROOT, "tools", "guard", "classes.txt")

found = []
for dirpath, dirnames, filenames in os.walk(os.path.join(SRC, "ctrmap")):
    if os.path.basename(dirpath) == "tests":
        dirnames[:] = []
        continue
    for name in filenames:
        if name.endswith(".java"):
            rel = os.path.relpath(os.path.join(dirpath, name), SRC).replace(os.sep, "/")
            found.append(rel)

found.sort()
io.open(OUT, "w", encoding="utf-8", newline=LF).write(LF.join(found) + LF)
print("%d production class(es) recorded in %s" % (len(found), OUT))
