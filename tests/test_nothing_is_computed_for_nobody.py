"""Nothing in the program is computed for nobody (census L.41).

Every click in the 3D view allocated a 1920x1080 image - 7.9 MiB - and a Graphics on it that was never
read and never disposed, three lines of statement-level dead code that DuplicateWorkTest's class-level
reachability walk cannot see. MEASURED 2026-10-12 with the scan below: 24 more locals in the tree were
declared and never named again - a matrix file built for nobody in GeometryForker, a polygon list in
Picking, a trainer's name and class composed and dropped in the trainer editor, and stream fields
read into names nothing used. The dead ones are gone; a field a reader steps over is read as a
statement, so the stream moves exactly as it did and nothing pretends to keep it.

Asked here, by shape, over every production source:
  unread    a local declared and never named again in its method
  graphics  a Graphics a method obtains is disposed by that method
Each has a control - the census's own three lines among them - so a scan reading nothing fails.
"""
import io
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _owner_switches  # noqa: E402,F401  - the owner's settings out of this process, before any hook can read one
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
HERE = _tree_root.require(__file__)
SRC = os.path.join(HERE, "src", "ctrmap")

DECL = re.compile(r"^[ \t]{2,}(?:final\s+)?([A-Za-z_][\w.]*(?:<[^;=()]*>)?(?:\[\])*)\s+([a-z_]\w*)\s*=\s*[^=]", re.M)
NOT_A_TYPE = {"return", "else", "new", "throw", "case", "assert", "yield"}
TYPE_HEAD = re.compile(r"\b(?:class|interface|enum)\s+\w+[^;{}]*$|\bnew\s+[\w.<>\[\], ]+\([^;{}]*\)\s*$")
GRAPHICS = re.compile(r"\b(?:java\.awt\.)?Graphics(?:2D)?\s+(\w+)\s*=\s*[^;]*\.(?:getGraphics|createGraphics)\(\)")

#: The census's three lines, as they stood in H3DRenderingPanel.cycleSelection.
L41 = ("class P {\n\tpublic void cycleSelection(MouseEvent e) {\n"
       "\t\tBufferedImage out = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);\n"
       "\t\tGraphics g = out.getGraphics();\n\t\tg.setColor(Color.BLUE);\n"
       "\t\tCM3DComponents.forEach((t) -> {\n\t\t\tt.doSelectionLoop(e, this);\n\t\t});\n\t}\n}\n")


def strip(src):
    """`src` with comments and the insides of string and char literals blanked, line numbers kept."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            end = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in src[i:end]))
            i = end
        elif c in "\"'":
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == "\\" else 1
            out.append(c + " " * max(0, j - i - 1) + c)
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _header(text, brace):
    k = brace - 1
    while k >= 0 and text[k] not in ";{}":
        k -= 1
    return text[k + 1:brace].strip()


def method_body(text, pos):
    """(start, end) of the method body around `pos` - the block just inside its innermost class body -
    or None when `pos` is in a class body itself: a field, which this does not judge."""
    opens = []
    for k in range(pos):
        if text[k] == "{":
            opens.append(k)
        elif text[k] == "}" and opens:
            opens.pop()
    if not opens or TYPE_HEAD.search(_header(text, opens[-1])):
        return None
    start = next((opens[i] for i in range(len(opens) - 1, 0, -1) if TYPE_HEAD.search(_header(text, opens[i - 1]))), None)
    if start is None:
        return None
    depth = 0
    for k in range(start, len(text)):
        depth += text[k] == "{"
        depth -= text[k] == "}"
        if depth == 0:
            return start, k
    return start, len(text)


def unread_locals(source):
    """[(line, name)] of every local `source` declares and never names again in its method."""
    text = strip(source)
    found = []
    for m in DECL.finditer(text):
        if m.group(1) in NOT_A_TYPE:
            continue
        span = method_body(text, m.start(2))
        if span and len(re.findall(r"(?<![\w.])" + re.escape(m.group(2)) + r"\b", text[span[0]:span[1]])) <= 1:
            found.append((text.count("\n", 0, m.start()) + 1, m.group(2)))
    return found


def undisposed_graphics(source):
    """[(line, name)] of every Graphics a method obtains and does not dispose."""
    text = strip(source)
    found = []
    for m in GRAPHICS.finditer(text):
        span = method_body(text, m.start(1))
        if span and (m.group(1) + ".dispose()") not in text[span[0]:span[1]]:
            found.append((text.count("\n", 0, m.start()) + 1, m.group(1)))
    return found


def production_sources():
    for dirpath, dirnames, files in os.walk(SRC):
        dirnames[:] = [d for d in dirnames if d != "tests"]
        for name in sorted(files):
            if name.endswith(".java"):
                path = os.path.join(dirpath, name)
                with io.open(path, encoding="utf-8") as handle:
                    yield os.path.relpath(path, SRC).replace(os.sep, "/"), handle.read()


class NothingIsComputedForNobody(unittest.TestCase):

    def test_the_scans_see_the_census_lines_and_pass_what_is_used(self):
        self.assertEqual([n for _, n in undisposed_graphics(L41)], ["g"],
                         "CONTROL: the census's own undisposed Graphics was not found")
        used = L41.replace("g.setColor(Color.BLUE);", "g.setColor(Color.BLUE);\n\t\tg.dispose();\n\t\tshow(out);")
        self.assertEqual(undisposed_graphics(used), [], "CONTROL: a disposed Graphics was reported")
        ctl = ("class C {\n\tfinal int field = 1;\n\tvoid a() {\n\t\tint unread = 3;\n\t\tint used = 4;\n"
               "\t\tfoo(used);\n\t\tRunnable r = new Runnable() {\n\t\t\tint anon = 1;\n"
               "\t\t\tpublic void run() {\n\t\t\t\tint x = 5;\n\t\t\t\tbar(x, \"unread\");\n\t\t\t}\n\t\t};\n"
               "\t\tr.run();\n\t}\n}\n")
        self.assertEqual([n for _, n in unread_locals(ctl)], ["unread"],
                         "CONTROL: the unread local was missed, or a field, a used local or a string was reported")

    def test_no_local_is_computed_for_nobody(self):
        found, scanned = [], 0
        for rel, source in production_sources():
            scanned += 1
            found += ["%s:%d %s" % (rel, line, name) for line, name in unread_locals(source)]
        self.assertGreater(scanned, 200, "CONTROL: %d production sources read" % scanned)
        self.assertEqual(found, [], "locals declared and never read - delete them, or read a skipped "
                                    "stream field as a statement: %s" % found)

    def test_every_graphics_a_method_takes_it_disposes(self):
        found = []
        for rel, source in production_sources():
            found += ["%s:%d %s" % (rel, line, name) for line, name in undisposed_graphics(source)]
        self.assertEqual(found, [], "a Graphics obtained and never disposed: %s" % found)


if __name__ == "__main__":
    unittest.main()
