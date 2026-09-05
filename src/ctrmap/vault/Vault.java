package ctrmap.vault;

import ctrmap.ModDeployer;
import ctrmap.Workspace;
import ctrmap.gamedef.GameProfile;
import ctrmap.setup.DumpCheck;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A sealed, verified copy of a freshly dumped game, kept outside every
 * workspace so it survives the thing that goes wrong.
 *
 * <p>WHY THIS EXISTS. The editor already takes a pristine snapshot on first
 * load ({@link Workspace#snapshotOriginals}), and it is not enough, in four
 * measured ways:
 * <ul>
 * <li>It copies only {@link ModDeployer#MODDABLE} - 161 MB of a 1.8 GB ORAS
 *     dump. It is a baseline for diffing edits, not a copy of the game.</li>
 * <li>Nothing restores from it. {@code discardSnapshot()} deletes it; putting
 *     files back was a manual procedure.</li>
 * <li>It lives INSIDE the workspace, so it dies with the workspace - which is
 *     precisely the moment it is wanted.</li>
 * <li>Nothing verifies it, so silent rot is discovered at the moment of need.</li>
 * </ul>
 * A stale pack once corrupted zone 536 in this project and needed a hand
 * repair; the snapshot could not help, for the third and fourth reasons.
 *
 * <p>WHAT MAKES A VAULT DIFFERENT. It is keyed on the dump's title id, so
 * every 3DS Pokemon game takes the same path and several workspaces cut from
 * one dump share one entry. It records a per-file digest, so "is my backup
 * still what I dumped" is a question with an answer. It restores a SINGLE FILE
 * as well as the whole game - the zone-536 case is one archive, and restoring
 * 1.8 GB to fix one file would throw away every edit made since. And it is
 * gated on {@link DumpCheck}: a vault of an already-modified dump is worse than
 * no vault, because it looks pristine. Such a dump is still sealed, and
 * labelled, so the label survives a reload rather than living in someone's
 * memory.
 *
 * <p>PARTIAL SEALS ARE THE DANGEROUS STATE, and that is not a guess - it is the
 * lesson {@link Workspace#snapshotMissingArchives} already records: a backup
 * that is missing archives while its stamp says it is legitimate is worse than
 * an absent one. So the manifest is written LAST and a marker file exists for
 * the duration; an interrupted seal is therefore detectable rather than
 * plausible-looking. {@link #isSealed} is false until the manifest lands.
 */
public final class Vault {

	/**
	 * How much of a dump to keep. The user picks once, at setup.
	 *
	 * <p>MEASURED on a retail ORAS dump, 1.87 GB across 655 files, deflate
	 * level 6 over the six largest files and a random 40 others:
	 * <pre>
	 *   FULL_RAW          1.87 GB
	 *   FULL_COMPRESSED   1.62 GB   ratio 0.862 - it saves 13.8%
	 *   MODDABLE          ~167 MB   (161 MB of archives + a 5.4 MB code.bin)
	 * </pre>
	 * Compression buys far less here than it would anywhere else, because the
	 * data is already compressed: the GARCs are LZ11-packed and the textures
	 * and models inside them are compressed again. Deflating a second time
	 * costs both seal and restore time to save an eighth of the space. So
	 * FULL_RAW is the sensible default despite being the largest, and
	 * FULL_COMPRESSED is kept for a genuinely tight disk rather than
	 * recommended. Do not quote these numbers for another game without
	 * measuring it - a Gen 7 dump is a different size and may pack differently.
	 */
	public enum Scope {
		/** Everything, file for file. Fastest to seal and restore, and browsable in Explorer. */
		FULL_RAW,
		/**
		 * Everything, deflated into one archive. Saves about an eighth of the
		 * space and costs time at both ends - worth it only on a tight disk.
		 */
		FULL_COMPRESSED,
		/**
		 * The archives this editor can write, plus the executable. Covers every
		 * way CTRMap or an IPS patch can alter the game - and nothing else, so
		 * it does not protect against damage from outside the editor.
		 */
		MODDABLE
	}

	private Vault() {
	}

	/**
	 * Where vaults live: outside every workspace, so a lost workspace does not
	 * take the backup with it.
	 *
	 * <p>The {@code ctrmap.vault.root} property redirects this, and exists so a
	 * suite can exercise sealing without writing into the machine's real vault.
	 * That is not a convenience: a test that sealed into the user's own vault
	 * could refuse their next real seal - {@link #seal} never overwrites a
	 * sealed entry - and the failure would look like the vault being broken.
	 */
	public static File root() {
		String override = System.getProperty("ctrmap.vault.root");
		if (override != null && !override.isEmpty()) {
			return new File(override);
		}
		String local = System.getenv("LOCALAPPDATA");
		if (local == null || local.isEmpty()) {
			local = System.getProperty("user.home");
		}
		return new File(local, "CTRMap" + File.separator + "vault");
	}

	/**
	 * The vault folder for a dump, named by its title id.
	 *
	 * <p>A 3DS RomFS dump sits in a folder named for the title, which is what
	 * makes one dump distinguishable from another of a different region. When
	 * the folder is not named that way the game type is used instead, which
	 * still separates ORAS from XY but cannot separate two regions - so
	 * {@link #seal} records the detected name in the manifest and
	 * {@link #wouldOverwriteDifferentGame} checks it before writing.
	 */
	public static File entryDir(File dumpRoot, GameProfile profile) {
		String name = dumpRoot == null ? null : dumpRoot.getName();
		String key = looksLikeTitleId(name) ? name
				: ("game-" + (profile != null ? profile.type().name() : "UNKNOWN"));
		return new File(root(), key);
	}

	static boolean looksLikeTitleId(String s) {
		if (s == null || s.length() != 16) {
			return false;
		}
		for (int i = 0; i < 16; i++) {
			char c = Character.toLowerCase(s.charAt(i));
			if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
				return false;
			}
		}
		return true;
	}

	/** True once a manifest exists, which happens only after every file is written. */
	public static boolean isSealed(File entry) {
		return VaultManifest.file(entry).isFile();
	}

	/**
	 * True when a seal was started here and did not finish.
	 *
	 * <p>Worth asking separately from {@link #isSealed}, because the two
	 * absences mean different things: nothing here at all is a workspace that
	 * has not been vaulted, while a marker with no manifest is a vault someone
	 * will otherwise trust. The second must be visible.
	 */
	public static boolean isInterrupted(File entry) {
		return marker(entry).isFile() && !isSealed(entry);
	}

	private static File marker(File entry) {
		return new File(entry, "sealing.in-progress");
	}

	/** What happened, in a form the UI and a test can both read. */
	public static final class SealResult {

		public boolean sealed;
		public File entry;
		public VaultManifest manifest;
		/** Empty when all is well; otherwise why not, in plain language. */
		public String problem = "";
		public long bytesWritten;
		public int filesWritten;
	}

	/** Told how far along a seal is, so a 1.8 GB copy is not a frozen window. */
	public interface Progress {

		/** Return false to abort; an aborted seal leaves the marker, never a manifest. */
		boolean at(int filesDone, int filesTotal, String currentRelPath);
	}

	/**
	 * Copies a dump into its vault entry, once.
	 *
	 * <p>Refuses rather than overwrite: a sealed entry is never rewritten, so a
	 * later, already-edited dump cannot quietly replace the pristine record.
	 * That is the same rule {@code snapshotOriginals} follows and for the same
	 * reason - re-taking a snapshot against edited archives bakes the edits in
	 * as "pristine".
	 */
	public static SealResult seal(File dumpRoot, Scope scope, Progress progress) {
		SealResult out = new SealResult();
		DumpCheck.Result dc = DumpCheck.check(dumpRoot);
		GameProfile profile = dc.profile;
		File entry = entryDir(dumpRoot, profile);
		out.entry = entry;

		if (isSealed(entry)) {
			VaultManifest existing = VaultManifest.read(entry);
			if (existing != null && wouldOverwriteDifferentGame(existing, dc)) {
				out.problem = "That vault already holds " + existing.displayName
						+ ", and this folder is " + dc.gameName()
						+ ". Refusing to replace one game's backup with another's.";
				return out;
			}
			out.sealed = true;
			out.manifest = existing;
			return out;                       // already vaulted; nothing to do
		}

		List<String> rel = filesToSeal(dumpRoot, scope, profile);
		if (rel.isEmpty()) {
			out.problem = "Nothing to back up was found in " + dumpRoot
					+ " - it does not look like a game dump.";
			return out;
		}

		VaultManifest man = new VaultManifest();
		man.titleId = looksLikeTitleId(dumpRoot.getName()) ? dumpRoot.getName() : "";
		man.gameType = profile != null ? profile.type().name() : "UNKNOWN";
		man.displayName = dc.gameName();
		man.scope = scope.name();
		man.sealedAt = System.currentTimeMillis();
		man.takenFrom = dumpRoot.getAbsolutePath();
		//a dump that did not pass the check is still worth keeping - it may be
		//the only copy the user has - but it must never be mistaken for retail
		man.verifiedVanilla = dc.usable();
		man.dumpCheckStatus = dc.status != null ? dc.status.name() : "UNKNOWN";
		man.dumpCheckHeadline = dc.headline == null ? "" : dc.headline;

		entry.mkdirs();
		try {
			writeMarker(entry);
			if (scope == Scope.FULL_COMPRESSED) {
				sealZipped(dumpRoot, entry, rel, man, progress, out);
			} else {
				sealRaw(dumpRoot, entry, rel, man, progress, out);
			}
			if (out.problem.isEmpty()) {
				//LAST. Until this line runs there is no manifest, so nothing
				//can read this entry as a finished backup.
				VaultManifest.write(entry, man);
				marker(entry).delete();
				out.sealed = true;
				out.manifest = man;
			}
		} catch (IOException | RuntimeException ex) {
			out.problem = "The backup could not be completed: "
					+ (ex.getMessage() != null ? ex.getMessage() : ex.toString())
					+ "\nThe part-written vault is marked unfinished and will not be trusted.";
		}
		return out;
	}

	private static void writeMarker(File entry) throws IOException {
		OutputStream os = new FileOutputStream(marker(entry));
		try {
			os.write(("A backup was started here and has not finished.\r\n"
					+ "Nothing in this folder should be trusted as a complete copy.\r\n")
					.getBytes("UTF-8"));
		} finally {
			os.close();
		}
	}

	private static void sealRaw(File dumpRoot, File entry, List<String> rel, VaultManifest man,
			Progress progress, SealResult out) throws IOException {
		File filesDir = new File(entry, "files");
		int i = 0;
		for (String r : rel) {
			if (progress != null && !progress.at(i, rel.size(), r)) {
				out.problem = "The backup was stopped before it finished.";
				return;
			}
			File src = new File(dumpRoot, r);
			File dst = new File(filesDir, r);
			dst.getParentFile().mkdirs();
			man.entries.put(r, copyAndDigest(src, dst));
			out.filesWritten++;
			out.bytesWritten += src.length();
			i++;
		}
	}

	private static void sealZipped(File dumpRoot, File entry, List<String> rel, VaultManifest man,
			Progress progress, SealResult out) throws IOException {
		File zip = new File(entry, "dump.zip");
		ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip));
		try {
			int i = 0;
			for (String r : rel) {
				if (progress != null && !progress.at(i, rel.size(), r)) {
					out.problem = "The backup was stopped before it finished.";
					return;
				}
				File src = new File(dumpRoot, r);
				zos.putNextEntry(new ZipEntry(r));
				man.entries.put(r, copyAndDigest(src, zos));
				zos.closeEntry();
				out.filesWritten++;
				out.bytesWritten += src.length();
				i++;
			}
		} finally {
			zos.close();
		}
	}

	/** Copies and digests in ONE pass - a 1.8 GB dump should not be read twice. */
	private static VaultManifest.Entry copyAndDigest(File src, File dst) throws IOException {
		OutputStream os = new FileOutputStream(dst);
		try {
			return copyAndDigest(src, os);
		} finally {
			os.close();
		}
	}

	private static VaultManifest.Entry copyAndDigest(File src, OutputStream os) throws IOException {
		MessageDigest md = sha256();
		InputStream is = new FileInputStream(src);
		byte[] buf = new byte[1 << 16];
		long total = 0;
		try {
			int n;
			while ((n = is.read(buf)) > 0) {
				os.write(buf, 0, n);
				md.update(buf, 0, n);
				total += n;
			}
		} finally {
			is.close();
		}
		VaultManifest.Entry e = new VaultManifest.Entry();
		e.size = total;
		e.mtime = src.lastModified();
		e.sha256 = hex(md.digest());
		return e;
	}

	static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 is required and this JRE does not have it", ex);
		}
	}

	static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (int i = 0; i < b.length; i++) {
			sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b[i] & 0xF, 16));
		}
		return sb.toString();
	}

	/** True when this entry belongs to a different game than the folder being sealed. */
	static boolean wouldOverwriteDifferentGame(VaultManifest existing, DumpCheck.Result dc) {
		if (existing == null || dc == null || dc.profile == null) {
			return false;
		}
		return !existing.gameType.equals(dc.profile.type().name());
	}

	private static List<String> filesToSeal(File dumpRoot, Scope scope, GameProfile profile) {
		List<String> out = new ArrayList<>();
		if (dumpRoot == null || !dumpRoot.isDirectory()) {
			return out;
		}
		if (scope == Scope.MODDABLE) {
			if (profile != null) {
				for (Workspace.ArchiveType t : ModDeployer.MODDABLE) {
					String rel = profile.archivePath(t);
					//a profile returning null means "not present or not verified
					//for this game" - absence, never a guess. Skip it rather
					//than invent a path that would silently back up nothing.
					if (rel != null && new File(dumpRoot, rel).isFile()) {
						out.add(rel.replace('\\', '/').replaceAll("^/+", ""));
					}
				}
			}
			//the executable, because an IPS patch alters it and nothing else here would
			for (String exe : new String[]{"code.bin", "exefs/code.bin", "../code.bin"}) {
				File f = new File(dumpRoot, exe);
				if (f.isFile()) {
					out.add(exe);
				}
			}
		} else {
			collect(dumpRoot, "", out);
		}
		Collections.sort(out);
		return out;
	}

	private static void collect(File dir, String prefix, List<String> out) {
		File[] kids = dir.listFiles();
		if (kids == null) {
			return;
		}
		//sorted so a seal is reproducible and two runs can be compared
		java.util.Arrays.sort(kids);
		for (File k : kids) {
			String rel = prefix.isEmpty() ? k.getName() : prefix + "/" + k.getName();
			if (k.isDirectory()) {
				collect(k, rel, out);
			} else if (k.isFile()) {
				out.add(rel);
			}
		}
	}

	// ---- verifying ---------------------------------------------------------

	/** One file that is not what it was when the vault was sealed. */
	public static final class Drift {

		public String relPath;
		public String what;

		Drift(String relPath, String what) {
			this.relPath = relPath;
			this.what = what;
		}

		@Override
		public String toString() {
			return relPath + ": " + what;
		}
	}

	/**
	 * Checks the VAULT against its own manifest - has the backup rotted?
	 *
	 * <p>Cheap first: size and modification time, which catch the ordinary
	 * cases without reading a gigabyte. A digest is computed only where those
	 * disagree, or when {@code deep} asks for it. Never repairs anything:
	 * silently fixing a backup is how a backup stops being evidence.
	 */
	public static List<Drift> verify(File entry, boolean deep) {
		List<Drift> bad = new ArrayList<>();
		VaultManifest man = VaultManifest.read(entry);
		if (man == null) {
			bad.add(new Drift("", isInterrupted(entry)
					? "this backup was never finished - it is missing its manifest, so nothing in it can be trusted"
					: "there is no backup here"));
			return bad;
		}
		boolean zipped = Scope.FULL_COMPRESSED.name().equals(man.scope);
		ZipFile zf = null;
		try {
			if (zipped) {
				zf = new ZipFile(new File(entry, "dump.zip"));
			}
			for (Map.Entry<String, VaultManifest.Entry> e : man.entries.entrySet()) {
				VaultManifest.Entry want = e.getValue();
				if (zipped) {
					ZipEntry ze = zf.getEntry(e.getKey());
					if (ze == null) {
						bad.add(new Drift(e.getKey(), "missing from the backup archive"));
						continue;
					}
					if (deep) {
						String got = digestOf(zf.getInputStream(ze));
						if (!want.sha256.equals(got)) {
							bad.add(new Drift(e.getKey(), "contents have changed since the backup was sealed"));
						}
					} else if (ze.getSize() >= 0 && ze.getSize() != want.size) {
						bad.add(new Drift(e.getKey(), "size has changed since the backup was sealed"));
					}
				} else {
					File f = new File(new File(entry, "files"), e.getKey());
					if (!f.isFile()) {
						bad.add(new Drift(e.getKey(), "missing from the backup"));
						continue;
					}
					boolean cheapOk = f.length() == want.size && f.lastModified() == want.mtime;
					if (!cheapOk || deep) {
						String got = digestOf(new FileInputStream(f));
						if (!want.sha256.equals(got)) {
							bad.add(new Drift(e.getKey(), "contents have changed since the backup was sealed"));
						}
					}
				}
			}
		} catch (IOException ex) {
			bad.add(new Drift("", "the backup could not be read: "
					+ (ex.getMessage() != null ? ex.getMessage() : ex.toString())));
		} finally {
			if (zf != null) {
				try {
					zf.close();
				} catch (IOException ignored) {
				}
			}
		}
		return bad;
	}

	private static String digestOf(InputStream is) throws IOException {
		MessageDigest md = sha256();
		byte[] buf = new byte[1 << 16];
		try {
			int n;
			while ((n = is.read(buf)) > 0) {
				md.update(buf, 0, n);
			}
		} finally {
			is.close();
		}
		return hex(md.digest());
	}

	// ---- restoring ---------------------------------------------------------

	/**
	 * Puts ONE file back where it came from.
	 *
	 * <p>This is the granularity that matters. A stale pack once corrupted a
	 * single archive here; restoring the whole game to fix it would have thrown
	 * away every edit made since. Returns an empty string on success, or why
	 * not.
	 */
	public static String restoreOne(File entry, String relPath, File targetRoot) {
		VaultManifest man = VaultManifest.read(entry);
		if (man == null) {
			return "There is no finished backup to restore from.";
		}
		VaultManifest.Entry want = man.entries.get(relPath);
		if (want == null) {
			return "The backup does not contain " + relPath + ".";
		}
		File dst = new File(targetRoot, relPath);
		try {
			dst.getParentFile().mkdirs();
			if (Scope.FULL_COMPRESSED.name().equals(man.scope)) {
				ZipFile zf = new ZipFile(new File(entry, "dump.zip"));
				try {
					ZipEntry ze = zf.getEntry(relPath);
					if (ze == null) {
						return "The backup archive is missing " + relPath
								+ " - it is listed in the manifest but not present, so this backup is damaged.";
					}
					pipe(zf.getInputStream(ze), dst);
				} finally {
					zf.close();
				}
			} else {
				File src = new File(new File(entry, "files"), relPath);
				if (!src.isFile()) {
					return "The backup is missing " + relPath
							+ " - it is listed in the manifest but not present, so this backup is damaged.";
				}
				pipe(new FileInputStream(src), dst);
			}
		} catch (IOException ex) {
			return "Could not restore " + relPath + ": "
					+ (ex.getMessage() != null ? ex.getMessage() : ex.toString());
		}
		//restoring must land the bytes that were sealed, not merely some bytes
		String got;
		try {
			got = digestOf(new FileInputStream(dst));
		} catch (IOException ex) {
			return "Restored " + relPath + " but could not read it back to check it.";
		}
		if (!want.sha256.equals(got)) {
			return "Restored " + relPath + " but it does not match the backup - the vault is damaged.";
		}
		return "";
	}

	/**
	 * Puts the whole game back. Returns the files that could not be restored,
	 * each with why; empty means every one landed and verified.
	 */
	public static List<String> restoreAll(File entry, File targetRoot, Progress progress) {
		List<String> bad = new ArrayList<>();
		VaultManifest man = VaultManifest.read(entry);
		if (man == null) {
			bad.add("There is no finished backup to restore from.");
			return bad;
		}
		int i = 0, n = man.entries.size();
		for (String rel : new ArrayList<>(man.entries.keySet())) {
			if (progress != null && !progress.at(i, n, rel)) {
				bad.add("The restore was stopped after " + i + " of " + n + " file(s).");
				return bad;
			}
			String why = restoreOne(entry, rel, targetRoot);
			if (!why.isEmpty()) {
				bad.add(why);
			}
			i++;
		}
		return bad;
	}

	private static void pipe(InputStream is, File dst) throws IOException {
		OutputStream os = new FileOutputStream(dst);
		byte[] buf = new byte[1 << 16];
		try {
			int n;
			while ((n = is.read(buf)) > 0) {
				os.write(buf, 0, n);
			}
		} finally {
			try {
				is.close();
			} finally {
				os.close();
			}
		}
	}

	/** Every sealed vault on this machine, newest first. Never null. */
	public static List<VaultManifest> list() {
		List<VaultManifest> out = new ArrayList<>();
		File[] kids = root().listFiles();
		if (kids == null) {
			return out;
		}
		Map<Long, VaultManifest> byTime = new LinkedHashMap<>();
		for (File k : kids) {
			if (!k.isDirectory()) {
				continue;
			}
			VaultManifest m = VaultManifest.read(k);
			if (m != null) {
				m.entryDir = k;
				byTime.put(m.sealedAt, m);
			}
		}
		out.addAll(byTime.values());
		Collections.sort(out, new java.util.Comparator<VaultManifest>() {
			@Override
			public int compare(VaultManifest a, VaultManifest b) {
				return Long.compare(b.sealedAt, a.sealedAt);
			}
		});
		return out;
	}
}
