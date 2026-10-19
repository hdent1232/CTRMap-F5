package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.List;

/**
 * One-click LayeredFS mod deployment. Instead of the user hand-copying archives
 * with a shell script, this content-diffs each moddable RomFS archive against
 * the pristine snapshot (see {@link Workspace#snapshotOriginals()}) and copies
 * only the ones that actually changed into an emulator/console mod folder,
 * plus an optional executable code patch.
 *
 * <p>Content-diffing by DECOMPRESSED content matters: Pack Workspace recompresses
 * every archive (CTRMap's LZ11 differs from Nintendo's), so the container bytes
 * change even when nothing was edited - shipping those is pointless and can even
 * break the game. Only archives whose actual data differs are deployed.
 *
 * <p>Layout written:
 * <pre>
 *   &lt;modRoot&gt;/romfs/a/0/1/3   (changed archives, mirroring the RomFS tree)
 *   &lt;modRoot&gt;/exefs/code.ips  (the code patch, if supplied)
 * </pre>
 * For Azahar this is {@code %APPDATA%/Azahar/load/mods/<titleId>/}; the same
 * layout works for Citra and for a Luma3DS SD card copy.
 */
public class ModDeployer {

	/** RomFS archives CTRMap can edit (STORYTEXT included; it is lazily loaded but deployable). */
	public static final ArchiveType[] MODDABLE = {
		ArchiveType.ZONE_DATA,
		ArchiveType.GAMETEXT,
		ArchiveType.STORYTEXT,
		ArchiveType.FIELD_DATA,
		ArchiveType.MAP_MATRIX,
		ArchiveType.AREA_DATA,
		ArchiveType.BUILDING_MODELS,
		ArchiveType.NPC_REGISTRIES,
		ArchiveType.MOVE_MODELS,
		ArchiveType.TRAINER_DATA,
		ArchiveType.TRAINER_POKE,
		ArchiveType.MAISON_SET_POOL_A,
		ArchiveType.MAISON_CLASS_LIST_A,
		ArchiveType.MAISON_SET_POOL_B,
		ArchiveType.MAISON_CLASS_LIST_B,
		ArchiveType.MAISON_SET_POOL_C
	};

	/**
	 * Archives the editor writes IN PLACE - a seek and a fixed-size record -
	 * rather than by extracting and repacking.
	 *
	 * <p>WHY THIS IS A SEPARATE LIST AND NOT MORE OF {@link #MODDABLE}. That
	 * list is also the pristine snapshot's contract, and the snapshot refuses to
	 * complete itself from a game that has been in use ({@link
	 * Workspace#snapshotOriginals}) - deliberately, after six archives in the
	 * author's own workspace were captured from an already-edited game and
	 * recorded as retail. So an archive added to MODDABLE today can never be
	 * captured into a workspace stamped yesterday: every existing workspace
	 * would report a permanently partial backup, on every pack, and be advised
	 * to delete it - which for an already-edited game is the worst advice this
	 * program could give.
	 *
	 * <p>These archives carry their own pre-edit copy instead, taken at the last
	 * moment it is provably retail: the first time an edit to them is staged
	 * ({@link ctrmap.formats.pokedata.ItemTable#baselineArchive}). Deploy diffs
	 * against that, so it ships them when they were edited and not otherwise.
	 * Staged is not packed: an item edit reaches the game, and so a deploy, the
	 * way every other edit does - through Pack Workspace (F082).
	 */
	public static final ArchiveType[] MODDABLE_IN_PLACE = {
		ArchiveType.ITEM_DATA
	};

	/** Every archive the editor can write, however it writes it - for a backup that must be whole. */
	public static List<ArchiveType> allWritableArchives() {
		List<ArchiveType> out = new ArrayList<>(Arrays.asList(MODDABLE));
		out.addAll(Arrays.asList(MODDABLE_IN_PLACE));
		return out;
	}

