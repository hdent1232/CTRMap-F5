package ctrmap;

import ctrmap.formats.garc.GARC;
import ctrmap.gamedef.ArchiveType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static ctrmap.formats.LittleEndian.u16;
import static ctrmap.formats.containers.ContainerBytes.subfile;

/**
 * Cross-archive invariants: the things that must stay in lockstep, checked in
 * one place.
 *
 * <p>Every archive in the game is internally consistent on its own and refers
 * to the others by bare index. Nothing validates those references, so an
 * operation that grows one archive and not another leaves a dangling index
 * that no single-archive check can see. It surfaces later as a
 * FileNotFoundException, a dialog, or a hardlock, a long way from the edit that
 * caused it.
 *
 * <p>This has now bitten repeatedly, in the same shape each time:
 * <ul>
 * <li>an area fork grew AreaData to 230 entries and left NPCRegistries at 229,
 *     so loading the new area threw and rendering the zone was impossible;</li>
 * <li>a zone fork rewired a map matrix at a region index the FieldData archive
 *     did not yet have;</li>
 * <li>a pack from a stale entry table wrote offsets into a file that had moved;</li>
 * <li>an area fork from an older build wrote the new area's NPC registry one
 *     slot early, so the counts matched, every id was in range, and the forked
 *     zone loaded a different area's NPCs.</li>
 * </ul>
 * Each was found by hand, after the damage. A check is cheap - it reads five
 * entry counts and a master table - and it turns a silent inconsistency into a
 * sentence naming the two archives that disagree.
 *
 * <p>Deliberately reports rather than repairs. What the right repair is depends
 * on which side is correct, and guessing is how a small inconsistency becomes a
 * large one.
 */
public class WorkspaceIntegrity {

	public static final int MASTER_ROW = ctrmap.formats.zone.ZoneHeader.SIZE;
	public static final int HDR_AREA_OFF = 2;
	public static final int HDR_MATRIX_OFF = 4;

