package ctrmap.tests;

import ctrmap.update.AppVersion;
import ctrmap.update.UpdateChecker;
import ctrmap.update.Updater;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
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
		zipSlipRefused();
		unverifiedRefused();

		System.out.println(failures == 0 ? "ALL PASS" : "FAILURES PRESENT (" + failures + ")");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static void versionOrdering() {
		check("1.0.0 < 1.0.1", AppVersion.compare("1.0.0", "1.0.1") < 0);
		check("1.9.0 < 1.10.0", AppVersion.compare("1.9.0", "1.10.0") < 0);
		check("2.0.0 > 1.99.99", AppVersion.compare("2.0.0", "1.99.99") > 0);
		check("v-prefix ignored", AppVersion.compare("v1.2.3", "1.2.3") == 0);
		check("suffix ignored", AppVersion.compare("1.2.3-beta", "1.2.3") == 0);
		check("same is not newer", !AppVersion.isNewerThanCurrent(AppVersion.current()));
		//an unreadable version must never HIDE an update
		check("unknown is oldest", AppVersion.compare(AppVersion.UNKNOWN, "0.0.1") < 0);
		System.out.println("  version ordering ok (running " + AppVersion.current() + ")");
	}

	private static void jsonReading() {
		String json = "{\"tag_name\": \"v2.5.0\", \"body\": \"line one\\nline two\","
				+ "\"assets\":[{\"name\":\"CTRMap-F5-2.5.0.zip\",\"size\":1234,"
				+ "\"digest\":\"sha256:" + repeat("ab", 32) + "\","
				+ "\"browser_download_url\":\"https://github.com/x/y/releases/download/v2.5.0/a.zip\"}]}";
		check("tag read", "v2.5.0".equals(UpdateChecker.field(json, "tag_name")));
		check("escaped newline decoded", "line one\nline two".equals(UpdateChecker.field(json, "body")));
		check("size read", UpdateChecker.longField(json, "size") == 1234);
		check("digest read", repeat("ab", 32).equals(UpdateChecker.sha256Of(json)));
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
		check("nothing escaped", !new File(install.getParentFile(), "escaped.txt").exists());
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

	// ---- helpers ----------------------------------------------------------

	private static void ready(File install, String version) throws IOException {
		File f = new File(new File(install, Updater.STAGE_DIR), "READY");
		f.getParentFile().mkdirs();
		write(f, "version=" + version + "\n");
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

	private static void check(String what, boolean ok) {
		if (!ok) {
			failures++;
			System.out.println("FAIL " + what);
		}
	}
}
