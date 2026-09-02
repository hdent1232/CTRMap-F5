package ctrmap;

import ctrmap.formats.garc.GARC;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Gives a zone its OWN private AREA so that editing its atmosphere, water
 * animations, props or NPC models stops changing every other zone that happens
 * to share it. The area counterpart of {@link GeometryForker}: forking geometry
 * makes a zone's MAP private, forking the area makes its LOOK AND FEEL private.
 *
 * <p>A zone header's {@code areadataID} (u16 at header offset 2) selects an
 * AreaData entry (a/0/1/4) - fog and lighting (subfile 4), world animations
 * incl. water scroll (2), the prop registry (0) and texture packs (1/11) - AND
 * the matching NPC-registry entry (a/1/3/7), which is indexed by the SAME id.
 * 77% of retail zones share their area with at least one other zone, so an
 * unforked atmosphere edit is felt game-wide.
 *
 * <p>Forking appends through the proven {@link GARC#packDirectory} path:
 * <ol>
 *   <li>the AreaData container is copied VERBATIM to a new tail index (nothing
 *       inside it names its own id, so no rewiring - the same argument that
 *       makes region cloning safe);</li>
 *   <li>the NPC registry entry is copied to the matching index, preserving the
 *       {@code npcRegIndex == areadataID} invariant the engine relies on;</li>
 *   <li>the GLOBAL PER-AREA TABLE grows: AreaData entry {@value #AD_GLOBAL_TABLE}
 *       is not an area at all but a flat array of {@value #AREA_ROW}-byte rows,
 *       one per area, that the engine indexes by area id (measured in code.bin:
 *       the loader reads {@code arc[228] @ (areaId * 44)}). A new area MUST get
 *       its row or the engine reads past the end of that entry;</li>
 *   <li>the zone's areadataID is repointed in BOTH the ZO container header and
 *       the master zone-header table (the runtime-authoritative copy).</li>
 * </ol>
 * The reverse-engineering pass found no hardcoded AreaData or NPC-registry
 * count anywhere in the executable (both come from the archive's own FATB at
 * load time), so this is pure data - NO code patch. The one measured ceiling:
 * the engine masks the area id to 8 bits when indexing the global table, so
 * forked areas must stay at or below {@value #MAX_AREA_ID}.
 *
 * <p>As with {@link GeometryForker}, the caller Packs the Workspace immediately
 * afterwards; only one area fork may be pending per pack cycle.
 */
public class AreaForker {

	/** AreaData index of the global per-area table (not an area itself). */
	public static final int AD_GLOBAL_TABLE = 228;
	/** Its per-area record stride, measured from the engine's row arithmetic. */
	public static final int AREA_ROW = 44;
	/** The engine masks area ids to 8 bits in the global-table lookup. */
	public static final int MAX_AREA_ID = 255;
	/** Master zone-header table row stride (shared with {@link GeometryForker}). */
	public static final int MASTER_ROW = GeometryForker.MASTER_ROW;
	/** Byte offset of areadataID inside a zone header. */
	public static final int HDR_AREA_OFF = 2;

	private static Map<Integer, Boolean> pendingAreaOverrides = null;
	private static Map<Integer, Boolean> pendingNpcRegOverrides = null;

	/** What a fork produced, for user-facing reporting. */
	public static class ForkResult {

		public int zoneIndex;
		public int oldArea;
		public int newArea;
		/** False when the zone already owned its area and nothing was appended. */
		public boolean forked;
	}

	/**
	 * Pure, headless byte plan of an area fork (no filesystem access) - unit
	 * tested against real archive bytes exactly like {@link GeometryForker#planFork}.
	 */
	public static class ForkPlan {

		public int oldArea;
		public byte[] newZoBytes;    // the zone container, areadataID repointed
		public byte[] newAdBytes;    // verbatim clone of the source area
		public byte[] newNpcBytes;   // verbatim clone of the source NPC registry
		public byte[] newTableBytes; // the global table grown to cover the new id
	}

	/**
	 * Builds the fork plan. {@code srcNpc} may be null/empty (36 retail areas
	 * have an empty registry) and is cloned as-is.
	 */
	public static ForkPlan planFork(byte[] zoBytes, byte[] srcAd, byte[] srcNpc, byte[] table, int newArea) {
		if (zoBytes == null || zoBytes.length < 8) {
			throw new IllegalArgumentException("Zone container too short.");
		}
		if (newArea > MAX_AREA_ID) {
			throw new IllegalArgumentException("Area id " + newArea + " exceeds the engine's 8-bit area index ("
					+ MAX_AREA_ID + "). Remove some forked areas first.");
		}
		if (newArea == AD_GLOBAL_TABLE) {
			throw new IllegalArgumentException("Area id " + AD_GLOBAL_TABLE + " is the engine's per-area table, not an area.");
		}
		int hdrOff = u32(zoBytes, 4);
		if (hdrOff < 0 || hdrOff + HDR_AREA_OFF + 2 > zoBytes.length) {
			throw new IllegalArgumentException("Zone header subfile out of range.");
		}
		int oldArea = u16(zoBytes, hdrOff + HDR_AREA_OFF);
		if (oldArea >= AD_GLOBAL_TABLE) {
			throw new IllegalArgumentException("Zone references area " + oldArea + ", which is not a real area.");
		}
		if (srcAd == null || srcAd.length == 0) {
			throw new IllegalArgumentException("Source area " + oldArea + " is empty.");
		}
		if (table == null || table.length < (oldArea + 1) * AREA_ROW) {
			throw new IllegalArgumentException("The per-area table is too short for area " + oldArea + ".");
		}
		ForkPlan p = new ForkPlan();
		p.oldArea = oldArea;
		p.newZoBytes = zoBytes.clone();
		putU16(p.newZoBytes, hdrOff + HDR_AREA_OFF, newArea);
		p.newAdBytes = srcAd.clone();
		p.newNpcBytes = srcNpc == null ? new byte[0] : srcNpc.clone();
		//grow the global table so the new id has a row, copied from the source
		//area (rows between the old tail and the new id stay zero filler)
		p.newTableBytes = Arrays.copyOf(table, (newArea + 1) * AREA_ROW);
		System.arraycopy(table, oldArea * AREA_ROW, p.newTableBytes, newArea * AREA_ROW, AREA_ROW);
		return p;
	}

	/**
	 * How many OTHER zones use this zone's area, per the master zone-header
	 * table. 0 = the area is already private.
	 */
	public static int areaSharers(int zoneIndex) throws IOException {
		GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		if (zo == null) {
			throw new IOException("No workspace is loaded (ZoneData archive unavailable).");
		}
		int zoneCount = zo.length - 2;
		File masterFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneCount);
		if (masterFile == null) {
			throw new IOException("Could not extract the master zone-header table.");
		}
		byte[] master = readAll(masterFile);
		if (master.length != zoneCount * MASTER_ROW) {
			byte[] fromGarc = zo.getDecompressedEntry(zoneCount);
			if (fromGarc == null || fromGarc.length != zoneCount * MASTER_ROW) {
				return 1; //unreadable: report shared - forking needlessly is safe
			}
			master = fromGarc;
		}
		int off = zoneIndex * MASTER_ROW + HDR_AREA_OFF;
		if (off + 2 > master.length) {
			throw new IOException("Master-table row for zone " + zoneIndex + " out of range.");
		}
		return zonesUsingArea(master, u16(master, off), zoneIndex).size();
	}

	/**
	 * Which zones point at an area, per a master zone-header table - excluding
	 * one zone, normally the one being edited.
	 *
	 * <p>That exclusion is the whole point. "Does another map depend on this
	 * area?" and "which rows name this area?" are different questions, and a
	 * caller that asks the second while meaning the first will always find the
	 * editing zone itself and conclude the area is shared. Every custom zone in
	 * this project occupies a repurposed retail slot, so the moment one is given
	 * a private area, its own row is the only row naming it - and a guard that
	 * counted rows refused every texture import into it, reporting the zone as
	 * conflicting with itself.
	 *
	 * <p>Pass -1 to exclude nothing. Returns empty for a null or misaligned
	 * table; callers that must fail closed check for that themselves.
	 */
	public static java.util.List<Integer> zonesUsingArea(byte[] master, int area, int excludeZone) {
		java.util.List<Integer> hits = new java.util.ArrayList<>();
		if (master == null || master.length < MASTER_ROW) {
			return hits;
		}
		int rows = master.length / MASTER_ROW;
		for (int z = 0; z < rows; z++) {
			if (z != excludeZone && u16(master, z * MASTER_ROW + HDR_AREA_OFF) == area) {
				hits.add(z);
			}
		}
		return hits;
	}

	/** The zone's current areadataID, straight from its container header. */
	public static int currentArea(int zoneIndex) throws IOException {
		File zoneFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
		if (zoneFile == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		byte[] zoBytes = readAll(zoneFile);
		return u16(zoBytes, u32(zoBytes, 4) + HDR_AREA_OFF);
	}

	/**
	 * Gives the zone its own private area when it still shares one; a no-op
	 * (reporting the existing id) when the area is already private. The caller
	 * applies its edit to {@link ForkResult#newArea} and packs ONCE.
	 */
	public static ForkResult forkIfShared(int zoneIndex) throws IOException {
		if (areaSharers(zoneIndex) > 0) {
			return forkArea(zoneIndex);
		}
		ForkResult r = new ForkResult();
		r.zoneIndex = zoneIndex;
		r.oldArea = currentArea(zoneIndex);
		r.newArea = r.oldArea;
		r.forked = false;
		return r;
	}

	/**
	 * Clones this zone's area (and its NPC registry) to private tail entries and
	 * repoints the zone at them. Pack the Workspace afterwards.
	 */
	public static ForkResult forkArea(int zoneIndex) throws IOException {
		if (!Workspace.isOA()) {
			throw new IOException("Area fork is ORAS-only in v1.");
		}
		GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
		GARC ad = Workspace.getArchive(Workspace.ArchiveType.AREA_DATA);
		GARC np = Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES);
		if (zo == null || ad == null || np == null) {
			throw new IOException("No workspace is loaded (ZoneData/AreaData/NPCRegistries unavailable).");
		}
		int zoneCount = zo.length - 2;
		if (zoneIndex < 0 || zoneIndex >= zoneCount) {
			throw new IOException("Zone " + zoneIndex + " out of range (0.." + (zoneCount - 1) + ").");
		}
		int newArea = ad.length;
		if (newArea > MAX_AREA_ID) {
			throw new IOException("No area ids left: the engine indexes areas with 8 bits, so "
					+ MAX_AREA_ID + " is the last usable id.");
		}
		//the engine indexes the NPC registry by area id - the two archives must
		//stay aligned (AreaData carries one extra entry: the global table)
		if (np.length != newArea - 1) {
			throw new IOException("AreaData (" + ad.length + " entries) and the NPC registry ("
					+ np.length + ") are out of step; an area fork would desynchronise them.");
		}
		File adDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.AREA_DATA);
		File npDir = Workspace.getExtractionDirectory(Workspace.ArchiveType.NPC_REGISTRIES);
		File adOut = new File(adDir, String.valueOf(newArea));
		if (Workspace.persist_paths.contains(adOut.getAbsolutePath())) {
			throw new IOException("An area fork is already pending. Pack the workspace before forking again.");
		}
		File zoneFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, zoneIndex);
		if (zoneFile == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		byte[] zoBytes = readAll(zoneFile);
		int oldArea = u16(zoBytes, u32(zoBytes, 4) + HDR_AREA_OFF);
		File srcAdFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, oldArea);
		File srcNpFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.NPC_REGISTRIES, oldArea);
		File tableFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.AREA_DATA, AD_GLOBAL_TABLE);
		if (srcAdFile == null || tableFile == null) {
			throw new IOException("Could not extract area " + oldArea + " from the workspace.");
		}
		byte[] srcNp = (srcNpFile != null && srcNpFile.exists()) ? readAll(srcNpFile) : new byte[0];
		ForkPlan plan = planFork(zoBytes, readAll(srcAdFile), srcNp, readAll(tableFile), newArea);

		//stage: new area, its registry, the grown table, the repointed zone
		writeAll(adOut, plan.newAdBytes);
		Workspace.addPersist(adOut);
		registerPendingArea(newArea, ad.isEntryCompressed(oldArea));

		File npOut = new File(npDir, String.valueOf(newArea));
		npDir.mkdirs();
		writeAll(npOut, plan.newNpcBytes);
		Workspace.addPersist(npOut);
		registerPendingNpcReg(newArea, np.length > 0 && np.isEntryCompressed(Math.min(oldArea, np.length - 1)));

		writeAll(tableFile, plan.newTableBytes);
		Workspace.addPersist(tableFile);

		writeAll(zoneFile, plan.newZoBytes);
		Workspace.addPersist(zoneFile);
		repointMasterArea(zo, zoneIndex, newArea);

		ForkResult r = new ForkResult();
		r.zoneIndex = zoneIndex;
		r.oldArea = oldArea;
		r.newArea = newArea;
		r.forked = true;
		//A fork writes an AreaData entry AND an NPC registry entry. If only one
		//of them survives the pack, the new area exists with nothing behind it
		//and every later load of that zone throws. That has happened; say so
		//here, next to the fork, rather than leaving it to be found by hand.
		WorkspaceIntegrity.report("forking area " + oldArea + " -> " + newArea);
		return r;
	}

	/** Repoints a zone's areadataID in the master zone-header table file. */
	public static void repointMasterArea(GARC zo, int zoneIndex, int newArea) throws IOException {
		int masterIndex = zo.length - 2;
		File masterFile = Workspace.getWorkspaceFile(Workspace.ArchiveType.ZONE_DATA, masterIndex);
		if (masterFile == null) {
			throw new IOException("Could not extract the master zone-header table.");
		}
		byte[] master = readAll(masterFile);
		int rowOff = zoneIndex * MASTER_ROW + HDR_AREA_OFF;
		if (rowOff + 2 > master.length) {
			throw new IOException("Master-table row for zone " + zoneIndex + " out of range.");
		}
		putU16(master, rowOff, newArea);
		writeAll(masterFile, master);
		Workspace.addPersist(masterFile);
	}

	public static void registerPendingArea(int index, boolean compressed) {
		if (pendingAreaOverrides == null) {
			pendingAreaOverrides = new HashMap<>();
		}
		pendingAreaOverrides.put(index, compressed);
	}

	public static void registerPendingNpcReg(int index, boolean compressed) {
		if (pendingNpcRegOverrides == null) {
			pendingNpcRegOverrides = new HashMap<>();
		}
		pendingNpcRegOverrides.put(index, compressed);
	}

	/** Drains the AreaData compression overrides of a pending fork (one pack). */
	public static Map<Integer, Boolean> consumePendingAreaOverrides() {
		Map<Integer, Boolean> m = pendingAreaOverrides;
		pendingAreaOverrides = null;
		return m;
	}

	/** Drains the NPC-registry compression overrides of a pending fork. */
	public static Map<Integer, Boolean> consumePendingNpcRegOverrides() {
		Map<Integer, Boolean> m = pendingNpcRegOverrides;
		pendingNpcRegOverrides = null;
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
