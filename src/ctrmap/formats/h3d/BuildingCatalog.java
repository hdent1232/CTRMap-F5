package ctrmap.formats.h3d;

import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The building palette's catalog: named, tile-box-exact locations of the
 * retail game's buildings and decorations (Pokemon Centers, Marts, houses,
 * signs, trees...), mined and render-verified from the pristine dump. The
 * catalog itself carries ONLY metadata (donor region + coordinates + wiring
 * info) - the actual geometry is cut from the USER'S OWN dump at placement
 * time via {@link MapPrefab#extract}, so no game assets ship with the editor.
 *
 * <p>The cut is made from the game this class is HANDED. It used to resolve
 * the pristine snapshot, the archive's location and its scratch directory
 * from the application's global session, so a donor could only ever be cut
 * from the application's game, and no suite could hand it a snapshot of its
 * own and read back which region it opened. Three things are needed - the
 * snapshot directory, the profile's archive path and a scratch directory -
 * which is why the whole {@link GameFiles} contract is asked for rather than
 * one file. {@code ctrmap.tests.HandedGameTest} hands it two games.
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
			return MapPrefab.span(tx0, tx1);
		}

		public int tilesH() {
			return MapPrefab.span(ty0, ty1);
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
	private static List<String> problems;

	/** All catalog entries: the 48 hand-curated ones (door/interior wiring,
	 *  pinned first) plus the auto-harvested game-wide sweep. */
	public static synchronized List<Entry> entries() {
		if (entries != null) {
			return entries;
		}
		List<Entry> read = new ArrayList<>();
		List<String> said = new ArrayList<>();
		load("ctrmap/resources/oras_buildings.tsv", false, read, said);
		load("ctrmap/resources/oras_buildings_auto.tsv", true, read, said);
		entries = read;
		problems = said;
		return entries;
	}

	/**
	 * What the catalog could not read - a missing file, or a row by its line - each skipped with
	 * the rest kept. Empty when every row read. They went to stderr, and one malformed number
	 * ended the whole file there, so a catalog could lose most of itself and say so nowhere the
	 * user looks (F067).
	 */
	public static synchronized List<String> loadProblems() {
		entries();
		return java.util.Collections.unmodifiableList(problems);
	}

	private static void load(String resource, boolean auto, List<Entry> into, List<String> problems) {
		try (InputStream in = BuildingCatalog.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				problems.add(resource + " is missing from this build");
				return;
			}
			parse(in, resource, auto, into, problems);
		} catch (java.io.IOException ex) {
			problems.add(resource + " could not be read: " + ex);
		}
	}

	/**
	 * Reads one catalog file's rows into {@code into}. A row it cannot read is named in
	 * {@code problems} by its line and skipped, and the rows after it are still read.
	 */
	public static void parse(InputStream in, String name, boolean auto, List<Entry> into, List<String> problems) {
		//a BufferedReader, not a Scanner: a Scanner keeps a read failure to itself and simply runs
		//out of lines, so a catalog cut short by the disk read as a shorter catalog (F211)
		java.io.BufferedReader lines = new java.io.BufferedReader(
				new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
		int lineNo = 0;
		while (true) {
			String raw;
			try {
				raw = lines.readLine();
			} catch (java.io.IOException ex) {
				problems.add(name + " could not be read past line " + lineNo + ": " + ctrmap.util.Bytes.reason(ex));
				return;
			}
			if (raw == null) {
				break;
			}
			lineNo++;
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] f = line.split("\t");
			if (f.length < 14) {
				problems.add(name + " line " + lineNo + " has " + f.length + " fields; a row needs 14");
				continue;
			}
			Entry e = new Entry();
			try {
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
			} catch (NumberFormatException notANumber) {
				problems.add(name + " line " + lineNo + ": " + notANumber.getMessage());
				continue;
			}
			//AN INVERTED BOX IS REFUSED, BY ITS LINE (F129): one was listed 0 wide and cut 2 wide
			if (e.tx1 < e.tx0 || e.ty1 < e.ty0) {
				problems.add(name + " line " + lineNo + ": \"" + e.name + "\" has a box that ends before it starts ("
						+ e.tx0 + "," + e.ty0 + " to " + e.tx1 + "," + e.ty1 + ")");
				continue;
			}
			if (f.length >= 16) {
				e.location = f[14];
				try {
					e.retailCount = Integer.parseInt(f[15]);
				} catch (NumberFormatException notANumber) {
					problems.add(name + " line " + lineNo + ": the retail count " + notANumber.getMessage()
							+ "; 1 is used");
				}
			}
			e.auto = auto;
			into.add(e);
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
	 * Cuts this entry's prefab from the PRISTINE snapshot of the handed game,
	 * so donors stay retail even after the user edits their own maps. Returns
	 * null when there is no game or no snapshot to cut from, the region cannot
	 * be read or the box holds no geometry.
	 */
	public static MapPrefab extract(GameFiles files, Entry e) {
		if (files == null) {
			return null; //nothing to cut from, and nothing to log about it
		}
		try {
			GR gr = pristineRegion(files, e.donorRegion);
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
	 * Whether {@link #pristineRegion} can be asked of this game at all right now.
	 *
	 * <p>The snapshot's path is resolved through the handed game's profile, so
	 * a call made with NO game handed - which is what a build made before any
	 * workspace exists hands - has nothing to resolve against. Callers on the
	 * map-build path used to ask anyway and swallow the throw into a stderr
	 * line, so every single painted-region build printed "cliff import failed:
	 * no profile for null" - noise nobody read, and, being constant, noise that
	 * hid the one build where the import really did fail. Null therefore
	 * answers false, SILENTLY, and is the one null this class does not refuse
	 * in words: {@code ctrmap.tests.TerrainImportNoiseTest} pins that the
	 * cliff import tells nobody anything before there is a game.
	 *
	 * <p>A game whose snapshot is missing answers false too, rather than
	 * nagging once per Apply: the workspace's validation already tells the
	 * user their pristine backup is gone, which is where that belongs.
	 */
	public static boolean canCutDonor(GameFiles files) {
		return files != null && snapshotArchive(files) != null;
	}

	/**
	 * The pristine FieldData archive of the handed game, or null when there is no untouched copy -
	 * asked of the one pristine reader, {@link GameFiles#pristineArchive} (F232). An untouched copy
	 * that is there and cannot be read as one is not "none": it is thrown, unchecked, because a
	 * damaged backup answered "cannot cut" here would read exactly as never having had one.
	 */
	private static File snapshotArchive(GameFiles files) {
		try {
			return files.pristineArchive(ArchiveType.FIELD_DATA);
		} catch (java.io.IOException damaged) {
			throw new IllegalStateException("The untouched copy of the map archive could not be read: "
					+ damaged.getMessage(), damaged);
		}
	}

	/**
	 * Opens a region from the PRISTINE snapshot of the handed game - the copy
	 * of the game as it was before anything was edited - staged as a container
	 * in the handed game's scratch directory.
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
	 *
	 * @param files the game whose snapshot is read; null is refused in words,
	 * since a caller that has not asked {@link #canCutDonor} first has nothing
	 * to cut from and must not be answered with a region from nowhere
	 */
	public static GR pristineRegion(GameFiles files, int region) throws Exception {
		if (files == null) {
			throw new IllegalArgumentException("a pristine region must be cut from a handed game;"
					+ " handed null there is no snapshot to cut it from (ask canCutDonor first)");
		}
		File garcFile = snapshotArchive(files);
		if (garcFile == null) {
			System.err.println("BuildingCatalog: no pristine snapshot for " + files + " ("
					+ files.pristine() + ") - refusing to cut a donor from"
					+ " edited data. Load the workspace in CTRMap once to create it.");
			return null;
		}
		byte[] bytes = snapshotHandle(garcFile).getDecompressedEntry(region);
		if (bytes == null) {
			return null;
		}
		File tmp = new File(files.scratch(), "bcat_region_" + region);
		try (FileOutputStream fo = new FileOutputStream(tmp)) {
			fo.write(bytes);
		}
		//THE COPY GOES WITH THE PROGRAM (F067): it was left behind, one per donor region cut
		tmp.deleteOnExit();
		return new GR(tmp, files);
	}

	private static final java.util.Map<String, GARC> SNAPSHOTS = new java.util.HashMap<>();

	/**
	 * The snapshot's archive, opened once while the file is unchanged. Every cut - a placement, a
	 * preview, each of a catalog's thousands of entries - opened it again (F067). A snapshot that
	 * has been rewritten since has another length or time, and is opened anew.
	 */
	public static synchronized GARC snapshotHandle(File garcFile) {
		String key = garcFile.getAbsolutePath() + "|" + garcFile.length() + "|" + garcFile.lastModified();
		GARC g = SNAPSHOTS.get(key);
		if (g == null) {
			SNAPSHOTS.keySet().removeIf(k -> k.startsWith(garcFile.getAbsolutePath() + "|"));
			g = new GARC(garcFile);
			SNAPSHOTS.put(key, g);
		}
		return g;
	}
}
