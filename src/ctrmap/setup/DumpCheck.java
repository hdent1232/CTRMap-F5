package ctrmap.setup;

import ctrmap.Workspace;
import ctrmap.gamedef.GameProfile;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Decides whether a folder the user picked is a Pokemon game CTRMap can edit,
 * and when it is not, says what they actually picked and where the right folder
 * probably is.
 *
 * <p>CTRMap used to answer this question with a list of eight missing archive
 * names, which tells someone who has never heard of a GARC nothing at all. Yet
 * almost every wrong answer is one of a handful of near misses - the folder one
 * level above the dump, the {@code a} folder one level inside it, the ExeFS
 * next to the RomFS, or the .3ds file they never unpacked - and each of those is
 * both recognisable and, in two cases, automatically fixable. So this reports a
 * sentence a person can act on, plus the folder to use instead when it can work
 * that out.
 */
public class DumpCheck {

	/** Archives {@link Workspace#validate} refuses to start without. */
	private static final Workspace.ArchiveType[] REQUIRED = {
		Workspace.ArchiveType.ZONE_DATA,
		Workspace.ArchiveType.AREA_DATA,
		Workspace.ArchiveType.FIELD_DATA,
		Workspace.ArchiveType.MAP_MATRIX,
		Workspace.ArchiveType.GAMETEXT,
		Workspace.ArchiveType.BUILDING_MODELS,
		Workspace.ArchiveType.NPC_REGISTRIES,
		Workspace.ArchiveType.MOVE_MODELS
	};

	/** Nice names for the archives, so no message ever shows a raw archive path. */
	private static String friendly(Workspace.ArchiveType t) {
		switch (t) {
			case ZONE_DATA: return "the zone list";
			case AREA_DATA: return "area data";
			case FIELD_DATA: return "map geometry";
			case MAP_MATRIX: return "map layout";
			case GAMETEXT: return "in-game text";
			case BUILDING_MODELS: return "building models";
			case NPC_REGISTRIES: return "NPC data";
			case MOVE_MODELS: return "character models";
			default: return t.toString();
		}
	}

	public enum Status {
		/** Good to go. */
		VALID,
		/** A real dump is nearby - {@link Result#suggestion} says where. */
		WRONG_FOLDER,
		/** Recognisably a dump, but incomplete or damaged. */
		DAMAGED,
		/** Not a game dump at all. */
		NOT_A_DUMP
	}

	public static class Result {

		public Status status;
		public Workspace.GameType game;
		public GameProfile profile;
		/** One sentence, plain language, safe to show as a heading. */
		public String headline = "";
		/** Optional extra explanation. May be empty, never null. */
		public String detail = "";
		/** A folder to use instead, when one could be worked out. */
		public File suggestion;
		/** Other dumps found beside this folder, when it held several. */
		public final List<File> alternatives = new ArrayList<>();

		public boolean usable() {
			return status == Status.VALID;
		}

		/** What to call the detected game, for the UI. */
		public String gameName() {
			return profile != null ? profile.displayName() : "unknown game";
		}
	}

	/** Examines {@code folder} and reports what it is. Never throws. */
	public static Result check(File folder) {
		Result r = new Result();
		try {
			return checkInner(folder, r);
		} catch (RuntimeException ex) {
			r.status = Status.NOT_A_DUMP;
			r.headline = "CTRMap could not read that folder.";
			r.detail = String.valueOf(ex.getMessage());
			return r;
		}
	}

	private static Result checkInner(File folder, Result r) {
		if (folder == null || folder.getPath().trim().isEmpty()) {
			r.status = Status.NOT_A_DUMP;
			r.headline = "No folder chosen yet.";
			return r;
		}
		if (!folder.exists()) {
			r.status = Status.NOT_A_DUMP;
			r.headline = "That folder does not exist.";
			r.detail = "Check the path, or browse to it instead of typing it.";
			return r;
		}
		if (folder.isFile()) {
			return fileInstead(folder, r);
		}

		//the happy path
		if (fillIfDump(folder, r)) {
			return r;
		}

		//a near miss we can point at the right folder
		File inside = new File(folder, "a");
		if ("a".equalsIgnoreCase(folder.getName()) && isDump(folder.getParentFile())) {
			r.status = Status.WRONG_FOLDER;
			r.suggestion = folder.getParentFile();
			r.headline = "That is the data folder INSIDE the game, one level too deep.";
			r.detail = "CTRMap wants the folder that CONTAINS it.";
			return r;
		}
		List<File> kids = dumpsDirectlyInside(folder);
		if (kids.size() == 1) {
			r.status = Status.WRONG_FOLDER;
			r.suggestion = kids.get(0);
			r.headline = "That is the folder ABOVE the game, one level too high.";
			r.detail = "The game itself is the folder inside it.";
			return r;
		}
		if (kids.size() > 1) {
			r.status = Status.WRONG_FOLDER;
			r.suggestion = kids.get(0);
			r.alternatives.addAll(kids);
			r.headline = "That folder holds " + kids.size() + " games - pick one of them.";
			return r;
		}
		if (new File(folder, "code.bin").isFile() || "exefs".equalsIgnoreCase(folder.getName())) {
			File romfs = new File(folder.getParentFile(), "romfs");
			r.status = Status.WRONG_FOLDER;
			r.headline = "That is the game's program code, not its data.";
			r.detail = "Unpacking a game gives you two folders. CTRMap edits the data one"
					+ (romfs.isDirectory() ? ", which is right next to this one." : " - usually named \"romfs\".");
			if (isDump(romfs)) {
				r.suggestion = romfs;
			}
			return r;
		}

		//it has the shape of a dump but something is wrong with it
		if (inside.isDirectory()) {
			return diagnoseIncomplete(folder, r);
		}

		r.status = Status.NOT_A_DUMP;
		r.headline = "That is not an unpacked Pokemon game.";
		r.detail = "An unpacked game is a folder containing folders named \"a\", \"sound\" and"
				+ " \"shader\", next to a lot of files ending in .cro. This folder has none of"
				+ " that" + countHint(folder) + ".";
		return r;
	}

