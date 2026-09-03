package ctrmap;

import ctrmap.formats.garc.GARC;
import java.util.ArrayList;
import java.util.List;

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
 * <li>a pack from a stale entry table wrote offsets into a file that had moved.</li>
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

	public static final int MASTER_ROW = 0x38;
	public static final int HDR_AREA_OFF = 2;
	public static final int HDR_MATRIX_OFF = 4;

	/**
	 * Every violated invariant, as a human sentence. Empty means consistent.
	 *
	 * @param deep also walk every zone header and every matrix cell; costs a
	 *             full read of ZoneData and MapMatrix.
	 */
	public static List<String> check(boolean deep) {
		List<String> bad = new ArrayList<>();
		try {
			GARC ad = Workspace.getArchive(Workspace.ArchiveType.AREA_DATA);
			GARC np = Workspace.getArchive(Workspace.ArchiveType.NPC_REGISTRIES);
			GARC zo = Workspace.getArchive(Workspace.ArchiveType.ZONE_DATA);
			GARC mm = Workspace.getArchive(Workspace.ArchiveType.MAP_MATRIX);
			GARC gr = Workspace.getArchive(Workspace.ArchiveType.FIELD_DATA);
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
			byte[] masterTable = zo.getDecompressedEntry(zo.length - 2);
			if (masterTable != null && masterTable.length % MASTER_ROW == 0) {
				int n = Math.min(masterTable.length / MASTER_ROW, zo.length - 2);
				for (int z = 0; z < n; z++) {
					int a = u16(masterTable, z * MASTER_ROW + HDR_AREA_OFF);
					if (a > maxAreaInUse) {
						maxAreaInUse = a;
						maxAreaZone = z;
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

			if (!deep) {
				return bad;
			}

			//2. every zone must point at an area and a matrix that exist
			int zoneCount = zo.length - 2;
			byte[] master = masterTable;
			if (master == null || master.length % MASTER_ROW != 0) {
				bad.add("the master zone table is unreadable or misaligned ("
						+ (master == null ? "absent" : master.length + " bytes, not a multiple of "
						+ MASTER_ROW) + ")");
				return bad;
			}
			int rows = master.length / MASTER_ROW;
			if (rows != zoneCount) {
				bad.add("the master table describes " + rows + " zones but ZoneData holds "
						+ zoneCount + "; the two disagree about how many zones exist");
			}
			int badArea = 0, badMatrix = 0, firstBadZone = -1;
			for (int z = 0; z < Math.min(rows, zoneCount); z++) {
				int area = u16(master, z * MASTER_ROW + HDR_AREA_OFF);
				int matrix = u16(master, z * MASTER_ROW + HDR_MATRIX_OFF);
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
	public static List<String> report(String after) {
		List<String> bad = check(false);
		if (bad.isEmpty()) {
			return bad;
		}
		System.err.println("Workspace integrity problems after " + after + ":");
		for (String b : bad) {
			System.err.println("  - " + b);
		}
		return bad;
	}

	private static int u16(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
	}

	private static int u32(byte[] b, int o) {
		return u16(b, o) | (u16(b, o + 2) << 16);
	}

	/**
	 * One subfile out of a Gamefreak container: u16 magic, u16 subfile count,
	 * then count+1 u32 offsets. Null when the bytes are not a container of that
	 * shape, or do not hold subfile {@code i}.
	 */
	private static byte[] subfile(byte[] c, int i) {
		if (c == null || c.length < 8) {
			return null;
		}
		int count = u16(c, 2);
		if (i >= count || 4 + (i + 2) * 4 > c.length) {
			return null;
		}
		int from = u32(c, 4 + i * 4), to = u32(c, 4 + (i + 1) * 4);
		if (from < 0 || to < from || to > c.length) {
			return null;
		}
		return java.util.Arrays.copyOfRange(c, from, to);
	}
}
