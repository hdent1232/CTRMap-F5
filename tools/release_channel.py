"""THE PUBLISH INSTRUCTIONS FOR ONE PACKAGED VERSION - CORE OR NIGHTLY (2026-10-12).

The owner asked for a nightly channel: work pushed and downloadable at once, while core waits for it to be swept,
weighed and through the commit gate. Two channels, one decision, made here and nowhere else:

  core     x.y.z            the tag goes to master and the GitHub release is a full one - the one every copy's
                            updater is offered, because UpdateChecker reads only releases/latest.
  nightly  x.y.z-nightly.N  the tag goes to the nightly branch and the release is a PRE-release. GitHub never returns
                            a pre-release as releases/latest, so no copy's updater is offered it; whoever wants it
                            downloads it from the Releases page.

A NIGHTLY IS NAMED AFTER THE LAST CORE RELEASE IT IS AHEAD OF. AppVersion reads a version's three numbers and drops
its suffix, so a nightly called 1.0.4-nightly.1 would read as 1.0.4 itself - and the real 1.0.4, when it is cut, would
never be offered to the people running the nightly. Called 1.0.3-nightly.1, it reads as 1.0.3 and 1.0.4 is newer.

THE TAG IS NAMED IN THE PUSH (BatteryHygieneTest): --follow-tags carries only annotated tags, and every tag here is
lightweight. BOTH builds go on the release, by the two suffixes Updater.Flavour selects on.

NO BUILD WHOSE ZIP NAMES A PATH WITH A BACKSLASH IS PUBLISHED (2026-10-23). Compress-Archive in Windows PowerShell 5.1
wrote every name with one: measured on the 1.0.4 zips, 160 of 160 in the Windows build and 10 of 16 in the portable
one ("lib\\jogl-all.jar"). The updater unpacks with new File(dest, name), and off Windows that is ONE file named
"lib\\jogl-all.jar" beside an untouched lib/ - and 1.0.4 changed those jars. The names are read raw (orig_filename):
zipfile rewrites a backslash to "/" in every name it reports on Windows, so a check of namelist() passes there always.
A build this cannot open is refused too: a zip that could not be read is not one that was clean.

Usage: python -B tools/release_channel.py <version> <windows zip> <portable zip>"""
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cli_words  # noqa: E402  - a word this does not read stops the run (2026-10-10)

CORE = re.compile(r"^\d+\.\d+\.\d+$")
NIGHTLY = re.compile(r"^\d+\.\d+\.\d+-nightly\.\d+$")
#: Where each channel's commit and tag go on the remote. BOTH SEND HEAD - the commit `git tag` just named - never a
#: local branch by name (2026-10-23): 1.0.5 was packaged on a working branch whose local master was 4 commits behind
#: origin/master, so "git push origin master v1.0.5" would have sent that stale master (refused, or worse, a master
#: that does not hold the tagged commit) beside a tag on the real one. A HEAD that is not ahead of the remote master
#: is refused by git as a non-fast-forward, which is the right answer for a release cut from the wrong place.
BRANCH = {"core": "HEAD:refs/heads/master", "nightly": "HEAD:refs/heads/nightly"}


def channel(version):
    """'core', 'nightly', or None for a version that is neither - which nothing may publish."""
    if CORE.match(version):
        return "core"
    if NIGHTLY.match(version):
        return "nightly"
    return None


def instructions(version, win_zip, zip_):
    """The three commands that publish `version` on its channel: the tag, the push naming it, and the release."""
    kind = channel(version)
    if kind is None:
        raise ValueError("version %r is neither core (1.2.3) nor nightly (1.2.3-nightly.4)" % version)
    title = "CTRMap-F5 %s%s" % (version, " (nightly)" if kind == "nightly" else "")
    release = 'gh release create v%s %s %s --title "%s" --notes-file NOTES.md' % (version, win_zip, zip_, title)
    if kind == "nightly":
        release += " --prerelease"
    return ["git tag v%s" % version, "git push origin %s v%s" % (BRANCH[kind], version), release]


def unpublishable(zip_path):
    """Why this build may not be published - its names that hold a backslash, or that it could not be read - or []."""
    try:
        with zipfile.ZipFile(zip_path) as z:
            raw = [info.orig_filename for info in z.infolist()]
    except (OSError, zipfile.BadZipFile) as unread:
        return ["%s could not be read, so whether its names hold a backslash is unknown: %s" % (zip_path, unread)]
    bad = [name for name in raw if chr(92) in name]
    if bad:
        return ["%s names %d of its %d paths with a backslash, which off Windows unpack as one file each: %s"
                % (zip_path, len(bad), len(raw), bad[:3])]
    return []


def main(argv):
    unread = cli_words.refused(argv[1:], most=3)
    if unread or len(argv) != 4:
        print(unread or __doc__.splitlines()[-1])
        return 2
    refusals = unpublishable(argv[2]) + unpublishable(argv[3])
    if refusals:
        for why in refusals:
            print("REFUSING: %s" % why)
        return 2
    try:
        for line in instructions(argv[1], argv[2], argv[3]):
            print("  " + line)
    except ValueError as bad:
        print("REFUSING: %s" % bad)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