	public static class Result {
		public final List<String> deployed = new ArrayList<>();
		public int unchanged = 0;
		public final List<String> skipped = new ArrayList<>();
		/** Archives back to retail whose copy from an earlier deploy was taken out of the mod, and where it went. */
		public final List<String> removed = new ArrayList<>();
		public boolean codeIpsDeployed = false;
		public File modRoot;
	}

	/**
	 * Default Azahar mods folder for a title on this machine's platform, or null if the platform's
	 * user-data folder cannot be named OR no title id is known. A null id used to build
	 * "...\mods\null"; a folder named after nothing is not a suggestion.
	 */
	public static File azaharModRoot(String titleId) {
		return azaharModRoot(titleId, System.getProperty("os.name"), System.getenv(), System.getProperty("user.home"));
	}

	/**
	 * The same, for a named platform (F023). Only the Windows folder was known - %APPDATA% - so on
	 * macOS and Linux, where the portable release is advertised to run, the Deploy dialog opened with
	 * no folder and the user had to find Azahar's themselves. Each platform's user-data folder by its
	 * own convention: %APPDATA%\Azahar on Windows, ~/Library/Application Support/Azahar on macOS,
	 * and $XDG_DATA_HOME/azahar-emu (~/.local/share/azahar-emu when unset) everywhere else. Null
	 * when the platform's folder cannot be named, or no title id is known.
	 */
	public static File azaharModRoot(String titleId, String osName, java.util.Map<String, String> env, String home) {
		if (titleId == null) {
			return null;
		}
		String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
		File user;
		if (os.startsWith("windows")) {
			String appdata = env.get("APPDATA");
			user = appdata == null || appdata.isEmpty() ? null : new File(appdata, "Azahar");
		} else if (os.startsWith("mac")) {
			user = home == null || home.isEmpty() ? null : new File(new File(new File(home, "Library"), "Application Support"),
					"Azahar");
		} else {
			String xdg = env.get("XDG_DATA_HOME");
			user = xdg != null && !xdg.isEmpty() ? new File(xdg, "azahar-emu")
					: home == null || home.isEmpty() ? null : new File(new File(new File(home, ".local"), "share"), "azahar-emu");
		}
		return user == null ? null : new File(new File(new File(user, "load"), "mods"), titleId);
	}

	/**
	 * The dumped game's title id, as the dump says it - the folder's own name, its parent's, or
	 * the ExHeader beside the romfs ({@link ctrmap.vault.Vault#titleIdOf}, the one reader) - and
	 * null when it does not say. It fell back to the profile's first version, so an Alpha
	 * Sapphire or Y dump in a folder not named by its id was deployed into Omega Ruby's or X's
	 * mods folder and never loaded, while Deploy reported success (F238); null makes the deploy
	 * dialog ask which version it is ({@link #titleChoices}).
	 *
	 * <p>The fallback was {@code isXY() ? X : Omega Ruby}. "Not X/Y" is three
	 * games, so a Sun/Moon or Ultra Sun/Ultra Moon workspace was offered Omega
	 * Ruby's mods folder: a mod deployed where the game it was built for never
	 * looks, and where another game does. Null instead means the deploy dialog
	 * offers no folder and the user says which, which is the honest answer for
	 * a game whose id nobody recorded.
	 */
	public static String guessTitleId() {
		if (Workspace.GAMEDIR_PATH == null || Workspace.GAMEDIR_PATH.trim().isEmpty()) {
			return null;
		}
		String tid = ctrmap.vault.Vault.titleIdOf(new File(Workspace.GAMEDIR_PATH));
		return tid == null ? null : tid.toUpperCase();
	}

	/** The first line Deploy's title box shows when the dump does not say which version it is. */
	public static final String PICK_VERSION = "(which version did you dump? its folder does not say)";

	/**
	 * What Deploy offers as the title (F238): the dump's own id when it says one, else a line
	 * asking which, then every version the open game's profile covers - never one of them already
	 * picked as if it had been read.
	 */
	public static String[] titleChoices(String detected) {
		if (detected != null) {
			return new String[]{detected};
		}
		List<String> out = new ArrayList<>();
		out.add(PICK_VERSION);
		if (Workspace.isValid()) {
			for (java.util.Map.Entry<String, String> v : Workspace.profile().titleIds().entrySet()) {
				out.add(v.getKey() + " - " + v.getValue());
			}
		}
		return out.toArray(new String[0]);
	}

