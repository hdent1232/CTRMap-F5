package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static ctrmap.formats.LittleEndian.u16;
import static ctrmap.formats.LittleEndian.i32;
import static ctrmap.formats.LittleEndian.putU16;
import java.nio.file.Files;

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
		int hdrOff = i32(zoBytes, 4);
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
		File zoneFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		if (zoneFile == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		byte[] zoBytes = Files.readAllBytes(zoneFile.toPath());
		return u16(zoBytes, i32(zoBytes, 4) + HDR_AREA_OFF);
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
	 * How many more areas this workspace can create before the engine runs out of
	 * ids. Asked BEFORE an append writes anything, because running out half way
	 * through leaves some new zones private and some sharing.
	 */
	public static int areaIdsLeft() throws IOException {
		GARC ad = Workspace.getArchive(ArchiveType.AREA_DATA);
		if (ad == null) {
			throw new IOException("No workspace is loaded (AreaData unavailable).");
		}
		return Math.max(0, MAX_AREA_ID - ad.length + 1);
	}

	/**
	 * Gives each of {@code count} freshly appended zones its OWN area, in ONE pack
	 * cycle - the area half of making a created zone independent from birth.
	 *
	 * <p>WHY A BATCH AND NOT {@code count} CALLS TO {@link #forkArea}. That method
	 * takes its new id from {@code ad.length} and refuses when the slot is already
	 * staged, so it can only run ONCE between packs: a second call in the same
	 * cycle asks for the same id and is told a fork is pending. An append creates
	 * four zones at a time, so four calls could never have worked. This threads the
	 * id, and the global per-area table, through the loop - the table especially,
	 * because each fork GROWS it and the next fork has to grow the grown one.
	 *
	 * <p>Mutates the caller's payloads in place, exactly as
	 * {@code GeometryForker.forkAppendedZones} does: {@code newZos[i]} becomes the
	 * repointed container and {@code master}'s area column is repointed for each.
	 * The appender has not written either yet, so this is the last moment they can
	 * be changed without a second pass.
	 *
	 * <p>IT REFUSES BEFORE IT WRITES. Area ids are an 8-bit index and retail uses
	 * 229 of the 256, so the budget is small enough to exhaust. Checking it per
	 * zone inside the loop would leave an append that gave the first two zones
	 * their own area and the last two somebody else's - the exact half-state the
	 * appender promises never to leave.
	 *
	 * @param newZos the appended zones' ZO containers (mutated in place)
	 * @param master the grown master zone-header table (rows repointed in place)
	 * @param oldCount the first new zone's index, which is its master-table row
	 * @param count how many of them to give an area to
	 */
	public static void forkAppendedAreas(byte[][] newZos, byte[] master, int oldCount, int count)
			throws IOException {
		if (count <= 0) {
			return;
		}
		requireForkSupport();
		GARC ad = Workspace.getArchive(ArchiveType.AREA_DATA);
		GARC np = Workspace.getArchive(ArchiveType.NPC_REGISTRIES);
		GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		if (ad == null || np == null || zo == null) {
			throw new IOException("No workspace is loaded (AreaData/NPCRegistries unavailable).");
		}
		int firstArea = ad.length;
		if (firstArea + count - 1 > MAX_AREA_ID) {
			throw new IOException("Giving " + count + " new zone(s) their own area needs " + count
				+ " area id(s), and only " + areaIdsLeft() + " are left: the engine indexes areas"
				+ " with 8 bits, so " + MAX_AREA_ID + " is the last usable id."
				+ "\n\nRemove some added zones, or some forked areas, first.");
		}
		File adDir = Workspace.getExtractionDirectory(ArchiveType.AREA_DATA);
		File npDir = Workspace.getExtractionDirectory(ArchiveType.NPC_REGISTRIES);
		if (Workspace.isPendingArtifact(new File(adDir, String.valueOf(firstArea)))) {
			throw new IOException("An area fork is already staged and not yet packed.");
		}
		File tableFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, AD_GLOBAL_TABLE);
		if (tableFile == null) {
			throw new IOException("Could not extract the per-area table.");
		}
		byte[] table = Files.readAllBytes(tableFile.toPath());
		npDir.mkdirs();
		//how far the registry archive REACHES, not how many entries it has: the
		//engine indexes registries by area id, and an archive only grows at its tail
		int npReaches = np.length;
		for (int i = 0; i < count; i++) {
			int newArea = firstArea + i;
			int oldArea = u16(newZos[i], i32(newZos[i], 4) + HDR_AREA_OFF);
			File srcAdFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, oldArea);
			if (srcAdFile == null) {
				throw new IOException("Could not extract area " + oldArea + " from the workspace.");
			}
			File srcNpFile = Workspace.getWorkspaceFile(ArchiveType.NPC_REGISTRIES, oldArea);
			byte[] srcNp = (srcNpFile != null && srcNpFile.exists())
				? Files.readAllBytes(srcNpFile.toPath()) : new byte[0];
			ForkPlan plan = planFork(newZos[i], Files.readAllBytes(srcAdFile.toPath()), srcNp,
				table, newArea);
			
			File adOut = new File(adDir, String.valueOf(newArea));
			Files.write(adOut.toPath(), plan.newAdBytes);
			Workspace.addPersist(adOut);
			registerPendingArea(newArea, ad.isEntryCompressed(oldArea));
			for (int filler = npReaches; filler < newArea; filler++) {
				File fillOut = new File(npDir, String.valueOf(filler));
				Files.write(fillOut.toPath(), new byte[0]);
				Workspace.addPersist(fillOut);
				registerPendingNpcReg(filler, false);
			}
			File npOut = new File(npDir, String.valueOf(newArea));
			Files.write(npOut.toPath(), plan.newNpcBytes);
			Workspace.addPersist(npOut);
			registerPendingNpcReg(newArea,
				np.length > 0 && np.isEntryCompressed(Math.min(oldArea, np.length - 1)));
			npReaches = newArea + 1;
			
			table = plan.newTableBytes;   // each fork grows it; the next grows the grown one
			newZos[i] = plan.newZoBytes;  // the repointed container the caller will write
			int rowOff = (oldCount + i) * MASTER_ROW + HDR_AREA_OFF;
			if (rowOff + 2 > master.length) {
				throw new IOException("Master-table row for zone " + (oldCount + i) + " out of range.");
			}
			putU16(master, rowOff, newArea);
		}
		Files.write(tableFile.toPath(), table);
		Workspace.addPersist(tableFile);
	}
	/**
	 * Clones this zone's area (and its NPC registry) to private tail entries and
	 * repoints the zone at them. Pack the Workspace afterwards.
	 */
	public static ForkResult forkArea(int zoneIndex) throws IOException {
		//asks what this game CAN DO, not which game it is: a gate reading
		//"not ORAS" refuses XY and Sun/Moon for the same reason and tells the
		//user neither of them
		requireForkSupport();
		GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		GARC ad = Workspace.getArchive(ArchiveType.AREA_DATA);
		GARC np = Workspace.getArchive(ArchiveType.NPC_REGISTRIES);
		if (zo == null || ad == null || np == null) {
			throw new IOException("No workspace is loaded (ZoneData/AreaData/NPCRegistries unavailable).");
		}
		int zoneCount = ZoneTables.zoneCount(zo);
		if (zoneIndex < 0 || zoneIndex >= zoneCount) {
			throw new IOException("Zone " + zoneIndex + " out of range (0.." + (zoneCount - 1) + ").");
		}
		int newArea = ad.length;
		if (newArea > MAX_AREA_ID) {
			throw new IOException("No area ids left: the engine indexes areas with 8 bits, so "
					+ MAX_AREA_ID + " is the last usable id.");
		}
		//The engine indexes the NPC registry by area id, so the archive has to
		//REACH the new id - a count relationship is not the same thing. Retail
		//ships 229 AreaData entries (228 of them areas, plus the per-area table
		//at index 228) against 228 registries, so the first forked id, 229, is
		//two past the end of the registry archive. The gap is filled below.
		if (np.length > newArea + 1) {
			throw new IOException("The NPC registry (" + np.length + " entries) already reaches"
					+ " past the new area id " + newArea + "; it and AreaData (" + ad.length
					+ " entries) are out of step, and an area fork would not repair that.");
		}
		File adDir = Workspace.getExtractionDirectory(ArchiveType.AREA_DATA);
		File npDir = Workspace.getExtractionDirectory(ArchiveType.NPC_REGISTRIES);
		File adOut = new File(adDir, String.valueOf(newArea));
		if (Workspace.isPendingArtifact(adOut)) {
			throw new IOException("An area fork is already pending. Pack the workspace before forking again.");
		}
		File zoneFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		if (zoneFile == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		byte[] zoBytes = Files.readAllBytes(zoneFile.toPath());
		int oldArea = u16(zoBytes, i32(zoBytes, 4) + HDR_AREA_OFF);
		File srcAdFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, oldArea);
		File srcNpFile = Workspace.getWorkspaceFile(ArchiveType.NPC_REGISTRIES, oldArea);
		File tableFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, AD_GLOBAL_TABLE);
		if (srcAdFile == null || tableFile == null) {
			throw new IOException("Could not extract area " + oldArea + " from the workspace.");
		}
		byte[] srcNp = (srcNpFile != null && srcNpFile.exists()) ? Files.readAllBytes(srcNpFile.toPath()) : new byte[0];
		ForkPlan plan = planFork(zoBytes, Files.readAllBytes(srcAdFile.toPath()), srcNp, Files.readAllBytes(tableFile.toPath()), newArea);

		//stage: new area, its registry, the grown table, the repointed zone
		Files.write(adOut.toPath(), plan.newAdBytes);
		Workspace.addPersist(adOut);
		registerPendingArea(newArea, ad.isEntryCompressed(oldArea));

		npDir.mkdirs();
		//An archive can only grow at its tail: a file named past the end lands
		//at the first free slot instead, which is how the clone of area 21 came
		//to sit at index 228 while the zone asked for 229. Every index between
		//the registry's end and the new id gets an entry of its own first, so
		//the clone lands where the engine will look for it. They are empty, and
		//an empty registry is a real thing - 36 retail areas ship one - and no
		//zone can name them anyway: the only id skipped this way is 228, the
		//per-area table's slot, which planFork refuses as an area.
		for (int filler = np.length; filler < newArea; filler++) {
			File fillOut = new File(npDir, String.valueOf(filler));
			Files.write(fillOut.toPath(), new byte[0]);
			Workspace.addPersist(fillOut);
			registerPendingNpcReg(filler, false);
		}
		File npOut = new File(npDir, String.valueOf(newArea));
		Files.write(npOut.toPath(), plan.newNpcBytes);
		Workspace.addPersist(npOut);
		registerPendingNpcReg(newArea, np.length > 0 && np.isEntryCompressed(Math.min(oldArea, np.length - 1)));

		Files.write(tableFile.toPath(), plan.newTableBytes);
		Workspace.addPersist(tableFile);

		Files.write(zoneFile.toPath(), plan.newZoBytes);
		Workspace.addPersist(zoneFile);
		repointMasterArea(zo, zoneIndex, newArea);

		ForkResult r = new ForkResult();
		r.zoneIndex = zoneIndex;
		r.oldArea = oldArea;
		r.newArea = newArea;
		r.forked = true;
		//A fork writes an AreaData entry AND an NPC registry entry. If only one
		//of them survives the pack, the new area exists with nothing behind it
		//and every later load of that zone throws. That check used to run here,
		//before the pack, when the archives on disk still held none of this and
		//it could only ever report clean; Workspace.packArchives runs it after
		//the writes instead, and shows what it finds.
		return r;
	}

	/** Repoints a zone's areadataID in the master zone-header table file. */
	public static void repointMasterArea(GARC zo, int zoneIndex, int newArea) throws IOException {
		int masterIndex = ZoneTables.masterIndex(zo);
		File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, masterIndex);
		if (masterFile == null) {
			throw new IOException("Could not extract the master zone-header table.");
		}
		byte[] master = Files.readAllBytes(masterFile.toPath());
		int rowOff = zoneIndex * MASTER_ROW + HDR_AREA_OFF;
		if (rowOff + 2 > master.length) {
			throw new IOException("Master-table row for zone " + zoneIndex + " out of range.");
		}
		putU16(master, rowOff, newArea);
		Files.write(masterFile.toPath(), master);
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

	/**
	 * Refuses an area fork, in the loaded game's own name, when that game has
	 * no verified fork support.
	 *
	 * <p>The old sentence was "Area fork is ORAS-only in v1." - true, and it
	 * told a user who had opened X/Y nothing about what they had opened or why
	 * it was refused, and it would have said the same to Sun/Moon while the
	 * gate it guarded ({@code !Workspace.isOA()}) lumped every non-ORAS game
	 * together without asking any of them anything.
	 */
	static void requireForkSupport() throws IOException {
		if (!Workspace.isValid()) {
			throw new IOException("No workspace is loaded, so there is no game to ask.");
		}
		ctrmap.gamedef.GameProfile p = Workspace.profile();
		if (!p.supports(ctrmap.gamedef.GameProfile.Feature.AREA_FORK)) {
			throw new IOException("Forking an area is not available for " + p.displayName() + "."
					+ "\n\nGiving one zone its own private copy of a shared area appends to four"
					+ " archives and rewrites the engine's global per-area table. Every offset"
					+ " it uses was measured on Omega Ruby / Alpha Sapphire and none of them has"
					+ " been checked against " + p.displayName() + ", so CTRMap will not write"
					+ " them there.");
		}
	}
}