	/**
	 * Every violated invariant, as a human sentence. Empty means consistent.
	 *
	 * @param ws   the open game to check - handed in, so a suite can check a
	 *             session it built (or one archive swapped) without installing it
	 * @param deep also walk every zone header and every matrix cell; costs a
	 *             full read of ZoneData and MapMatrix.
	 */
	public static List<String> check(WorkspaceSession ws, boolean deep) {
		List<String> bad = new ArrayList<>();
		try {
			GARC ad = ws.getArchive(ArchiveType.AREA_DATA);
			GARC np = ws.getArchive(ArchiveType.NPC_REGISTRIES);
			GARC zo = ws.getArchive(ArchiveType.ZONE_DATA);
			GARC mm = ws.getArchive(ArchiveType.MAP_MATRIX);
			GARC gr = ws.getArchive(ArchiveType.FIELD_DATA);
			if (ad == null || np == null || zo == null || mm == null || gr == null) {
				return bad; //no workspace loaded; nothing to check
			}

			//1. Every area id a zone actually uses must have a registry AT THAT
			//   INDEX. ZoneHeader:249 reads
			//   getWorkspaceFile(NPC_REGISTRIES, areadataID) - a direct index,
			//   not a count.
			//
			//This is deliberately NOT a relationship between the two archive
			//lengths, and two wrong versions of it were written before the data
			//was measured. "One registry per area" declares the retail game
			//broken. "Registries trail areas by one" matches retail, but only by
			//accident: retail's highest areadataID is 227 and it ships 228
			//registries, so ten AreaData entries - the global table among them -
			//are never named by any zone. Fork a zone onto a new area and the
			//count rule still passes while the registry the engine will ask for
			//does not exist.
			//
			//Measured rather than assumed: retail max areadataID 227 against 228
			//registries (fine); a workspace with one forked area, max
			//areadataID 229 against 229 registries (index 229 missing, and the
			//zone would not load).
			int maxAreaInUse = -1, maxAreaZone = -1;
			//forked area id -> the first zone that names it, for check 4 below
			Map<Integer, Integer> forkedAreaZone = new TreeMap<>();
			//THE ONE READER, AT THE PROFILE'S ZONE COUNT (F013). This read entry "length - 2" -
			//ORAS's tail as a literal - and took any whole number of rows as the table. The archive's
			//copy is the right one here: this runs after a pack, on what the game will load.
			int zoneCount = ZoneTables.zoneCount(zo, ws.profile());
			MasterTable masterRows = null;
			String masterUnread = null;
			try {
				masterRows = MasterTable.detached(zo.getDecompressedEntry(zoneCount), zoneCount);
			} catch (java.io.IOException wrong) {
				masterUnread = ctrmap.util.Bytes.reason(wrong);
			}
			if (masterRows != null) {
				for (int z = 0; z < masterRows.zones(); z++) {
					int a = masterRows.id(ZoneResource.AREA, z);
					if (a > maxAreaInUse) {
						maxAreaInUse = a;
						maxAreaZone = z;
					}
					if (a >= AreaForker.AD_GLOBAL_TABLE && !forkedAreaZone.containsKey(a)) {
						forkedAreaZone.put(a, z);
					}
				}
			}
			if (maxAreaInUse >= np.length) {
				bad.add("zone " + maxAreaZone + " uses area " + maxAreaInUse
						+ ", but NPCRegistries only has indices 0.." + (np.length - 1)
						+ "; the engine reads the registry by area id, so that zone"
						+ " cannot load. An area fork must extend NPCRegistries far"
						+ " enough to contain the new area's id.");
			}
			if (maxAreaInUse >= ad.length) {
				bad.add("zone " + maxAreaZone + " uses area " + maxAreaInUse
						+ ", but AreaData only has indices 0.." + (ad.length - 1));
			}

			//4. A REGISTRY NOTHING CAN READ, which is what the OLD area fork
			//   left behind and what check 1 is blind to.
			//
			//The fork appends the new area's registry at the new area's own id.
			//The pre-fix build named a file past the end of the archive and the
			//packer renumbered it to the first free slot, so the clone made for
			//area 229 landed at 228 - AreaData's per-area TABLE, not an area,
			//and an id planFork refuses, so no zone can ever name it. A later
			//fork back-filled 229 with a different area's clone. The result has
			//AreaData and NPCRegistries the same length and every id in range,
			//so check 1 passes and the game loads the wrong NPCs.
			//
			//Detected by the one thing that cannot be innocent: a NON-EMPTY
			//registry at an id at or past the per-area table that no zone
			//names. Retail stops at 227 and never reaches these indices, and
			//the fixed fork writes its own filler slots EMPTY, so a healthy
			//workspace has nothing here. Deliberately not "the clone still
			//matches its source" - forking exists so the copy can be edited,
			//and that check would fire on every fork the user actually used.
			//Asks for the CAPABILITY, not for the game: this check is about the
			//wreckage the area fork leaves, AD_GLOBAL_TABLE is the fork's own
			//ORAS-measured index, and "ws.isOA()" - what stood here - would
			//have run the check on Sun/Moon the day a Gen 7 dump opened, with
			//ORAS's 228 as the threshold on an archive nobody has measured.
			if (ws.profile().supports(ctrmap.gamedef.GameProfile.Feature.AREA_FORK)
					&& np.length > AreaForker.AD_GLOBAL_TABLE) {
				List<Integer> stranded = new ArrayList<>();
				for (int i = AreaForker.AD_GLOBAL_TABLE; i < np.length; i++) {
					if (forkedAreaZone.containsKey(i)) {
						continue;
					}
					byte[] reg;
					try {
						reg = np.getDecompressedEntry(i);
					} catch (RuntimeException ignore) {
						continue; //unreadable is a different complaint
					}
					if (reg != null && reg.length > 0) {
						stranded.add(i);
					}
				}
				if (!stranded.isEmpty()) {
					StringBuilder forked = new StringBuilder();
					for (Map.Entry<Integer, Integer> e : forkedAreaZone.entrySet()) {
						forked.append(forked.length() == 0 ? "" : ", ")
								.append("zone ").append(e.getValue())
								.append(" on area ").append(e.getKey());
					}
					bad.add("NPC registry " + stranded + " holds real NPCs, but nothing will ever"
							+ " read it: AreaData " + AreaForker.AD_GLOBAL_TABLE + " is the engine's"
							+ " per-area table rather than an area, and no zone names any of these"
							+ " ids. An area fork made by an older build wrote the new area's"
							+ " registry one slot early and leaves exactly this"
							+ (forked.length() == 0 ? ", with the area it was cloned for since gone"
									: ", while the forked " + forked + " reads whatever now sits at"
									+ " its own id - somebody else's NPCs")
							+ ". This is NOT repaired automatically, because the right repair is not"
							+ " unambiguous: any registry from " + stranded.get(0) + " upwards may"
							+ " have been edited since the fork, and shifting one into place"
							+ " overwrites the next. Either re-fork the affected zone(s) on a clean"
							+ " copy of the game, or move the numbered files in the workspace's"
							+ " npcregistries folder into the right slots by hand and pack again.");
				}
			}

			if (!deep) {
				return bad;
			}

			//2. every zone must point at an area and a matrix that exist
			if (masterRows == null) {
				bad.add("the master zone table could not be read: " + masterUnread);
				return bad;
			}
			int badArea = 0, badMatrix = 0, firstBadZone = -1;
			for (int z = 0; z < zoneCount; z++) {
				int area = masterRows.id(ZoneResource.AREA, z);
				int matrix = masterRows.id(ZoneResource.MAP, z);
				if (area >= ad.length) {
					badArea++;
					firstBadZone = firstBadZone < 0 ? z : firstBadZone;
				}
				if (matrix >= mm.length) {
					badMatrix++;
					firstBadZone = firstBadZone < 0 ? z : firstBadZone;
				}
			}
			if (badArea > 0) {
				bad.add(badArea + " zone(s) point at an area that does not exist (first: zone "
						+ firstBadZone + "); AreaData has " + ad.length);
			}
			if (badMatrix > 0) {
				bad.add(badMatrix + " zone(s) point at a map matrix that does not exist;"
						+ " MapMatrix has " + mm.length);
			}

			//5. EVERY ZONE'S OWN HEADER AGREES WITH ITS MASTER ROW (F031). The forks and the
			//open-time repair write the two separately, and the game reads only the master row: a
			//zone whose header names one area and whose row names another is edited as one place
			//and loaded as the other. The ids those paths write - area, map, story text - are
			//compared; a ZoneData entry that is not a zone container is not a zone, and is left out.
			int disagree = 0, headersRead = 0;
			String firstDisagreement = null;
			for (int z = 0; z < zoneCount; z++) {
				byte[] zoBytes;
				try {
					zoBytes = zo.getDecompressedEntry(z);
				} catch (RuntimeException unreadable) {
					zoBytes = null;
				}
				byte[] header = zoBytes == null || zoBytes.length < 2 || zoBytes[0] != 'Z' || zoBytes[1] != 'O'
						? null : subfile(zoBytes, 0);
				if (header == null || header.length < MASTER_ROW) {
					continue;
				}
				headersRead++;
				for (ZoneResource res : ZoneAppender.madePrivate()) {
					int own = u16(header, res.headerOffset);
					int row = masterRows.id(res, z);
					if (own != row) {
						disagree++;
						if (firstDisagreement == null) {
							firstDisagreement = "zone " + z + "'s " + res.label + ": its header says " + own
									+ ", the master table says " + row;
						}
						break;
					}
				}
			}
			if (disagree > 0) {
				bad.add(disagree + " zone(s) whose own header and master-table row disagree (first: "
						+ firstDisagreement + "); the game reads the master row, so the editor shows one"
						+ " place and the game loads another");
			}
			//a pass that read no header proved nothing; said, as the matrix pass says it
			if (zoneCount > 0 && headersRead == 0) {
				bad.add("the header check could not read any of the " + zoneCount + " zones, so nothing"
						+ " was verified about headers agreeing with the master table");
			}

			//3. every matrix cell must name a region that exists
			int badRegion = 0, firstBadMatrix = -1, scanned = 0;
			for (int m = 0; m < mm.length; m++) {
				byte[] mat;
				try {
					mat = mm.getDecompressedEntry(m);
				} catch (RuntimeException ignore) {
					continue;
				}
				//the grid is inside subfile 0, not at the top of the container.
				//Reading the container's own header as the grid's gave w=16 h=0
				//for all 431 retail matrices, so every one failed the shape test
				//below and this pass scanned nothing at all - silently, which is
				//the one thing a check must never do.
				byte[] grid = subfile(mat, 0);
				if (grid == null || grid.length < 8) {
					continue;
				}
				int w = u16(grid, 4), h = u16(grid, 6);
				long cells = (long) w * h;
				if (w <= 0 || h <= 0 || cells > 4096 || 8 + cells * 2 > grid.length) {
					continue; //not a shape this check understands; leave it alone
				}
				scanned++;
				for (int i = 0; i < cells; i++) {
					int region = u16(grid, 8 + i * 2);
					if (region != 0xFFFF && region >= gr.length) {
						badRegion++;
						firstBadMatrix = firstBadMatrix < 0 ? m : firstBadMatrix;
					}
				}
			}
			if (badRegion > 0) {
				bad.add(badRegion + " map-matrix cell(s) name a FieldData region that does not"
						+ " exist (first in matrix " + firstBadMatrix + "); FieldData has "
						+ gr.length + " regions");
			}
			//a pass that understood no matrix at all proved nothing; say so
			//rather than reporting a clean bill of health
			if (mm.length > 0 && scanned == 0) {
				bad.add("the map-matrix check could not read any of the " + mm.length
						+ " matrices, so nothing was verified about region references");
			}
		} catch (Exception ex) {
			bad.add("the integrity check itself failed: " + ex);
		}
		return bad;
	}

	/**
	 * Runs the shallow check and RETURNS anything wrong, so the caller can put
	 * it in front of the user. Called after packing so an inconsistency is
	 * reported next to the edit that made it, not hours later when a zone
	 * refuses to load.
	 *
	 * <p>This used to only print. The shipped build is a windowed app-image
	 * with no console attached, so "that zone cannot load" was written to a
	 * handle that goes nowhere and the user saw the success dialog alone. The
	 * stderr line is still written - it is useful when running from a terminal -
	 * but the returned list is the channel that reaches anybody.
	 */
	public static List<String> report(WorkspaceSession ws, String after) {
		//deep: the region pass was fixed to read every matrix and then never
		//ran outside IntegrityTest, because this called check(false), which
		//returns before it. A dangling region typed into the matrix editor
		//reached the archive and the Pack dialog said nothing. One 224 KB read.
		List<String> bad = check(ws, true);
		if (bad.isEmpty()) {
			return bad;
		}
		System.err.println("Workspace integrity problems after " + after + ":");
		for (String b : bad) {
			System.err.println("  - " + b);
		}
		return bad;
	}
}