	/** The title id a choice starts with, or null for the question itself or anything else. */
	public static String titleIdIn(Object choice) {
		String s = choice == null ? "" : choice.toString().trim();
		return s.length() >= 16 && s.substring(0, 16).matches("[0-9a-fA-F]{16}") ? s.substring(0, 16).toUpperCase() : null;
	}

	/**
	 * The mod folder the deploy dialog shows once a title is picked: the one that title's id names on this machine,
	 * or {@code current} when it names none (F238). Out of the combo box's listener, where no suite reached it.
	 */
	public static String folderForTitle(Object titleItem, String current) {
		File picked = azaharModRoot(titleIdIn(titleItem));
		return picked != null ? picked.getAbsolutePath() : current;
	}

	/**
	 * Deploys the current workspace's edits as a LayeredFS mod at modRoot: only
	 * archives whose decompressed contents differ from the pristine snapshot are
	 * copied into {@code modRoot/romfs/...}; if codeIps is non-null it is copied
	 * to {@code modRoot/exefs/code.ips}.
	 */
	public static Result deploy(File modRoot, File codeIps) {
		return deploy(modRoot, codeIps, Workspace.GAMEDIR_PATH);
	}

	/**
	 * {@link #deploy(File, File)} against the game folder {@code gameDir}, read by the caller. A worker hands it in:
	 * the game folder is a plain static the event thread writes, and the deploy's worker read it (EdtReachTest, 2026-10-12).
	 */
	@ctrmap.util.NotAnEdit("it copies the archives the workspace changed, and code.ips, into the emulator's mod"
		+ " folder: the program's output, made again by the next deploy, while the workspace keeps every edit")
	public static Result deploy(File modRoot, File codeIps, String gameDir) {
		//NEVER ON THE EVENT THREAD (census M.63). A deploy compares every moddable archive with the retail copy -
		//two whole archives parsed and every entry of both inflated, hundreds of megabytes - and it ran inside the
		//pack worker's done(), after the progress dialog had closed: the window froze with nothing on it and
		//looked hung. Refused here, so no caller can put it back there
		if (java.awt.EventQueue.isDispatchThread()) {
			throw new IllegalStateException("a deploy reads every moddable archive and runs for minutes, so it is never"
					+ " run on the event thread, where it freezes the window");
		}
		//DEPLOYING ON TOP OF A PARKED COPY MAKES THE SWITCH UNUSABLE. Turning the
		//mod off MOVES the folder aside rather than deleting it - that is the promise
		//("nothing is deleted, save data is never touched") and it is why the switch is
		//safe. But deploying again while a parked copy is still there leaves BOTH on
		//disk, and the window's toggle asks isParked first, so it then offers "turn the
		//mod back ON" and tries to move the parked copy over a folder that now exists.
		//The user gets "already exists, refusing to overwrite" and a path, from a menu
		//item that had been working a moment earlier.
		//
		//Refused here rather than resolved for them: the parked copy is a deploy they
		//chose to keep, and picking which one survives is theirs to make. Found by
		//WorkflowGuardsTest driving deploy -> off -> deploy -> off.
		File alreadyParked = parkedRoot(modRoot);
		if (alreadyParked.isDirectory() && modRoot != null && !modRoot.isDirectory()) {
			throw new IllegalStateException("This mod is currently switched OFF - its files are"
				+ " parked at:\n  " + alreadyParked.getAbsolutePath()
				+ "\n\nTurn the mod back ON first, then deploy over it. Deploying now would"
				+ " leave two copies and the on/off switch would not know which one to use.");
		}
		Result r = new Result();
		r.modRoot = modRoot;
		WorkspaceSession session = Workspace.session();
		File romfsOut = new File(modRoot, "romfs");
		for (ArchiveType t : MODDABLE) {
			String rel = Workspace.getArchivePath(t, Workspace.game());
			if (rel == null) {
				continue;
			}
			File live = new File(gameDir + rel);
			if (!live.exists()) {
				continue;
			}
			boolean changed;
			try {
				//WHAT SHIPPED, FROM THE ONE PRISTINE READER (F232): the snapshot, else the sealed backup.
				//With neither, the archive is deployed whole - a copy too many is harmless, a missed edit
				//is not - and a damaged backup throws, so the archive is reported skipped with the reason
				//rather than compared against a copy nobody could read.
				File orig = session == null ? null : session.pristineArchive(t);
				changed = orig == null || !garcContentsEqual(live, orig);
			} catch (Exception ex) {
				r.skipped.add(rel + " (compare failed: " + ctrmap.util.Bytes.reason(ex) + ")");
				continue;
			}
			if (!changed) {
				r.unchanged++;
				takeOutOfTheMod(modRoot, romfsOut, rel, r);
				continue;
			}
			try {
				copyFile(live, new File(romfsOut.getAbsolutePath() + rel));
				r.deployed.add(rel);
			} catch (IOException ex) {
				r.skipped.add(rel + " (copy failed: " + ctrmap.util.Bytes.reason(ex) + ")");
			}
		}
		//The in-place archives are diffed against the copy the editor took
		//before its FIRST write, not against the pristine snapshot, because
		//they are deliberately not part of the snapshot's contract (see
		//MODDABLE_IN_PLACE). No copy means the editor has never written the
		//archive, so there is nothing of the user's in it and nothing to ship.
		if (ctrmap.formats.pokedata.ItemTable.changedSinceBaseline(Workspace.session())) {
			String rel = Workspace.getArchivePath(ArchiveType.ITEM_DATA, Workspace.game());
			File live = ctrmap.formats.pokedata.ItemTable.archiveFile(Workspace.session());
			if (rel != null && live != null) {
				try {
					copyFile(live, new File(romfsOut.getAbsolutePath() + rel));
					r.deployed.add(rel);
				} catch (IOException ex) {
					r.skipped.add(rel + " (copy failed: " + ctrmap.util.Bytes.reason(ex) + ")");
				}
			}
		} else if (ctrmap.formats.pokedata.ItemTable.baselineArchive(Workspace.session()) != null) {
			//written once, now back to its baseline: the same as any archive back to retail. With
			//no baseline the editor never wrote it, so a copy in the mod is not one this deploy made.
			String rel = Workspace.getArchivePath(ArchiveType.ITEM_DATA, Workspace.game());
			if (rel != null) {
				takeOutOfTheMod(modRoot, romfsOut, rel, r);
			}
		}
		if (codeIps != null && codeIps.exists()) {
			try {
				copyFile(codeIps, new File(new File(modRoot, "exefs"), "code.ips"));
				r.codeIpsDeployed = true;
			} catch (IOException ex) {
				r.skipped.add("exefs/code.ips (copy failed: " + ctrmap.util.Bytes.reason(ex) + ")");
			}
		}
		return r;
	}

