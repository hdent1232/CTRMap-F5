package ctrmap.formats.h3d;

import ctrmap.Workspace;
import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * The building palette's catalog: named, tile-box-exact locations of the
 * retail game's buildings and decorations (Pokemon Centers, Marts, houses,
 * signs, trees...), mined and render-verified from the pristine dump. The
 * catalog itself carries ONLY metadata (donor region + coordinates + wiring
 * info) - the actual geometry is cut from the USER'S OWN dump at placement
 * time via {@link MapPrefab#extract}, so no game assets ship with the editor.
 *
 * <p>Resource format ({@code oras_buildings.tsv}, tab-separated, '#' comments):
 * {@code kind name donorRegion donorArea tx0 ty0 tx1 ty1 baseY doorDX doorDY
 * doorProp interiorZone interiorWarpId} - box in region-local tiles
 * (inclusive); baseY the donor floor height (stamp dy = targetY - baseY);
 * doorDX/doorDY anchor-relative door tile or -1; doorProp the door prop's
 * model name or "-"; interiorZone/-WarpId the retail interior a working door
 * warps into, or -1.
 */
public class BuildingCatalog {

	public static class Entry {

		public String kind;
		public String name;
		public int donorRegion;
		public int donorArea;
		public int tx0, ty0, tx1, ty1;
		/** The building's floor Y in the donor region (stamp dy = targetY - baseY). */
		public int baseY;
		public int doorDX = -1, doorDY = -1;
		public String doorProp = "-";
		public int interiorZone = -1;
		public int interiorWarpId = -1;
		/** Where the donor lives in-game ("Rustboro City"...) - the harvested
		 *  catalog fills this; curated entries derive nothing (empty). */
		public String location = "";
		/** How many times this exact structure appears in the retail game. */
		public int retailCount = 1;
		/** True for auto-harvested entries (curated entries stay pinned first). */
		public boolean auto;

		public int tilesW() {
			return tx1 - tx0 + 1;
		}

		public int tilesH() {
			return ty1 - ty0 + 1;
		}

		public boolean enterable() {
			return doorDX >= 0 && interiorZone >= 0;
		}

		@Override
		public String toString() {
			return name + "  (" + tilesW() + "x" + tilesH() + (enterable() ? ", enterable" : "") + ")";
		}
	}

	private static List<Entry> entries;

	/** All catalog entries: the 48 hand-curated ones (door/interior wiring,
	 *  pinned first) plus the auto-harvested game-wide sweep. */
	public static synchronized List<Entry> entries() {
		if (entries != null) {
			return entries;
		}
		entries = new ArrayList<>();
		load("ctrmap/resources/oras_buildings.tsv", false);
		load("ctrmap/resources/oras_buildings_auto.tsv", true);
		return entries;
	}

	private static void load(String resource, boolean auto) {
		try (InputStream in = BuildingCatalog.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				return;
			}
			Scanner sc = new Scanner(in, "UTF-8");
			while (sc.hasNextLine()) {
				String line = sc.nextLine().trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] f = line.split("\t");
				if (f.length < 14) {
					continue;
				}
				Entry e = new Entry();
				e.kind = f[0];
				e.name = f[1];
				e.donorRegion = Integer.parseInt(f[2]);
				e.donorArea = Integer.parseInt(f[3]);
				e.tx0 = Integer.parseInt(f[4]);
				e.ty0 = Integer.parseInt(f[5]);
				e.tx1 = Integer.parseInt(f[6]);
				e.ty1 = Integer.parseInt(f[7]);
				e.baseY = Integer.parseInt(f[8]);
				e.doorDX = Integer.parseInt(f[9]);
				e.doorDY = Integer.parseInt(f[10]);
				e.doorProp = f[11];
				e.interiorZone = Integer.parseInt(f[12]);
				e.interiorWarpId = Integer.parseInt(f[13]);
				if (f.length >= 16) {
					e.location = f[14];
					try {
						e.retailCount = Integer.parseInt(f[15]);
					} catch (NumberFormatException ignore) {
					}
				}
				e.auto = auto;
				entries.add(e);
			}
		} catch (Exception ex) {
			System.err.println("BuildingCatalog: load " + resource + " failed: " + ex);
		}
	}

	/** Entries of one kind, or all for null. */
	public static List<Entry> byKind(String kind) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries()) {
			if (kind == null || e.kind.equals(kind)) {
				out.add(e);
			}
		}
		return out;
	}

	/**
	 * Cuts this entry's prefab from the PRISTINE dump (the original-archives
	 * snapshot when present, else the live game dir) so donors stay retail
	 * even after the user edits their own maps. Returns null when the region
	 * cannot be read or the box holds no geometry.
	 */
	public static MapPrefab extract(Entry e) {
		try {
			GR gr = pristineRegion(e.donorRegion);
			if (gr == null) {
				return null;
			}
			MapPrefab p = MapPrefab.extract(gr, e.tx0, e.ty0, e.tx1, e.ty1, e.name);
			if (p != null) {
				p.sourceRegion = e.donorRegion;
				p.donorArea = e.donorArea;
			}
			return p;
		} catch (Exception ex) {
			System.err.println("BuildingCatalog: extract '" + e.name + "' failed: " + ex);
			return null;
		}
	}

	/**
	 * Opens a region from the PRISTINE snapshot - the copy of the game as it was
	 * before anything was edited.
	 *
	 * <p>Returns null when there is no snapshot, and deliberately does NOT fall
	 * back to the live game folder. It used to, and that quietly turned editing
	 * into a feedback loop: a building cut from a map the user had already
	 * painted carried the paint with it, that result was written back, and the
	 * next cut took the paint twice. Measured on a real workspace, two retail
	 * regions had drifted this way and one of them had been captured into the
	 * "pristine" snapshot itself.
	 *
	 * <p>Refusing is the safe answer. A missing snapshot means the workspace was
	 * never validated, which the caller can fix; silently substituting edited
	 * data cannot be detected at all.
	 */
	/**
	 * Whether {@link #pristineRegion} can be asked at all right now.
	 *
	 * <p>The snapshot's path is resolved through the OPEN WORKSPACE's game
	 * profile, so a call made before any workspace exists has nothing to
	 * resolve against and can only throw. Callers on the map-build path asked
	 * anyway and swallowed the throw into a stderr line, so every single
	 * painted-region build printed "cliff import failed: no profile for null" -
	 * noise nobody read, and, being constant, noise that hid the one build
	 * where the import really did fail.
	 *
	 * <p>A workspace whose snapshot is missing answers false too, rather than
	 * nagging once per Apply: {@link ctrmap.Workspace}'s validation already
	 * tells the user their pristine backup is gone, which is where that belongs.
	 */
	public static boolean canCutDonor() {
		if (Workspace.game == null) {
			return false;
		}
		String rel = Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game);
		return new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel).exists();
	}

	public static GR pristineRegion(int region) throws Exception {
		String rel = Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game);
		File garcFile = new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel);
		if (!garcFile.exists()) {
			System.err.println("BuildingCatalog: no pristine snapshot in this workspace ("
					+ Workspace.originalSnapshotDir() + ") - refusing to cut a donor from"
					+ " edited data. Load the workspace in CTRMap once to create it.");
			return null;
		}
		byte[] bytes = new GARC(garcFile).getDecompressedEntry(region);
		if (bytes == null) {
			return null;
		}
		File tmp = new File(Workspace.temp, "bcat_region_" + region);
		try (FileOutputStream fo = new FileOutputStream(tmp)) {
			fo.write(bytes);
		}
		return new GR(tmp);
	}
}
