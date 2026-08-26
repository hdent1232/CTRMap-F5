package ctrmap;

import ctrmap.formats.garc.GARC;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gives a zone its OWN private map geometry so that editing its map no longer
 * changes every other zone that shares it. This runs AUTOMATICALLY for every new
 * zone created by {@link ZoneAppender} (a fresh zone is independent by default,
 * which is what users expect); {@link #forkGeometry(int)} is the manual entry
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
	}

	/**
	 * Builds the fork plan from the zone's ZO container and its MapMatrix entry.
	 * Derives the zone's current mapmatrixID from the header, enumerates the
	 * unique FieldData region IDs in the matrix grid, assigns them new tail
	 * indices, and produces the rewired matrix + repointed header. No I/O.
	 */
	public static ForkPlan planFork(byte[] zoBytes, byte[] matBytes, int firstNewRegion, int newMatrixIndex) {
		if (zoBytes == null || zoBytes.length < 8) {
			throw new IllegalArgumentException("Zone container too short.");
		}
		int hdrOff = u32(zoBytes, 4);
		if (hdrOff < 0 || hdrOff + 6 > zoBytes.length) {
			throw new IllegalArgumentException("Zone header subfile out of range.");
		}
		int oldMatrix = u16(zoBytes, hdrOff + 4);

		if (matBytes == null || matBytes.length < 12) {
			throw new IllegalArgumentException("Map matrix container too short.");
		}
		int sub0 = u32(matBytes, 4);
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
		byte[] newZo = zoBytes.clone();
		putU16(newZo, hdrOff + 4, newMatrixIndex);

		ForkPlan p = new ForkPlan();
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
	private static ForkPlan forkArchives(byte[] zoBytes, int firstNewRegion, int newMatrix,
			GARC gr, GARC mm, File fdDir, File mmDir) throws IOException {
		int hdrOff = u32(zoBytes, 4);
		int oldMatrix = u16(zoBytes, hdrOff + 4);
		if (oldMatrix < 0 || oldMatrix >= mm.length) {
			throw new IOException("Zone references matrix " + oldMatrix + " which does not exist.");
		}
		File srcMatrixFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.MAP_MATRIX, oldMatrix);
		if (srcMatrixFile == null) {
			throw new IOException("Could not extract map matrix " + oldMatrix + " from the workspace.");
		}
		ForkPlan plan = planFork(zoBytes, readAll(srcMatrixFile), firstNewRegion, newMatrix);

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
			File srcRegionFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.FIELD_DATA, oldR);
			if (srcRegionFile == null) {
				throw new IOException("Could not extract FieldData region " + oldR + " from the workspace.");
			}
			File out = new File(fdDir, String.valueOf(newR));
			writeAll(out, readAll(srcRegionFile));
			Workspace.addPersist(out);
			pendingFieldOverrides.put(newR, gr.isEntryCompressed(oldR));
		}
		File matrixOut = new File(mmDir, String.valueOf(newMatrix));
		writeAll(matrixOut, plan.newMatrixBytes);
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
		GARC gr = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA);
		GARC mm = Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX);
		if (gr == null || mm == null) {
			throw new IOException("FieldData/MapMatrix archive unavailable.");
		}
		File fdDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA);
		File mmDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.MAP_MATRIX);
		int nextRegion = gr.length;
		int nextMatrix = mm.length;
		for (int i = 0; i < newRealZones; i++) {
			int newMatrix = nextMatrix++;
			ForkPlan plan = forkArchives(newZos[i], nextRegion, newMatrix, gr, mm, fdDir, mmDir);
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
	 * Manually forks the given (already-existing) zone's map geometry to a
	 * private copy in the current ORAS workspace. Pack Workspace afterwards.
	 */
	public static ForkResult forkGeometry(int zoneIndex) throws IOException {
		if (!Workspace.isOA()) {
			throw new IOException("Geometry fork is ORAS-only in v1.");
		}
		GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		GARC gr = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA);
		GARC mm = Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX);
		if (zo == null || gr == null || mm == null) {
			throw new IOException("No workspace is loaded (ZoneData/FieldData/MapMatrix unavailable).");
		}
		int zoneCount = zo.length - 2; // master table + EN pack occupy the last two entries
		if (zoneIndex < 0 || zoneIndex >= zoneCount) {
			throw new IOException("Zone " + zoneIndex + " out of range (0.." + (zoneCount - 1) + "). "
					+ "Note: the last two ZoneData entries are the master/EN tables, not zones.");
		}
		File fdDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.FIELD_DATA);
		File mmDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.MAP_MATRIX);
		int newMatrix = mm.length;
		File matrixOut = new File(mmDir, String.valueOf(newMatrix));
		if (Workspace.persist_paths.contains(matrixOut.getAbsolutePath())) {
			throw new IOException("A geometry fork/append is already pending. Pack the workspace before forking again.");
		}
		File zoneFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
		if (zoneFile == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		byte[] zoBytes = readAll(zoneFile);
		ForkPlan plan = forkArchives(zoBytes, gr.length, newMatrix, gr, mm, fdDir, mmDir);

		// repoint the ZO container header, in place
		writeAll(zoneFile, plan.newZoBytes);
		Workspace.addPersist(zoneFile);
		// repoint the master zone-header table row (the runtime-authoritative copy)
		repointMasterRow(zo, zoneIndex, newMatrix);

		ForkResult r = new ForkResult();
		r.zoneIndex = zoneIndex;
		r.oldMatrix = plan.oldMatrix;
		r.newMatrix = newMatrix;
		r.srcRegions = plan.srcRegions;
		r.newRegions = plan.newRegions;
		return r;
	}

	/** Repoints a zone's mapmatrixID in the master zone-header table file. */
	private static void repointMasterRow(GARC zo, int zoneIndex, int newMatrix) throws IOException {
		int masterIndex = zo.length - 2;
		File masterFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, masterIndex);
		if (masterFile == null) {
			throw new IOException("Could not extract the master zone-header table.");
		}
		byte[] master = readAll(masterFile);
		int rowOff = zoneIndex * MASTER_ROW + 4;
		if (rowOff + 2 > master.length) {
			throw new IOException("Master-table row for zone " + zoneIndex + " out of range.");
		}
		putU16(master, rowOff, newMatrix);
		writeAll(masterFile, master);
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

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	private static void putU16(byte[] b, int o, int v) {
		b[o] = (byte) (v & 0xFF);
		b[o + 1] = (byte) ((v >> 8) & 0xFF);
	}

	private static byte[] readAll(File f) throws IOException {
		InputStream in = new FileInputStream(f);
		byte[] b = new byte[in.available()];
		in.read(b);
		in.close();
		return b;
	}

	private static void writeAll(File f, byte[] b) throws IOException {
		OutputStream os = new FileOutputStream(f);
		os.write(b);
		os.flush();
		os.close();
	}
}