	/**
	 * BACK TO RETAIL IS A CHANGE TOO (F021). Deploy only ever added: an archive shipped once and
	 * since put back to retail - Restore from vault, Remove added zones, an edit undone by hand -
	 * was reported "unchanged, skipped" while the modified copy shipped last time stayed in the
	 * mod, and the game kept loading it. The copy is taken out of the load path. MOVED, never
	 * deleted, as the on/off switch moves rather than deletes: it may be something the user put
	 * there, and a copy set aside costs nothing.
	 */
	private static void takeOutOfTheMod(File modRoot, File romfsOut, String rel, Result r) {
		File stale = new File(romfsOut.getAbsolutePath() + rel);
		if (!stale.exists()) {
			return;
		}
		File kept = new File(removedRoot(modRoot).getAbsolutePath() + File.separator + "romfs" + rel);
		for (int n = 1; kept.exists(); n++) {
			kept = new File(new File(removedRoot(modRoot).getAbsolutePath() + File.separator + "romfs" + rel)
					.getPath() + "." + n);
		}
		try {
			kept.getParentFile().mkdirs();
			java.nio.file.Files.move(stale.toPath(), kept.toPath());
			r.removed.add(rel + "  (its old copy was moved to " + kept.getAbsolutePath() + ")");
		} catch (IOException | RuntimeException ex) {
			r.skipped.add(rel + " is back to retail, but the copy an earlier deploy left in the mod could not"
					+ " be moved out, so the game still loads it: " + ctrmap.util.Bytes.reason(ex));
		}
	}

