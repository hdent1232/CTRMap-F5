package ctrmap.tests;

import ctrmap.update.AppVersion;
import ctrmap.update.UpdateChecker;
import ctrmap.update.Updater;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Guards the promises the updater makes to the user:
 * <ul>
 * <li>an update REPLACES the copy they have - it does not leave a second one;</li>
 * <li>nothing they own is deleted, moved or overwritten;</li>
 * <li>a package that fails to verify is never installed;</li>
 * <li>a package that tries to write outside the install folder is refused.</li>
 * </ul>
 * All of it runs against a throwaway folder; no network, no real install.
 */
public class UpdaterTest {

	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		versionOrdering();
		jsonReading();
		applyReplacesInPlace();
		applyKeepsEverythingElse();
		aFailedInstallPutsBackWhatItReplaced();
		zipSlipRefused();
		unverifiedRefused();
		aStagedUpdateThatCannotBeClearedIsNotStagedOver();
		anInstallOverAnUnclearedBackupIsRefused();
		aSweepThatCannotRemoveTheStagedFolderSaysSo();
		aFailedApplyIsSaidAndKeptForTheNextStart();
		anApplySaysWhatItDid();
		anAppliedUpdateIsStartedAgain();
		anAnswerIsReadOnlyWhenItIsAnAnswer();
		asidesAreSweptAway();
		linksNameThisProject();
		aShippedFileIsLookedForBesideTheProgram();

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static void versionOrdering() throws java.io.IOException {
		check("1.0.0 < 1.0.1", AppVersion.compare("1.0.0", "1.0.1") < 0);
		check("1.9.0 < 1.10.0", AppVersion.compare("1.9.0", "1.10.0") < 0);
		check("2.0.0 > 1.99.99", AppVersion.compare("2.0.0", "1.99.99") > 0);
		check("v-prefix ignored", AppVersion.compare("v1.2.3", "1.2.3") == 0);
		check("suffix ignored", AppVersion.compare("1.2.3-beta", "1.2.3") == 0);
		check("same is not newer", !AppVersion.isNewerThanCurrent(AppVersion.current()));
		//an unreadable version must never HIDE an update
		check("unknown is oldest", AppVersion.compare(AppVersion.UNKNOWN, "0.0.1") < 0);
		System.out.println("  version ordering ok (running " + AppVersion.current() + ")");
		//the build reads the version its own version.properties names (the 2026-10-15 shared sweep, AppVersion 43,
		//negate-if, SURVIVED: no suite compared the two). Inverted, a stamped build called itself unknown - and is
		//offered every release, its own included - and an unstamped one threw on the version it does not have.
		java.util.Properties stamped = new java.util.Properties();
		try (java.io.InputStream in = new java.io.FileInputStream("src/ctrmap/resources/version.properties")) {
			stamped.load(in);
		}
		String named = stamped.getProperty("version", "").trim();
		check("the build reads the version its version.properties names (" + named + "): it says " + AppVersion.current()
				+ ", " + AppVersion.described(), !named.isEmpty() && named.equals(AppVersion.current())
				&& !AppVersion.UNKNOWN.equals(AppVersion.current()));
	}

