#!/usr/bin/env python3
"""BUILD GENERAL SYSTEMS, NOT THE USER'S EXAMPLE.

The owner describes what THEY would do as an illustration. The deliverable is the capability
for the whole class, and this project has been corrected on it twice: CTRMap is XY, ORAS, SM
and USUM by design, the gamedef seam exists, and the owner's own game is one profile out of
five - not the subject.

The failure has a shape you can measure: a game's IDENTITY, hardcoded where the code should be
asking which game it is holding. `src/ctrmap/gamedef/` is where a game says who it is -
`OrasProfile` returning ORAS's title id is that seam working. The same literal anywhere else is
a piece of the editor that only works for the owner's copy.

MEASURED ON 2026-09-20: two sites outside the seam.

  * `formats/codepatch/ZoneLimitPatch.TITLE_ID` - the executable patcher, pinned to ORAS
  * `humaninterface/ZoneLoadingPanel` - the title id inside a help message

Both are real and neither is a one-line fix: the patcher would have to take its identity from
the profile, which changes behaviour and needs the owner to check it in the game. So this is a
CEILING, named and falling, rather than a ban that would fire on two known sites and get
raised. A THIRD one is refused.

Usage: python tools/guard/game_identity.py [root]
Exit 1 with reasons, 0 when no new site has appeared.
"""
import io
import os
import re
import sys

#: Where a game is allowed to say who it is.
SEAM = os.path.join("src", "ctrmap", "gamedef")

#: What a game's identity looks like. A 16-hex-digit 3DS title id, and the owner's own project
#: name - the two things that make a file work for one copy of one game.
_IDENTITY = re.compile(r"\b[0-9A-F]{8}0011[0-9A-F]{4}\b|Delta Emerald", re.I)

#: The two sites that existed when this was written. Each may only leave this list.
KNOWN = (
    "src/ctrmap/formats/codepatch/ZoneLimitPatch.java",
    "src/ctrmap/humaninterface/ZoneLoadingPanel.java",
)
CEILING = len(KNOWN)

SKIP_DIRS = ("build", "dist", ".git", "__pycache__", "node_modules", "wt", "lib", "tests")


def files(root):
    out = []
    src = os.path.join(root, "src")
    for dirpath, dirs, names in os.walk(src):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in names:
            if name.endswith(".java"):
                out.append(os.path.join(dirpath, name))
    return out


def sites(root):
    """Every production file outside the gamedef seam that hardcodes a game's identity."""
    found = []
    seam = os.path.normcase(os.path.join(root, SEAM))
    for path in files(root):
        if os.path.normcase(path).startswith(seam):
            continue
        try:
            body = io.open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            found.append((os.path.relpath(path, root).replace(os.sep, "/"), "could not read it"))
            continue
        hit = _IDENTITY.search(body)
        if hit:
            found.append((os.path.relpath(path, root).replace(os.sep, "/"), hit.group(0)))
    return found


def findings(root):
    """Every NEW place the editor is pinned to one game."""
    if not os.path.isdir(os.path.join(root, "src")):
        return ["there is no src/ under %s, so this could not look at the production code at "
                "all - which is not the same as finding nothing" % os.path.abspath(root)]
    found = sites(root)
    why = []
    for path, what in found:
        if path in KNOWN:
            continue
        why.append(
            "%s hardcodes a game's identity (%r) outside %s. BUILD GENERAL SYSTEMS, NOT THE "
            "USER'S EXAMPLE: this editor is XY, ORAS, SM and USUM by design, and the owner's "
            "copy is one profile of five. Ask the GameProfile which game it is holding, or - "
            "if this really is a per-game constant - put it in the profile beside the others."
            % (path, what, SEAM.replace(os.sep, "/")))
    if len(found) > CEILING:
        why.append("%d site(s) now pin the editor to one game, over the ceiling of %d. It may "
                   "only fall." % (len(found), CEILING))
    gone = [k for k in KNOWN if k not in [p for p, _ in found]]
    if gone:
        print("(%d known site(s) have been cleaned up: %s - lower KNOWN and the ceiling)"
              % (len(gone), ", ".join(gone)))
    return why


def main(argv):
    root = argv[1] if len(argv) > 1 else "."
    why = findings(root)
    found = sites(root) if os.path.isdir(os.path.join(root, "src")) else []
    if not why:
        print("%d known site(s) pin the editor to one game, none new (ceiling %d)"
              % (len(found), CEILING))
        return 0
    print("THE EDITOR IS BEING BUILT FOR ONE GAME:")
    for reason in why:
        print("  - " + reason)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
