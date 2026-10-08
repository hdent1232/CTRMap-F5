package ctrmap.vault;

import ctrmap.ModDeployer;
import ctrmap.Ui;
import ctrmap.Workspace;
import ctrmap.gamedef.ArchiveType;
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
	 * The vault folder for a dump: named by its title id when the dump says one ({@link #titleIdOf}),
	 * else by its game.
	 *
	 * <p>THE ONE WAY AN ENTRY IS NAMED (F121). Seal named it with the game it detected, and the setup
	 * offer and the restore action with none, so a dump in a folder not named by its title was sealed
	 * under one name and looked for under another. Named by game, an entry cannot tell the two
	 * versions of a pair apart - Omega Ruby and Alpha Sapphire share a game and a display name - so a
	 * backup is never called a dump's until {@link #mismatch} has matched the two.
	 */
	public static File entryFor(File dumpRoot) {
		String tid = titleIdOf(dumpRoot);
		if (tid != null) {
			return new File(root(), tid);
		}
		GameProfile p = dumpRoot == null ? null : GameProfile.detect(dumpRoot);
		return new File(root(), "game-" + (p != null ? p.type().name() : "UNKNOWN"));
	}

	/**
	 * The dump's title id, from where the dump tools leave it: the folder's own name, its parent's (a
	 * romfs folder inside one named by the title), or the ExHeader beside the romfs. Null when none
	 * says - which is unknown, and never read as "the same as the backup".
	 */
	public static String titleIdOf(File dumpRoot) {
		if (dumpRoot == null) {
			return null;
		}
		if (looksLikeTitleId(dumpRoot.getName())) {
			return dumpRoot.getName();
		}
		File parent = dumpRoot.getAbsoluteFile().getParentFile();
		if (parent != null && looksLikeTitleId(parent.getName())) {
			return parent.getName();
		}
		for (File dir : new File[]{dumpRoot, parent}) {
			String tid = dir == null ? null : programIdIn(new File(dir, "exheader.bin"));
			if (tid != null) {
				return tid;
			}
		}
		return null;
	}

	/**
	 * The program id an ExHeader records at 0x200 - the first field of the ARM11 local system
	 * capabilities - when it is an application title's (high word 0x00040000); null otherwise, or
	 * when it cannot be read.
	 */
	static String programIdIn(File exheader) {
		//an ExHeader is 0x800 bytes (0x400 without its access descriptor): anything far larger is not one
		if (!exheader.isFile() || exheader.length() < PROGRAM_ID + 8 || exheader.length() > 0x10000) {
			return null;
		}
		byte[] b;
		try {
			b = java.nio.file.Files.readAllBytes(exheader.toPath());
		} catch (IOException unread) {
			return null;
		}
		long id = 0;
		for (int i = 7; i >= 0; i--) {
			id = (id << 8) | (b[PROGRAM_ID + i] & 0xFF);
		}
		return (id >>> 32) == 0x00040000L ? String.format("%016X", id) : null;
	}

	/** Where an ExHeader records the program id: the first field of its ARM11 local system capabilities. */
	static final int PROGRAM_ID = 0x200;

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
		File entry = entryFor(dumpRoot);
		out.entry = entry;

		if (isSealed(entry)) {
			VaultManifest existing = VaultManifest.read(entry);
			//ALREADY BACKED UP ONLY WHEN IT IS THIS DUMP'S (F121): the second of a version pair in a folder
			//not named by its title was told it was backed up, by the first one's backup
			String why = existing == null ? "The backup there cannot be read." : mismatch(existing, dumpRoot);
			if (!why.isEmpty()) {
				out.problem = "The vault holds a backup under that name, and it may not be this dump's:\n" + why
						+ "\nThis dump has NOT been backed up, and the backup there was left as it is.";
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
		String tid = titleIdOf(dumpRoot);
		man.titleId = tid != null ? tid : "";
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
					+ Ui.reason(ex)
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

	/**
	 * Why {@code dumpRoot} cannot be matched to the backup {@code m} records, or "" when it can: the
	 * same game, and the same title by id - or, when neither knows its id, the folder the backup was
	 * taken from (F121). Unknown is never taken for the same: a version pair shares a game and a
	 * display name, and only checking those let the second version be called backed up by the
	 * first's backup, and a restore write the first one's files into it.
	 */
	public static String mismatch(VaultManifest m, File dumpRoot) {
		GameProfile p = dumpRoot == null ? null : GameProfile.detect(dumpRoot);
		if (p != null && !p.type().name().equals(m.gameType)) {
			return "The backup is of " + m.displayName + ", and this folder is " + p.displayName() + ".";
		}
		String tid = titleIdOf(dumpRoot);
		boolean recorded = m.titleId != null && !m.titleId.isEmpty();
		if (tid != null && recorded) {
			return tid.equalsIgnoreCase(m.titleId) ? ""
					: "The backup is of title " + m.titleId + ", and this folder is title " + tid + ".";
		}
		if (tid == null && !recorded && dumpRoot != null && m.takenFrom != null
				&& new File(m.takenFrom).getAbsoluteFile().equals(dumpRoot.getAbsoluteFile())) {
			return "";
		}
		return "The backup is of " + m.displayName + (recorded ? " (title " + m.titleId + ")" : "") + ", taken from "
				+ m.takenFrom + ", and this folder's title id " + (tid != null ? "is " + tid : "cannot be read")
				+ ", so CTRMap cannot tell that they are the same version of the same game.\n"
				+ "Name the dump folder by its title id - the 16-digit number the dump tool shows - and try again.";
	}

	private static List<String> filesToSeal(File dumpRoot, Scope scope, GameProfile profile) {
		List<String> out = new ArrayList<>();
		if (dumpRoot == null || !dumpRoot.isDirectory()) {
			return out;
		}
		if (scope == Scope.MODDABLE) {
			if (profile != null) {
				//everything the editor can WRITE, not only what it repacks: the
				//item archive is written in place and would otherwise be missing
				//from a backup whose whole purpose is putting the game back
				for (ArchiveType t : ModDeployer.allWritableArchives()) {
					String rel = profile.archivePath(t);
					//a profile returning null means "not present or not verified
					//for this game" - absence, never a guess. Skip it rather
					//than invent a path that would silently back up nothing.
					if (rel != null && new File(dumpRoot, rel).isFile()) {
						out.add(key(rel));
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

	/**
	 * A path inside a dump as a backup keys it: forward slashes, no leading slash. The one spelling
	 * (F155) - the manifest stripped the slash the profile's paths start with and the restore looked
	 * up what it was handed, so the restore dialog's own suggestion, "/a/0/1/3", was refused, and so
	 * was a Windows user's "a\\0\\1\\3".
	 */
	public static String key(String relPath) {
		return relPath == null ? null : relPath.trim().replace('\\', '/').replaceAll("^/+", "");
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
		//a null resource is not closed, so the loose-file backup needs no second shape
		try (ZipFile zf = zipped ? new ZipFile(new File(entry, "dump.zip")) : null) {
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
						String got;
						try (InputStream in = new FileInputStream(f)) {
							got = digestOf(in);
						}
						if (!want.sha256.equals(got)) {
							bad.add(new Drift(e.getKey(), "contents have changed since the backup was sealed"));
						}
					}
				}
			}
		} catch (IOException ex) {
			bad.add(new Drift("", "the backup could not be read: "
					+ Ui.reason(ex)));
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
		relPath = key(relPath);
		VaultManifest man = VaultManifest.read(entry);
		if (man == null) {
			return "There is no finished backup to restore from.";
		}
		//NOTHING IS WRITTEN FROM ANOTHER GAME'S BACKUP (F121): the digest check after the write compares
		//against the backup's own manifest, so it passed another version's bytes
		String other = mismatch(man, targetRoot);
		if (!other.isEmpty()) {
			return "Nothing was restored: " + other;
		}
		File dst = new File(targetRoot, relPath);
		//STAGED BESIDE THE GAME FILE AND CHECKED BEFORE IT REPLACES IT (F140): the backup's bytes were
		//copied over the file first and checked after, so a damaged backup destroyed the very file it
		//was asked to repair, and a copy that failed half way left it cut short
		File staged = new File(dst.getParentFile(), dst.getName() + ".restoring");
		try {
			dst.getParentFile().mkdirs();
			String why = stageVerified(man, entry, relPath, staged);
			if (!why.isEmpty()) {
				return why.startsWith(DAMAGED) ? "Nothing was restored: " + why + " " + relPath + " was left as it was."
						: why;
			}
			ctrmap.util.Bytes.moveOver(staged, dst);
		} catch (IOException ex) {
			return "Could not restore " + relPath + ", and it was left as it was: " + Ui.reason(ex);
		} finally {
			staged.delete();   //gone already when it was moved over; a refused or failed copy is not left behind
		}
		return "";
	}

	/**
	 * One sealed file copied to {@code into} and checked against what was sealed: the game as it
	 * SHIPPED, verified, for a reader that needs retail bytes rather than the user's edits (F232).
	 * "" when {@code into} holds exactly the sealed bytes, or why not - and then nothing is left at
	 * {@code into}. Only from this dump's own backup (F121).
	 */
	public static String verifiedCopy(File entry, File dumpRoot, String relPath, File into) {
		relPath = key(relPath);
		VaultManifest man = VaultManifest.read(entry);
		if (man == null) {
			return "There is no finished backup to read " + relPath + " from.";
		}
		String other = mismatch(man, dumpRoot);
		if (!other.isEmpty()) {
			return "The backup is not this game's: " + other;
		}
		String why;
		try {
			into.getParentFile().mkdirs();
			why = stageVerified(man, entry, relPath, into);
		} catch (IOException ex) {
			why = "The backup's copy of " + relPath + " could not be read: " + Ui.reason(ex);
		}
		if (!why.isEmpty()) {
			into.delete();
		}
		return why;
	}

	/** How {@link #stageVerified} opens the answer for sealed bytes that do not match the manifest. */
	private static final String DAMAGED = "the backup's copy of ";

	/**
	 * {@code relPath}'s sealed bytes written to {@code staged} and checked against the manifest - the
	 * one reading of a sealed file, for a restore and for a verified copy alike. "" or why not.
	 */
	private static String stageVerified(VaultManifest man, File entry, String relPath, File staged) throws IOException {
		VaultManifest.Entry want = man.entries.get(relPath);
		if (want == null) {
			return "The backup does not contain " + relPath + ".";
		}
		if (Scope.FULL_COMPRESSED.name().equals(man.scope)) {
			ZipFile zf = new ZipFile(new File(entry, "dump.zip"));
			try {
				ZipEntry ze = zf.getEntry(relPath);
				if (ze == null) {
					return "The backup archive is missing " + relPath
							+ " - it is listed in the manifest but not present, so this backup is damaged.";
				}
				pipe(zf.getInputStream(ze), staged);
			} finally {
				zf.close();
			}
		} else {
			File src = new File(new File(entry, "files"), relPath);
			if (!src.isFile()) {
				return "The backup is missing " + relPath
						+ " - it is listed in the manifest but not present, so this backup is damaged.";
			}
			try (InputStream in = new FileInputStream(src)) {
				pipe(in, staged);
			}
		}
		//what lands must be the bytes that were sealed, not merely some bytes
		String got;
		try (InputStream in = new FileInputStream(staged)) {
			got = digestOf(in);
		}
		if (!want.sha256.equals(got)) {
			return DAMAGED + relPath + " does not match what was sealed - the vault is damaged.";
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
		String other = mismatch(man, targetRoot);
		if (!other.isEmpty()) {
			bad.add("Nothing was restored: " + other);
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