	private static void jsonReading() {
		String json = "{\"tag_name\": \"v2.5.0\", \"body\": \"line one\\nline two\","
				+ "\"assets\":[{\"name\":\"CTRMap-F5-2.5.0.zip\",\"size\":1234,"
				+ "\"digest\":\"sha256:" + repeat("ab", 32) + "\","
				+ "\"browser_download_url\":\"https://github.com/x/y/releases/download/v2.5.0/a.zip\"}]}";
		//the release an answer describes: offered when it is newer than this build, not when it is this build, and
		//refused when the answer names none (the 2026-10-15 shared sweep, UpdateChecker 93, negate-if, SURVIVED: no
		//suite may go to GitHub, and latest() was the fetch and the reading in one)
		String newer;
		String same;
		String untagged;
		try {
			UpdateChecker.Release r = UpdateChecker.releaseIn("{\"tag_name\": \"v999.0.0\", \"body\": \"notes\"}");
			newer = r == null ? "nothing offered" : "offered " + r.version;
			r = UpdateChecker.releaseIn("{\"tag_name\": \"v" + AppVersion.current() + "\", \"body\": \"notes\"}");
			same = r == null ? "nothing offered" : "offered " + r.version;
		} catch (java.io.IOException threw) {
			newer = same = "threw " + threw;
		}
		try {
			untagged = "answered " + UpdateChecker.releaseIn("{\"body\": \"no tag\"}");
		} catch (java.io.IOException refused) {
			untagged = "refused";
		} catch (RuntimeException other) {
			untagged = "threw " + other;   //not a refusal: the check says so rather than ending the suite
		}
		check("a release newer than this build is offered (" + newer + "), this build's own is not (" + same + "), and"
				+ " an answer naming none is refused (" + untagged + ")", "offered 999.0.0".equals(newer)
				&& "nothing offered".equals(same) && "refused".equals(untagged));
		//and a build that is no installed app image - a suite's, a source build's - is offered the release with no file
		//to fetch, whatever assets it carries (the 2026-10-16 assembly, UpdateChecker 117, negate-if, SURVIVED: no suite
		//handed releaseIn an answer with assets). Inverted, the assets were searched for a build with no kind of its own
		String asAsset;
		try {
			UpdateChecker.Release r = UpdateChecker.releaseIn(json.replace("v2.5.0", "v999.0.0"));
			asAsset = Updater.flavour() != null ? "control: this build is an installed one (" + Updater.flavour() + ")"
					: r == null ? "nothing offered" : "offered " + r.version + (r.downloadUrl == null ? " with no file" : " with " + r.downloadUrl);
		} catch (java.io.IOException | RuntimeException threw) {
			asAsset = "threw " + threw;
		}
		check("a build that is no installed app image is offered a release with assets, and no file to fetch (" + asAsset
				+ ")", "offered 999.0.0 with no file".equals(asAsset));
		check("tag read", "v2.5.0".equals(UpdateChecker.field(json, "tag_name")));
		check("escaped newline decoded", "line one\nline two".equals(UpdateChecker.field(json, "body")));
		check("size read", UpdateChecker.longField(json, "size") == 1234);
		check("digest read", repeat("ab", 32).equals(UpdateChecker.sha256Of(json)));
		check("a \\u escape is decoded", "aéb".equals(UpdateChecker.field("{\"n\": \"a\\u00e9b\"}", "n")));
		//F211: a \\u that was not hex was dropped and the string read on as if nothing were missing
		String notHex;
		try {
			notHex = "answered '" + UpdateChecker.field("{\"n\": \"a\\uZZZZb\"}", "n") + "'";
		} catch (IllegalArgumentException ex) {
			notHex = null;
		}
		check("a \\u escape that is not hex is refused, not dropped (" + (notHex == null ? "refused" : notHex) + ")",
				notHex == null);
		check("github https accepted",
				UpdateChecker.isGithubHttps("https://github.com/a/b/releases/download/v1/a.zip"));
		check("http refused", !UpdateChecker.isGithubHttps("http://github.com/a/b.zip"));
		check("other host refused", !UpdateChecker.isGithubHttps("https://evil.example.com/a.zip"));
		System.out.println("  release parsing ok");
	}

	/** The new files land in the SAME folder; no second copy appears beside them. */
	private static void applyReplacesInPlace() throws IOException {
		File install = temp("apply");
		write(new File(install, "CTRMap-F5.jar"), "OLD JAR");
		write(new File(install, "run.bat"), "OLD LAUNCHER");

		File staged = new File(new File(install, Updater.STAGE_DIR), "staged");
		write(new File(staged, "CTRMap-F5.jar"), "NEW JAR");
		write(new File(staged, "run.bat"), "NEW LAUNCHER");
		write(new File(staged, "lib/extra.jar"), "NEW LIB");
		ready(install, "9.9.9");

		check("staged update detected", Updater.isUpdateStaged(install));
		check("staged version read", "9.9.9".equals(Updater.stagedVersion(install)));
		Updater.applyStaged(install);

		check("jar replaced", "NEW JAR".equals(read(new File(install, "CTRMap-F5.jar"))));
		check("launcher replaced", "NEW LAUNCHER".equals(read(new File(install, "run.bat"))));
		check("new file added", "NEW LIB".equals(read(new File(install, "lib/extra.jar"))));
		check("marker cleared", !Updater.isUpdateStaged(install));

		//the only thing the folder gained is the hidden staging dir
		for (File f : install.listFiles()) {
			if (f.getName().equals(Updater.STAGE_DIR)) {
				continue;
			}
			check("no stray copy: " + f.getName(),
					f.getName().equals("CTRMap-F5.jar") || f.getName().equals("run.bat")
					|| f.getName().equals("lib"));
		}
		//and the staging dir goes away once the update is applied
		Updater.sweep(install);
		check("staged tree swept", !new File(new File(install, Updater.STAGE_DIR), "staged").exists());
		System.out.println("  applies in place, leaves no second copy");
	}

