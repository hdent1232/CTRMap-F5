package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import static ctrmap.formats.LittleEndian.u16;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.LittleEndian.putU16;
import java.nio.file.Files;

/**
 * Gives a zone its OWN private map geometry so that editing its map no longer
 * changes every other zone that shares it. This runs AUTOMATICALLY for every new
 * zone created by {@link ZoneAppender} (a fresh zone is independent by default,
 * which is what users expect); {@link #ensurePrivate(int)} is the manual entry
 * point for zones that already existed before auto-fork, or for giving an
 * existing base-game zone its own map.
 *
 * <p>ORAS zone geometry is shared by reference: a zone header's {@code
 * mapmatrixID} (u16 at header offset 4) selects a MapMatrix entry (a/0/4/0),
 * whose grid holds FieldData region IDs (a/0/3/9), each a GR container whose
 * subfile 1 is the .bch map model. Cloning a zone copies only the per-zone data
 * (entities/scripts/warps) and keeps the SAME mapmatrixID, so the clone and its
 * source render identical geometry (measured: base Mauville zone 15 and clones
 * 536-539 all use mapmatrixID 14 -> region 153; a geometry edit on one shows on
 * all of them).
 *
 * <p>Forking makes the geometry private with archive appends, all through the
 * proven {@link GARC#packDirectory} append path (the same mechanism
 * {@link ZoneAppender} uses):
 * <ol>
 *   <li>each unique FieldData region in the zone's matrix is copied VERBATIM to
 *       a new tail index (region GR containers are addressed purely by GARC
 *       index - no internal rewiring);</li>
 *   <li>the MapMatrix entry is copied verbatim except its grid region-ID cells,
 *       rewired old-&gt;new (byte-length preserved: a surgical u16 overwrite);</li>
 *   <li>the zone's mapmatrixID is repointed to the new matrix in BOTH the ZO
 *       container header AND the master zone-header table (the game reads the
 *       header from the RAM master table loaded at boot from ZoneData entry
 *       {@code length-2}; the editor mirrors edits to both - see
 *       ZoneLoadingPanel.saveEntry).</li>
 * </ol>
 * The reverse-engineering pass on code.bin proved neither the FieldData region
 * count nor the MapMatrix count is bounded by a hardcoded constant (both read
 * from the archive FATB header at runtime), so this is pure data - NO code patch
 * is needed (unlike the zone-count limit).
 *
 * <p>As with {@link ZoneAppender}, the GARCs are not rewritten here: the caller
 * Packs the Workspace immediately afterwards (which reloads the archives). Only
 * one fork/append is allowed per pack cycle.
 */
public class GeometryForker {

	/** Master zone-header table row stride (a 0x38 zone header per zone). */
	public static final int MASTER_ROW = 0x38;

	private static Map<Integer, Boolean> pendingFieldOverrides = null;
	private static Map<Integer, Boolean> pendingMatrixOverrides = null;

	/** What a manual fork produced, for user-facing reporting. */
	public static class ForkResult {
		public int zoneIndex;
		public int oldMatrix;
		public int newMatrix;
		public int[] srcRegions;   // the shared regions the zone used
		public int[] newRegions;   // their new private copies (parallel to srcRegions)
		/** Zones whose ground the private copy still carries; empty when it is the zone's alone. */
		public int[] otherZones = new int[0];
		/** False when the zone already owned its map and nothing was appended. */
		public boolean forked;
	}

	/**
	 * Pure, headless byte plan of a fork (no filesystem access): the matrix
	 * rewire and header repoint, plus the resolved region mapping. Unit-tested
	 * against real archive bytes exactly like {@link ZoneAppender}'s payload
	 * builders.
	 */
	public static class ForkPlan {
		public int oldMatrix;
		public int[] srcRegions;      // unique region IDs in the matrix grid, first-seen order
		public int[] newRegions;      // firstNewRegion, firstNewRegion+1, ... (parallel to srcRegions)
		public byte[] newMatrixBytes; // source matrix, verbatim, with grid region IDs rewired
		public byte[] newZoBytes;     // source zone ZO container, verbatim, with mapmatrixID repointed
		public int zoneCellsRewritten; // quarter cells in the zone-switch layer repointed at the forking zone
		public int[] otherZones;      // zones whose ground the private copy still carries (empty when it claimed the map)
	}

