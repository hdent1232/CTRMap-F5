package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static ctrmap.util.LittleEndian.i32;
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

	/**
	 * AreaData index of the global per-area table (not an area itself): Omega Ruby / Alpha Sapphire's
	 * profile answers it, the only game the fork is open to (F205).
	 */
	public static final int AD_GLOBAL_TABLE = ctrmap.gamedef.GameProfile.onlyGameWith(
			ctrmap.gamedef.GameProfile.Feature.AREA_FORK).areaTableIndex();
	/** Its per-area record stride, measured from the engine's row arithmetic. */
	public static final int AREA_ROW = 44;
	/** The engine masks area ids to 8 bits in the global-table lookup. */
	public static final int MAX_AREA_ID = 255;
	/** Master zone-header table row stride (shared with {@link GeometryForker}). */
	public static final int MASTER_ROW = GeometryForker.MASTER_ROW;

	/** What a fork produced, for user-facing reporting. */
	public static class ForkResult {

		public int zoneIndex;
		public int oldArea;
		public int newArea;
		/** False when the zone already owned its area and nothing was appended. */
		public boolean forked;

		/** What the user is told an area fork did; both branches, said rather than implied. */
		public String report(int zoneIndex) {
			if (!forked) {
				return "Zone " + zoneIndex + " already has its own area (area " + oldArea + ").\n\n"
						+ "Nothing was changed - editing its fog, lighting, props or textures already affects"
						+ " no other zone.";
			}
			return "Zone " + zoneIndex + " now has its own area.\n\nArea " + oldArea + " -> " + newArea
					+ " (private copy, identical until you edit it). Its fog, lighting, props, textures and NPC"
					+ " models no longer change any other zone.";
		}
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
		if (hdrOff < 0 || hdrOff + ZoneResource.AREA.headerOffset + 2 > zoBytes.length) {
			throw new IllegalArgumentException("Zone header subfile out of range.");
		}
		int oldArea = ZoneResource.AREA.idIn(zoBytes);
		//ONLY THE TABLE'S SLOT IS NOT AN AREA (F001). This refused every id from 228 up - and a
		//fork's new areas are 229..255, so a zone cloned from a forked one could never be forked
		if (oldArea == AD_GLOBAL_TABLE || oldArea > MAX_AREA_ID) {
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
		ZoneResource.AREA.setIn(p.newZoBytes, newArea);
		p.newAdBytes = srcAd.clone();
		p.newNpcBytes = srcNpc == null ? new byte[0] : srcNpc.clone();
		//grow the global table so the new id has a row, copied from the source
		//area (rows between the old tail and the new id stay zero filler). Never
		//SHRINK it: an id given back (F043) sits inside the table, and cutting the
		//table at it would drop every row past it - other forked zones' rows
		p.newTableBytes = Arrays.copyOf(table, Math.max(table.length, (newArea + 1) * AREA_ROW));
		System.arraycopy(table, oldArea * AREA_ROW, p.newTableBytes, newArea * AREA_ROW, AREA_ROW);
		return p;
	}

	/**
	 * How many OTHER zones use this zone's area, per the master zone-header
	 * table. 0 = the area is already private.
	 */
	public static int areaSharers(int zoneIndex) throws IOException {
		//through the one reader (F013): an unreadable table is said, not answered "1 sharer"
		MasterTable master = MasterTable.read(Workspace.session());
		if (zoneIndex < 0 || zoneIndex >= master.zones()) {
			throw new IOException("Master-table row for zone " + zoneIndex + " out of range.");
		}
		return master.sharing(ZoneResource.AREA, zoneIndex).size();
	}

	/** The zone's current areadataID, straight from its container header. */
	public static int currentArea(int zoneIndex) throws IOException {
		File zoneFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneIndex);
		if (zoneFile == null) {
			throw new IOException("Could not extract zone " + zoneIndex + " from the workspace.");
		}
		byte[] zoBytes = Files.readAllBytes(zoneFile.toPath());
		return ZoneResource.AREA.idIn(zoBytes);
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
	 * ids. The append does not ask this: {@link #takeAreaIds} refuses the whole
	 * batch before anything is written, because running out half way through
	 * leaves some new zones private and some sharing.
	 */
	@ctrmap.util.ForSuites("SEAM: a suite asks how many area forks the open workspace has left, to size an append past them; the append itself refuses through takeAreaIds before it writes anything")
	public static int areaIdsLeft() throws IOException {
		GARC ad = Workspace.getArchive(ArchiveType.AREA_DATA);
		GARC np = Workspace.getArchive(ArchiveType.NPC_REGISTRIES);
		if (ad == null || np == null) {
			throw new IOException("No workspace is loaded (AreaData unavailable).");
		}
		return idsLeft(MasterTable.read(Workspace.session()), ad.length, registryEmpty(Workspace.session(), np));
	}

	/**
	 * The forked area ids past the per-area table's slot, below the archive's end, that no
	 * master-table row names, lowest first. Retail's areas all sit below the table's slot, so
	 * every id here was made by a fork and its zone has since gone (F043).
	 *
	 * @param master every zone's row, as the game will read them
	 * @param adLength how many AreaData entries exist
	 */
	public static List<Integer> unnamedForkedAreas(MasterTable master, int adLength) {
		boolean[] named = new boolean[Math.max(adLength, MAX_AREA_ID + 1)];
		for (int z = 0; z < master.zones(); z++) {
			int a = master.id(ZoneResource.AREA, z);
			if (a >= 0 && a < named.length) {
				named[a] = true;
			}
		}
		List<Integer> out = new ArrayList<>();
		for (int id = AD_GLOBAL_TABLE + 1; id < adLength && id <= MAX_AREA_ID; id++) {
			if (!named[id]) {
				out.add(id);
			}
		}
		return out;
	}

	/**
	 * The ids a fork may take back: forked, named by no zone, AND with an empty NPC registry -
	 * the mark {@link ZoneRemover#remove} leaves on the ids of the zones it removed.
	 *
	 * <p>NOT EVERY UNNAMED ID. A non-empty registry at an id no zone names is what an older
	 * build's misplaced fork left, and {@link WorkspaceIntegrity} tells the user to move it into
	 * place by hand; it may be the only copy of those NPCs. Reusing that id would overwrite it.
	 */
	public static List<Integer> givenBack(MasterTable master, int adLength, java.util.function.IntPredicate registryEmpty) {
		List<Integer> out = new ArrayList<>();
		for (int id : unnamedForkedAreas(master, adLength)) {
			if (registryEmpty.test(id)) {
				out.add(id);
			}
		}
		return out;
	}

	/**
	 * Whether the open workspace's NPC registry at an id is empty: the staged copy when there is
	 * one, else the archive's entry; an id past the archive's end has none.
	 */
	static java.util.function.IntPredicate registryEmpty(WorkspaceSession s, GARC np) {
		File dir = s.getExtractionDirectory(ArchiveType.NPC_REGISTRIES);
		return id -> {
			File staged = new File(dir, String.valueOf(id));
			if (s.isPendingArtifact(staged)) {
				return staged.length() == 0;
			}
			return id >= np.length || np.getEntryStoredLength(id) == 0;
		};
	}

	/** How many forks can still be made: the ids given back, then the ones past the archive's end. */
	static int idsLeft(MasterTable master, int adLength, java.util.function.IntPredicate registryEmpty) {
		return givenBack(master, adLength, registryEmpty).size() + Math.max(0, MAX_AREA_ID - adLength + 1);
	}

	/**
	 * The ids {@code count} forks take: the ones given back first, lowest first, then new ids
	 * at the archive's end. Refused in words, before anything is written, when there are not
	 * that many.
	 *
	 * <p>WHY IT REUSES (F043). Every id came from the archive's end and nothing ever gave one
	 * back, so removing added zones left their areas behind on no zone's row, and the 27 ids
	 * past retail's 229 ran out after about six add-four-remove-four cycles - on a workspace
	 * with no added zones at all, refused for good.
	 */
	public static int[] takeAreaIds(MasterTable master, int adLength, java.util.function.IntPredicate registryEmpty,
			int count) throws IOException {
		List<Integer> back = givenBack(master, adLength, registryEmpty);
		int left = back.size() + Math.max(0, MAX_AREA_ID - adLength + 1);
		if (count > left) {
			throw new IOException("Giving " + count + " zone(s) their own area needs " + count
				+ " area id(s), and only " + left + " are left: the engine indexes areas"
				+ " with 8 bits, so " + MAX_AREA_ID + " is the last usable id."
				+ "\n\nRemove some added zones, or some forked areas, first.");
		}
		int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = i < back.size() ? back.get(i) : adLength + (i - back.size());
		}
		return ids;
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
	 * <p>TAKES WHICH ZONES, NOT HOW MANY FROM WHERE. An append hands a contiguous
	 * run; a REPAIR of a workspace an older version made hands whatever is still
	 * sharing, which is rarely contiguous - the owner's own game has 537, 538 and
	 * 539 to fix and 536 already done. One shape serves both.
	 *
	 * @param zos the zones' ZO containers, parallel to {@code zoneIndices} (mutated)
	 * @param master the master zone-header table (rows repointed in place)
	 * @param zoneIndices which zone each entry of {@code zos} is
	 */
	public static void forkAppendedAreas(byte[][] zos, MasterTable master, int[] zoneIndices)
			throws IOException {
		//ONE UNIT (F002): the budget was the only thing checked before the loop wrote, so a zone
		//that failed half way through left the earlier ones' area and NPC entries staged with no
		//row in the per-area table - the state the class comment says makes the engine read past
		//the end. Inside an append or a repair this joins theirs.
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Give new zones their own area", Workspace.session())) {
			forkAppendedAreasWrites(zos, master, zoneIndices);
			unit.commit();
		}
	}

	private static void forkAppendedAreasWrites(byte[][] zos, MasterTable master, int[] zoneIndices)
			throws IOException {
		int count = zoneIndices == null ? 0 : zoneIndices.length;
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
		//the ids given back first, then the archive's end (F043); refused here, before any write
		int[] ids = takeAreaIds(master, ad.length, registryEmpty(Workspace.session(), np), count);
		File adDir = Workspace.getExtractionDirectory(ArchiveType.AREA_DATA);
		File npDir = Workspace.getExtractionDirectory(ArchiveType.NPC_REGISTRIES);
		for (int id : ids) {
			if (Workspace.isPendingArtifact(new File(adDir, String.valueOf(id)))) {
				throw new IOException("An area fork is already staged and not yet packed.");
			}
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
			int newArea = ids[i];
			requireInStep(newArea, ad.length, np.length);
			int oldArea = ZoneResource.AREA.idIn(zos[i]);
			File srcAdFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, oldArea);
			if (srcAdFile == null) {
				throw new IOException("Could not extract area " + oldArea + " from the workspace.");
			}
			File srcNpFile = Workspace.getWorkspaceFile(ArchiveType.NPC_REGISTRIES, oldArea);
			byte[] srcNp = (srcNpFile != null && srcNpFile.exists())
				? Files.readAllBytes(srcNpFile.toPath()) : new byte[0];
			ForkPlan plan = planFork(zos[i], Files.readAllBytes(srcAdFile.toPath()), srcNp,
				table, newArea);
			
			File adOut = new File(adDir, String.valueOf(newArea));
			WorkspaceUnit.write(adOut, plan.newAdBytes);
			Workspace.addPersist(adOut);
			registerPendingArea(newArea, ad.isEntryCompressed(oldArea));
			for (int filler = npReaches; filler < newArea; filler++) {
				File fillOut = new File(npDir, String.valueOf(filler));
				WorkspaceUnit.write(fillOut, new byte[0]);
				Workspace.addPersist(fillOut);
				registerPendingNpcReg(filler, false);
			}
			File npOut = new File(npDir, String.valueOf(newArea));
			WorkspaceUnit.write(npOut, plan.newNpcBytes);
			Workspace.addPersist(npOut);
			registerPendingNpcReg(newArea,
				np.length > 0 && np.isEntryCompressed(Math.min(oldArea, np.length - 1)));
			//an id given back lies inside the archive: the reach never moves backwards, or the
			//next tail id's fillers would overwrite real registries with empty ones
			npReaches = Math.max(npReaches, newArea + 1);
			
			table = plan.newTableBytes;   // each fork grows it; the next grows the grown one
			zos[i] = plan.newZoBytes;  // the repointed container the caller will write
			if (zoneIndices[i] < 0 || zoneIndices[i] >= master.zones()) {
				throw new IOException("Master-table row for zone " + zoneIndices[i] + " out of range.");
			}
			master.set(ZoneResource.AREA, zoneIndices[i], newArea);
		}
		WorkspaceUnit.write(tableFile, table);
		Workspace.addPersist(tableFile);
	}
	/**
	 * Refuses a fork to tail id {@code newArea} when the NPC registry archive already reaches past it: the engine
	 * indexes registries by area id, so the registry and AreaData are out of step, and a fork would not repair that
	 * - it would write its registry over whatever sits at that id. ONE CHECK FOR EVERY FORK (census M.43): Fork area
	 * asked it and the append's fork did not.
	 */
	public static void requireInStep(int newArea, int adLength, int npLength) throws IOException {
		if (newArea >= adLength && npLength > newArea + 1) {
			throw new IOException("The NPC registry (" + npLength + " entries) already reaches"
					+ " past the new area id " + newArea + "; it and AreaData (" + adLength
					+ " entries) are out of step, and an area fork would not repair that.");
		}
	}

	/**
	 * Clones this zone's area (and its NPC registry) to private tail entries and
	 * repoints the zone at them. Pack the Workspace afterwards.
	 */
	public static ForkResult forkArea(int zoneIndex) throws IOException {
		//ONE UNIT (F003): the area, its NPC fillers and registry, the per-area table, the zone and
		//its master row were written in turn, and the master row's write can refuse after the zone
		//header was rewritten - a zone header and master row naming different areas
		try (WorkspaceUnit unit = WorkspaceUnit.begin("Fork area", Workspace.session())) {
			ForkResult r = forkAreaWrites(zoneIndex);
			unit.commit();
			return r;
		}
	}

	private static ForkResult forkAreaWrites(int zoneIndex) throws IOException {
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
		//one allocator for every fork: an id given back first, then the archive's end (F043)
		int newArea = takeAreaIds(MasterTable.read(Workspace.session()), ad.length,
				registryEmpty(Workspace.session(), np), 1)[0];
		//The engine indexes the NPC registry by area id, so the archive has to
		//REACH the new id - a count relationship is not the same thing. Retail
		//ships 229 AreaData entries (228 of them areas, plus the per-area table
		//at index 228) against 228 registries, so the first forked id, 229, is
		//two past the end of the registry archive. The gap is filled below. An id
		//given back sits inside both archives, where there is no gap to fill.
		requireInStep(newArea, ad.length, np.length);
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
		int oldArea = ZoneResource.AREA.idIn(zoBytes);
		File srcAdFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, oldArea);
		File srcNpFile = Workspace.getWorkspaceFile(ArchiveType.NPC_REGISTRIES, oldArea);
		File tableFile = Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, AD_GLOBAL_TABLE);
		if (srcAdFile == null || tableFile == null) {
			throw new IOException("Could not extract area " + oldArea + " from the workspace.");
		}
		byte[] srcNp = (srcNpFile != null && srcNpFile.exists()) ? Files.readAllBytes(srcNpFile.toPath()) : new byte[0];
		ForkPlan plan = planFork(zoBytes, Files.readAllBytes(srcAdFile.toPath()), srcNp, Files.readAllBytes(tableFile.toPath()), newArea);

		//stage: new area, its registry, the grown table, the repointed zone
		WorkspaceUnit.write(adOut, plan.newAdBytes);
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
			WorkspaceUnit.write(fillOut, new byte[0]);
			Workspace.addPersist(fillOut);
			registerPendingNpcReg(filler, false);
		}
		File npOut = new File(npDir, String.valueOf(newArea));
		WorkspaceUnit.write(npOut, plan.newNpcBytes);
		Workspace.addPersist(npOut);
		registerPendingNpcReg(newArea, np.length > 0 && np.isEntryCompressed(Math.min(oldArea, np.length - 1)));

		WorkspaceUnit.write(tableFile, plan.newTableBytes);
		Workspace.addPersist(tableFile);

		WorkspaceUnit.write(zoneFile, plan.newZoBytes);
		Workspace.addPersist(zoneFile);
		//the one repoint (F012): this class carried its own copy of the row arithmetic
		GeometryForker.repointMasterRow(zo, zoneIndex, ZoneResource.AREA, newArea);

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

	/** The open workspace's AreaData pending-compression flag for an added area (F014: the session holds them). */
	private static void registerPendingArea(int index, boolean compressed) {
		Workspace.session().registerPendingCompression(ArchiveType.AREA_DATA, index, compressed);
	}

	/** The same for an added NPC-registry entry. */
	private static void registerPendingNpcReg(int index, boolean compressed) {
		Workspace.session().registerPendingCompression(ArchiveType.NPC_REGISTRIES, index, compressed);
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
