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
	 * workspace. EVERY appended zone is forked, spares included; the spares that
	 * round the count up to a multiple of four are then BLANKED, because a slot
	 * nobody asked for should open empty rather than as a second copy of the donor.
	 *
	 * @param newZos       the appended zones' ZO containers, all forked
	 *                     (mutated in place)
	 * @param master       the grown master zone-header table (rows repointed in place)
	 * @param oldCount     the first new zone's GARC index (== its master-table row)
	 * @param newRealZones how many were ASKED for; the rest are the padding
	 *                     spares, which are forked too and then blanked
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
			//A PADDING ZONE GETS A BLANK MAP, NOT A COPY OF THE DONOR'S. The zones the
			//user ASKED for are clones of the donor, which is the point of picking a
			//donor. The spares that round the count up to a multiple of four were never
			//asked for, and a user opening one expects an empty slot, not a second copy
			//of the city they cloned. It is also the cheaper answer by a wide margin:
			//measured on Sootopolis, one spare is 1,550,976 bytes copied against 349,440
			//blanked, so three spares cost 1.0 MB instead of 4.4 MB before the pack
			//compresses anything.
			//
			//AFTER the fork, deliberately. The spare still gets its OWN regions and its
			//own matrix - independence is the property that matters and it is what the
			//fork buys - and only their CONTENTS are then emptied. blankRegionFiles
			//answers false rather than throwing for a region it cannot rebuild, so a
			//donor with an unusual model leaves that spare holding its copy instead of
			//aborting an append that has already staged files.
			if (i >= newRealZones) {
				for (int newRegion : plan.newRegions) {
					ctrmap.formats.h3d.RegionFactory.blankRegionFiles(
						new ctrmap.formats.containers.GR(new File(fdDir, String.valueOf(newRegion)),
							Workspace.session()), -1);
				}
			}
			newZos[i] = plan.newZoBytes;                              // caller writes the repointed ZO
			int rowOff = (oldCount + i) * MASTER_ROW + 4;
			if (rowOff + 2 > master.length) {
				throw new IOException("Master-table row for zone " + (oldCount + i) + " out of range.");
			}
			putU16(master, rowOff, newMatrix);                        // repoint the master-table row
		}
	}


	/**
	 * Gives appended zones their own copy of any resource they still SHARE, for
	 * every resource the appender makes private - the repair half of
	 * {@link ZoneAppender#madePrivate()}.
	 *
	 * <p>WHY IT IS NOT THE MAP REPAIR. The map has its own pass because forking one
	 * means copying regions and then emptying them, and because a spare that was
	 * never built on should come out blank. Area and story text are plain copies:
	 * the zone gets its own and it looks the same afterwards. Running them through
	 * the table rather than naming them means the day SCRIPT becomes forkable - if
	 * it ever does - this repairs it too, without being edited.
	 *
	 * <p>IT REPAIRS WHAT IS SHARED, NOT WHAT IS APPENDED. A zone whose area is
	 * already its own is left alone: forking one that is already private appends a
	 * copy nothing uses and orphans the old one, which is the waste
	 * {@link #ensurePrivate} exists to avoid. Measured on the owner's game, this is
	 * the normal case rather than an edge one - zone 536 had its own area and
	 * 537-539 did not, because the first was forked by hand through a dialog.
	 */
	public static RepairReport repairSharedResources(int baseZones) {
		RepairReport r = new RepairReport();
		try {
			GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
			if (zo == null || Workspace.session() == null || Workspace.session().isReadOnly()) {
				r.refusedBecause = zo == null ? "no workspace is loaded" : "this workspace is open read-only";
				return r;
			}
			int zoneCount = ZoneTables.zoneCount(zo);
			if (zoneCount <= baseZones) {
				return r;
			}
			File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneCount);
			if (masterFile == null) {
				r.refusedBecause = "the master zone-header table could not be read";
				return r;
			}
			byte[] master = Files.readAllBytes(masterFile.toPath());
			if (master.length != zoneCount * MASTER_ROW) {
				r.refusedBecause = "the master zone-header table is " + master.length + " bytes, not the "
					+ (zoneCount * MASTER_ROW) + " this game's " + zoneCount + " zones need";
				return r;
			}
			boolean wrote = false;
			for (ZoneResource res : ZoneAppender.madePrivate()) {
				if (res == ZoneResource.MAP) {
					continue; //repaired by repairSharedAppendedZones, which also blanks
				}
				//who shares what, read from the master table - the copy the game reads
				java.util.Map<Integer, Integer> users = new HashMap<>();
				for (int z = 0; z < zoneCount; z++) {
					int id = res.idInMasterRow(master, z);
					Integer n = users.get(id);
					users.put(id, n == null ? 1 : n + 1);
				}
				java.util.List<Integer> needy = new java.util.ArrayList<>();
				for (int z = baseZones; z < zoneCount; z++) {
					if (users.get(res.idInMasterRow(master, z)) > 1) {
						needy.add(z);
					}
				}
				if (needy.isEmpty()) {
					continue;
				}
				int[] zones = new int[needy.size()];
				byte[][] zos = new byte[needy.size()][];
				for (int i = 0; i < zones.length; i++) {
					zones[i] = needy.get(i);
					File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zones[i]);
					if (zf == null) {
						throw new IOException("Zone " + zones[i] + " could not be read out of the workspace.");
					}
					zos[i] = Files.readAllBytes(zf.toPath());
				}
				if (res == ZoneResource.AREA) {
					AreaForker.forkAppendedAreas(zos, master, zones);
				} else if (res == ZoneResource.TEXT) {
					TextForker.forkAppendedTexts(Workspace.session(), zos, master, zones);
				} else {
					//a row joined MADE_PRIVATE and nothing here knows how to repair it. Said,
					//not skipped: a repair that quietly does nothing for a resource is the
					//silence this whole class of work exists to remove.
					r.kept.add("zone(s) " + needy + " still share their " + res.label
						+ " - the append makes it private, but nothing here can repair one that is not");
					continue;
				}
				for (int i = 0; i < zones.length; i++) {
					File zf = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zones[i]);
					Files.write(zf.toPath(), zos[i]);
					Workspace.addPersist(zf);
					if (!r.forked.contains(zones[i])) {
						r.forked.add(zones[i]);
					}
				}
				r.shared.addAll(needy);
				wrote = true;
			}
			if (wrote) {
				Files.write(masterFile.toPath(), master);
				Workspace.addPersist(masterFile);
			}
		} catch (Exception ex) {
		//said, never swallowed - this runs while the editor is opening
			r.refusedBecause = String.valueOf(ex.getMessage());
		}
		return r;
	}
	/** What a repair pass found, and what it did about it. */
	public static class RepairReport {
		/** Appended zones that still shared a donor's map, in index order. */
		public final java.util.List<Integer> shared = new java.util.ArrayList<>();
		/** Those that were given their own map. */
		public final java.util.List<Integer> forked = new java.util.ArrayList<>();
		/** Those whose new map was also emptied, because nothing had been built on the slot. */
		public final java.util.List<Integer> blanked = new java.util.ArrayList<>();
		/** Slots that kept their contents, with the reason - an edited slot is not blanked. */
		public final java.util.List<String> kept = new java.util.ArrayList<>();
		/** Why the pass did nothing, when it did nothing. Empty means it ran. */
		public String refusedBecause = "";
		
		/** True when files were staged and the workspace therefore needs a pack. */
		public boolean changedAnything() {
			return !forked.isEmpty();
		}
	}

	/**
	 * Gives every APPENDED zone that still shares a donor's map its own, in one
	 * pack cycle, and empties the ones nobody has built on.
	 *
	 * <p>WHO THIS IS FOR. Adding zones rounds the count up to a multiple of four,
	 * and versions up to 1.0.1 forked only the zones the user ASKED for - the
	 * padding spares kept pointing at the donor's map. So opening a spare showed
	 * the donor's city, and painting it rewrote the donor and every sibling spare.
	 * {@link #forkAppendedZones} fixes that for zones created from now on; this
	 * fixes the workspaces that already have them, which no amount of fixing the
	 * appender can reach.
	 *
	 * <p>ONE PASS, NOT ONE CALL PER ZONE. {@link #ensurePrivate} takes its new
	 * matrix number from {@code mm.length} and refuses when that slot is already
	 * staged, so it can only run ONCE between packs - a second call in the same
	 * cycle would ask for the same matrix number and be told an append is pending.
	 * A workspace with three spares therefore cannot be repaired by three of those.
	 * This threads the region and matrix counters the way the appender does, so
	 * every spare is staged together and one pack carries all of them.
	 *
	 * <p>IT BLANKS ONLY WHAT NOBODY HAS TOUCHED, and that is decidable rather than
	 * guessed at. An untouched spare is byte-identical to its donor's ZO except for
	 * the zone number the appender patched in, so a slot that differs anywhere else
	 * has been edited and KEEPS its contents - it still gets its own map, which is
	 * the part that matters, and the report says which. Nothing is destroyed even
	 * then: what a shared slot displays IS the donor's regions, and those are
	 * copied, never written.
	 *
	 * <p>It refuses rather than half-running, and says why, because the alternative
	 * is a workspace with regions staged against a matrix that was never written.
	 *
	 * @param baseZones the first appended zone's index - below it are the game's own
	 * @return what it found and did; never null
	 */
	public static RepairReport repairSharedAppendedZones(int baseZones) {
		RepairReport r = new RepairReport();
		try {
			GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
			GARC gr = Workspace.getArchive(ArchiveType.FIELD_DATA);
			GARC mm = Workspace.getArchive(ArchiveType.MAP_MATRIX);
			if (zo == null || gr == null || mm == null) {
				r.refusedBecause = "no workspace is loaded";
				return r;
			}
			if (Workspace.session() == null || Workspace.session().isReadOnly()) {
				//A READ-ONLY SESSION TAKES THE WRITES AND DROPS THEM. It accepts staged
				//files and persist entries, and only the PACK refuses - which the window
				//skips without a word. Staging first and finding out at pack time would
				//leave megabytes of region copies that never become anything.
				r.refusedBecause = "this workspace is open read-only";
				return r;
			}
			if (!Workspace.profile().supports(ctrmap.gamedef.GameProfile.Feature.AREA_FORK)) {
				r.refusedBecause = "CTRMap has not measured how to fork a map for "
					+ Workspace.profile().displayName();
				return r;
			}
			int zoneCount = ZoneTables.zoneCount(zo);
			if (zoneCount <= baseZones) {
				return r; //no appended zones at all, which is the normal case
			}
			File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneCount);
			if (masterFile == null) {
				r.refusedBecause = "the master zone-header table could not be read";
				return r;
			}
			byte[] master = Files.readAllBytes(masterFile.toPath());
			if (master.length != zoneCount * MASTER_ROW) {
				r.refusedBecause = "the master zone-header table is " + master.length
					+ " bytes, not the " + (zoneCount * MASTER_ROW) + " this game's "
					+ zoneCount + " zones need";
				return r;
			}
			
			//WHO SHARES WITH WHOM, from the master table, which is the copy the game
			//actually reads. Counting sharers across EVERY zone matters: a spare can
			//share with the retail donor it was padded out of and with its sibling
			//spares at the same time, which is exactly what the reporter's workspace
			//holds - 538 and 539 both on zone 534's matrix.
			int[] matrixOf = new int[zoneCount];
			java.util.Map<Integer, Integer> users = new HashMap<>();
			for (int z = 0; z < zoneCount; z++) {
				matrixOf[z] = u16(master, z * MASTER_ROW + 4);
				Integer n = users.get(matrixOf[z]);
				users.put(matrixOf[z], n == null ? 1 : n + 1);
			}
			for (int z = baseZones; z < zoneCount; z++) {
				if (users.get(matrixOf[z]) > 1) {
					r.shared.add(z);
				}
			}
			if (r.shared.isEmpty()) {
				return r;
			}
			
			File fdDir = Workspace.getExtractionDirectory(ArchiveType.FIELD_DATA);
			File mmDir = Workspace.getExtractionDirectory(ArchiveType.MAP_MATRIX);
			int nextRegion = gr.length;
			int nextMatrix = mm.length;
			if (Workspace.isPendingArtifact(new File(mmDir, String.valueOf(nextMatrix)))) {
				r.refusedBecause = "a map append is already staged and not yet packed";
				return r;
			}
			for (int z : r.shared) {
				File zoneFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, z);
				if (zoneFile == null) {
					r.kept.add("zone " + z + " could not be read out of the workspace");
					continue;
				}
				byte[] zoBytes = Files.readAllBytes(zoneFile.toPath());
				boolean untouched = looksUntouched(zoBytes, matrixOf[z], z);
				ForkPlan plan = forkArchives(zoBytes, nextRegion, nextMatrix, z, true, gr, mm, fdDir, mmDir);
				nextRegion += plan.srcRegions.length;
				Files.write(zoneFile.toPath(), plan.newZoBytes);
				Workspace.addPersist(zoneFile);
				putU16(master, z * MASTER_ROW + 4, nextMatrix);
				nextMatrix++;
				r.forked.add(z);
				if (!untouched) {
					r.kept.add("zone " + z + " has been edited, so its copy keeps what is on it");
					continue;
				}
				boolean allBlank = true;
				for (int newRegion : plan.newRegions) {
					allBlank &= ctrmap.formats.h3d.RegionFactory.blankRegionFiles(
						new ctrmap.formats.containers.GR(new File(fdDir, String.valueOf(newRegion)),
							Workspace.session()), -1);
				}
				if (allBlank) {
					r.blanked.add(z);
				} else {
					r.kept.add("zone " + z + " has its own map, but part of it could not be emptied");
				}
			}
			if (!r.forked.isEmpty()) {
				Files.write(masterFile.toPath(), master);
				Workspace.addPersist(masterFile);
			}
		} catch (Exception ex) {
		//SAID, NEVER SWALLOWED. This runs while the editor is opening, where a
		//thrown exception would take the whole window with it for a repair nobody
		//asked for - so it is caught, and the caller reports it like any other
		//outcome rather than the user meeting a workspace that half-repaired.
			r.refusedBecause = String.valueOf(ex.getMessage());
		}
		return r;
	}

	/**
	 * Whether a padding slot is still exactly what the append made it.
	 *
	 * <p>THE IDS CREATION ASSIGNS ARE NOT EDITS. An append clones the donor and then
	 * repoints the resources it makes private - the map, and now the area - so a
	 * spare differs from a plain clone at exactly those fields and at no others.
	 * Comparing without allowing for them called every spare "edited" the day area
	 * forking was added, and the repair stopped blanking anything. The set comes
	 * from {@link ctrmap.ZoneAppender#madePrivate()} rather than being listed again
	 * here, so the next resource to join it is allowed for on the same day.
	 *
	 * <p>ASKED OF THE CLONE FUNCTION, NOT OF A BYTE RANGE. The appender builds each
	 * spare with {@link ctrmap.ZoneCloner#cloneZoneBytes}, so the question "is this
	 * still what the append made" has an exact answer: run that function on the
	 * donor for this zone number and see whether the bytes match. Writing out which
	 * offsets it patches instead - the header's zone-number bits - would be a second
	 * copy of a fact that already lives in one place, and the first draft of this
	 * got that copy WRONG, comparing a window the patched bytes were not inside.
	 *
	 * <p>Compared against the donor rather than against a remembered flag, because
	 * nothing was remembering: these workspaces were written by a version that did
	 * not know it was leaving anything behind.
	 */
	static boolean looksUntouched(byte[] spareZo, int matrix, int zoneIndex) throws IOException {
		GARC zo = Workspace.getArchive(ArchiveType.ZONE_DATA);
		int zoneCount = ZoneTables.zoneCount(zo);
		int donor = -1;
		File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, zoneCount);
		byte[] master = Files.readAllBytes(masterFile.toPath());
		for (int z = 0; z < zoneCount && donor < 0; z++) {
			if (z != zoneIndex && u16(master, z * MASTER_ROW + 4) == matrix) {
				donor = z;
			}
		}
		if (donor < 0) {
			return false; //nothing to compare against: keep what is there
		}
		File donorFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, donor);
		if (donorFile == null) {
			return false;
		}
		byte[] donorZo = Files.readAllBytes(donorFile.toPath());
		if (donorZo.length != spareZo.length) {
			return false;
		}
		try {
			byte[] expected = ctrmap.ZoneCloner.cloneZoneBytes(donorZo, zoneIndex, true);
			for (ZoneResource res : ZoneAppender.madePrivate()) {
				res.setIn(expected, res.idIn(spareZo));
			}
			return java.util.Arrays.equals(spareZo, expected);
		} catch (RuntimeException notAZone) {
			//a ZO the cloner will not read is not one this can make a claim about
			return false;
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
		repointMasterRow(zo, zoneIndex, ZoneResource.MAP, newMatrix);
	}

	/**
	 * The same, for ANY resource a zone header points at.
	 *
	 * <p>The matrix version hard-coded its own offset, which was right while the map
	 * was the only thing being repointed. It is not any more - an area, and a story
	 * text, are repointed in the same row by the same arithmetic - so the offset
	 * comes from {@link ZoneResource} and there is one copy of the arithmetic rather
	 * than one per resource.
	 */
	public static void repointMasterRow(GARC zo, int zoneIndex, ZoneResource res, int newId)
			throws IOException {
		int masterIndex = ZoneTables.masterIndex(zo);
		File masterFile = Workspace.getWorkspaceFile(ArchiveType.ZONE_DATA, masterIndex);
		if (masterFile == null) {
			throw new IOException("Could not extract the master zone-header table.");
		}
		byte[] master = Files.readAllBytes(masterFile.toPath());
		int rowOff = zoneIndex * MASTER_ROW + res.headerOffset;
		if (rowOff + 2 > master.length) {
			throw new IOException("Master-table row for zone " + zoneIndex + " out of range.");
		}
		putU16(master, rowOff, newId);
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