	/** Where copies taken out of a mod are set aside: beside the mods folder, out of the load path. */
	public static File removedRoot(File modRoot) {
		File parent = modRoot.getParentFile();
		if (parent != null && parent.getName().equalsIgnoreCase("mods")) {
			return new File(new File(parent.getParentFile(), "mods_removed"), modRoot.getName());
		}
		return new File(parent, modRoot.getName() + "__removed");
	}

	/**
	 * Where a switched-off mod is parked: a sibling of the mods folder, so the
	 * emulator no longer sees it. Nothing is ever deleted - turning the mod back
	 * on is a move in the other direction.
	 */
	public static File parkedRoot(File modRoot) {
		File parent = modRoot.getParentFile();
		if (parent != null && parent.getName().equalsIgnoreCase("mods")) {
			return new File(new File(parent.getParentFile(), "mods_disabled"), modRoot.getName());
		}
		return new File(parent, modRoot.getName() + "__off");
	}

	/** True when the mod folder is in the emulator's load path and has files in it. */
	public static boolean isDeployed(File modRoot) {
		if (modRoot == null || !modRoot.isDirectory()) {
			return false;
		}
		String[] kids = modRoot.list();
		return kids != null && kids.length > 0;
	}

	/** True when a previously deployed mod is sitting parked, ready to switch back on. */
	public static boolean isParked(File modRoot) {
		return modRoot != null && isDeployed(parkedRoot(modRoot));
	}

	/**
	 * Turns the mod OFF by moving it out of the emulator's load path, so the game
	 * boots completely stock. Returns where it was parked. Reversible with
	 * {@link #enable}; the user's save data is untouched either way.
	 */
	@ctrmap.util.NotAnEdit("it moves the deployed mod out of the emulator's load path: nothing is deleted, enable"
		+ " moves it back, and the workspace is not touched")
	public static File disable(File modRoot) throws IOException {
		File parked = parkedRoot(modRoot);
		moveDir(modRoot, parked);
		return parked;
	}

	/** Turns a parked mod back ON. Returns the mod folder it was restored to. */
	@ctrmap.util.NotAnEdit("it moves a parked mod back into the emulator's load path: nothing is deleted, disable"
		+ " moves it out again, and the workspace is not touched")
	public static File enable(File modRoot) throws IOException {
		moveDir(parkedRoot(modRoot), modRoot);
		return modRoot;
	}