	/**
	 * Builds the fork plan from the zone's ZO container and its MapMatrix entry.
	 * Derives the zone's current mapmatrixID from the header, enumerates the
	 * unique FieldData region IDs in the matrix grid, assigns them new tail
	 * indices, and produces the rewired matrix + repointed header. No I/O.
	 *
	 * @param claimEveryCell true when the map is being cloned for a BRAND-NEW
	 *                       zone: the copy belongs entirely to it, so every
	 *                       occupied quarter cell of the zone-switch layer is
	 *                       relabelled or the new zone never becomes current.
	 *                       False when an EXISTING zone is taking its own map
	 *                       away from one it shares - then only the cells that
	 *                       already said this zone may move.
	 */
	public static ForkPlan planFork(byte[] zoBytes, byte[] matBytes, int firstNewRegion, int newMatrixIndex,
			int owningZone, boolean claimEveryCell) {
		if (zoBytes == null || zoBytes.length < 8) {
			throw new IllegalArgumentException("Zone container too short.");
		}
		int hdrOff = i32(zoBytes, 4);
		if (hdrOff < 0 || hdrOff + 6 > zoBytes.length) {
			throw new IllegalArgumentException("Zone header subfile out of range.");
		}
		int oldMatrix = u16(zoBytes, hdrOff + 4);

		if (matBytes == null || matBytes.length < 12) {
			throw new IllegalArgumentException("Map matrix container too short.");
		}
		int sub0 = i32(matBytes, 4);
		if (sub0 < 0 || sub0 + 8 > matBytes.length) {
			throw new IllegalArgumentException("Map matrix grid subfile out of range.");
		}
		int w = u16(matBytes, sub0 + 4);
		int h = u16(matBytes, sub0 + 6);
		long cells = (long) w * h;
		if (w <= 0 || h <= 0 || cells > 4096 || sub0 + 8 + cells * 2 > matBytes.length) {
			throw new IllegalArgumentException("Map matrix grid " + w + "x" + h + " does not fit its subfile.");
		}

		LinkedHashMap<Integer, Integer> map = new LinkedHashMap<>();
		int next = firstNewRegion;
		for (int k = 0; k < w * h; k++) {
			int id = u16(matBytes, sub0 + 8 + k * 2);
			if (id != 0xFFFF && !map.containsKey(id)) {
				map.put(id, next++);
			}
		}
		if (map.isEmpty()) {
			throw new IllegalArgumentException("The zone's matrix references no FieldData regions to fork.");
		}

		byte[] newMat = matBytes.clone();
		for (int k = 0; k < w * h; k++) {
			int pos = sub0 + 8 + k * 2;
			Integer nid = map.get(u16(newMat, pos));
			if (nid != null) {
				putU16(newMat, pos, nid);
			}
		}

		//A matrix carries more than the region grid. When hasLOD == 1 a
		//zone-switch layer follows it - one entry per QUARTER cell, (w*4)x(h*4) -
		//that tells the engine which zone the ground under the player belongs to.
		//For a brand-new zone cloned from a donor, copying it verbatim hands the
		//map straight back to the donor: the new zone never becomes current (no
		//location banner, no music, its header ignored) and the donor's entities
		//stay live on the new ground - retail item balls and trainers included.
		//Measured: all 61 retail zones whose matrix has this layer appear in
		//their own layer; the appended zones scored 0 until this ran.
		//
		//For an EXISTING zone leaving a map it SHARES, claiming every cell does
		//the same damage in reverse. 19 retail matrices host several zones: on
		//matrix 8 the blanket rewrite turned {Fallarbor 16, Route 111 64,
		//Route 112 16, Route 113 64, Route 114 128} into {Fallarbor 288}, so
		//inside the forked map those four routes' ground resolved to Fallarbor
		//Town and their banner, music, header and entities stopped applying.
		//Only cells that already said this zone may move; the rest stay as they
		//are, and the caller tells the user whose ground came along.
		int zoneCells = 0;
		java.util.List<Integer> others = new java.util.ArrayList<>();
		if (u16(matBytes, sub0) == 1) {
			int layerOff = sub0 + 8 + w * h * 2;
			long quads = (long) (w * 4) * (h * 4);
			if (layerOff + quads * 2 > matBytes.length) {
				throw new IllegalArgumentException("Map matrix zone-switch layer "
						+ (w * 4) + "x" + (h * 4) + " does not fit its subfile.");
			}
			for (int q = 0; q < quads; q++) {
				int pos = layerOff + (int) q * 2;
				int cell = u16(newMat, pos);
				if (cell == 0xFFFF) {
					continue;
				}
				if (claimEveryCell || cell == owningZone) {
					putU16(newMat, pos, owningZone);
					zoneCells++;
				} else if (!others.contains(cell)) {
					others.add(cell);
				}
			}
		}

		byte[] newZo = zoBytes.clone();
		putU16(newZo, hdrOff + 4, newMatrixIndex);

		ForkPlan p = new ForkPlan();
		p.zoneCellsRewritten = zoneCells;
		p.otherZones = new int[others.size()];
		for (int k = 0; k < others.size(); k++) {
			p.otherZones[k] = others.get(k);
		}
		p.oldMatrix = oldMatrix;
		p.newMatrixBytes = newMat;
		p.newZoBytes = newZo;
		p.srcRegions = new int[map.size()];
		p.newRegions = new int[map.size()];
		int i = 0;
		for (Map.Entry<Integer, Integer> e : map.entrySet()) {
			p.srcRegions[i] = e.getKey();
			p.newRegions[i] = e.getValue();
			i++;
		}
		return p;
	}