	private static Result fileInstead(File f, Result r) {
		String n = f.getName().toLowerCase();
		r.status = Status.NOT_A_DUMP;
		if (n.endsWith(".3ds") || n.endsWith(".cia") || n.endsWith(".cxi") || n.endsWith(".app")
				|| n.endsWith(".3dsx") || n.endsWith(".ncch")) {
			r.headline = "That is a packed game file, not an unpacked folder.";
			r.detail = "CTRMap edits a game that has already been unpacked into folders."
					+ " Unpack it first with a 3DS ROM tool, then choose the \"romfs\" folder"
					+ " that comes out.";
		} else {
			r.headline = "That is a file. CTRMap needs a folder.";
			r.detail = "Choose the folder your game was unpacked into.";
		}
		return r;
	}

	/** Fills in a VALID or DAMAGED result for something that looks like a dump. */
	private static Result diagnoseIncomplete(File folder, Result r) {
		GameProfile p = GameProfile.detect(folder);
		if (p == null) {
			r.status = Status.NOT_A_DUMP;
			r.headline = "That folder is not a game CTRMap recognises.";
			r.detail = "It has the right shape, but none of the games CTRMap knows"
					+ " (Pokemon X, Y, Omega Ruby or Alpha Sapphire) are in it."
					+ " Sun/Moon and Ultra Sun/Ultra Moon are not supported yet.";
			return r;
		}
		r.profile = p;
		r.game = p.type();
		if (!p.supports(GameProfile.Feature.H3D_MAPS)) {
			r.status = Status.DAMAGED;
			r.headline = p.displayName() + " is not supported yet.";
			r.detail = "CTRMap can edit maps in Pokemon X, Y, Omega Ruby and Alpha Sapphire.";
			return r;
		}
		List<String> missing = new ArrayList<>();
		List<String> broken = new ArrayList<>();
		for (Workspace.ArchiveType t : REQUIRED) {
			String rel = Workspace.getArchivePath(t, p.type());
			if (rel == null) {
				continue;
			}
			File f = new File(folder + rel);
			if (!f.isFile() || f.length() == 0) {
				missing.add(friendly(t));
			} else if (!looksLikeArchive(f)) {
				broken.add(friendly(t));
			}
		}
		if (missing.isEmpty() && broken.isEmpty()) {
			r.status = Status.VALID;
			r.headline = p.displayName() + " - ready to edit.";
			return r;
		}
		r.status = Status.DAMAGED;
		r.headline = "That looks like " + p.displayName() + ", but part of it is missing.";
		StringBuilder sb = new StringBuilder();
		if (!missing.isEmpty()) {
			sb.append("CTRMap could not find ").append(join(missing)).append(". ");
		}
		if (!broken.isEmpty()) {
			sb.append("These are present but unreadable: ").append(join(broken)).append(". ");
		}
		sb.append("The unpacking probably did not finish - try unpacking your game again.");
		r.detail = sb.toString();
		if (isSnapshot(folder)) {
			r.detail = "This is a backup CTRMap made of a game folder, not the game itself."
					+ " Choose the folder you originally pointed CTRMap at.";
		}
		return r;
	}

	/** True when the folder is a complete, usable dump; fills {@code r} when it is. */
	private static boolean fillIfDump(File folder, Result r) {
		if (!new File(folder, "a").isDirectory()) {
			return false;
		}
		GameProfile p = GameProfile.detect(folder);
		if (p == null || !p.supports(GameProfile.Feature.H3D_MAPS)) {
			return false;
		}
		for (Workspace.ArchiveType t : REQUIRED) {
			String rel = Workspace.getArchivePath(t, p.type());
			if (rel == null) {
				continue;
			}
			File f = new File(folder + rel);
			if (!f.isFile() || f.length() == 0 || !looksLikeArchive(f)) {
				return false;
			}
		}
		r.status = Status.VALID;
		r.profile = p;
		r.game = p.type();
		r.headline = p.displayName() + " - ready to edit.";
		return true;
	}