	/** Move that falls back to copy+delete when src and dst are on different volumes. */
	private static void moveDir(File src, File dst) throws IOException {
		if (!src.isDirectory()) {
			throw new IOException("not there: " + src.getAbsolutePath());
		}
		if (dst.exists()) {
			throw new IOException("already exists, refusing to overwrite: " + dst.getAbsolutePath());
		}
		if (dst.getParentFile() != null) {
			dst.getParentFile().mkdirs();
		}
		try {
			Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE);
			return;
		} catch (IOException crossVolume) {
			//fall through to a manual copy
		}
		copyThenRemove(src, dst, Files::delete);
	}

	/** Removes one file or empty folder - {@code Files::delete}, or a suite's held file. */
	public interface Remover {
		void remove(java.nio.file.Path path) throws IOException;
	}

	/**
	 * The move's fallback - a copy, then the original removed - which ENDS WITH ONE COMPLETE MOD,
	 * never two and never a partial one (F022). Any IOException sends the move here (a file the
	 * emulator holds, not only another volume), and every delete's answer was dropped: the toggle
	 * said it worked with both folders left behind, and deploy and both toggles then refused for
	 * good ("already exists").
	 *
	 * <p>A file that cannot be removed means the original is now PART-removed, so its copy is the
	 * only whole one and is not thrown away first: what was removed is put back from it, then the
	 * copy goes, and the move is refused naming what is held. Only if putting back fails is the
	 * copy kept, and the message says where. Public so the suite can hand it a held file.
	 */
	public static void copyThenRemove(File src, File dst, Remover remover) throws IOException {
		try {
			copyTree(src, dst);
		} catch (IOException copyFailed) {
			List<String> left = removeTree(dst, Files::delete);
			throw new IOException("Could not copy " + src.getAbsolutePath() + " to " + dst.getAbsolutePath()
					+ ": " + ctrmap.util.Bytes.reason(copyFailed) + (left.isEmpty() ? ""
					: ". The partial copy could not be removed either: " + left), copyFailed);
		}
		List<String> held = removeTree(src, remover);
		if (held.isEmpty()) {
			return;
		}
		try {
			restoreMissing(dst, src);
		} catch (IOException cannotPutBack) {
			throw new IOException("Could not move " + src.getAbsolutePath() + ": " + held.size()
					+ " file(s) are held open (" + String.join("; ", held) + "), and the files already removed"
					+ " could not be put back (" + ctrmap.util.Bytes.reason(cannotPutBack) + "). The complete mod"
					+ " is at " + dst.getAbsolutePath() + " - close the emulator, then delete "
					+ src.getAbsolutePath() + " and try again.", cannotPutBack);
		}
		List<String> copyLeft = removeTree(dst, Files::delete);
		throw new IOException("Could not move " + src.getAbsolutePath() + ": " + held.size()
				+ " file(s) are held open - close the emulator and try again. Nothing was moved. Held: "
				+ String.join("; ", held) + (copyLeft.isEmpty() ? ""
				: ". The copy at " + dst.getAbsolutePath() + " could not be removed either: " + copyLeft));
	}

	private static void copyTree(File src, File dst) throws IOException {
		if (src.isDirectory()) {
			dst.mkdirs();
			File[] kids = src.listFiles();
			if (kids != null) {
				for (File k : kids) {
					copyTree(k, new File(dst, k.getName()));
				}
			}
		} else {
			copyFile(src, dst);
		}
	}

	/**
	 * Removes {@code f} and everything under it; answers what could NOT be removed, each with why.
	 * A folder still holding a file it could not remove is not asked - that answer is the file's.
	 */
	private static List<String> removeTree(File f, Remover remover) {
		List<String> held = new ArrayList<>();
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				held.addAll(removeTree(k, remover));
			}
		}
		if (!held.isEmpty() || !f.exists()) {
			return held;
		}
		try {
			remover.remove(f.toPath());
		} catch (IOException cannot) {
			held.add(f.getAbsolutePath() + " (" + ctrmap.util.Bytes.reason(cannot) + ")");
		}
		return held;
	}

	/** Copies back into {@code to} every file of {@code from} that {@code to} no longer has. */
	private static void restoreMissing(File from, File to) throws IOException {
		if (from.isDirectory()) {
			if (!to.isDirectory() && !to.mkdirs()) {
				throw new IOException("could not make " + to.getAbsolutePath());
			}
			File[] kids = from.listFiles();
			if (kids == null) {
				throw new IOException("cannot list " + from.getAbsolutePath());
			}
			for (File k : kids) {
				restoreMissing(k, new File(to, k.getName()));
			}
		} else if (!to.exists()) {
			copyFile(from, to);
		}
	}

	/** True when two GARCs hold identical decompressed contents (ignoring container/compression bytes). */
	public static boolean garcContentsEqual(File a, File b) {
		GARC ga;
		GARC gb;
		try {
			ga = new GARC(a);
			gb = new GARC(b);
		} catch (RuntimeException notAnArchive) {
			//A FILE THAT CANNOT BE READ IS NOT EVIDENCE OF SAMENESS. This used to answer
			//TRUE for two files that are not archives at all - it was pinned as a defect,
			//not endorsed - because a half-parsed GARC reported zero entries and two of
			//those matched. "Equal" here means "already shipped, skip it", so an archive
			//the deployer could not read was the one it decided not to deploy.
			Logger.getLogger(ModDeployer.class.getName()).log(Level.WARNING,
				"comparing " + a + " with " + b + ": " + ctrmap.util.Bytes.reason(notAnArchive));
			return false;
		}
		//AND THE SAME ANSWER WHEN IT DID NOT THROW. The refusal above was written when a
		//malformed archive raised from the constructor, so it was a guard standing on an
		//exception somebody else was obliged to keep throwing. GARC now RECORDS truncation
		//instead of raising - deliberately, so a partly-readable archive can still be opened
		//and read up to the cut - and the moment it did, two files that are not archives at
		//all compared EQUAL again: the constructor returned, both reported zero entries, and
		//the loop below had nothing to disagree about. Exactly the defect ModDeployerTest
		//pins, reintroduced by a change three files away that had no reason to look here.
		//Ask the archive whether it was fully read, which is a question it answers whatever
		//the constructor does.
		if (ga.readableEntries >= 0 || gb.readableEntries >= 0) {
			Logger.getLogger(ModDeployer.class.getName()).log(Level.WARNING,
				"comparing " + a + " with " + b + ": one of them is TRUNCATED, so they cannot"
				+ " be called identical - and identical here means 'already shipped, skip it'.");
			return false;
		}
		if (ga.length != gb.length) {
			return false;
		}
		//THE SAME BYTES ARE THE SAME ENTRIES (census M.63): both archives are whole by here, so two files byte for
		//byte alike hold the same decompressed entries, and the 14 archives a deploy did not touch were each
		//compared by inflating every entry of both - FieldData alone is 857 entries twice. Different bytes can
		//still be the same entries, packed differently, so those are compared entry by entry as before
		try {
			if (a.length() == b.length() && sameBytes(a, b)) {
				return true;
			}
		} catch (IOException unread) {
			//not a default: the deploy reports a comparison that threw as skipped, with this reason
			throw new java.io.UncheckedIOException("comparing " + a + " with " + b + ": " + ctrmap.util.Bytes.reason(unread), unread);
		}
		return sameEntries(ga, gb, a, b);
	}

	/**
	 * Whether two archives of one length hold the same decompressed entries, entry by entry - refusing, naming it,
	 * an entry either side cannot read.
	 */
	public static boolean sameEntries(GARC ga, GARC gb, File a, File b) {
		for (int i = 0; i < ga.length; i++) {
			byte[] ea = ga.getDecompressedEntry(i);
			byte[] eb = gb.getDecompressedEntry(i);
			//TWO FAILED READS ARE NOT THE SAME ENTRY (census M.105): getDecompressedEntry answers null for an entry
			//it could not read, and Arrays.equals(null, null) is true - so a failure at the same index on both
			//sides voted "unchanged" and the user's edit was not deployed, with nothing in the skipped list
			if (ea == null || eb == null) {
				throw new IllegalStateException("entry " + i + " of " + (ea == null ? a : b).getName()
						+ " could not be read, so it cannot be compared");
			}
			if (!Arrays.equals(ea, eb)) {
				return false;
			}
		}
		return true;
	}

	/** Whether two files of one length hold the same bytes, read a megabyte at a time. */
	public static boolean sameBytes(File a, File b) throws IOException {
		try (java.io.DataInputStream ia = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(a)));
				java.io.DataInputStream ib = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(b)))) {
			byte[] ba = new byte[1 << 20];
			byte[] bb = new byte[1 << 20];
			for (long left = a.length(); left > 0; ) {
				int n = (int) Math.min(ba.length, left);
				ia.readFully(ba, 0, n);
				ib.readFully(bb, 0, n);
				for (int k = 0; k < n; k++) {
					if (ba[k] != bb[k]) {
						return false;
					}
				}
				left -= n;
			}
			return true;
		}
	}

	private static void copyFile(File src, File dst) throws IOException {
		if (dst.getParentFile() != null) {
			dst.getParentFile().mkdirs();
		}
		Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}
}
