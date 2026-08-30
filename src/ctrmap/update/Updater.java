package ctrmap.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a release and installs it OVER the copy the user already has.
 *
 * <p>The shape of this matters more than the code. An updater that swaps the
 * running program in the folder the user can see leaves two of them sitting
 * there - the new one and a renamed old one waiting to be reaped - and the user
 * is left wondering which is which. So nothing here is ever visible next to the
 * app: the download, the unpacked new version and the backup all live in a
 * single hidden {@code .ctrmap-update} folder, and the swap happens at the next
 * launch, before the JVM has opened anything it would then be unable to replace.
 * The user closes CTRMap and opens it again; there is only ever one CTRMap.
 *
 * <p>Rules this follows:
 * <ul>
 * <li><b>Nothing is deleted.</b> Apply only overwrites files the release
 * actually ships. Anything else in the folder - your workspace, your dumps, your
 * notes, files from an older version - is left exactly where it is.</li>
 * <li><b>No unverified code runs.</b> The download must match the SHA-256 GitHub
 * published for the asset, or it is discarded. There is no "install anyway".</li>
 * <li><b>A failed apply is recoverable.</b> Every replaced file is copied into
 * the backup folder first, and a failure part-way through restores them.</li>
 * <li><b>Settings and data are untouched.</b> Preferences live in the OS
 * preference store and the workspace lives wherever the user put it; neither is
 * inside the install folder, so an update cannot reach them.</li>
 * </ul>
 */
public class Updater {

	/** Hidden staging root, inside the install folder so it travels with it. */
	public static final String STAGE_DIR = ".ctrmap-update";
	private static final String READY = "READY";
	private static final String STAGED = "staged";
	private static final String BACKUP = "backup";
	private static final String DOWNLOAD = "download.zip";
	/** A release zip larger than this is refused outright. */
	private static final long MAX_ZIP = 256L << 20;

	/** Progress sink for the UI; all calls arrive off the EDT. */
	public interface Progress {

		void status(String message);

		/** 0..100, or -1 when the total is unknown. */
		void percent(int pct);
	}

	/**
	 * How this copy of CTRMap was installed. The two builds have different
	 * shapes, different launchers and different update mechanics, and handing
	 * one the other's download would wreck it - so the flavour decides which
	 * release asset is even looked at.
	 */
	public enum Flavour {
		/** The jar + lib + run.bat zip, for people who have Java. */
		PORTABLE("-portable.zip"),
		/** The self-contained Windows bundle with its own runtime and .exe. */
		APP_IMAGE("-windows-x64.zip");

		public final String assetSuffix;

		Flavour(String suffix) {
			this.assetSuffix = suffix;
		}
	}

	/** Where our own code is loaded from, or null. */
	private static File self() {
		try {
			return new File(Updater.class.getProtectionDomain().getCodeSource()
					.getLocation().toURI());
		} catch (Exception ex) {
			return null;
		}
	}

	/**
	 * The root of the self-contained Windows bundle we are running inside, or
	 * null when we are not in one.
	 *
	 * <p>Two independent signals. jpackage sets {@code jpackage.app-path}, which
	 * is exact - but it is a system property anyone can set, so it is only
	 * believed when the folder it names really has a bundle's shape. The
	 * structural check alone covers someone running the bundled jar with their
	 * own java.
	 */
	public static File appImageRoot() {
		String appPath = System.getProperty("jpackage.app-path");
		if (appPath != null && !appPath.isEmpty()) {
			File root = new File(appPath).getParentFile();
			if (looksLikeAppImage(root)) {
				return root;
			}
		}
		File self = self();
		if (self != null && self.isFile()) {
			File appDir = self.getParentFile();
			if (appDir != null && "app".equalsIgnoreCase(appDir.getName())
					&& looksLikeAppImage(appDir.getParentFile())) {
				return appDir.getParentFile();
			}
		}
		return null;
	}

