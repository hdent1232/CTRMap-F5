"""CORE AND NIGHTLY: tools/release_channel.py says how one packaged version is published, and package.ps1 says nothing
of its own (2026-10-12, the owner's request for a nightly channel).

A core version is a full GitHub release, which every copy's updater is offered - UpdateChecker reads releases/latest.
A nightly is a PRE-release on the nightly branch, which GitHub never returns as releases/latest, so no updater is
offered it. The ways this goes wrong are each one line: a nightly published as a full release reaches every user
before it was swept or gated; a nightly pushed to master moves core; a suffix that is neither, accepted, publishes a
version nobody chose; and jpackage on Windows refuses a version with a suffix, so the bundle would not build.
"""
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _tree_root  # noqa: E402  - the project root, anchored: never counted (F216)
import bounded  # noqa: E402  - a deadline ends the child's whole tree

HERE = _tree_root.require(__file__)
sys.path.insert(0, os.path.join(HERE, "tools"))
import release_channel  # noqa: E402  - the one place a version's channel is decided

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def build(folder, name, *paths):
    """A stand-in build: a zip holding `paths` as written, a backslash kept raw (zipfile would make it "/")."""
    with zipfile.ZipFile(os.path.join(folder, name), "w") as z:
        for path in paths:
            info = zipfile.ZipInfo("x")
            info.filename = path
            z.writestr(info, "x")


def said(version, portable=("run.bat", "lib/jogl-all.jar"), make=True):
    """What the tool prints for `version` and two builds W.zip and P.zip, made here unless `make` is False."""
    folder = tempfile.mkdtemp(prefix="release-channel-")
    try:
        if make:
            build(folder, "W.zip", "CTRMap-F5/CTRMap-F5.exe")
            build(folder, "P.zip", *portable)
        done = bounded.run([sys.executable, "-B", os.path.join(HERE, "tools", "release_channel.py"), version,
                            "W.zip", "P.zip"], capture_output=True, text=True, timeout=60, creationflags=NO_WINDOW,
                           cwd=folder)
        return done.returncode, [line.strip() for line in (done.stdout or "").splitlines()]
    finally:
        shutil.rmtree(folder, ignore_errors=True)


class EachVersionIsPublishedOnItsOwnChannel(unittest.TestCase):

    def test_a_core_version_is_a_full_release_from_master(self):
        code, lines = said("1.0.4")
        self.assertEqual(code, 0, lines)
        self.assertIn("git push origin HEAD:refs/heads/master v1.0.4", lines,
                      "a core version did not send the tagged commit, with its tag, to master")
        self.assertFalse(any(line.startswith("git push origin master ") for line in lines),
                         "a core version pushes the LOCAL master by name, which need not hold the tagged commit: %s"
                         % lines)
        release = [line for line in lines if line.startswith("gh release create")]
        self.assertEqual(len(release), 1, "a core version did not say exactly one release: %s" % lines)
        self.assertIn("v1.0.4 W.zip P.zip", release[0], "the release does not carry both builds: %s" % release)
        self.assertNotIn("--prerelease", release[0], "a core release was hidden from every updater as a pre-release")

    def test_a_nightly_is_a_pre_release_from_the_nightly_branch(self):
        code, lines = said("1.0.3-nightly.1")
        self.assertEqual(code, 0, lines)
        self.assertIn("git push origin HEAD:refs/heads/nightly v1.0.3-nightly.1", lines,
                      "a nightly was not pushed to the nightly branch: %s" % lines)
        self.assertFalse(any("origin master" in line for line in lines), "a nightly moved master: %s" % lines)
        release = [line for line in lines if line.startswith("gh release create")]
        self.assertEqual(len(release), 1, lines)
        self.assertIn("v1.0.3-nightly.1 W.zip P.zip", release[0], "the nightly does not carry both builds")
        self.assertIn("--prerelease", release[0],
                      "a nightly was published as a full release, which every copy's updater is offered")

    def test_a_version_that_is_neither_is_refused(self):
        for version in ("1.0.4-beta", "1.0.4-nightly", "1.0", "v1.0.4", "1.0.4-nightly.1-x"):
            with self.subTest(version):
                code, lines = said(version)
                self.assertEqual(code, 2, "a version neither core nor nightly was published: %s %s" % (version, lines))
                self.assertFalse(any(line.startswith("gh release create") for line in lines), lines)
        self.assertEqual((release_channel.channel("2.0.0"), release_channel.channel("2.0.0-nightly.12")),
                         ("core", "nightly"), "CONTROL: the two channels are not told apart")