	/**
	 * An install that fails half way puts back what it replaced, on both paths (census M.143). The launcher's
	 * apply did; the app image's copied over the program with no rollback and reported to a log in a hidden
	 * folder, so a failure left the program part old and part new and it never came back up.
	 */
	private static void aFailedInstallPutsBackWhatItReplaced() throws IOException {
		File install = temp("apply_fails");
		write(new File(install, "CTRMap-F5.jar"), "OLD JAR");
		write(new File(install, "lib"), "a file where the update wants a folder");
		File staged = new File(new File(install, Updater.STAGE_DIR), "staged");
		write(new File(staged, "CTRMap-F5.jar"), "NEW JAR");
		write(new File(staged, "lib/extra.jar"), "NEW LIB");
		ready(install, "9.9.9");
		String said = null;
		try {
			Updater.applyStaged(install);
		} catch (IOException refused) {
			said = ctrmap.util.Bytes.reason(refused);
		}
		check("control: an update that cannot be installed is refused: " + said, said != null && said.contains("put back"));
		check("and the file it had already replaced is the one there before", "OLD JAR".equals(read(new File(install, "CTRMap-F5.jar"))));
		String src = new String(java.nio.file.Files.readAllBytes(new File("src/ctrmap/update/Updater.java").toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		int run = src.indexOf("public static void runApply(String[] args) {");
		int end = run < 0 ? -1 : src.indexOf("private static boolean waitForExit(", run);
		String runApply = run >= 0 && end > run ? src.substring(run, end) : "";
		check("the app image's apply goes through the same install, with its rollback",
				runApply.contains("install(payload, target);") && !runApply.contains("copyOver("));
		int caught = runApply.indexOf("} catch (IOException ex) {");
		check("and a failure there is kept for the next start, which then starts the version put back",
				caught > 0 && runApply.indexOf("APPLY_FAILED", caught) > caught && runApply.indexOf("return;", caught) < 0);
		File note = new File(new File(install, Updater.STAGE_DIR), "apply-failed.txt");
		write(note, "planted: lib is a file");
		String taken = Updater.takeApplyFailure(install);
		check("the next start takes the reason, once: " + taken, "planted: lib is a file".equals(taken)
				&& !note.exists() && Updater.takeApplyFailure(install) == null);
		System.out.println("  a failed install puts back what it replaced, and the next start says why");
	}

	/** The user's own files are not touched, however the update is shaped. */
	private static void applyKeepsEverythingElse() throws IOException {
		File install = temp("keep");
		write(new File(install, "CTRMap-F5.jar"), "OLD");
		write(new File(install, "my notes.txt"), "MINE");
		write(new File(install, "Workspace/zonedata/3"), "MY WORKSPACE");
		write(new File(install, "lib/jogl-all.jar"), "OLD JOGL");

		File staged = new File(new File(install, Updater.STAGE_DIR), "staged");
		write(new File(staged, "CTRMap-F5.jar"), "NEW");
		ready(install, "2.0.0");
		Updater.applyStaged(install);

		check("user file untouched", "MINE".equals(read(new File(install, "my notes.txt"))));
		check("workspace untouched", "MY WORKSPACE".equals(read(new File(install, "Workspace/zonedata/3"))));
		check("file the release does not ship is left alone",
				"OLD JOGL".equals(read(new File(install, "lib/jogl-all.jar"))));
		check("the app itself did update", "NEW".equals(read(new File(install, "CTRMap-F5.jar"))));

		//the replaced file is recoverable
		File backup = new File(new File(install, Updater.STAGE_DIR), "backup/CTRMap-F5.jar");
		check("previous version backed up", "OLD".equals(read(backup)));
		System.out.println("  keeps every file the release does not ship");
	}

	/** A package with a ../ entry must be refused, not allowed to escape. */
	private static void zipSlipRefused() throws Exception {
		File install = temp("slip");
		File zip = new File(install, "evil.zip");
		try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
			z.putNextEntry(new ZipEntry("../escaped.txt"));
			z.write("pwned".getBytes("UTF-8"));
			z.closeEntry();
		}
		boolean refused = false;
		try {
			java.lang.reflect.Method m = Updater.class.getDeclaredMethod("unzip", File.class, File.class);
			m.setAccessible(true);
			m.invoke(null, zip, new File(install, "out"));
		} catch (java.lang.reflect.InvocationTargetException ex) {
			refused = ex.getCause() instanceof IOException;
		}
		check("path traversal refused", refused);
		//WHERE THE ENTRY WOULD LAND, resolved against the folder unpacked into (F164): this looked one
		//directory higher, where no escape could ever be, so it passed with the refusal removed
		File landing = new File(new File(install, "out"), "../escaped.txt").getCanonicalFile();
		check("nothing escaped to " + landing, !landing.exists());
		System.out.println("  refuses packages that write outside the folder");
	}

	/** No checksum, no install - there is no "install anyway" path. */
	private static void unverifiedRefused() throws IOException {
		File install = temp("unverified");
		UpdateChecker.Release rel = new UpdateChecker.Release();
		rel.version = "9.0.0";
		rel.downloadUrl = "https://github.com/a/b/releases/download/v9/a.zip";
		rel.sha256 = null;
		String msg = null;
		try {
			Updater.stage(rel, install, null);
		} catch (IOException ex) {
			msg = ex.getMessage();
		}
		check("unverifiable update refused", msg != null && msg.contains("checksum"));
		check("nothing was downloaded", !new File(install, Updater.STAGE_DIR + "/staged").exists());
		System.out.println("  refuses an update it cannot verify");
	}

	/**
	 * An update is not staged over a last one that could not be cleared (the 2026-10-15 shared sweep, Updater 289,
	 * drop-throw, SURVIVED: no suite had a staged folder that would not go). Dropped, the new package was unpacked
	 * among the old one's leftovers and applied as one. The old folder holds a file kept open - java.io opens it
	 * without delete sharing, which stops a delete on Windows - and is read-only itself, which stops one on POSIX; a
	 * probe built the same way is the control (MEASURED: a read-only file alone was deleted on Windows, and the
	 * first version of this check skipped). The release names an address that is not GitHub's, so a staging that
	 * went on stops before any connection.
	 */
	private static void aStagedUpdateThatCannotBeClearedIsNotStagedOver() throws IOException {
		File install = temp("uncleared");
		File staged = new File(new File(install, Updater.STAGE_DIR), "staged");
		File probe = new File(temp("uncleared_probe"), "staged");
		java.util.List<java.io.RandomAccessFile> held = new java.util.ArrayList<>();
		for (File dir : new File[]{staged, probe}) {
			write(new File(dir, "left.txt"), "FROM THE LAST UPDATE");
			held.add(new java.io.RandomAccessFile(new File(dir, "left.txt"), "rw"));
			dir.setWritable(false);
		}
		String said;
		try {
			if (ctrmap.util.Bytes.deleteTree(probe)) {
				Skip.section("a file held open in a read-only folder is deleted here, so a staged update that cannot be"
						+ " cleared cannot be made");
				return;
			}
			UpdateChecker.Release rel = new UpdateChecker.Release();
			rel.version = "9.0.0";
			rel.downloadUrl = "https://example.invalid/not-github/a.zip";
			rel.sha256 = repeat("ab", 32);
			try {
				Updater.stage(rel, install, null);
				said = "staged";
			} catch (IOException refused) {
				said = refused.getMessage();
			}
		} finally {
			for (java.io.RandomAccessFile f : held) {
				f.close();
			}
			for (File dir : new File[]{staged, probe}) {
				dir.setWritable(true);
			}
		}
		check("an update is not staged over a last one that could not be cleared: " + said,
				said.startsWith("The last staged update could not be cleared"));
	}

	/**
	 * An install does not go ahead over the last one's backup when that backup could not be cleared (the 2026-10-15
	 * shared sweep, Updater 360, drop-throw, SURVIVED: no suite had a backup folder that would not go). Dropped, the
	 * new files were copied in with a backup mixing two installs' files, so a rollback could put back the wrong ones.
	 * The same lever as the staging check above - a file held open in a read-only folder - and the same probe.
	 */
	private static void anInstallOverAnUnclearedBackupIsRefused() throws IOException {
		File install = temp("backup_uncleared");
		write(new File(install, "CTRMap-F5.jar"), "OLD JAR");
		File staged = new File(new File(install, Updater.STAGE_DIR), "staged");
		write(new File(staged, "CTRMap-F5.jar"), "NEW JAR");
		ready(install, "9.9.9");
		File backup = new File(new File(install, Updater.STAGE_DIR), "backup");
		File probe = new File(temp("backup_uncleared_probe"), "backup");
		List<java.io.RandomAccessFile> held = new java.util.ArrayList<>();
		for (File dir : new File[]{backup, probe}) {
			write(new File(dir, "left.txt"), "FROM THE LAST INSTALL");
			held.add(new java.io.RandomAccessFile(new File(dir, "left.txt"), "rw"));
			dir.setWritable(false);
		}
		String said;
		try {
			if (ctrmap.util.Bytes.deleteTree(probe)) {
				Skip.section("a file held open in a read-only folder is deleted here, so a backup that cannot be cleared"
						+ " cannot be made");
				return;
			}
			try {
				Updater.applyStaged(install);
				said = "installed";
			} catch (IOException refused) {
				said = refused.getMessage();
			}
		} finally {
			for (java.io.RandomAccessFile f : held) {
				f.close();
			}
			for (File dir : new File[]{backup, probe}) {
				dir.setWritable(true);
			}
		}
		check("an install over a backup that could not be cleared is refused, the program left as it was: " + said
				+ ", the jar " + read(new File(install, "CTRMap-F5.jar")),
				said.startsWith("The previous update's backup could not be cleared")
				&& "OLD JAR".equals(read(new File(install, "CTRMap-F5.jar"))));
	}

	/**
	 * The startup sweep says, to the stream it is handed, an applied update's staging folder it could not remove -
	 * the next update refuses to stage over it (the 2026-10-15 shared sweep, Updater 424, drop-report, SURVIVED: the
	 * line went to System.out, read by nobody). Dropped, the folder stayed and nothing said why the next update would
	 * refuse. The held-file lever and its probe; the stream is the suite's own.
	 */
	private static void aSweepThatCannotRemoveTheStagedFolderSaysSo() throws IOException {
		File install = temp("sweep_stuck");
		File staged = new File(new File(install, Updater.STAGE_DIR), "staged");
		File probe = new File(temp("sweep_stuck_probe"), "staged");
		List<java.io.RandomAccessFile> held = new java.util.ArrayList<>();
		for (File dir : new File[]{staged, probe}) {
			write(new File(dir, "left.txt"), "FROM THE APPLIED UPDATE");
			held.add(new java.io.RandomAccessFile(new File(dir, "left.txt"), "rw"));
			dir.setWritable(false);
		}
		java.io.ByteArrayOutputStream heard = new java.io.ByteArrayOutputStream();
		try {
			if (ctrmap.util.Bytes.deleteTree(probe)) {
				Skip.section("a file held open in a read-only folder is deleted here, so a staged folder that cannot be"
						+ " removed cannot be made");
				return;
			}
			Updater.sweep(install, new java.io.PrintStream(heard, true, "UTF-8"));
		} finally {
			for (java.io.RandomAccessFile f : held) {
				f.close();
			}
			for (File dir : new File[]{staged, probe}) {
				dir.setWritable(true);
			}
		}
		String said = heard.toString("UTF-8").trim();
		check("a sweep that cannot remove the staged folder says so, naming it: " + said,
				said.startsWith("CTRMap: an applied update's staging folder could not be removed yet")
				&& said.contains(staged.getPath()));
	}

	/**
	 * An apply that fails says so to the stream it is handed, keeps its reason for the next start, and still answers
	 * that the program is to be started again - the previous version, put back (census M.143; the 2026-10-15 shared
	 * sweep, Updater 604, drop-report, SURVIVED: the line went to a log nobody read). A target holding a FILE where the
	 * update wants a folder, the same lever as the failed-install check above.
	 */
	private static void aFailedApplyIsSaidAndKeptForTheNextStart() throws Exception {
		File target = temp("apply_said");
		write(new File(target, "app"), "a file where the update wants a folder");
		File payload = temp("apply_said_payload");
		write(new File(payload, "app/.jpackage.xml"), "<jpackage/>");
		write(new File(payload, "app/CTRMap-F5.jar"), "NEW JAR");
		new File(payload, "runtime").mkdirs();
		check("control: the payload is an app image", Updater.shapeOf(payload) == Updater.Flavour.APP_IMAGE);
		java.lang.reflect.Method apply = Updater.class.getDeclaredMethod("applyOver", File.class, File.class,
				java.io.PrintStream.class);
		apply.setAccessible(true);
		java.io.ByteArrayOutputStream heard = new java.io.ByteArrayOutputStream();
		boolean again = (Boolean) apply.invoke(null, payload, target, new java.io.PrintStream(heard, true, "UTF-8"));
		String said = heard.toString("UTF-8").trim();
		String kept = Updater.takeApplyFailure(target);
		check("a failed apply says so (" + said + "), keeps why for the next start (" + kept + "), puts the old file back and"
				+ " starts the program again (" + again + ")", again && said.startsWith("CTRMap: could not apply the update: ")
				&& kept != null && !kept.isEmpty() && "a file where the update wants a folder".equals(read(new File(target, "app"))));
	}

	/**
	 * An apply says what it did: a staged copy that is no app image changes nothing and says so, and is not started;
	 * one that installs says it was applied, and is (the 2026-10-16 assembly, Updater 647 and 652, drop-report,
	 * SURVIVED: the only apply a suite made was one that failed). Dropped, the apply's log said nothing either way.
	 */
	private static void anApplySaysWhatItDid() throws Exception {
		java.lang.reflect.Method apply = Updater.class.getDeclaredMethod("applyOver", File.class, File.class,
				java.io.PrintStream.class);
		apply.setAccessible(true);
		File target = temp("apply_odd_target");
		write(new File(target, "app/CTRMap-F5.jar"), "OLD JAR");
		File odd = temp("apply_odd_payload");
		write(new File(odd, "notes.txt"), "not an app image");
		check("control: the odd payload is no app image", Updater.shapeOf(odd) != Updater.Flavour.APP_IMAGE);
		java.io.ByteArrayOutputStream heard = new java.io.ByteArrayOutputStream();
		boolean again = (Boolean) apply.invoke(null, odd, target, new java.io.PrintStream(heard, true, "UTF-8"));
		String said = heard.toString("UTF-8").trim();
		check("a staged copy that is no app image changes nothing, says so, and is not started (" + said + ", started "
				+ again + ")", !again && "CTRMap: staged copy does not look right - nothing changed.".equals(said)
				&& "OLD JAR".equals(read(new File(target, "app/CTRMap-F5.jar"))));
		File fine = temp("apply_fine_target");
		write(new File(fine, "app/CTRMap-F5.jar"), "OLD JAR");
		File payload = temp("apply_fine_payload");
		write(new File(payload, "app/.jpackage.xml"), "<jpackage/>");
		write(new File(payload, "app/CTRMap-F5.jar"), "NEW JAR");
		new File(payload, "runtime").mkdirs();
		check("control: the payload is an app image", Updater.shapeOf(payload) == Updater.Flavour.APP_IMAGE);
		heard = new java.io.ByteArrayOutputStream();
		again = (Boolean) apply.invoke(null, payload, fine, new java.io.PrintStream(heard, true, "UTF-8"));
		said = heard.toString("UTF-8").trim();
		check("an apply that installs says it was applied, and is started again (" + said + ", started " + again + ")",
				again && "CTRMap: update applied.".equals(said) && "NEW JAR".equals(read(new File(fine, "app/CTRMap-F5.jar"))));
	}

	/**
	 * GitHub's answer is read when it is 200 and refused, naming the status, when it is not (the 2026-10-15 shared
	 * sweep, UpdateChecker 214, negate-if, SURVIVED: no suite may go to GitHub). Inverted, every answer that came was
	 * refused as "GitHub answered 200" and an error page was read as a release. Asked of a server on this machine's
	 * loopback - nothing leaves it - through the fetch itself.
	 */
	private static void anAnswerIsReadOnlyWhenItIsAnAnswer() throws Exception {
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
				new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/ok", ex -> {
			byte[] body = "{\"tag_name\": \"v9.9.9\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.createContext("/gone", ex -> {
			byte[] body = "{\"message\": \"Not Found\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			ex.sendResponseHeaders(404, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		//and an answer past 4 MB, which a release never is, is refused rather than read without end (UpdateChecker 226,
		//drop-throw, SURVIVED in the same sweep)
		server.createContext("/huge", ex -> {
			ex.sendResponseHeaders(200, 5 << 20);
			byte[] chunk = new byte[1 << 16];
			java.util.Arrays.fill(chunk, (byte) ' ');
			try (java.io.OutputStream out = ex.getResponseBody()) {
				for (int sent = 0; sent < 5 << 20; sent += chunk.length) {
					out.write(chunk);
				}
			} catch (IOException readerStopped) {
				//the reader stopping part way is what is being asked for
			}
		});
		server.start();
		String ok;
		String gone;
		String huge;
		try {
			java.lang.reflect.Method get = UpdateChecker.class.getDeclaredMethod("get", String.class);
			get.setAccessible(true);
			String base = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
			ok = answered(get, base + "/ok");
			gone = answered(get, base + "/gone");
			huge = answered(get, base + "/huge");
		} finally {
			server.stop(0);
		}
		check("an answer of 200 is read (" + ok + ") and any other is refused naming its status (" + gone + ")",
				ok.contains("v9.9.9") && gone.startsWith("refused: GitHub answered 404"));
		check("an answer past 4 MB is refused, not read without end: " + (huge.length() > 120 ? huge.substring(0, 120) : huge),
				huge.equals("refused: GitHub's answer is over 4 MB, which a release never is"));
	}

	private static String answered(java.lang.reflect.Method get, String url) throws Exception {
		try {
			return "read " + get.invoke(null, url);
		} catch (java.lang.reflect.InvocationTargetException threw) {
			return "refused: " + threw.getCause().getMessage();
		}
	}

	/**
	 * A file the apply cannot overwrite in place is renamed beside itself, and the next startup's
	 * sweep deletes it (F120) - it used to stay next to the app for good. The locked-jar path is
	 * driven with a target that is a non-empty folder: like a jar Windows holds mapped, it cannot
	 * be copied over and can be renamed out of the way.
	 */
	private static void asidesAreSweptAway() throws IOException {
		File install = temp("aside");
		write(new File(install, "CTRMap-F5.jar/held"), "IN USE");
		write(new File(install, "my notes.old-abcdef12"), "MINE, NAMED LIKE ONE");
		File staged = new File(new File(install, Updater.STAGE_DIR), "staged");
		write(new File(staged, "CTRMap-F5.jar"), "NEW");
		ready(install, "3.0.0");
		Updater.applyStaged(install);
		check("the held file was replaced", "NEW".equals(read(new File(install, "CTRMap-F5.jar"))));
		List<String> set = asides(install);
		check("and what held its place was set aside beside it, once (" + set + ")", set.size() == 1);
		//an aside still held open when the sweep runs stays on the list, and the startup after it is let go deletes
		//it (the 2026-10-15 shared sweep, Updater 450, negate-if, SURVIVED: every aside a suite swept went at once).
		//Inverted, one still held was forgotten - left beside the program for good - and the ones that went were
		//listed. Held open through java.io (no delete sharing on Windows) in a read-only folder (POSIX), with a probe
		File list = new File(new File(install, Updater.STAGE_DIR), "set-aside.txt");
		File probeDir = temp("aside_probe");
		write(new File(probeDir, "held"), "PROBE");
		if (set.size() == 1) {
			//the aside here is a FOLDER - the stand-in for a jar Windows holds - so the file in it is what is held
			File aside = new File(install, set.get(0));
			java.io.RandomAccessFile holding = new java.io.RandomAccessFile(new File(aside, "held"), "rw");
			java.io.RandomAccessFile probing = new java.io.RandomAccessFile(new File(probeDir, "held"), "rw");
			aside.setWritable(false);
			probeDir.setWritable(false);
			boolean lever;
			String kept;
			try {
				lever = !ctrmap.util.Bytes.deleteTree(new File(probeDir, "held"));
				Updater.sweep(install);
				kept = aside.exists() && list.isFile() && new String(Files.readAllBytes(list.toPath()),
						java.nio.charset.StandardCharsets.UTF_8).contains(aside.getName()) ? "kept on the list"
						: "forgotten (" + (aside.exists() ? "still there" : "gone") + ", list "
						+ (list.isFile() ? "names it not" : "gone") + ")";
			} finally {
				holding.close();
				probing.close();
				aside.setWritable(true);
				probeDir.setWritable(true);
			}
			if (!lever) {
				Skip.section("a file held open in a read-only folder is deleted here, so an aside the sweep cannot"
						+ " remove cannot be made");
			} else {
				check("an aside still held when the sweep runs is kept on the list for the next one: " + kept,
						"kept on the list".equals(kept));
			}
		}
		Updater.sweep(install);
		check("the next startup's sweep deletes what was set aside (left: " + asides(install) + ")",
				asides(install).isEmpty());
		check("a file of the user's that is merely NAMED like one stays: only what an apply recorded goes",
				"MINE, NAMED LIKE ONE".equals(read(new File(install, "my notes.old-abcdef12"))));
		System.out.println("  sweeps away what an apply set aside, and nothing else");
	}

	private static List<String> asides(File install) {
		List<String> out = new ArrayList<>();
		File[] kids = install.listFiles();
		for (File k : kids == null ? new File[0] : kids) {
			if (k.getName().startsWith("CTRMap-F5.jar.old-")) {
				out.add(k.getName());
			}
		}
		return out;
	}

	/**
	 * Every link the program sends the user to for an issue, a release or a pull request names this
	 * project (F005): Help's issue tracker opened the upstream 2019 repository's. Read from the
	 * compiled program's string constants, where {@code "https://github.com/" + REPO + "/issues"} is
	 * one folded string, so a link spelled in pieces is read whole.
	 */
	private static void linksNameThisProject() throws IOException {
		File classes = new File("build/classes");
		if (!new File(classes, "ctrmap").isDirectory()) {
			check("there is a compiled program to read at " + classes.getAbsolutePath(), false);
			return;
		}
		Pattern link = Pattern.compile("github\\.com/([^/\\s\"<>]+/[^/\\s\"<>]+)/(issues|releases|pulls)");
		List<String> foreign = new ArrayList<>();
		boolean releases = false;
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.application(classes)) {
			for (String s : cf.strings) {
				Matcher m = link.matcher(s);
				while (m.find()) {
					releases |= cf.name.equals("ctrmap/update/UpdateChecker") && m.group(2).equals("releases");
					if (!m.group(1).equals(UpdateChecker.REPO)) {
						foreign.add(cf.name + ": " + m.group());
					}
				}
			}
		}
		check("control: the releases page is read whole from the updater's folded constant", releases);
		check("every issue, release and pull-request link names " + UpdateChecker.REPO
				+ (foreign.isEmpty() ? "" : " - these do not: " + foreign), foreign.isEmpty());
		System.out.println("  every link a user is sent to names this project");
	}

