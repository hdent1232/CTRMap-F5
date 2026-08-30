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
	 * The folder CTRMap is installed in - the one holding the jar - or null when
	 * running from loose classes (a source checkout), where there is nothing to
	 * update and the user should pull and rebuild instead.
	 */
	public static File installDir() {
		try {
			java.net.URI uri = Updater.class.getProtectionDomain().getCodeSource()
					.getLocation().toURI();
			File self = new File(uri);
			if (self.isFile() && self.getName().toLowerCase().endsWith(".jar")) {
				return self.getParentFile();
			}
			return null; //running from build/classes
		} catch (Exception ex) {
			return null;
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
		File staged = new File(root, STAGED);
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
				Files.copy(k.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		}
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
