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
 * {@code kind name donorRegion donorArea tx0 ty0 tx1 ty1 doorDX doorDY
 * doorProp interiorZone interiorWarpId} - box in region-local tiles
 * (inclusive); doorDX/doorDY anchor-relative door tile or -1; doorProp the
 * door prop's model name or "-"; interiorZone/-WarpId the retail interior a
 * working door warps into, or -1.
 */
public class BuildingCatalog {

	public static class Entry {

		public String kind;
		public String name;
		public int donorRegion;
		public int donorArea;
		public int tx0, ty0, tx1, ty1;
		public int doorDX = -1, doorDY = -1;
		public String doorProp = "-";
		public int interiorZone = -1;
		public int interiorWarpId = -1;

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

	/** All catalog entries (loaded once from the resource). */
	public static synchronized List<Entry> entries() {
		if (entries != null) {
			return entries;
		}
		entries = new ArrayList<>();
		try (InputStream in = BuildingCatalog.class.getClassLoader()
				.getResourceAsStream("ctrmap/resources/oras_buildings.tsv")) {
			if (in == null) {
				return entries;
			}
			Scanner sc = new Scanner(in, "UTF-8");
			while (sc.hasNextLine()) {
				String line = sc.nextLine().trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] f = line.split("\t");
				if (f.length < 13) {
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
				e.doorDX = Integer.parseInt(f[8]);
				e.doorDY = Integer.parseInt(f[9]);
				e.doorProp = f[10];
				e.interiorZone = Integer.parseInt(f[11]);
				e.interiorWarpId = Integer.parseInt(f[12]);
				entries.add(e);
			}
		} catch (Exception ex) {
			System.err.println("BuildingCatalog: load failed: " + ex);
		}
		return entries;
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

	/** Opens a region GR from the pristine snapshot (fallback: game dir). */
	public static GR pristineRegion(int region) throws Exception {
		String rel = Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game);
		File garcFile = new File(Workspace.originalSnapshotDir().getAbsolutePath() + rel);
		if (!garcFile.exists()) {
			garcFile = new File(Workspace.GAMEDIR_PATH + rel);
		}
		if (!garcFile.exists()) {
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
