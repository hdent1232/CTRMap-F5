package ctrmap;

import ctrmap.formats.garc.GARC;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
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
	public static final Workspace.ArchiveType[] MODDABLE = {
		Workspace.ArchiveType.ZONE_DATA,
		Workspace.ArchiveType.GAMETEXT,
		Workspace.ArchiveType.STORYTEXT,
		Workspace.ArchiveType.FIELD_DATA,
		Workspace.ArchiveType.MAP_MATRIX,
		Workspace.ArchiveType.AREA_DATA,
		Workspace.ArchiveType.BUILDING_MODELS,
		Workspace.ArchiveType.NPC_REGISTRIES,
		Workspace.ArchiveType.MOVE_MODELS,
		Workspace.ArchiveType.TRAINER_DATA,
		Workspace.ArchiveType.TRAINER_POKE,
		Workspace.ArchiveType.MAISON_SET_POOL_A,
		Workspace.ArchiveType.MAISON_CLASS_LIST_A,
		Workspace.ArchiveType.MAISON_SET_POOL_B,
		Workspace.ArchiveType.MAISON_CLASS_LIST_B,
		Workspace.ArchiveType.MAISON_SET_POOL_C
	};

	public static class Result {
		public final List<String> deployed = new ArrayList<>();
		public int unchanged = 0;
		public final List<String> skipped = new ArrayList<>();
		public boolean codeIpsDeployed = false;
		public File modRoot;
	}

	/** Default Azahar mods folder for a title, or null if %APPDATA% is unavailable. */
	public static File azaharModRoot(String titleId) {
		String appdata = System.getenv("APPDATA");
		if (appdata == null) {
			return null;
		}
		return new File(appdata + File.separator + "Azahar" + File.separator + "load"
				+ File.separator + "mods" + File.separator + titleId);
	}

	/** Best-guess title id: the RomFS folder name if it looks like one, else the stock id for the game. */
	public static String guessTitleId() {
		if (Workspace.GAMEDIR_PATH != null) {
			String name = new File(Workspace.GAMEDIR_PATH).getName();
			if (name.matches("(?i)[0-9a-fA-F]{16}")) {
				return name.toUpperCase();
			}
		}
		return Workspace.game == Workspace.GameType.XY ? "0004000000055D00" : "000400000011C400"; // Pokemon X / Omega Ruby
	}

	/**
	 * Deploys the current workspace's edits as a LayeredFS mod at modRoot: only
	 * archives whose decompressed contents differ from the pristine snapshot are
	 * copied into {@code modRoot/romfs/...}; if codeIps is non-null it is copied
	 * to {@code modRoot/exefs/code.ips}.
	 */
	public static Result deploy(File modRoot, File codeIps) {
		Result r = new Result();
		r.modRoot = modRoot;
		File snapshot = Workspace.originalSnapshotDir();
		File romfsOut = new File(modRoot, "romfs");
		for (Workspace.ArchiveType t : MODDABLE) {
			String rel = Workspace.getArchivePath(t, Workspace.game);
			if (rel == null) {
				continue;
			}
			File live = new File(Workspace.GAMEDIR_PATH + rel);
			File orig = new File(snapshot.getAbsolutePath() + rel);
			if (!live.exists()) {
				continue;
			}
			boolean changed;
			try {
				changed = !orig.exists() || !garcContentsEqual(live, orig);
			} catch (Exception ex) {
				r.skipped.add(rel + " (compare failed: " + ex.getMessage() + ")");
				continue;
			}
			if (!changed) {
				r.unchanged++;
				continue;
			}
			try {
				copyFile(live, new File(romfsOut.getAbsolutePath() + rel));
				r.deployed.add(rel);
			} catch (IOException ex) {
				r.skipped.add(rel + " (copy failed: " + ex.getMessage() + ")");
			}
		}
		if (codeIps != null && codeIps.exists()) {
			try {
				copyFile(codeIps, new File(new File(modRoot, "exefs"), "code.ips"));
				r.codeIpsDeployed = true;
			} catch (IOException ex) {
				r.skipped.add("exefs/code.ips (copy failed: " + ex.getMessage() + ")");
			}
		}
		return r;
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
	public static File disable(File modRoot) throws IOException {
		File parked = parkedRoot(modRoot);
		moveDir(modRoot, parked);
		return parked;
	}

	/** Turns a parked mod back ON. Returns the mod folder it was restored to. */
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
		copyTree(src, dst);
		deleteTree(src);
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

	private static void deleteTree(File f) {
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteTree(k);
			}
		}
		f.delete();
	}

	/** True when two GARCs hold identical decompressed contents (ignoring container/compression bytes). */
	public static boolean garcContentsEqual(File a, File b) {
		GARC ga = new GARC(a);
		GARC gb = new GARC(b);
		if (ga.length != gb.length) {
			return false;
		}
		for (int i = 0; i < ga.length; i++) {
			if (!Arrays.equals(ga.getDecompressedEntry(i), gb.getDecompressedEntry(i))) {
				return false;
			}
		}
		return true;
	}

	private static void copyFile(File src, File dst) throws IOException {
		if (dst.getParentFile() != null) {
			dst.getParentFile().mkdirs();
		}
		Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}
}