class NoBuildThatUnpacksWrongIsPublished(unittest.TestCase):
    """MEASURED 2026-10-23: both 1.0.4 zips named every path with a backslash (Compress-Archive), which the updater
    unpacks off Windows as one file named "lib\\jogl-all.jar" beside an untouched lib/."""

    def test_a_build_whose_zip_names_a_path_with_a_backslash_is_refused(self):
        code, lines = said("1.0.5", portable=("run.bat", "lib" + chr(92) + "jogl-all.jar"))
        self.assertEqual(code, 2, "a build with backslash paths was published: %s" % lines)
        self.assertFalse(any(line.startswith("gh release create") for line in lines), lines)
        self.assertTrue(any("P.zip names 1 of its 2 paths with a backslash" in line for line in lines),
                        "the refusal does not say which build and how many paths: %s" % lines)
        code, lines = said("1.0.5")
        self.assertEqual(code, 0, "CONTROL: the same build with '/' paths is refused too, so nothing was asked: %s"
                         % lines)

    def test_a_build_that_cannot_be_read_is_refused_not_passed(self):
        code, lines = said("1.0.5", make=False)
        self.assertEqual(code, 2, "a build nobody could open was published as clean: %s" % lines)
        self.assertTrue(any("could not be read" in line for line in lines), lines)


class ThePackagerSaysOnlyWhatTheChannelToolSays(unittest.TestCase):

    def setUp(self):
        with io.open(os.path.join(HERE, "package.ps1"), encoding="utf-8") as handle:
            self.code = "\n".join(line for line in handle.read().splitlines() if not line.lstrip().startswith("#"))

    def test_the_packager_hands_the_channel_tool_the_version_and_both_builds(self):
        self.assertIn('tools\\release_channel.py") $Version $winZip $zip', self.code,
                      "package.ps1 does not hand the channel tool the version and both builds it made")
        own = [line for line in self.code.splitlines() if "git push" in line or "gh release create" in line]
        self.assertEqual(own, [], "package.ps1 says a push or release of its own, a copy to drift: %s" % own)

    def test_the_version_the_packager_writes_ends_its_lines_as_gitattributes_says(self):
        """MEASURED 2026-10-12: packaging 1.0.3-nightly.1 rewrote the tracked version.properties through Set-Content,
        which ends lines CRLF where .gitattributes gives the file LF, and lf_checkout refused the tree after it."""
        writes = [line for line in self.code.splitlines() if "$verFile" in line and ("Set-Content" in line
                                                                                      or "Out-File" in line)]
        self.assertEqual(writes, [], "the packager writes version.properties with CRLF line ends: %s" % writes)
        self.assertIn("WriteAllText((Join-Path $root $verFile), (($verLines -join \"`n\") + \"`n\")", self.code,
                      "CONTROL: the packager no longer writes the version at all, so nothing was asked")

    def test_the_packager_writes_its_zips_with_the_jdks_jar(self):
        """Compress-Archive wrote backslash names into both 1.0.4 zips; the channel tool refuses such a zip at publish,
        and this says so at the packager, where it would otherwise be found only after a whole packaging run."""
        self.assertNotIn("Compress-Archive", self.code, "the packager zips with Compress-Archive again")
        self.assertIn('--create --file $zip --no-manifest -C $stage .', self.code,
                      "CONTROL: the portable build is no longer written by jar, so nothing was asked")
        self.assertIn('--create --file $winZip --no-manifest -C "dist\\app" CTRMap-F5', self.code,
                      "CONTROL: the Windows build is no longer written by jar, so nothing was asked")

    def test_jpackage_is_handed_the_numbers_only(self):
        """jpackage on Windows takes numbers only for --app-version, so a nightly's suffix would fail the build."""
        self.assertIn("$appVersion = $Version -replace '-.*$', ''", self.code,
                      "CONTROL: the packager no longer derives the numbers-only version")
        self.assertIn("--app-version $appVersion", self.code,
                      "jpackage is handed the whole version, which it refuses for a nightly")


if __name__ == "__main__":
    unittest.main()