	// ---- helpers ----------------------------------------------------------

	private static void ready(File install, String version) throws IOException {
		File f = new File(new File(install, Updater.STAGE_DIR), "READY");
		f.getParentFile().mkdirs();
		write(f, "version=" + version + "\n");
	}

	/**
	 * After an apply the installed editor is started again in its own folder, marked as reopened; a folder with no
	 * launcher starts nothing; one that will not start leaves the installed update standing, unsaid as before; and the
	 * real launch does start a process; and the apply starts only an update it installed. The 2026-10-17 sweep recorded
	 * Updater 621, that check negated, as a HOLE: no suite may start the editor - so the start is asked of a launcher a
	 * suite hands in, and the real one of `java`.
	 */
	private static void anAppliedUpdateIsStartedAgain() throws Exception {
		Class<?> launcher = Class.forName("ctrmap.update.Updater$Launcher");
		java.lang.reflect.Method again = Updater.class.getDeclaredMethod("startAgain", File.class, launcher);
		again.setAccessible(true);
		java.util.List<Object> asked = new java.util.ArrayList<>();
		Object recording = java.lang.reflect.Proxy.newProxyInstance(launcher.getClassLoader(), new Class<?>[]{launcher},
				(proxy, method, args) -> {
					asked.add(args[0]);
					asked.add(args[1]);
					return null;
				});
		File installed = temp("started_again");
		write(new File(installed, "CTRMap-F5.exe"), "a launcher");
		again.invoke(null, installed, recording);
		check("the installed editor is started again in its folder, marked as reopened: " + asked, asked.size() == 2
				&& asked.get(0).equals(java.util.Arrays.asList(new File(installed, "CTRMap-F5.exe").getAbsolutePath(),
						Updater.REOPENED_FLAG)) && installed.equals(asked.get(1)));
		asked.clear();
		again.invoke(null, temp("started_again_none"), recording);
		check("a folder with no launcher starts nothing: " + asked, asked.isEmpty());
		Object refusing = java.lang.reflect.Proxy.newProxyInstance(launcher.getClassLoader(), new Class<?>[]{launcher},
				(proxy, method, args) -> {
					throw new IOException("it would not start");
				});
		String left = "(nothing thrown)";
		try {
			again.invoke(null, installed, refusing);
		} catch (java.lang.reflect.InvocationTargetException thrown) {
			left = String.valueOf(thrown.getCause());
		}
		check("and a launcher that will not start leaves the installed update standing: " + left, "(nothing thrown)".equals(left));
		java.lang.reflect.Method launch = Updater.class.getDeclaredMethod("launch", java.util.List.class, File.class);
		launch.setAccessible(true);
		File jvm = new File(new File(System.getProperty("java.home"), "bin"), File.separatorChar == '\\' ? "java.exe" : "java");
		Process started = (Process) launch.invoke(null, java.util.Arrays.asList(jvm.getAbsolutePath(), "-version"),
				temp("started_java"));
		check("the real launch starts a process (java -version, exited " + (started == null ? "nothing" : started.waitFor())
				+ ")", started != null && started.exitValue() == 0);
		//THE APPLY THAT DECIDES IT (2026-10-24): Updater 621 was the check in runApply that starts only an installed update,
		//negated - and no suite reached runApply. Its body after the wait is installAndStart, handed a launcher
		java.lang.reflect.Method installAndStart = Updater.class.getDeclaredMethod("installAndStart", File.class, File.class,
				java.io.PrintStream.class, launcher);
		installAndStart.setAccessible(true);
		java.io.PrintStream quiet = new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, "UTF-8");
		File fine = temp("install_start_target");
		write(new File(fine, "CTRMap-F5.exe"), "a launcher");
		write(new File(fine, "app/CTRMap-F5.jar"), "OLD JAR");
		File payload = temp("install_start_payload");
		write(new File(payload, "CTRMap-F5.exe"), "a launcher");
		write(new File(payload, "app/.jpackage.xml"), "<jpackage/>");
		write(new File(payload, "app/CTRMap-F5.jar"), "NEW JAR");
		new File(payload, "runtime").mkdirs();
		asked.clear();
		installAndStart.invoke(null, payload, fine, quiet, recording);
		check("an update that installs is started again: " + asked, "NEW JAR".equals(read(new File(fine, "app/CTRMap-F5.jar")))
				&& asked.size() == 2 && fine.equals(asked.get(1)));
		File kept = temp("install_start_odd_target");
		write(new File(kept, "CTRMap-F5.exe"), "a launcher");
		write(new File(kept, "app/CTRMap-F5.jar"), "OLD JAR");
		File odd = temp("install_start_odd_payload");
		write(new File(odd, "notes.txt"), "not an app image");
		asked.clear();
		installAndStart.invoke(null, odd, kept, quiet, recording);
		check("and a staged copy refused, nothing changed, is not: " + asked, asked.isEmpty()
				&& "OLD JAR".equals(read(new File(kept, "app/CTRMap-F5.jar"))));
	}

	private static File temp(String name) throws IOException {
		File d = new File(Scratch.dir("ctrmap_updater_test"), name);
		d.mkdirs();
		return d;
	}

	private static void write(File f, String s) throws IOException {
		if (f.getParentFile() != null) {
			f.getParentFile().mkdirs();
		}
		Files.write(f.toPath(), s.getBytes("UTF-8"));
	}

	private static String read(File f) {
		try {
			return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
		} catch (IOException ex) {
			return null;
		}
	}

	private static String repeat(String s, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			sb.append(s);
		}
		return sb.toString();
	}

	/**
	 * Census L.13: a file that ships beside the program is looked for beside the installed program first, then in
	 * the working folder - the Quick start guide looked in the working folder twice, so a shortcut whose working
	 * folder is not the install opened onto an error.
	 */
	static void aShippedFileIsLookedForBesideTheProgram() {
		System.out.println("--- a file shipped beside the program is looked for there first (census L.13)");
		File install = new File("C:/Program Files/CTRMap");
		File elsewhere = new File("D:/Shortcuts/start here");   //a working folder that is not the install
		java.util.List<File> places = ctrmap.update.Updater.besideTheProgram("QUICKSTART.md", install, elsewhere);
		check("the install folder first, then the working folder: " + places, places.size() == 2
				&& places.get(0).equals(new File(install, "QUICKSTART.md").getAbsoluteFile())
				&& places.get(1).equals(new File(elsewhere, "QUICKSTART.md").getAbsoluteFile()));
		check("and one folder that is both is looked in once", ctrmap.update.Updater.besideTheProgram("QUICKSTART.md",
				install, install).size() == 1);
		check("and with no install folder known, the working folder alone",
				ctrmap.update.Updater.besideTheProgram("QUICKSTART.md", null, elsewhere).size() == 1);
	}

	private static void check(String what, boolean ok) {
		if (!ok) {
			failures++;
			System.out.println("FAIL " + what);
		}
	}
}