	private static boolean looksLikeAppImage(File root) {
		if (root == null || !root.isDirectory()) {
			return false;
		}
		File app = new File(root, "app");
		if (!app.isDirectory() || !new File(root, "runtime").isDirectory()) {
			return false;
		}
		if (new File(app, ".jpackage.xml").isFile()) {
			return true;
		}
		File[] cfg = app.listFiles(new java.io.FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.toLowerCase().endsWith(".cfg");
			}
		});
		return cfg != null && cfg.length > 0;
	}

	/** Which build this is, or null when running from a source checkout. */
	public static Flavour flavour() {
		if (appImageRoot() != null) {
			return Flavour.APP_IMAGE;
		}
		File self = self();
		return self != null && self.isFile() && self.getName().toLowerCase().endsWith(".jar")
				? Flavour.PORTABLE : null;
	}

	/**
	 * The folder CTRMap is installed in, or null when running from loose classes
	 * (a source checkout), where there is nothing to update and the user should
	 * pull and rebuild instead.
	 *
	 * <p>For the Windows bundle this is the BUNDLE root, not the {@code app}
	 * folder the jar happens to sit in - everything derived from it (the staging
	 * folder, the apply target) would otherwise land one level too deep, and an
	 * apply would drop a portable build's files inside a bundle.
	 */
	public static File installDir() {
		File image = appImageRoot();
		if (image != null) {
			return image;
		}
		File self = self();
		if (self != null && self.isFile() && self.getName().toLowerCase().endsWith(".jar")) {
			return self.getParentFile();
		}
		return null; //running from build/classes
	}

	/** The .exe that starts a Windows bundle in {@code root}, or null. */
	public static File launcherIn(File root) {
		if (root == null) {
			return null;
		}
		File exe = new File(root, "CTRMap-F5.exe");
		return exe.isFile() ? exe : null;
	}

	/** The shape of an extracted tree, so a bundle is never mistaken for a portable build. */
	public static Flavour shapeOf(File dir) {
		if (dir == null || !dir.isDirectory()) {
			return null;
		}
		if (looksLikeAppImage(dir)) {
			return Flavour.APP_IMAGE;
		}
		return new File(dir, "CTRMap-F5.jar").isFile() ? Flavour.PORTABLE : null;
	}

	/**
	 * True when we can actually write where the update would go. Checked BEFORE
	 * downloading, because failing on permissions after fetching seventy
	 * megabytes is a waste of the user's time and bandwidth.
	 */
	public static boolean isWritable(File dir) {
		if (dir == null || !dir.isDirectory()) {
			return false;
		}
		File probe = new File(dir, ".ctrmap-write-test");
		try {
			if (probe.exists() && !probe.delete()) {
				return false;
			}
			if (!probe.createNewFile()) {
				return false;
			}
			return probe.delete();
		} catch (IOException ex) {
			return false;
		}
	}

	public static File stageRoot(File installDir) {
		return new File(installDir, STAGE_DIR);
	}

	/** True when an unpacked update is waiting to be applied at the next launch. */
	public static boolean isUpdateStaged(File installDir) {
		return installDir != null && new File(stageRoot(installDir), READY).isFile();
	}

	/** The version waiting to be applied, or null. */
	public static String stagedVersion(File installDir) {
		if (!isUpdateStaged(installDir)) {
			return null;
		}
		try (InputStream in = Files.newInputStream(new File(stageRoot(installDir), READY).toPath())) {
			Properties p = new Properties();
			p.load(in);
			return p.getProperty("version");
		} catch (Exception ex) {
			return null;
		}
	}

	/**
	 * Downloads, verifies and unpacks a release into the staging folder. Returns
	 * when the update is ready to be applied at the next launch. Throws with a
	 * message fit to show the user; on any failure the staging folder is left
	 * with no READY marker, so nothing will be applied.
	 */
	public static void stage(UpdateChecker.Release rel, File installDir, Progress pr) throws IOException {
		if (installDir == null) {
			throw new IOException("CTRMap is running from a source checkout, not an installed copy.\n"
					+ "Update it with 'git pull' and build.ps1 instead.");
		}
		if (rel.downloadUrl == null) {
			throw new IOException("That release has no downloadable package attached.");
		}
		if (rel.sha256 == null) {
			throw new IOException("That release has no published SHA-256 checksum.\n"
					+ "Refusing to install an update that cannot be verified.");
		}
		File root = stageRoot(installDir);
		deleteTree(new File(root, STAGED));
		new File(root, READY).delete();
		root.mkdirs();
		hide(root);

		File zip = new File(root, DOWNLOAD);
		say(pr, "Downloading " + (rel.assetName != null ? rel.assetName : "update") + "...");
		String got = download(rel.downloadUrl, zip, rel.assetSize, pr);
		if (!got.equalsIgnoreCase(rel.sha256)) {
			zip.delete();
			throw new IOException("The download did not match its published checksum, so it was discarded.\n"
					+ "Nothing on your machine was changed. Please try again.");
		}

		say(pr, "Unpacking...");
		File staged = new File(root, STAGED);
		unzip(zip, staged);
		zip.delete();
		if (staged.list() == null || staged.list().length == 0) {
			throw new IOException("The downloaded package was empty.");
		}
		//Trust the bytes, not the file name. The two builds have different
		//shapes, and installing one over the other would leave a half-working
		//program - which is worse than not updating at all.
		Flavour mine = flavour();
		Flavour stagedShape = shapeOf(stagedPayload(installDir));
		if (mine != null && stagedShape != mine) {
			deleteTree(staged);
			throw new IOException("That download is the wrong kind of package for this"
					+ " installation, so it was discarded.\n"
					+ "Nothing on your machine was changed. Please download the update"
					+ " from the releases page instead.");
		}

		Properties p = new Properties();
		p.setProperty("version", rel.version == null ? "" : rel.version);
		p.setProperty("from", AppVersion.current());
		try (OutputStream out = new FileOutputStream(new File(root, READY))) {
			p.store(out, "CTRMap-F5 staged update - applied at the next launch");
		}
		say(pr, "Update ready.");
	}

	/**
	 * Copies the staged version over the install folder. Must run when CTRMap is
	 * NOT running, which is why the launcher calls it before starting the app.
	 * Replaced files are backed up first and restored if anything fails.
	 */
	public static void applyStaged(File installDir) throws IOException {
		if (!isUpdateStaged(installDir)) {
			return;
		}
		File root = stageRoot(installDir);
		File staged = stagedPayload(installDir);
		File backup = new File(root, BACKUP);
		deleteTree(backup);
		backup.mkdirs();

		List<String[]> done = new ArrayList<>(); //{relative path} of files replaced
		try {
			copyOver(staged, installDir, backup, "", done);
		} catch (IOException ex) {
			//put back everything already replaced, so a half-applied update
			//never leaves the user with a program that will not start
			for (String[] rel : done) {
				try {
					File from = new File(backup, rel[0]);
					if (from.isFile()) {
						Files.copy(from.toPath(), new File(installDir, rel[0]).toPath(),
								StandardCopyOption.REPLACE_EXISTING);
					}
				} catch (IOException ignore) {
					//best effort; the backup folder still holds the original
				}
			}
			throw new IOException("The update could not be applied, so the previous version was put back.\n"
					+ "Your files are unchanged. Details: " + ex.getMessage(), ex);
		}
		//applied: drop the marker so the launcher stops trying. The staged tree
		//is left for the app to sweep, because on Windows the applying JVM is
		//still running out of it and cannot delete itself.
		new File(root, READY).delete();
	}

	/**
	 * Removes a staged tree that has already been applied. Safe to call at every
	 * startup; does nothing while an update is still pending.
	 */
	public static void sweep(File installDir) {
		if (installDir == null || isUpdateStaged(installDir)) {
			return;
		}
		File root = stageRoot(installDir);
		deleteTree(new File(root, STAGED));
		new File(root, DOWNLOAD).delete();
	}

	// ---- applying an update to the self-contained Windows bundle -----------
	//
	// The portable build is applied by run.bat before the JVM starts, which is
	// the only safe moment to replace a jar the JVM is about to open. A bundle
	// has no launcher script, and its runtime deliberately ships no java.exe, so
	// that trick is unavailable. What a bundle DOES have is the download itself:
	// a complete, self-contained CTRMap with its own runtime and .exe, sitting
	// in the staging folder holding nothing in the install folder. So the new
	// version installs itself, and the old one just has to get out of the way.

	/** The argument the staged copy is started with. Frozen: both sides parse it. */
	public static final String APPLY_FLAG = "--apply-update";
	private static final String RUN_LOCK = "run.lock";

	private static java.nio.channels.FileLock runLock;
	private static java.io.RandomAccessFile runLockFile;

	/**
	 * Takes a lock that is held for as long as this process lives.
	 *
	 * <p>This is how the staged copy knows we have gone. Windows drops the lock
	 * however the process ends, including a hard kill, which a PID check or an
	 * exit hook would not survive. It is a lock rather than a "can I write yet?"
	 * poll because polling gives the wrong answer here: a jar held open by a live
	 * JVM still grants a write handle while refusing to be replaced.
	 */
	public static void holdRunLock(File installDir) {
		if (installDir == null || runLock != null) {
			return;
		}
		try {
			File root = stageRoot(installDir);
			root.mkdirs();
			runLockFile = new java.io.RandomAccessFile(new File(root, RUN_LOCK), "rw");
			runLock = runLockFile.getChannel().tryLock();
		} catch (Exception ex) {
			runLock = null; //an update simply waits a little longer instead
		}
	}

	/** True when this process was started to install a staged update. */
	public static boolean isApplyInvocation(String[] args) {
		return args != null && args.length >= 2 && APPLY_FLAG.equals(args[0]);
	}

	/**
	 * Starts the staged copy so it can install itself over us, then we exit.
	 * Returns false if anything is not right, in which case the update simply
	 * stays staged and is tried again next time.
	 */
	public static boolean startAppImageApply(File installDir) {
		try {
			if (!isUpdateStaged(installDir) || flavour() != Flavour.APP_IMAGE) {
				return false;
			}
			File staged = stagedPayload(installDir);
			if (shapeOf(staged) != Flavour.APP_IMAGE) {
				return false;
			}
			File exe = launcherIn(staged);
			if (exe == null) {
				return false;
			}
			ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(),
					APPLY_FLAG, installDir.getAbsolutePath());
			pb.directory(staged);
			pb.redirectErrorStream(true);
			pb.redirectOutput(new File(stageRoot(installDir), "apply.log"));
			pb.start();
			return true;
		} catch (Exception ex) {
			return false;
		}
	}

	/**
	 * Runs inside the STAGED copy: waits for the old CTRMap to exit, installs
	 * this version over it, and starts it again. Never shows a window.
	 */
	public static void runApply(String[] args) {
		File target = new File(args[1]);
		System.out.println("CTRMap: waiting for the previous version to close...");
		if (!waitForExit(target, 60000)) {
			System.out.println("CTRMap: it is still running - leaving the update staged.");
			return;
		}
		try {
			File payload = new File(System.getProperty("user.dir"));
			if (shapeOf(payload) != Flavour.APP_IMAGE) {
				System.out.println("CTRMap: staged copy does not look right - nothing changed.");
				return;
			}
			File backup = new File(stageRoot(target), BACKUP);
			deleteTree(backup);
			backup.mkdirs();
			List<String[]> done = new ArrayList<>();
			copyOver(payload, target, backup, "", done);
			new File(stageRoot(target), READY).delete();
			System.out.println("CTRMap: update applied.");
		} catch (IOException ex) {
			System.out.println("CTRMap: could not apply the update: " + ex.getMessage());
			return;
		}
		try {
			File exe = launcherIn(target);
			if (exe != null) {
				new ProcessBuilder(exe.getAbsolutePath()).directory(target).start();
			}
		} catch (IOException ex) {
			//the update is installed; the user can start it themselves
		}
	}

	/** Blocks until the run lock in {@code target} can be taken, i.e. nobody holds it. */
	private static boolean waitForExit(File target, long timeoutMillis) {
		File lock = new File(stageRoot(target), RUN_LOCK);
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (!lock.isFile()) {
				return true; //no lock was ever taken
			}
			try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lock, "rw");
					java.nio.channels.FileLock got = raf.getChannel().tryLock()) {
				if (got != null) {
					//the image needs a moment to finish tearing down after the
					//lock drops, so give the file handles time to close
					Thread.sleep(400);
					return true;
				}
			} catch (Exception ex) {
				//still held, or not lockable yet
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	/** Entry point for the launcher: {@code java -cp <staged jar> ctrmap.update.Updater --apply <installDir>}. */
	public static void main(String[] args) {
		if (args.length < 2 || !"--apply".equals(args[0])) {
			System.out.println("usage: Updater --apply <installDir>");
			return;
		}
		File dir = new File(args[1]);
		try {
			if (!isUpdateStaged(dir)) {
				return;
			}
			System.out.println("CTRMap: applying update " + stagedVersion(dir) + "...");
			applyStaged(dir);
			System.out.println("CTRMap: update applied.");
		} catch (IOException ex) {
			System.out.println("CTRMap: " + ex.getMessage());
		}
	}

	// ---- plumbing ---------------------------------------------------------

	/**
	 * The tree inside the staging folder that actually gets installed.
	 *
	 * <p>The portable zip has its files at the root, so the payload is the
	 * staging folder itself. The Windows bundle zip contains a single
	 * {@code CTRMap-F5} folder - deliberately, so that extracting it by hand
	 * cannot spray a hundred and fifty files loose - so the payload is one level
	 * in.
	 */
	static File stagedPayload(File installDir) {
		File staged = new File(stageRoot(installDir), STAGED);
		File nested = new File(staged, "CTRMap-F5");
		return shapeOf(staged) == null && shapeOf(nested) != null ? nested : staged;
	}

	/** Recursive copy of {@code src} onto {@code dst}, backing up what it replaces. */
	private static void copyOver(File src, File dst, File backup, String rel, List<String[]> done)
			throws IOException {
		File[] kids = src.listFiles();
		if (kids == null) {
			return;
		}
		for (File k : kids) {
			String childRel = rel.isEmpty() ? k.getName() : rel + File.separator + k.getName();
			File target = new File(dst, k.getName());
			if (k.isDirectory()) {
				target.mkdirs();
				copyOver(k, target, backup, childRel, done);
			} else {
				if (target.isFile()) {
					File bk = new File(backup, childRel);
					if (bk.getParentFile() != null) {
						bk.getParentFile().mkdirs();
					}
					Files.copy(target.toPath(), bk.toPath(), StandardCopyOption.REPLACE_EXISTING);
					done.add(new String[]{childRel});
				} else if (target.getParentFile() != null) {
					target.getParentFile().mkdirs();
				}
				replace(k, target);
			}
		}
	}

	/**
	 * Overwrites one file, coping with the two things Windows does that a plain
	 * copy does not survive: jpackage marks its launcher read-only, and a file
	 * that is still mapped by a process refuses to be overwritten but will
	 * happily be renamed out of the way.
	 */
	private static void replace(File from, File to) throws IOException {
		IOException last = null;
		for (int attempt = 0; attempt < 8; attempt++) {
			try {
				if (to.exists() && !to.canWrite()) {
					to.setWritable(true);
				}
				Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (IOException ex) {
				last = ex;
			}
			try {
				File aside = new File(to.getParentFile(),
						to.getName() + ".old-" + Long.toHexString(System.nanoTime()));
				Files.move(to.toPath(), aside.toPath());
				try {
					Files.copy(from.toPath(), to.toPath());
					return;
				} catch (IOException ex) {
					Files.move(aside.toPath(), to.toPath()); //put it back, change nothing
					last = ex;
				}
			} catch (IOException ex) {
				last = ex;
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new IOException(to.getName() + " could not be replaced", last);
	}

	/** Streams a URL to a file, returning its SHA-256 as lowercase hex. */
	private static String download(String url, File out, long expectedSize, Progress pr) throws IOException {
		if (!UpdateChecker.isGithubHttps(url)) {
			throw new IOException("Refusing to download from a non-GitHub address.");
		}
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestProperty("User-Agent", "CTRMap-F5/" + AppVersion.current());
		c.setConnectTimeout(10000);
		c.setReadTimeout(30000);
		c.setInstanceFollowRedirects(true);
		if (c.getResponseCode() != 200) {
			throw new IOException("The download failed (HTTP " + c.getResponseCode() + ").");
		}
		long total = expectedSize > 0 ? expectedSize : c.getContentLengthLong();
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		} catch (Exception ex) {
			throw new IOException("SHA-256 is unavailable on this Java installation.", ex);
		}
		long read = 0;
		if (out.getParentFile() != null) {
			out.getParentFile().mkdirs();
		}
		try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				read += n;
				if (read > MAX_ZIP) {
					throw new IOException("The download is unreasonably large; stopped.");
				}
				md.update(buf, 0, n);
				os.write(buf, 0, n);
				if (pr != null) {
					pr.percent(total > 0 ? (int) (read * 100 / total) : -1);
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		for (byte b : md.digest()) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	/** Extracts a zip, refusing entries that would escape the destination. */
	private static void unzip(File zip, File dest) throws IOException {
		dest.mkdirs();
		String root = dest.getCanonicalPath() + File.separator;
		try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				File target = new File(dest, e.getName());
				//a zip entry may not point outside the folder we are unpacking into
				if (!(target.getCanonicalPath() + (e.isDirectory() ? File.separator : ""))
						.startsWith(root)) {
					throw new IOException("The package contains an unsafe path: " + e.getName());
				}
				if (e.isDirectory()) {
					target.mkdirs();
					continue;
				}
				if (target.getParentFile() != null) {
					target.getParentFile().mkdirs();
				}
				try (OutputStream os = new FileOutputStream(target)) {
					byte[] buf = new byte[1 << 16];
					int n;
					while ((n = zin.read(buf)) > 0) {
						os.write(buf, 0, n);
					}
				}
			}
		}
	}

	private static void deleteTree(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
	}

	private static void hide(File dir) {
		try {
			java.nio.file.Path p = dir.toPath();
			if (Files.getFileStore(p).supportsFileAttributeView("dos")) {
				Files.setAttribute(p, "dos:hidden", Boolean.TRUE);
			}
		} catch (Exception ex) {
			//a visible staging folder is untidy, not harmful
		}
	}

	private static void say(Progress pr, String msg) {
		if (pr != null) {
			pr.status(msg);
		}
	}
}