	/** Cheap complete-dump test used for suggestions and scanning. */
	public static boolean isDump(File folder) {
		return folder != null && folder.isDirectory() && fillIfDump(folder, new Result());
	}

	/** A CTRMap pristine backup rather than a game folder. */
	private static boolean isSnapshot(File folder) {
		return new File(folder, "taken-from.txt").isFile()
				|| "_original_garcs".equalsIgnoreCase(folder.getName());
	}

	/** Every complete dump sitting directly inside {@code folder}. */
	private static List<File> dumpsDirectlyInside(File folder) {
		List<File> out = new ArrayList<>();
		File[] kids = folder.listFiles();
		if (kids == null) {
			return out;
		}
		Arrays.sort(kids);
		for (File k : kids) {
			if (k.isDirectory() && isDump(k)) {
				out.add(k);
			}
			if (out.size() >= 8) {
				break;
			}
		}
		return out;
	}

	/** GARC magic check - enough to tell a real archive from a truncated file. */
	private static boolean looksLikeArchive(File f) {
		try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
			if (raf.length() < 16) {
				return false;
			}
			byte[] magic = new byte[4];
			raf.readFully(magic);
			return magic[0] == 'C' && magic[1] == 'R' && magic[2] == 'A' && magic[3] == 'G';
		} catch (IOException ex) {
			return false;
		}
	}

	private static String countHint(File folder) {
		String[] kids = folder.list();
		return kids == null ? "" : " (" + kids.length + " items in it)";
	}

	private static String join(List<String> items) {
		if (items.size() == 1) {
			return items.get(0);
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < items.size(); i++) {
			sb.append(i == 0 ? "" : i == items.size() - 1 ? " and " : ", ").append(items.get(i));
		}
		return sb.toString();
	}

	// ---- finding a dump the user already has -------------------------------

	/**
	 * Looks for unpacked games in the handful of places people keep them, so the
	 * common case is one click instead of a folder hunt.
	 *
	 * <p>Bounded on every axis - a fixed set of roots, a depth cap, a directory
	 * count cap and a wall-clock budget - and it never descends into a folder
	 * that is already a game, which is what keeps it cheap. It reads directory
	 * names only; it opens no files outside a candidate's own archives.
	 */
	public static List<File> findLikelyDumps(long budgetMillis) {
		List<File> found = new ArrayList<>();
		long deadline = System.currentTimeMillis() + Math.max(250, budgetMillis);
		Deque<File[]> queue = new ArrayDeque<>(); //{dir, depthMarker}
		int visited = 0;

		for (File root : searchRoots()) {
			if (root != null && root.isDirectory()) {
				queue.add(new File[]{root});
			}
		}
		//breadth-first with a depth cap, tracked by a parallel counter
		List<File> level = new ArrayList<>();
		for (File[] q : queue) {
			level.add(q[0]);
		}
		for (int depth = 0; depth <= 5 && !level.isEmpty(); depth++) {
			List<File> next = new ArrayList<>();
			for (File dir : level) {
				if (System.currentTimeMillis() > deadline || visited > 4000 || found.size() >= 12) {
					return found;
				}
				visited++;
				if (isDump(dir)) {
					found.add(dir);
					continue; //never descend into a game
				}
				File[] kids = dir.listFiles();
				if (kids == null) {
					continue;
				}
				for (File k : kids) {
					if (k.isDirectory() && !k.isHidden() && !skip(k.getName())) {
						next.add(k);
					}
				}
			}
			level = next;
		}
		return found;
	}

	/** Folders that never hold a game dump but are expensive to walk. */
	private static boolean skip(String name) {
		String n = name.toLowerCase();
		return n.equals("node_modules") || n.equals(".git") || n.equals("windows")
				|| n.equals("$recycle.bin") || n.equals("program files")
				|| n.equals("program files (x86)") || n.equals("appdata")
				|| n.equals("system volume information");
	}

	private static List<File> searchRoots() {
		List<File> roots = new ArrayList<>();
		String home = System.getProperty("user.home");
		if (home != null) {
			for (String d : new String[]{"Desktop", "Downloads", "Documents"}) {
				roots.add(new File(home, d));
			}
		}
		String appdata = System.getenv("APPDATA");
		if (appdata != null) {
			//emulators keep their own dumps; Azahar's dump folder is where its
			//"dump RomFS" command puts exactly the thing we are looking for
			for (String emu : new String[]{"Azahar", "Citra", "Lime3DS", "citra-emu"}) {
				roots.add(new File(appdata + File.separator + emu + File.separator + "dump"));
				roots.add(new File(appdata + File.separator + emu + File.separator + "load"
						+ File.separator + "mods"));
			}
		}
		return roots;
	}
}