	/**
	 * Copies a zone's regions + matrix to new private tail indices and registers
	 * their compression overrides (shared by the manual and auto paths). Returns
	 * the plan (whose {@code newZoBytes} the caller writes into place). No zone
	 * header / master-table write happens here.
	 */
	private static ForkPlan forkArchives(byte[] zoBytes, int firstNewRegion, int newMatrix, int owningZone,
			boolean claimEveryCell, GARC gr, GARC mm, File fdDir, File mmDir) throws IOException {
		int hdrOff = i32(zoBytes, 4);
		int oldMatrix = u16(zoBytes, hdrOff + 4);
		if (oldMatrix < 0 || oldMatrix >= mm.length) {
			throw new IOException("Zone references matrix " + oldMatrix + " which does not exist.");
		}
		File srcMatrixFile = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, oldMatrix);
		if (srcMatrixFile == null) {
			throw new IOException("Could not extract map matrix " + oldMatrix + " from the workspace.");
		}
		ForkPlan plan = planFork(zoBytes, Files.readAllBytes(srcMatrixFile.toPath()), firstNewRegion, newMatrix, owningZone,
				claimEveryCell);

		if (pendingFieldOverrides == null) {
			pendingFieldOverrides = new HashMap<>();
		}
		if (pendingMatrixOverrides == null) {
			pendingMatrixOverrides = new HashMap<>();
		}
		for (int i = 0; i < plan.srcRegions.length; i++) {
			int oldR = plan.srcRegions[i], newR = plan.newRegions[i];
			if (oldR < 0 || oldR >= gr.length) {
				throw new IOException("Zone matrix references region " + oldR + " which does not exist.");
			}
			File srcRegionFile = Workspace.getWorkspaceFile(ArchiveType.FIELD_DATA, oldR);
			if (srcRegionFile == null) {
				throw new IOException("Could not extract FieldData region " + oldR + " from the workspace.");
			}
			File out = new File(fdDir, String.valueOf(newR));
			Files.write(out.toPath(), Files.readAllBytes(srcRegionFile.toPath()));
			Workspace.addPersist(out);
			pendingFieldOverrides.put(newR, gr.isEntryCompressed(oldR));
		}
		File matrixOut = new File(mmDir, String.valueOf(newMatrix));
		Files.write(matrixOut.toPath(), plan.newMatrixBytes);
		Workspace.addPersist(matrixOut);
		pendingMatrixOverrides.put(newMatrix, mm.isEntryCompressed(oldMatrix));
		return plan;
	}

	/**
	 * Auto-fork hook for {@link ZoneAppender}: gives each of the first
	 * {@code newRealZones} appended zones its own private geometry, in one pack
	 * cycle. Mutates the caller's in-memory payloads in place - {@code newZos[i]}
	 * is replaced with the repointed ZO container and {@code master}'s row for
	 * each real zone is repointed - and writes the region/matrix copies into the
	 * workspace. Spare zones (padding to a multiple of 4) are left sharing the
	 * source map.
	 *
	 * @param newZos       the appended zones' ZO containers, indices
	 *                     0..newRealZones-1 forked (mutated in place)
	 * @param master       the grown master zone-header table (rows repointed in place)
	 * @param oldCount     the first new zone's GARC index (== its master-table row)
	 * @param newRealZones how many of the appended zones to fork
	 */
	public static void forkAppendedZones(byte[][] newZos, byte[] master, int oldCount, int newRealZones) throws IOException {
		GARC gr = Workspace.getArchive(ArchiveType.FIELD_DATA);
		GARC mm = Workspace.getArchive(ArchiveType.MAP_MATRIX);
		if (gr == null || mm == null) {
			throw new IOException("FieldData/MapMatrix archive unavailable.");
		}
		File fdDir = Workspace.getExtractionDirectory(ArchiveType.FIELD_DATA);
		File mmDir = Workspace.getExtractionDirectory(ArchiveType.MAP_MATRIX);
		int nextRegion = gr.length;
		int nextMatrix = mm.length;
		//EVERY ZONE THE APPEND CREATES, PADDING INCLUDED. This ran to newRealZones,
		//so the spares that round an append up to a multiple of four kept the DONOR's
		//map - and editing a spare rewrote the city it was padded out of, and its
		//siblings. The user who found it added one zone to Sootopolis, opened 537, and
		//was told the zone "was added before the editor forked new zones automatically"
		//- a sentence this editor had written about zones it had created seconds
		//earlier, because that dialog cannot tell a spare from a genuinely old zone.
		//A spare is a zone the user can open, paint and save like any other, so it
		//gets its own map like any other. Measured before choosing: a 2x2 city forks
		//for about 1.5 MB uncompressed, so three spares cost about 4.4 MB before the
		//pack compresses them - cheap next to a silent shared-map edit. A spare that
		//should be empty can be blanked afterwards with Blank map canvas, which is a
		//choice the user gets to make rather than one the padding makes for them.
		for (int i = 0; i < newZos.length; i++) {
			int newMatrix = nextMatrix++;
			//a brand-new zone's map is a copy of the donor's and belongs to it alone
			ForkPlan plan = forkArchives(newZos[i], nextRegion, newMatrix, oldCount + i, true, gr, mm, fdDir, mmDir);
			nextRegion += plan.srcRegions.length;
			newZos[i] = plan.newZoBytes;                              // caller writes the repointed ZO
			int rowOff = (oldCount + i) * MASTER_ROW + 4;
			if (rowOff + 2 > master.length) {
				throw new IOException("Master-table row for zone " + (oldCount + i) + " out of range.");
			}
			putU16(master, rowOff, newMatrix);                        // repoint the master-table row
		}
	}

	/**
	 * Makes sure the zone owns its map, and is the ONLY way in: it forks when
	 * the map is still shared and does nothing when it is already private.
	 * Pack Workspace afterwards. {@link ForkResult#forked} says which happened,
	 * so the caller can report honestly.
	 *
	 * <p>Four of the five callers used to fork unconditionally. A fork appends
	 * a copy of every region in the zone's matrix plus a new matrix, so running
	 * one on a zone that was already private - iterating on a test zone does
	 * that every cycle - added another set and orphaned the previous one, which
	 * nothing ever reclaims (ZoneRemover leaves them as harmless tail data).
	 * Measured: four forks of one already-private zone grew FieldData by
	 * 1.17 MB and left three matrices referenced by no zone at all, every run
	 * reporting "now has private map geometry" as though it had been needed.
	 * The worst matrix in the game carries 23 regions and 3.2 MB.
	 */
	public static ForkResult ensurePrivate(int zoneIndex) throws IOException {
		if (matrixSharers(zoneIndex) > 0) {
			return forkGeometry(zoneIndex);
		}
		return currentGeometry(zoneIndex);
	}

	/**
	 * Forks the given (already-existing) zone's map geometry to a private copy
	 * in the current ORAS workspace. Private because a fork that was not needed
	 * is pure waste and cannot be undone - go through {@link #ensurePrivate}.
	 */
	private static ForkResult forkGeometry(int zoneIndex) throws IOException {
		//what this game CAN DO, not which game it is; see AreaForker.requireForkSupport
		requireGeometryForkSupport();
		GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		GARC gr = Workspace.getArchive(ArchiveType.FIELD_DATA);
		GARC mm = Workspace.getArchive(ArchiveType.MAP_MATRIX);
		if (zo == null || gr == null || mm == null) {
			throw new IOException("No workspace is loaded (ZoneData/FieldData/MapMatrix unavailable).");
		}
		int trailing = ZoneTables.zoneTrailing();
		int zoneCount = zo.length - trailing;
		if (zoneIndex < 0 || zoneIndex >= zoneCount) {
			throw new IOException("Zone " + zoneIndex + " out of range (0.." + (zoneCount - 1) + "). "
					+ "Note: the last " + trailing + " ZoneData entries are tables, not zones.");
		}
		File fdDir = Workspace.getExtractionDirectory(ArchiveType.FIELD_DATA);
		File mmDir = Workspace.getExtractionDirectory(ArchiveType.MAP_MATRIX);
		int newMatrix = mm.length;
		File matrixOut = new File(mmDir, String.valueOf(newMatrix));
		if (Workspace.isPendingArtifact(matrixOut)) {
			throw new IOException("A geometry fork/append is already pending. Pack the workspace before forking again.");
		}
		File zoneFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		if (zoneFile == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		byte[] zoBytes = Files.readAllBytes(zoneFile.toPath());
		//an existing zone keeps its own cells and leaves its neighbours' alone
		ForkPlan plan = forkArchives(zoBytes, gr.length, newMatrix, zoneIndex, false, gr, mm, fdDir, mmDir);

		// repoint the ZO container header, in place
		Files.write(zoneFile.toPath(), plan.newZoBytes);
		Workspace.addPersist(zoneFile);
		// repoint the master zone-header table row (the runtime-authoritative copy)
		repointMasterRow(zo, zoneIndex, newMatrix);

		ForkResult r = new ForkResult();
		r.zoneIndex = zoneIndex;
		r.oldMatrix = plan.oldMatrix;
		r.newMatrix = newMatrix;
		r.srcRegions = plan.srcRegions;
		r.newRegions = plan.newRegions;
		r.otherZones = plan.otherZones;
		r.forked = true;
		return r;
	}

	/**
	 * How many OTHER zones use the same map matrix as this zone, per the master
	 * zone-header table (the runtime-authoritative copy). 0 = the geometry is
	 * already private and a fork would only orphan archive entries.
	 */
	public static int matrixSharers(int zoneIndex) throws IOException {
		GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		if (zo == null) {
			throw new IOException("No workspace is loaded (ZoneData archive unavailable).");
		}
		int zoneCount = ZoneTables.zoneCount(zo);
		File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneCount);
		if (masterFile == null) {
			throw new IOException("Could not extract the master zone-header table.");
		}
		byte[] master = Files.readAllBytes(masterFile.toPath());
		if (master.length != zoneCount * MASTER_ROW) {
			//a stale/foreign artifact in the workspace slot; the pristine table
			//comes from the archive itself. If even that disagrees, report
			//"shared" - forking needlessly is safe, skipping it is not.
			byte[] fromGarc = zo.getDecompressedEntry(zoneCount);
			if (fromGarc == null || fromGarc.length != zoneCount * MASTER_ROW) {
				return 1;
			}
			master = fromGarc;
		}
		int off = zoneIndex * MASTER_ROW + 4;
		if (off + 2 > master.length) {
			throw new IOException("Master-table row for zone " + zoneIndex + " out of range.");
		}
		int mm = u16(master, off);
		int sharers = 0;
		for (int z = 0; z < zoneCount; z++) {
			if (z != zoneIndex && u16(master, z * MASTER_ROW + 4) == mm) {
				sharers++;
			}
		}
		return sharers;
	}

	/**
	 * The zone's CURRENT geometry as a no-op ForkResult (newRegions ==
	 * srcRegions, matrix unchanged) - for callers that fork-if-shared and write
	 * into the zone's own regions when it is already private.
	 */
	public static ForkResult currentGeometry(int zoneIndex) throws IOException {
		File zoneFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		if (zoneFile == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		byte[] zoBytes = Files.readAllBytes(zoneFile.toPath());
		int hdrOff = i32(zoBytes, 4);
		int matrix = u16(zoBytes, hdrOff + 4);
		File matFile = Workspace.getWorkspaceFile(ArchiveType.MAP_MATRIX, matrix);
		if (matFile == null) {
			throw new IOException("Could not extract map matrix " + matrix + " from the workspace.");
		}
		byte[] mat = Files.readAllBytes(matFile.toPath());
		int sub0 = i32(mat, 4);
		int w = u16(mat, sub0 + 4), h = u16(mat, sub0 + 6);
		LinkedHashMap<Integer, Integer> seen = new LinkedHashMap<>();
		for (int k = 0; k < w * h; k++) {
			int id = u16(mat, sub0 + 8 + k * 2);
			if (id != 0xFFFF && !seen.containsKey(id)) {
				seen.put(id, id);
			}
		}
		ForkResult r = new ForkResult();
		r.zoneIndex = zoneIndex;
		r.oldMatrix = matrix;
		r.newMatrix = matrix;
		r.srcRegions = new int[seen.size()];
		r.newRegions = new int[seen.size()];
		int i = 0;
		for (Integer id : seen.keySet()) {
			r.srcRegions[i] = id;
			r.newRegions[i] = id;
			i++;
		}
		return r;
	}

	/** Registers a pending FieldData compression override (appenders other than the fork reuse this). */
	public static void registerPendingField(int index, boolean compressed) {
		if (pendingFieldOverrides == null) {
			pendingFieldOverrides = new HashMap<>();
		}
		pendingFieldOverrides.put(index, compressed);
	}

	/** Registers a pending MapMatrix compression override (appenders other than the fork reuse this). */
	public static void registerPendingMatrix(int index, boolean compressed) {
		if (pendingMatrixOverrides == null) {
			pendingMatrixOverrides = new HashMap<>();
		}
		pendingMatrixOverrides.put(index, compressed);
	}

	/** Repoints a zone's mapmatrixID in the master zone-header table file. */
	public static void repointMasterRow(GARC zo, int zoneIndex, int newMatrix) throws IOException {
		int masterIndex = ZoneTables.masterIndex(zo);
		File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, masterIndex);
		if (masterFile == null) {
			throw new IOException("Could not extract the master zone-header table.");
		}
		byte[] master = Files.readAllBytes(masterFile.toPath());
		int rowOff = zoneIndex * MASTER_ROW + 4;
		if (rowOff + 2 > master.length) {
			throw new IOException("Master-table row for zone " + zoneIndex + " out of range.");
		}
		putU16(master, rowOff, newMatrix);
		Files.write(masterFile.toPath(), master);
		Workspace.addPersist(masterFile);
	}

	/** Drains the FieldData compression overrides of a pending fork (one packDirectory call). */
	public static Map<Integer, Boolean> consumePendingFieldOverrides() {
		Map<Integer, Boolean> m = pendingFieldOverrides;
		pendingFieldOverrides = null;
		return m;
	}

	/** Drains the MapMatrix compression overrides of a pending fork (one packDirectory call). */
	public static Map<Integer, Boolean> consumePendingMatrixOverrides() {
		Map<Integer, Boolean> m = pendingMatrixOverrides;
		pendingMatrixOverrides = null;
		return m;
	}

	/**
	 * Refuses a geometry fork, in the loaded game's own name, when that game
	 * has no verified fork support. Same reasoning as
	 * {@link AreaForker#requireForkSupport}: the message a user gets must name
	 * the game they opened and the thing that was not measured for it, not the
	 * game that was.
	 */
	static void requireGeometryForkSupport() throws IOException {
		if (!Workspace.isValid()) {
			throw new IOException("No workspace is loaded, so there is no game to ask.");
		}
		ctrmap.gamedef.GameProfile p = Workspace.profile();
		if (!p.supports(ctrmap.gamedef.GameProfile.Feature.AREA_FORK)) {
			throw new IOException("Forking map geometry is not available for " + p.displayName() + "."
					+ "\n\nGiving one zone its own copy of its map appends a new MapMatrix and a"
					+ " copy of every region it names, then repoints the zone in both the ZO"
					+ " header and the master zone-header table. Those layouts were measured on"
					+ " Omega Ruby / Alpha Sapphire and have not been checked against "
					+ p.displayName() + ".");
		}
	}
}
